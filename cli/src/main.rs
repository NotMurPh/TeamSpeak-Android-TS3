//! Desktop test client for the core. Drives the same API the Android app uses.
//!
//! tscli <address> <nick> [script] [identity-file]
//! Script/stdin commands: tree | msg <t> | cmsg <t> | pm <id> <t> | poke <id> <t> | move <cid> [pw]
//!   | nick <n> | mute <0|1> | deaf <0|1> | away [msg] | tone <0|1> | level | sleep <ms> | raw <cmd> k=v.. | quit

use std::io::BufRead;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tscore::*;

struct Printer {
    name: String,
}

impl EventListener for Printer {
    fn on_status(&self, status: Status, message: String) {
        println!("[{}] status {status:?} {message}", self.name);
    }
    fn on_state_changed(&self) {}
    fn on_text_message(&self, m: TextMessage) {
        println!("[{}] text {:?} from {}({}): {}", self.name, m.target, m.from_name, m.from_id, m.message);
    }
    fn on_poke(&self, from_id: u16, from_name: String, message: String) {
        println!("[{}] poke from {from_name}({from_id}): {message}", self.name);
    }
    fn on_talk_status(&self, client_id: u16, talking: bool, whisper: bool) {
        println!("[{}] talk {client_id} {talking}{}", self.name, if whisper { " (whisper)" } else { "" });
    }
    fn on_log(&self, e: LogEvent) {
        println!("[{}] log {:?} {}{}", self.name, e.kind, e.text, if e.own_channel { " (own channel)" } else { "" });
    }
    fn on_command_result(&self, id: u32, error: Option<CommandFailure>) {
        if let Some(e) = error {
            println!("[{}] error #{id} {} {}", self.name, e.message, e.missing_permission.unwrap_or_default());
        }
    }
    fn on_transfer(&self, id: u32, done: u64, total: u64, finished: bool, error: Option<String>) {
        if finished {
            println!("[{}] transfer #{id} finished {done}/{total} {}", self.name, error.unwrap_or_else(|| "ok".into()));
        }
    }
    fn on_identity_changed(&self, identity: String) {
        println!("[{}] identity changed ({} chars)", self.name, identity.len());
    }
    fn on_notification(&self, name: String, parts: Vec<Vec<KeyValue>>) {
        let first: Vec<String> = parts.first().map(|p| p.iter().take(6).map(|kv| format!("{}={}", kv.key, kv.value)).collect()).unwrap_or_default();
        println!("[{}] notify {name} ({} parts) {}", self.name, parts.len(), first.join(" "));
    }
}

fn print_tree(v: &ServerView) {
    println!("== {} ({} {}), me={} in {}", v.name, v.platform, v.version, v.own_client_id, v.own_channel_id);
    for c in &v.channels {
        println!("{}#{} {}{}", "  ".repeat(c.depth as usize), c.id, c.name, if c.has_password { " [pw]" } else { "" });
        for cl in v.clients.iter().filter(|cl| cl.channel_id == c.id) {
            println!(
                "{}  - {} ({}){}{}{} badges={}",
                "  ".repeat(c.depth as usize),
                cl.name,
                cl.id,
                if cl.input_muted { " [mic off]" } else { "" },
                if cl.away { " [away]" } else { "" },
                if cl.talking { " [talking]" } else { "" },
                cl.badges,
            );
        }
    }
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let address = args.get(1).cloned().unwrap_or_else(|| "127.0.0.1".into());
    let nick = args.get(2).cloned().unwrap_or_else(|| "tscli".into());
    let script = args.get(3).cloned();

    let client = TsClient::new(Arc::new(Printer { name: nick.clone() }));
    // Optional 4th arg: identity file, created on first use and updated on upgrades.
    let id_file = args.get(4).cloned();
    let identity = match id_file.as_ref().and_then(|f| std::fs::read_to_string(f).ok()) {
        Some(s) => s.trim().to_string(),
        None => {
            let id = create_identity();
            if let Some(f) = &id_file { std::fs::write(f, &id)?; }
            id
        }
    };
    println!("identity uid {} level {}", identity_uid(identity.clone())?, identity_level(identity.clone())?);
    client.connect(ConnectOptions {
        address,
        nickname: nick.clone(),
        identity,
        server_password: None,
        channel: None,
        channel_password: None,
        input_muted: false,
        output_muted: false,
        subscribe_all: true,
    })?;

    // Audio pump: 20 ms ticks, optional 440 Hz tone out, playback energy in.
    let tone = Arc::new(AtomicBool::new(false));
    let energy = Arc::new(Mutex::new(0f64));
    let samples_heard = Arc::new(AtomicU64::new(0));
    // `play <wav>` streams a 48 kHz mono WAV as our voice; `record` keeps everything we hear (mono).
    let to_play: Arc<Mutex<std::collections::VecDeque<i16>>> = Default::default();
    let recorded: Arc<Mutex<Vec<i16>>> = Default::default();
    {
        let (client, tone, energy, heard) = (client.clone(), tone.clone(), energy.clone(), samples_heard.clone());
        let (to_play, recorded) = (to_play.clone(), recorded.clone());
        std::thread::spawn(move || {
            let mut phase = 0f32;
            let start = std::time::Instant::now();
            let mut tick = 0u64;
            loop {
                tick += 1;
                let due = start + Duration::from_millis(20 * tick);
                if let Some(d) = due.checked_duration_since(std::time::Instant::now()) { std::thread::sleep(d); }
                let mut q = to_play.lock().unwrap();
                let pcm: Vec<i16> = if !q.is_empty() {
                    (0..960).map(|_| q.pop_front().unwrap_or(0)).collect()
                } else if tone.load(Ordering::Relaxed) {
                    (0..960).map(|_| { phase += 440.0 * std::f32::consts::TAU / 48000.0; (phase.sin() * 8000.0) as i16 }).collect()
                } else {
                    vec![0; 960]
                };
                drop(q);
                client.push_capture(pcm.iter().flat_map(|s| s.to_le_bytes()).collect());
                let out: Vec<i16> = client.pull_playback(960).chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
                recorded.lock().unwrap().extend(out.chunks_exact(2).map(|f| ((f[0] as i32 + f[1] as i32) / 2) as i16));
                let e: f64 = out.iter().map(|s| (*s as f64).powi(2)).sum();
                if e > 0.0 {
                    heard.fetch_add(out.iter().filter(|s| **s != 0).count() as u64, Ordering::Relaxed);
                }
                *energy.lock().unwrap() += e;
            }
        });
    }
    client.set_voice_mode(VoiceMode::VoiceActivation);

    let mut lines: Box<dyn Iterator<Item = String>> = match script {
        Some(s) => Box::new(s.split(';').map(|l| l.trim().to_string()).collect::<Vec<_>>().into_iter()),
        None => Box::new(std::io::stdin().lock().lines().map_while(Result::ok).collect::<Vec<_>>().into_iter()),
    };
    // Wait for the connection before running commands.
    for _ in 0..100 {
        if client.is_connected() { break; }
        std::thread::sleep(Duration::from_millis(100));
    }
    while let Some(line) = lines.next() {
        // `@Name` resolves to that client's id, `#Name` to a channel id.
        let line = line.split(' ').map(|w| {
            let snap = client.snapshot();
            match (w.chars().next(), snap) {
                (Some('@'), Some(v)) => v.clients.iter().find(|c| c.name == w[1..]).map(|c| c.id.to_string()).unwrap_or(w.into()),
                (Some('#'), Some(v)) => v.channels.iter().find(|c| c.name.replace(' ', "_") == w[1..]).map(|c| c.id.to_string()).unwrap_or(w.into()),
                _ => w.to_string(),
            }
        }).collect::<Vec<_>>().join(" ");
        let mut parts = line.splitn(3, ' ');
        let cmd = parts.next().unwrap_or("");
        let a = parts.next().unwrap_or("").to_string();
        let rest = parts.next().unwrap_or("").to_string();
        let both = if rest.is_empty() { a.clone() } else { format!("{a} {rest}") };
        let run = || -> anyhow::Result<()> { match cmd {
            "tree" => match client.snapshot() { Some(v) => print_tree(&v), None => println!("not connected") },
            "msg" => { client.send_text(TextTarget::Server, both); }
            "cmsg" => { client.send_text(TextTarget::Channel, both); }
            "pm" => { client.send_text(TextTarget::Client { id: a.parse()? }, rest); }
            "poke" => { client.poke(a.parse()?, rest); }
            "move" => { client.move_self(a.parse()?, (!rest.is_empty()).then_some(rest)); }
            "nick" => { client.set_nickname(both); }
            "mute" => { client.set_input_muted(a == "1"); }
            "deaf" => { client.set_output_muted(a == "1"); }
            "away" => { client.set_away((!both.is_empty()).then_some(both)); }
            "whisper" => {
                // whisper <clientid> : hold-to-whisper a tone to one client
                client.set_whisper(Some(WhisperTarget { channels: vec![], clients: vec![a.parse()?] }));
                client.set_push_to_talk(true);
                tone.store(true, Ordering::Relaxed);
            }
            "unwhisper" => { tone.store(false, Ordering::Relaxed); client.set_push_to_talk(false); client.set_whisper(None); }
            "upload" => { client.upload_file(a.parse()?, format!("/{}", std::path::Path::new(&rest).file_name().unwrap().to_string_lossy()), String::new(), rest, true); }
            "download" => { let (path, dest) = rest.split_once(' ').unwrap_or((&rest, "download.bin")); client.download_file(a.parse()?, path.into(), String::new(), dest.into()); }
            "icons" => {
                // icons <dir> : downloads every group/channel/client icon of the server into <dir>
                if let Some(v) = client.snapshot() {
                    let mut ids: Vec<u32> = v.server_groups.iter().chain(v.channel_groups.iter()).map(|g| g.icon_id).collect();
                    ids.extend(v.clients.iter().map(|c| c.icon_id));
                    ids.sort(); ids.dedup();
                    let _ = std::fs::create_dir_all(&both);
                    for id in ids.into_iter().filter(|&i| i >= 1000 || (i as i32) < 0) {
                        println!("[{nick}] icon {id}");
                        client.download_file(0, format!("/icon_{id}"), String::new(), format!("{both}/icon_{id}"));
                    }
                }
            }
            "play" => {
                let wav = std::fs::read(&both)?;
                let data = wav.windows(4).position(|w| w == b"data").unwrap() + 8;
                to_play.lock().unwrap().extend(wav[data..].chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
            }
            "record" => {
                // record <wav>: writes what was heard so far (48 kHz mono) and clears the buffer.
                let r = std::mem::take(&mut *recorded.lock().unwrap());
                let n = (r.len() * 2) as u32;
                let mut v = Vec::new();
                v.extend_from_slice(b"RIFF"); v.extend_from_slice(&(36 + n).to_le_bytes()); v.extend_from_slice(b"WAVEfmt ");
                v.extend_from_slice(&16u32.to_le_bytes()); v.extend_from_slice(&1u16.to_le_bytes()); v.extend_from_slice(&1u16.to_le_bytes());
                v.extend_from_slice(&48000u32.to_le_bytes()); v.extend_from_slice(&96000u32.to_le_bytes());
                v.extend_from_slice(&2u16.to_le_bytes()); v.extend_from_slice(&16u16.to_le_bytes());
                v.extend_from_slice(b"data"); v.extend_from_slice(&n.to_le_bytes());
                for x in &r { v.extend_from_slice(&x.to_le_bytes()); }
                std::fs::write(&both, v)?;
                println!("[{nick}] recorded {:.1} s to {both}", r.len() as f64 / 48000.0);
            }
            "clear" => { recorded.lock().unwrap().clear(); }
            "stats" => println!("[{nick}] stats {:?}", client.connection_stats()),
            "tone" => tone.store(a == "1", Ordering::Relaxed),
            "level" => println!("[{nick}] playback energy {:.0}, nonzero samples {}", *energy.lock().unwrap(), samples_heard.load(Ordering::Relaxed)),
            "sleep" => std::thread::sleep(Duration::from_millis(a.parse()?)),
            "key" => { client.use_privilege_key(both); }
            "mkchan" => { client.create_channel(vec![KeyValue { key: "channel_name".into(), value: both }, KeyValue { key: "channel_flag_permanent".into(), value: "1".into() }]); }
            "delchan" => { client.delete_channel(a.parse()?, true); }
            "kick" => { client.kick_from_channel(a.parse()?, rest); }
            "raw" => {
                let kv = rest.split_whitespace().filter_map(|p| p.split_once('=')).map(|(k, v)| KeyValue { key: k.into(), value: v.into() }).collect();
                client.send_command(a, kv);
            }
            "" | "quit" => {}
            other => println!("unknown command {other}"),
        } Ok(()) };
        if cmd == "quit" { break; }
        if let Err(e) = run() { println!("[{nick}] command error: {e}"); }
    }
    client.disconnect("bye".into());
    Ok(())
}
