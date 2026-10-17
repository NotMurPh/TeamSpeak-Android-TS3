//! Owns the tsclientlib connection on a dedicated thread.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::prelude::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tracing::warn;
use tsclientlib::events::{Event, PropertyId, PropertyValue};
use tsclientlib::prelude::*;
use tsclientlib::{
    ChannelId, Connection, DisconnectOptions, FiletransferHandle, Identity, MessageHandle,
    MessageTarget, Reason, StreamItem, Version,
};
use tsproto_packets::packets::AudioData;

use crate::{
    export_identity, raw, view, Cmd, CommandFailure, ConnectOptions, EventKind, EventListener,
    LogEvent, Shared, Status, TextMessage, TextTarget,
};

/// Client additions right after connecting are the initial client list, not joins.
const INITIAL_QUIET: Duration = Duration::from_secs(2);
/// Give up when a server does not answer; the library itself would keep trying for minutes.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
const RECONNECT_TIMEOUT: Duration = Duration::from_secs(60);

pub fn run(
    opts: ConnectOptions, identity: Identity, shared: Arc<Shared>, listener: Arc<dyn EventListener>,
    rx: mpsc::UnboundedReceiver<Cmd>,
) {
    let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(rt) => rt,
        Err(e) => return listener.on_status(Status::Disconnected, e.to_string()),
    };
    let reason = rt.block_on(session(opts, identity, &shared, listener.clone(), rx));
    *shared.snapshot.lock().unwrap() = None;
    *shared.own_id.lock().unwrap() = 0;
    *shared.can_send.lock().unwrap() = false;
    shared.playback.lock().unwrap().reset();
    shared.talking.lock().unwrap().clear();
    listener.on_status(Status::Disconnected, reason);
    listener.on_state_changed();
}

enum Step {
    Cmd(Option<Cmd>),
    Ev(Option<Result<StreamItem, tsclientlib::Error>>),
    Timeout,
}

enum Transfer {
    Down { id: u32, dest: String },
    Up { id: u32, src: String },
}

/// Returns the disconnect reason.
async fn session(
    opts: ConnectOptions, identity: Identity, shared: &Shared, listener: Arc<dyn EventListener>,
    mut rx: mpsc::UnboundedReceiver<Cmd>,
) -> String {
    listener.on_status(Status::Connecting, opts.address.clone());
    let counter = identity.counter();
    let mut b = Connection::build(opts.address.clone())
        .name(opts.nickname.clone())
        .identity(identity)
        // Signed TS3 Android 3.6.0 version, so servers and other clients show "Android".
        .version(Version::Android_3_6_0)
        .input_muted(opts.input_muted)
        .output_muted(opts.output_muted);
    if let Some(p) = opts.server_password.clone().filter(|p| !p.is_empty()) {
        b = b.password(p);
    }
    if let Some(c) = opts.channel.clone().filter(|c| !c.is_empty()) {
        b = match c.strip_prefix('/').and_then(|id| id.parse::<u64>().ok()) {
            Some(id) => b.channel_id(ChannelId(id)),
            None => b.channel(c),
        };
    }
    if let Some(p) = opts.channel_password.clone().filter(|p| !p.is_empty()) {
        b = b.channel_password(p);
    }
    shared.playback.lock().unwrap().output_muted = opts.output_muted;

    let mut con = match b.connect() {
        Ok(c) => c,
        Err(e) => return e.to_string(),
    };

    let mut connected_at: Option<Instant> = None;
    // Decaying packet-loss counters: incoming count, incoming lost, outgoing count, outgoing lost.
    let mut loss = [0f64; 4];
    let mut reason = String::new();
    let mut requests: HashMap<u16, u32> = HashMap::new();
    let mut transfers: HashMap<u16, Transfer> = HashMap::new();
    // While connecting or reconnecting: when to give up. None while connected or improving the identity.
    let mut deadline = Some(tokio::time::Instant::now() + CONNECT_TIMEOUT);
    let mut was_connected = false;
    loop {
        let step = {
            let mut events = con.events();
            let expiry = async {
                match deadline {
                    Some(d) => tokio::time::sleep_until(d).await,
                    None => future::pending().await,
                }
            };
            tokio::select! {
                cmd = rx.recv() => Step::Cmd(cmd),
                ev = events.next() => Step::Ev(ev),
                _ = expiry => Step::Timeout,
            }
        };
        match step {
            Step::Timeout => {
                return if was_connected {
                    "Connection lost: the server stopped answering".into()
                } else {
                    format!("No answer from {} (timed out after {} s)", opts.address, CONNECT_TIMEOUT.as_secs())
                };
            }
            Step::Cmd(None) | Step::Cmd(Some(Cmd::Disconnect(_))) => {
                let msg = match step {
                    Step::Cmd(Some(Cmd::Disconnect(m))) => m,
                    _ => String::new(),
                };
                let mut o = DisconnectOptions::new().reason(Reason::Clientdisconnect);
                if !msg.is_empty() {
                    o = o.message(msg);
                }
                if con.disconnect(o).is_ok() {
                    let drain = con.events().for_each(|_| future::ready(()));
                    let _ = tokio::time::timeout(Duration::from_secs(3), drain).await;
                }
                return String::new();
            }
            Step::Cmd(Some(Cmd::Command(c, id))) => match c.send_with_result(&mut con) {
                Ok(MessageHandle(h)) => {
                    requests.insert(h, id);
                }
                Err(e) => listener.on_command_result(id, Some(failure(0, e.to_string(), None))),
            },
            Step::Cmd(Some(Cmd::Audio(p))) => {
                if let Err(e) = con.send_audio(p) {
                    warn!(%e, "send audio");
                }
            }
            Step::Cmd(Some(Cmd::Download { id, channel, path, password, dest })) => {
                let pw = (!password.is_empty()).then_some(password.as_str());
                match con.download_file(ChannelId(channel), &path, pw, None) {
                    Ok(FiletransferHandle(h)) => {
                        transfers.insert(h, Transfer::Down { id, dest });
                    }
                    Err(e) => listener.on_transfer(id, 0, 0, true, Some(e.to_string())),
                }
            }
            Step::Cmd(Some(Cmd::Upload { id, channel, path, password, src, overwrite })) => {
                let size = std::fs::metadata(&src).map(|m| m.len()).unwrap_or(0);
                let pw = (!password.is_empty()).then_some(password.as_str());
                match con.upload_file(ChannelId(channel), &path, pw, size, overwrite, false) {
                    Ok(FiletransferHandle(h)) => {
                        transfers.insert(h, Transfer::Up { id, src });
                    }
                    Err(e) => listener.on_transfer(id, 0, 0, true, Some(e.to_string())),
                }
            }
            Step::Ev(None) => return reason,
            Step::Ev(Some(Err(e))) => {
                return if reason.is_empty() { e.to_string() } else { reason };
            }
            Step::Ev(Some(Ok(item))) => match item {
                StreamItem::BookEvents(events) => {
                    deadline = None;
                    was_connected = true;
                    if connected_at.is_none() {
                        connected_at = Some(Instant::now());
                        listener.on_status(Status::Connected, String::new());
                        if opts.subscribe_all {
                            let _ = crate::build_command("channelsubscribeall", std::iter::empty()).send(&mut con);
                        }
                        if let Some(id) = con.get_options().get_identity() {
                            if id.counter() != counter {
                                listener.on_identity_changed(export_identity(id));
                            }
                        }
                    }
                    let quiet = connected_at.is_some_and(|t| t.elapsed() < INITIAL_QUIET);
                    if let Ok(state) = con.get_state() {
                        for e in &events {
                            handle_event(e, state, &*listener, &mut reason, quiet);
                        }
                    }
                    publish(&con, shared, &*listener);
                }
                StreamItem::Audio(packet) => {
                    let (from, whisper) = match packet.data().data() {
                        AudioData::S2C { from, .. } => (*from, false),
                        AudioData::S2CWhisper { from, .. } => (*from, true),
                        _ => continue,
                    };
                    let started = shared.playback.lock().unwrap().handle_packet(from, packet, whisper);
                    if let Some(id) = started {
                        if shared.talking.lock().unwrap().insert(id) {
                            listener.on_talk_status(id, true, whisper);
                        }
                    }
                }
                StreamItem::MessageResult(MessageHandle(h), res) => {
                    if let Some(id) = requests.remove(&h) {
                        let err = res.err().map(|e| {
                            failure(e.error as u32, format!("{:?}", e.error), e.missing_permission.map(|p| format!("{p:?}")))
                        });
                        listener.on_command_result(id, err);
                    }
                }
                StreamItem::RawCommand(line) => {
                    let (name, parts) = raw::parse(&line);
                    if !raw::is_book_command(&name) {
                        listener.on_notification(name, parts);
                    }
                }
                StreamItem::FileDownload(FiletransferHandle(h), res) => {
                    if let Some(Transfer::Down { id, dest }) = transfers.remove(&h) {
                        let l = listener.clone();
                        tokio::spawn(async move {
                            let r = download(res.stream, res.size, &dest, id, &*l).await;
                            l.on_transfer(id, res.size, res.size, true, r.err());
                        });
                    }
                }
                StreamItem::FileUpload(FiletransferHandle(h), res) => {
                    if let Some(Transfer::Up { id, src }) = transfers.remove(&h) {
                        let l = listener.clone();
                        tokio::spawn(async move {
                            let r = upload(res.stream, res.seek_position, &src, id, &*l).await;
                            let total = std::fs::metadata(&src).map(|m| m.len()).unwrap_or(0);
                            l.on_transfer(id, total, total, true, r.err());
                        });
                    }
                }
                StreamItem::FiletransferFailed(FiletransferHandle(h), e) => {
                    if let Some(Transfer::Down { id, .. } | Transfer::Up { id, .. }) = transfers.remove(&h) {
                        listener.on_transfer(id, 0, 0, true, Some(e.to_string()));
                    }
                }
                StreamItem::DisconnectedTemporarily(r) => {
                    listener.on_status(Status::Reconnecting, format!("{r:?}"));
                    connected_at = None;
                    deadline = Some(tokio::time::Instant::now() + RECONNECT_TIMEOUT);
                    shared.playback.lock().unwrap().reset();
                }
                StreamItem::AudioChange(_) => {
                    *shared.can_send.lock().unwrap() = con.can_send_audio();
                }
                StreamItem::NetworkStatsUpdated => {
                    if let Ok(s) = con.get_network_stats() {
                        // PacketStat order: in control, keepalive, speech, then out control, keepalive, speech.
                        let inn = |a: &[u64; 6]| a[..3].iter().sum::<u64>();
                        let out = |a: &[u64; 6]| a[3..].iter().sum::<u64>();
                        let last = s.last_second_bytes.last().copied().unwrap_or_default();
                        let (in_count, in_lost, out_count, out_lost) = s.get_packetloss_counts();
                        // Decay the counts so the loss covers roughly the last 20 seconds.
                        for (acc, v) in loss.iter_mut().zip([in_count, in_lost, out_count, out_lost]) {
                            *acc = *acc * 0.95 + v as f64;
                        }
                        let mut st = shared.stats.lock().unwrap();
                        st.ping_ms = s.rtt.as_secs_f32() * 1000.0;
                        st.ping_deviation_ms = s.rtt_dev.as_secs_f32() * 1000.0;
                        st.packets_sent = out(&s.total_packets);
                        st.packets_received = inn(&s.total_packets);
                        st.bytes_sent = out(&s.total_bytes);
                        st.bytes_received = inn(&s.total_bytes);
                        st.bandwidth_sent_last_second = last[3..].iter().map(|x| *x as u64).sum();
                        st.bandwidth_received_last_second = last[..3].iter().map(|x| *x as u64).sum();
                        st.packet_loss_in = if loss[0] > 0.0 { (loss[1] / loss[0]) as f32 } else { 0.0 };
                        st.packet_loss_out = if loss[2] > 0.0 { (loss[3] / loss[2]) as f32 } else { 0.0 };
                    }
                }
                StreamItem::IdentityLevelIncreasing(level) => {
                    deadline = None; // computing a higher level can take a while on a phone
                    listener.on_status(Status::Connecting, format!("Improving identity to level {level}"));
                }
                StreamItem::IdentityLevelIncreased => {
                    deadline = Some(tokio::time::Instant::now() + CONNECT_TIMEOUT);
                    if let Some(id) = con.get_options().get_identity() {
                        listener.on_identity_changed(export_identity(id));
                    }
                }
                _ => {}
            },
        }
    }
}

fn failure(code: u32, message: String, missing_permission: Option<String>) -> CommandFailure {
    CommandFailure { code, message, missing_permission }
}

async fn download(
    mut stream: tokio::net::TcpStream, size: u64, dest: &str, id: u32, l: &dyn EventListener,
) -> Result<(), String> {
    let tmp = format!("{dest}.part");
    let mut file = tokio::fs::File::create(&tmp).await.map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = 0u64;
    let mut last = Instant::now();
    while done < size {
        let n = stream.read(&mut buf).await.map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n]).await.map_err(|e| e.to_string())?;
        done += n as u64;
        if last.elapsed() > Duration::from_millis(250) {
            last = Instant::now();
            l.on_transfer(id, done, size, false, None);
        }
    }
    file.flush().await.map_err(|e| e.to_string())?;
    drop(file);
    if done < size {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(format!("connection closed after {done} of {size} bytes"));
    }
    tokio::fs::rename(&tmp, dest).await.map_err(|e| e.to_string())
}

async fn upload(
    mut stream: tokio::net::TcpStream, seek: u64, src: &str, id: u32, l: &dyn EventListener,
) -> Result<(), String> {
    use tokio::io::AsyncSeekExt;
    let mut file = tokio::fs::File::open(src).await.map_err(|e| e.to_string())?;
    let size = file.metadata().await.map_err(|e| e.to_string())?.len();
    file.seek(std::io::SeekFrom::Start(seek)).await.map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = seek;
    let mut last = Instant::now();
    loop {
        let n = file.read(&mut buf).await.map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        stream.write_all(&buf[..n]).await.map_err(|e| e.to_string())?;
        done += n as u64;
        if last.elapsed() > Duration::from_millis(250) {
            last = Instant::now();
            l.on_transfer(id, done, size, false, None);
        }
    }
    stream.shutdown().await.map_err(|e| e.to_string())
}

fn publish(con: &Connection, shared: &Shared, listener: &dyn EventListener) {
    let Ok(state) = con.get_state() else { return };
    let talking = shared.talking.lock().unwrap().clone();
    let muted = shared.playback.lock().unwrap().muted.clone();
    let snap = view::build(state, &talking, &muted);
    *shared.own_id.lock().unwrap() = snap.own_client_id;
    *shared.can_send.lock().unwrap() = con.can_send_audio();
    *shared.snapshot.lock().unwrap() = Some(snap);
    listener.on_state_changed();
}

fn name_of(state: &tsclientlib::data::Connection, id: tsclientlib::ClientId) -> String {
    state.clients.get(&id).map(|c| c.name.clone()).unwrap_or_else(|| format!("#{}", id.0))
}

fn channel_name(state: &tsclientlib::data::Connection, id: ChannelId) -> String {
    state.channels.get(&id).map(|c| c.name.clone()).unwrap_or_default()
}

fn handle_event(
    e: &Event, state: &tsclientlib::data::Connection, listener: &dyn EventListener, reason: &mut String,
    quiet: bool,
) {
    let own_channel = state.clients.get(&state.own_client).map(|c| c.channel);
    let log = |kind: EventKind, text: String, id: tsclientlib::ClientId, name: String, in_own: bool| {
        listener.on_log(LogEvent {
            kind,
            text,
            client_id: id.0,
            client_name: name,
            own: id == state.own_client,
            own_channel: in_own,
        })
    };
    match e {
        Event::Message { target, invoker, message } => {
            let uid = invoker.uid.as_ref().map(|u| tsclientlib_uid(&u.0)).unwrap_or_default();
            let target = match target {
                MessageTarget::Poke(_) => {
                    return listener.on_poke(invoker.id.0, invoker.name.clone(), message.clone());
                }
                MessageTarget::Server => TextTarget::Server,
                MessageTarget::Channel => TextTarget::Channel,
                MessageTarget::Client(id) => {
                    // Our own echo carries the receiver as target; report the conversation partner.
                    let other = if invoker.id == state.own_client { *id } else { invoker.id };
                    TextTarget::Client { id: other.0 }
                }
            };
            listener.on_text_message(TextMessage {
                target,
                from_id: invoker.id.0,
                from_name: invoker.name.clone(),
                from_uid: uid,
                message: message.clone(),
            });
        }
        Event::PropertyAdded { id: PropertyId::Client(id), extra, .. } => {
            if quiet || matches!(extra.reason, Some(Reason::Subscription)) {
                return;
            }
            let Some(c) = state.clients.get(id) else { return };
            let ch = channel_name(state, c.channel);
            let in_own = Some(c.channel) == own_channel;
            match extra.reason {
                Some(Reason::Moved) | Some(Reason::KickChannel) => {
                    log(EventKind::ClientSwitched, format!("{} switched to {ch}", c.name), *id, c.name.clone(), in_own)
                }
                _ => log(EventKind::ClientConnected, format!("{} connected to {ch}", c.name), *id, c.name.clone(), in_own),
            }
        }
        Event::PropertyRemoved { id: PropertyId::Client(id), old: PropertyValue::Client(c), invoker, extra } => {
            let by = invoker.as_ref().map(|i| format!(" by {}", i.name)).unwrap_or_default();
            let in_own = Some(c.channel) == own_channel;
            let (kind, text) = match extra.reason {
                Some(Reason::KickServer) => (EventKind::ClientKickedFromServer, format!("{} was kicked from the server{by}", c.name)),
                Some(Reason::KickServerBan) => (EventKind::ClientBanned, format!("{} was banned{by}", c.name)),
                Some(Reason::LostConnection) => (EventKind::ClientTimedOut, format!("{} timed out", c.name)),
                Some(Reason::KickChannel) => (EventKind::ClientKickedFromChannel, format!("{} was kicked from the channel{by}", c.name)),
                Some(Reason::Moved) | Some(Reason::Subscription) | Some(Reason::None) | Some(Reason::Channelupdate)
                | Some(Reason::Channeledit) => (EventKind::ClientSwitched, format!("{} left your view", c.name)),
                Some(Reason::Serverstop) | Some(Reason::ClientdisconnectServerShutdown) => {
                    (EventKind::ClientDisconnected, format!("{} disconnected (server shutdown)", c.name))
                }
                _ => (EventKind::ClientDisconnected, format!("{} disconnected", c.name)),
            };
            if *id == state.own_client {
                *reason = text.clone();
            }
            log(kind, text, *id, c.name.clone(), in_own);
        }
        Event::PropertyChanged { id: PropertyId::ClientChannel(id), old, invoker, extra } => {
            let Some(c) = state.clients.get(id) else { return };
            let ch = channel_name(state, c.channel);
            let from_own = matches!(old, PropertyValue::ChannelId(o) if Some(*o) == own_channel);
            let in_own = Some(c.channel) == own_channel || from_own;
            let (kind, text) = match (extra.reason, invoker) {
                (Some(Reason::KickChannel), Some(i)) => {
                    (EventKind::ClientKickedFromChannel, format!("{} was kicked to {ch} by {}", name_of(state, *id), i.name))
                }
                (Some(Reason::Moved), Some(i)) if i.id != *id => {
                    (EventKind::ClientMoved, format!("{} was moved to {ch} by {}", name_of(state, *id), i.name))
                }
                _ => (EventKind::ClientSwitched, format!("{} switched to {ch}", name_of(state, *id))),
            };
            log(kind, text, *id, c.name.clone(), in_own);
        }
        Event::PropertyAdded { id: PropertyId::Channel(id), invoker, .. } if !quiet => {
            let by = invoker.as_ref().map(|i| format!(" by {}", i.name)).unwrap_or_default();
            listener.on_log(LogEvent {
                kind: EventKind::ChannelCreated,
                text: format!("Channel {} created{by}", channel_name(state, *id)),
                client_id: invoker.as_ref().map(|i| i.id.0).unwrap_or(0),
                client_name: invoker.as_ref().map(|i| i.name.clone()).unwrap_or_default(),
                own: invoker.as_ref().is_some_and(|i| i.id == state.own_client),
                own_channel: false,
            });
        }
        Event::PropertyRemoved { id: PropertyId::Channel(_), old: PropertyValue::Channel(c), invoker, .. } => {
            let by = invoker.as_ref().map(|i| format!(" by {}", i.name)).unwrap_or_default();
            listener.on_log(LogEvent {
                kind: EventKind::ChannelDeleted,
                text: format!("Channel {} deleted{by}", c.name),
                client_id: invoker.as_ref().map(|i| i.id.0).unwrap_or(0),
                client_name: invoker.as_ref().map(|i| i.name.clone()).unwrap_or_default(),
                own: invoker.as_ref().is_some_and(|i| i.id == state.own_client),
                own_channel: false,
            });
        }
        _ => {}
    }
}

fn tsclientlib_uid(raw: &[u8]) -> String {
    use base64::prelude::*;
    BASE64_STANDARD.encode(raw)
}
