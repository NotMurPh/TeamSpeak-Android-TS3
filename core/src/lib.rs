//! TeamSpeak 3 client core: connection, state, voice, files. Exposed to Kotlin via UniFFI.

mod apm;
mod audio;
mod denoise;
mod raw;
mod session;
mod view;

use std::collections::HashSet;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use tokio::sync::mpsc;
use tsclientlib::Identity;
use tsproto_packets::packets::{Direction, Flags, OutCommand, PacketType};

pub use audio::{VoiceMode, WhisperTarget};
pub use apm::{Denoise, VoiceProcessor};
pub use denoise::Denoiser;
pub use view::{ChannelView, ClientView, GroupView, ServerView};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TsError {
    #[error("{0}")]
    Failed(String),
}

type Result<T> = std::result::Result<T, TsError>;

fn fail(e: impl std::fmt::Display) -> TsError {
    TsError::Failed(e.to_string())
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ConnectOptions {
    /// host, host:port, ip:port or TS3 server nickname
    pub address: String,
    pub nickname: String,
    /// Exported TS3 identity (`<counter>V<key>`); a new one is created when empty.
    pub identity: String,
    pub server_password: Option<String>,
    /// Default channel path ("Lobby/Sub") or `/<id>`
    pub channel: Option<String>,
    pub channel_password: Option<String>,
    pub input_muted: bool,
    pub output_muted: bool,
    /// Subscribe to all channels after connecting (like the original app).
    pub subscribe_all: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Status {
    Connecting,
    Connected,
    Reconnecting,
    Disconnected,
}

#[derive(Clone, Debug, uniffi::Enum)]
pub enum TextTarget {
    Server,
    Channel,
    Client { id: u16 },
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct TextMessage {
    pub target: TextTarget,
    pub from_id: u16,
    pub from_name: String,
    pub from_uid: String,
    pub message: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct KeyValue {
    pub key: String,
    pub value: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum EventKind {
    ClientConnected,
    ClientDisconnected,
    ClientTimedOut,
    ClientKickedFromServer,
    ClientBanned,
    ClientKickedFromChannel,
    /// Moved by someone else.
    ClientMoved,
    /// Switched channel by itself.
    ClientSwitched,
    ChannelCreated,
    ChannelDeleted,
    Other,
}

/// Something visible happened that the UI may log or announce.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LogEvent {
    pub kind: EventKind,
    pub text: String,
    pub client_id: u16,
    pub client_name: String,
    /// The event happened to our own client.
    pub own: bool,
    /// A client entered or left our current channel.
    pub own_channel: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CommandFailure {
    pub code: u32,
    pub message: String,
    pub missing_permission: Option<String>,
}

#[derive(Clone, Debug, Default, uniffi::Record)]
pub struct ConnectionStats {
    pub ping_ms: f32,
    pub ping_deviation_ms: f32,
    pub packets_sent: u64,
    pub packets_received: u64,
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub bandwidth_sent_last_second: u64,
    pub bandwidth_received_last_second: u64,
    /// Smoothed (about 20 s) packet loss, 0..1, server to us and us to server.
    pub packet_loss_in: f32,
    pub packet_loss_out: f32,
}

#[uniffi::export(with_foreign)]
pub trait EventListener: Send + Sync {
    fn on_status(&self, status: Status, message: String);
    /// Server state changed; call `snapshot()`.
    fn on_state_changed(&self);
    fn on_text_message(&self, msg: TextMessage);
    fn on_poke(&self, from_id: u16, from_name: String, message: String);
    fn on_talk_status(&self, client_id: u16, talking: bool, whisper: bool);
    fn on_log(&self, event: LogEvent);
    /// Result of a command started with a request id; `error` is None on success.
    fn on_command_result(&self, request_id: u32, error: Option<CommandFailure>);
    /// The identity was upgraded to a higher security level; persist it.
    fn on_identity_changed(&self, identity: String);
    /// A server notification not modelled in the snapshot, e.g. `notifybanlist`. One map per list entry.
    fn on_notification(&self, name: String, parts: Vec<Vec<KeyValue>>);
    /// File transfer progress; `finished` with `error` None means success.
    fn on_transfer(&self, transfer_id: u32, done: u64, total: u64, finished: bool, error: Option<String>);
}

pub(crate) enum Cmd {
    Command(OutCommand, u32),
    Audio(tsproto_packets::packets::OutPacket),
    Download { id: u32, channel: u64, path: String, password: String, dest: String },
    Upload { id: u32, channel: u64, path: String, password: String, src: String, overwrite: bool },
    Disconnect(String),
}

pub(crate) struct Shared {
    pub snapshot: Mutex<Option<ServerView>>,
    pub capture: Mutex<audio::Capture>,
    pub playback: Mutex<audio::Playback>,
    pub talking: Mutex<HashSet<u16>>,
    pub can_send: Mutex<bool>,
    pub own_id: Mutex<u16>,
    pub stats: Mutex<ConnectionStats>,
}

struct Session {
    tx: mpsc::UnboundedSender<Cmd>,
    thread: Option<JoinHandle<()>>,
}

#[derive(uniffi::Object)]
pub struct TsClient {
    listener: Arc<dyn EventListener>,
    shared: Arc<Shared>,
    session: Mutex<Option<Session>>,
    next_id: AtomicU32,
}

#[uniffi::export]
impl TsClient {
    #[uniffi::constructor]
    pub fn new(listener: Arc<dyn EventListener>) -> Arc<Self> {
        init_logging();
        Arc::new(Self {
            listener,
            shared: Arc::new(Shared {
                snapshot: Mutex::new(None),
                capture: Mutex::new(audio::Capture::new()),
                playback: Mutex::new(audio::Playback::new()),
                talking: Mutex::new(HashSet::new()),
                can_send: Mutex::new(false),
                own_id: Mutex::new(0),
                stats: Mutex::new(ConnectionStats::default()),
            }),
            session: Mutex::new(None),
            next_id: AtomicU32::new(1),
        })
    }

    /// Starts connecting in the background; progress arrives via `on_status`.
    pub fn connect(&self, options: ConnectOptions) -> Result<()> {
        self.disconnect("".into());
        let identity = if options.identity.trim().is_empty() {
            Identity::create()
        } else {
            Identity::new_from_str(options.identity.trim()).map_err(fail)?
        };
        let (tx, rx) = mpsc::unbounded_channel();
        let listener = self.listener.clone();
        let shared = self.shared.clone();
        *shared.snapshot.lock().unwrap() = None;
        shared.playback.lock().unwrap().reset();
        shared.talking.lock().unwrap().clear();
        let thread = std::thread::Builder::new()
            .name("ts-session".into())
            .spawn(move || session::run(options, identity, shared, listener, rx))
            .map_err(fail)?;
        *self.session.lock().unwrap() = Some(Session { tx, thread: Some(thread) });
        Ok(())
    }

    /// Disconnects and waits for the session thread to finish.
    pub fn disconnect(&self, message: String) {
        let session = self.session.lock().unwrap().take();
        if let Some(mut s) = session {
            let _ = s.tx.send(Cmd::Disconnect(message));
            if let Some(t) = s.thread.take() {
                let _ = t.join();
            }
        }
    }

    pub fn is_connected(&self) -> bool {
        self.shared.snapshot.lock().unwrap().is_some()
    }

    pub fn snapshot(&self) -> Option<ServerView> {
        self.shared.snapshot.lock().unwrap().clone()
    }

    pub fn connection_stats(&self) -> ConnectionStats {
        self.shared.stats.lock().unwrap().clone()
    }

    // ---- audio ----

    /// Feed 48 kHz mono 16-bit little-endian PCM from the microphone (any length).
    pub fn push_capture(&self, pcm: Vec<u8>) {
        let pcm: Vec<i16> = pcm.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
        let can_send = *self.shared.can_send.lock().unwrap();
        let (packets, changed, whisper) = {
            let mut c = self.shared.capture.lock().unwrap();
            let (p, ch) = c.process(&pcm, can_send);
            (p, ch, c.whisper.is_some())
        };
        for p in packets {
            self.send(Cmd::Audio(p));
        }
        if let Some(t) = changed {
            self.own_talking(t, whisper);
        }
    }

    /// Pull `frames` stereo frames of mixed 48 kHz playback as 16-bit little-endian PCM.
    pub fn pull_playback(&self, frames: u32) -> Vec<u8> {
        let (pcm, stopped) = self.shared.playback.lock().unwrap().fill(frames as usize);
        if !stopped.is_empty() {
            let mut talking = self.shared.talking.lock().unwrap();
            for id in stopped {
                if talking.remove(&id) {
                    self.listener.on_talk_status(id, false, false);
                }
            }
        }
        pcm.iter().flat_map(|s| s.to_le_bytes()).collect()
    }

    pub fn set_voice_mode(&self, mode: VoiceMode) {
        self.shared.capture.lock().unwrap().mode = mode;
    }

    pub fn set_push_to_talk(&self, pressed: bool) {
        let stop = {
            let mut c = self.shared.capture.lock().unwrap();
            c.ptt = pressed;
            if !pressed && (c.mode == VoiceMode::PushToTalk || c.whisper.is_some()) { c.stop() } else { None }
        };
        if let Some(p) = stop {
            self.send(Cmd::Audio(p));
            self.own_talking(false, false);
        }
    }

    /// While set, transmitted voice is whispered to these targets (hold-to-whisper).
    /// Whispering always transmits while `ptt` is held, regardless of the voice mode.
    pub fn set_whisper(&self, target: Option<WhisperTarget>) {
        let stop = {
            let mut c = self.shared.capture.lock().unwrap();
            let changed = c.whisper.is_some() != target.is_some();
            c.whisper = target;
            if changed { c.stop() } else { None }
        };
        if let Some(p) = stop {
            self.send(Cmd::Audio(p));
            self.own_talking(false, false);
        }
    }

    pub fn set_vad_threshold(&self, db: f32) {
        self.shared.capture.lock().unwrap().vad_threshold_db = db;
    }

    pub fn set_mic_gain(&self, gain: f32) {
        self.shared.capture.lock().unwrap().gain = gain;
    }

    pub fn set_bitrate(&self, bps: i32) {
        self.shared.capture.lock().unwrap().set_bitrate(bps);
    }

    /// Current microphone level in dBFS, for a VAD meter.
    pub fn mic_level(&self) -> f32 {
        self.shared.capture.lock().unwrap().level_db
    }

    pub fn set_master_volume(&self, volume: f32) {
        self.shared.playback.lock().unwrap().master = volume;
    }

    /// Ignore incoming whispers (TS3 "whisper allow list" off).
    /// Per-speaker automatic gain control for playback (on by default).
    pub fn set_playback_agc(&self, on: bool) {
        self.shared.playback.lock().unwrap().agc = on;
    }

    pub fn set_block_whispers(&self, block: bool) {
        self.shared.playback.lock().unwrap().block_whispers = block;
    }

    pub fn set_client_volume(&self, client_id: u16, volume: f32) {
        self.shared.playback.lock().unwrap().volumes.insert(client_id, volume);
    }

    pub fn set_client_locally_muted(&self, client_id: u16, muted: bool) {
        let mut p = self.shared.playback.lock().unwrap();
        if muted { p.muted.insert(client_id); } else { p.muted.remove(&client_id); }
        drop(p);
        self.refresh();
    }

    // ---- self ----

    pub fn set_input_muted(&self, muted: bool) -> u32 {
        if muted {
            if let Some(p) = self.shared.capture.lock().unwrap().stop() {
                self.send(Cmd::Audio(p));
                self.own_talking(false, false);
            }
        }
        self.client_update(&[("client_input_muted", b(muted))])
    }

    pub fn set_output_muted(&self, muted: bool) -> u32 {
        self.shared.playback.lock().unwrap().output_muted = muted;
        self.client_update(&[("client_output_muted", b(muted))])
    }

    /// `None` clears away.
    pub fn set_away(&self, message: Option<String>) -> u32 {
        match message {
            Some(m) => self.client_update(&[("client_away", "1".into()), ("client_away_message", m)]),
            None => self.client_update(&[("client_away", "0".into()), ("client_away_message", "".into())]),
        }
    }

    pub fn set_nickname(&self, name: String) -> u32 {
        self.client_update(&[("client_nickname", name)])
    }

    pub fn set_channel_commander(&self, on: bool) -> u32 {
        self.client_update(&[("client_is_channel_commander", b(on))])
    }

    pub fn request_talk_power(&self, request: bool, message: String) -> u32 {
        self.client_update(&[("client_talk_request", b(request)), ("client_talk_request_msg", message)])
    }

    /// `clientupdate` with arbitrary own properties.
    pub fn update_self(&self, properties: Vec<KeyValue>) -> u32 {
        self.command_kv("clientupdate", vec![], properties)
    }

    // ---- chat ----

    pub fn send_text(&self, target: TextTarget, message: String) -> u32 {
        let (mode, id) = match target {
            TextTarget::Client { id } => (1, id as u64),
            TextTarget::Channel => (2, 0),
            TextTarget::Server => (3, 0),
        };
        self.command("sendtextmessage", &[("targetmode", mode.to_string()), ("target", id.to_string()), ("msg", message)])
    }

    pub fn poke(&self, client_id: u16, message: String) -> u32 {
        self.command("clientpoke", &[("clid", client_id.to_string()), ("msg", message)])
    }

    /// Tells a private chat partner that we are typing.
    pub fn chat_composing(&self, client_id: u16) -> u32 {
        self.command("clientchatcomposing", &[("clid", client_id.to_string())])
    }

    // ---- moderation ----

    /// `password` is the plain channel password; it is hashed when sent.
    pub fn move_client(&self, client_id: u16, channel_id: u64, password: Option<String>) -> u32 {
        let mut args = vec![("clid", client_id.to_string()), ("cid", channel_id.to_string())];
        if let Some(p) = password.filter(|p| !p.is_empty()) {
            args.push(("cpw", p));
        }
        self.command("clientmove", &args)
    }

    pub fn move_self(&self, channel_id: u64, password: Option<String>) -> u32 {
        let own = *self.shared.own_id.lock().unwrap();
        self.move_client(own, channel_id, password)
    }

    pub fn kick_from_channel(&self, client_id: u16, reason: String) -> u32 {
        self.command("clientkick", &[("clid", client_id.to_string()), ("reasonid", "4".into()), ("reasonmsg", reason)])
    }

    pub fn kick_from_server(&self, client_id: u16, reason: String) -> u32 {
        self.command("clientkick", &[("clid", client_id.to_string()), ("reasonid", "5".into()), ("reasonmsg", reason)])
    }

    /// `seconds` = 0 bans permanently.
    pub fn ban_client(&self, client_id: u16, seconds: u64, reason: String) -> u32 {
        self.command("banclient", &[("clid", client_id.to_string()), ("time", seconds.to_string()), ("banreason", reason)])
    }

    /// `clientedit` with arbitrary properties (description, talker, ...).
    pub fn edit_client(&self, client_id: u16, properties: Vec<KeyValue>) -> u32 {
        self.command_kv("clientedit", vec![("clid".into(), client_id.to_string())], properties)
    }

    pub fn add_server_group(&self, group_id: u64, client_db_id: u64) -> u32 {
        self.command("servergroupaddclient", &[("sgid", group_id.to_string()), ("cldbid", client_db_id.to_string())])
    }

    pub fn remove_server_group(&self, group_id: u64, client_db_id: u64) -> u32 {
        self.command("servergroupdelclient", &[("sgid", group_id.to_string()), ("cldbid", client_db_id.to_string())])
    }

    pub fn set_channel_group(&self, group_id: u64, channel_id: u64, client_db_id: u64) -> u32 {
        self.command("setclientchannelgroup", &[("cgid", group_id.to_string()), ("cid", channel_id.to_string()), ("cldbid", client_db_id.to_string())])
    }

    pub fn use_privilege_key(&self, token: String) -> u32 {
        self.command("privilegekeyuse", &[("token", token)])
    }

    // ---- channels ----

    pub fn subscribe(&self, channel_id: u64, subscribe: bool) -> u32 {
        let name = if subscribe { "channelsubscribe" } else { "channelunsubscribe" };
        self.command(name, &[("cid", channel_id.to_string())])
    }

    pub fn subscribe_all(&self, subscribe: bool) -> u32 {
        self.command(if subscribe { "channelsubscribeall" } else { "channelunsubscribeall" }, &[])
    }

    /// Properties use the TS3 names (`channel_name`, `channel_topic`, `channel_password`, `cpid`, ...).
    pub fn create_channel(&self, properties: Vec<KeyValue>) -> u32 {
        self.command_kv("channelcreate", vec![], properties)
    }

    pub fn edit_channel(&self, channel_id: u64, properties: Vec<KeyValue>) -> u32 {
        self.command_kv("channeledit", vec![("cid".into(), channel_id.to_string())], properties)
    }

    pub fn delete_channel(&self, channel_id: u64, force: bool) -> u32 {
        self.command("channeldelete", &[("cid", channel_id.to_string()), ("force", b(force))])
    }

    pub fn move_channel(&self, channel_id: u64, parent_id: u64, order: u64) -> u32 {
        self.command("channelmove", &[("cid", channel_id.to_string()), ("cpid", parent_id.to_string()), ("order", order.to_string())])
    }

    pub fn request_channel_description(&self, channel_id: u64) -> u32 {
        self.command("channelgetdescription", &[("cid", channel_id.to_string())])
    }

    pub fn request_client_variables(&self, client_id: u16) -> u32 {
        self.command("clientgetvariables", &[("clid", client_id.to_string())])
    }

    /// Server answers with `notifyconnectioninfo`.
    pub fn request_connection_info(&self, client_id: u16) -> u32 {
        self.command("getconnectioninfo", &[("clid", client_id.to_string())])
    }

    // ---- server ----

    pub fn edit_server(&self, properties: Vec<KeyValue>) -> u32 {
        self.command_kv("serveredit", vec![], properties)
    }

    /// Any TS3 client command (`banlist`, `permissionlist`, `ftgetfilelist`, ...). Answers arrive via
    /// `on_notification`, completion via `on_command_result` with the returned id.
    pub fn send_command(&self, name: String, args: Vec<KeyValue>) -> u32 {
        self.command_kv(&name, vec![], args)
    }

    /// Sends a command with several list entries (`name a=1 b=2|a=3 b=4`), e.g. permission edits.
    pub fn send_command_list(&self, name: String, parts: Vec<Vec<KeyValue>>) -> u32 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let mut cmd = OutCommand::new(Direction::C2S, Flags::empty(), PacketType::Command, &name);
        for (i, part) in parts.iter().enumerate() {
            if i > 0 {
                cmd.start_new_part();
            }
            for kv in part {
                cmd.write_arg(&kv.key, &kv.value);
            }
        }
        self.send(Cmd::Command(cmd, id));
        id
    }

    // ---- files ----

    /// Downloads `path` from a channel's file storage (channel 0 = server icons/avatars) to `dest`.
    pub fn download_file(&self, channel_id: u64, path: String, password: String, dest: String) -> u32 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.send(Cmd::Download { id, channel: channel_id, path, password, dest });
        id
    }

    pub fn upload_file(&self, channel_id: u64, path: String, password: String, src: String, overwrite: bool) -> u32 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.send(Cmd::Upload { id, channel: channel_id, path, password, src, overwrite });
        id
    }
}

impl TsClient {
    fn send(&self, cmd: Cmd) {
        if let Some(s) = self.session.lock().unwrap().as_ref() {
            let _ = s.tx.send(cmd);
        }
    }

    fn command(&self, name: &str, args: &[(&str, String)]) -> u32 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.send(Cmd::Command(build_command(name, args.iter().map(|(k, v)| (*k, v.as_str()))), id));
        id
    }

    fn command_kv(&self, name: &str, mut fixed: Vec<(String, String)>, rest: Vec<KeyValue>) -> u32 {
        fixed.extend(rest.into_iter().map(|kv| (kv.key, kv.value)));
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.send(Cmd::Command(build_command(name, fixed.iter().map(|(k, v)| (k.as_str(), v.as_str()))), id));
        id
    }

    fn client_update(&self, args: &[(&str, String)]) -> u32 {
        self.command("clientupdate", args)
    }

    fn own_talking(&self, talking: bool, whisper: bool) {
        let own = *self.shared.own_id.lock().unwrap();
        if own != 0 {
            self.listener.on_talk_status(own, talking, whisper);
        }
    }

    fn refresh(&self) {
        self.listener.on_state_changed();
    }
}

fn b(v: bool) -> String {
    if v { "1" } else { "0" }.into()
}

pub(crate) fn build_command<'a>(name: &str, args: impl Iterator<Item = (&'a str, &'a str)>) -> OutCommand {
    let mut cmd = OutCommand::new(Direction::C2S, Flags::empty(), PacketType::Command, name);
    for (k, v) in args {
        if HASHED_ARGS.contains(&k) && !v.is_empty() {
            cmd.write_arg(k, &tsproto_types::crypto::encode_password(v.as_bytes()));
        } else {
            cmd.write_arg(k, &v);
        }
    }
    cmd
}

/// Channel and server passwords travel as base64(sha1(password)), like the official client sends them;
/// servers compare those hashes, so a plain password never matches.
const HASHED_ARGS: [&str; 4] = ["cpw", "tcpw", "channel_password", "virtualserver_password"];

pub(crate) fn export_identity(id: &Identity) -> String {
    format!("{}V{}", id.counter(), id.key().to_ts_obfuscated())
}

/// Creates a new identity (security level 8), exported in TS3 format.
#[uniffi::export]
pub fn create_identity() -> String {
    export_identity(&Identity::create())
}

/// Unique id (base64) of an exported identity.
#[uniffi::export]
pub fn identity_uid(identity: String) -> Result<String> {
    let id = Identity::new_from_str(identity.trim()).map_err(fail)?;
    Ok(id.key().to_pub().get_uid())
}

#[uniffi::export]
pub fn identity_level(identity: String) -> Result<u8> {
    Ok(Identity::new_from_str(identity.trim()).map_err(fail)?.level())
}

/// Raises the security level (hashcash); CPU heavy, run off the UI thread.
#[uniffi::export]
pub fn improve_identity(identity: String, level: u8) -> Result<String> {
    let mut id = Identity::new_from_str(identity.trim()).map_err(fail)?;
    id.upgrade_level(level);
    Ok(export_identity(&id))
}

/// File name of a client's avatar in channel 0 (`/avatar_<uid hex mapped to a..p>`).
#[uniffi::export]
pub fn avatar_path(uid: String) -> String {
    use base64::prelude::*;
    let raw = BASE64_STANDARD.decode(uid.trim()).unwrap_or_default();
    let mapped: String = raw.iter().flat_map(|b| [b >> 4, b & 0xf]).map(|n| (b'a' + n) as char).collect();
    format!("/avatar_{mapped}")
}

/// Parses a TS3 command line (`name k=v|k=v`) into its name and parts; for tests and tools.
#[uniffi::export]
pub fn parse_command(line: String) -> Vec<Vec<KeyValue>> {
    raw::parse(&line).1
}

fn init_logging() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::layer::SubscriberExt;
            if let Ok(layer) = tracing_android::layer("tscore") {
                let _ = tracing::subscriber::set_global_default(
                    tracing_subscriber::registry().with(layer).with(tracing_subscriber::filter::LevelFilter::INFO),
                );
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = tracing_subscriber::fmt().with_env_filter(
                std::env::var("RUST_LOG").unwrap_or_else(|_| "warn".into()),
            ).try_init();
        }
    });
}
