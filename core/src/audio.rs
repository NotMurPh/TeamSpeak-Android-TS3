//! Capture (mic -> Opus packets) and playback (packets -> mixed PCM).
//! The platform owns the audio devices: 48 kHz mono i16 in, 48 kHz stereo interleaved i16 out.

use std::collections::{HashMap, HashSet};

use audiopus::coder::Encoder;
use audiopus::{Application, Bandwidth, Bitrate, Channels, SampleRate};
use tsclientlib::audio::AudioHandler;
use tsproto_packets::packets::{AudioData, CodecType, InAudioBuf, OutAudio, OutPacket};

pub const FRAME: usize = 960; // 20 ms at 48 kHz

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum VoiceMode {
    PushToTalk,
    VoiceActivation,
    Continuous,
}

/// Whisper recipients: whole channels and/or single clients.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct WhisperTarget {
    pub channels: Vec<u64>,
    pub clients: Vec<u16>,
}

pub struct Capture {
    encoder: Encoder,
    pub whisper: Option<WhisperTarget>,
    pending: Vec<i16>,
    out: Vec<u8>,
    pub mode: VoiceMode,
    pub ptt: bool,
    pub vad_threshold_db: f32,
    pub gain: f32,
    hangover: u32,
    pub transmitting: bool,
    pub level_db: f32,
}

/// Frames kept open after voice drops below the threshold (400 ms).
const HANGOVER_FRAMES: u32 = 20;

impl Capture {
    pub fn new() -> Self {
        // Audio mode: the mic is already cleaned (WebRTC processing), so keep every detail.
        let mut encoder = Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Audio)
            .expect("opus encoder");
        // Best quality rather than saving bandwidth: full band, highest complexity, and in-band FEC so
        // a lost packet is rebuilt from the next one instead of becoming a gap.
        let _ = encoder.set_bitrate(Bitrate::BitsPerSecond(96_000));
        let _ = encoder.set_complexity(10);
        let _ = encoder.set_max_bandwidth(Bandwidth::Fullband);
        let _ = encoder.set_inband_fec(true);
        let _ = encoder.set_packet_loss_perc(5);
        Self {
            encoder,
            whisper: None,
            pending: Vec::with_capacity(FRAME * 2),
            out: vec![0; 1275],
            mode: VoiceMode::PushToTalk,
            ptt: false,
            vad_threshold_db: -45.0,
            gain: 1.0,
            hangover: 0,
            transmitting: false,
            level_db: -100.0,
        }
    }

    /// Quality is always the best: requests below 96 kbps are raised.
    pub fn set_bitrate(&mut self, bps: i32) {
        let _ = self.encoder.set_bitrate(Bitrate::BitsPerSecond(bps.max(96_000)));
    }

    /// Feeds mic samples; returns packets to send and whether transmission state changed.
    pub fn process(&mut self, pcm: &[i16], can_send: bool) -> (Vec<OutPacket>, Option<bool>) {
        self.pending.extend_from_slice(pcm);
        let mut packets = Vec::new();
        let was = self.transmitting;
        let mut frame = [0f32; FRAME];
        while self.pending.len() >= FRAME {
            let mut sum = 0f64;
            for (dst, src) in frame.iter_mut().zip(self.pending.drain(..FRAME)) {
                let v = (src as f32 / 32768.0 * self.gain).clamp(-1.0, 1.0);
                sum += (v * v) as f64;
                *dst = v;
            }
            self.level_db = 10.0 * ((sum / FRAME as f64).max(1e-10)).log10() as f32;
            let want = can_send
                && if self.whisper.is_some() { self.ptt } else { match self.mode {
                    VoiceMode::PushToTalk => self.ptt,
                    VoiceMode::Continuous => true,
                    VoiceMode::VoiceActivation => {
                        if self.level_db >= self.vad_threshold_db {
                            self.hangover = HANGOVER_FRAMES;
                        } else {
                            self.hangover = self.hangover.saturating_sub(1);
                        }
                        self.hangover > 0
                    }
                } };
            if want {
                if let Ok(len) = self.encoder.encode_float(&frame, &mut self.out) {
                    packets.push(voice_packet(&self.out[..len], self.whisper.as_ref()));
                }
                self.transmitting = true;
            } else if self.transmitting {
                // An empty voice packet ends the stream on the receiving side.
                packets.push(voice_packet(&[], self.whisper.as_ref()));
                self.transmitting = false;
            }
        }
        let changed = (was != self.transmitting).then_some(self.transmitting);
        (packets, changed)
    }

    /// Stops transmission immediately (e.g. PTT released while muted).
    pub fn stop(&mut self) -> Option<OutPacket> {
        self.pending.clear();
        self.hangover = 0;
        if self.transmitting {
            self.transmitting = false;
            Some(voice_packet(&[], self.whisper.as_ref()))
        } else {
            None
        }
    }
}

fn voice_packet(data: &[u8], whisper: Option<&WhisperTarget>) -> OutPacket {
    match whisper {
        Some(w) => OutAudio::new(&AudioData::C2SWhisper {
            id: 0,
            codec: CodecType::OpusVoice,
            channels: w.channels.clone(),
            clients: w.clients.clone(),
            data,
        }),
        None => OutAudio::new(&AudioData::C2S { id: 0, codec: CodecType::OpusVoice, data }),
    }
}

pub struct Playback {
    handler: AudioHandler<u16>,
    pub volumes: HashMap<u16, f32>,
    pub muted: HashSet<u16>,
    pub master: f32,
    pub output_muted: bool,
    pub block_whispers: bool,
    /// Per-speaker automatic gain control (like TS3's playback normalisation).
    pub agc: bool,
    voices: HashMap<u16, VoiceAgc>,
    limiter: f32,
    buf: Vec<f32>,
}

/// Brings one speaker towards a common loudness: quiet voices are raised (up to +18 dB), loud ones lowered.
#[derive(Default)]
struct VoiceAgc {
    /// Smoothed speech level (RMS, linear); 0 until the speaker first talks.
    level: f32,
    gain: f32,
}

/// Target speech level, about -16 dBFS RMS (loud on a phone speaker; the limiter catches peaks).
const AGC_TARGET: f32 = 0.16;
const AGC_MAX_GAIN: f32 = 8.0;
const AGC_MIN_GAIN: f32 = 0.35;
/// Frames quieter than this (-48 dBFS) are pauses and do not move the level.
const AGC_GATE: f32 = 0.004;
/// The mix is kept below this peak (-1 dBFS) by the limiter.
const LIMIT: f32 = 0.89;

impl VoiceAgc {
    fn gain_for(&mut self, data: &[f32]) -> f32 {
        let rms = (data.iter().map(|s| s * s).sum::<f32>() / data.len().max(1) as f32).sqrt();
        if rms > AGC_GATE {
            if self.level == 0.0 {
                // A new speaker starts at the right level instead of ramping up.
                self.level = rms;
                self.gain = (AGC_TARGET / rms).clamp(AGC_MIN_GAIN, AGC_MAX_GAIN);
            } else {
                // The level follows rises quickly and falls slowly (~1 s), so words keep their dynamics.
                let k = if rms > self.level { 0.25 } else { 0.05 };
                self.level += (rms - self.level) * k;
            }
        }
        if self.level == 0.0 {
            return 1.0;
        }
        let want = (AGC_TARGET / self.level).clamp(AGC_MIN_GAIN, AGC_MAX_GAIN);
        // Turn down fast, turn up gently.
        let k = if want < self.gain { 0.3 } else { 0.04 };
        self.gain += (want - self.gain) * k;
        self.gain
    }
}

impl Playback {
    pub fn new() -> Self {
        Self {
            handler: AudioHandler::new(),
            volumes: HashMap::new(),
            muted: HashSet::new(),
            master: 1.0,
            output_muted: false,
            block_whispers: false,
            agc: true,
            voices: HashMap::new(),
            limiter: 1.0,
            buf: Vec::new(),
        }
    }

    pub fn reset(&mut self) {
        self.handler.reset();
        self.voices.clear();
    }

    /// Returns `Some(id)` when that client started talking.
    pub fn handle_packet(&mut self, from: u16, packet: InAudioBuf, whisper: bool) -> Option<u16> {
        if self.muted.contains(&from) || (whisper && self.block_whispers) {
            return None;
        }
        let started = self.handler.handle_packet(from, packet).ok().flatten();
        if let Some(q) = self.handler.get_mut_queues().get_mut(&from) {
            q.volume = self.volumes.get(&from).copied().unwrap_or(1.0);
        }
        started
    }

    /// Mixes `frames` stereo frames. Returns the PCM and clients that stopped talking.
    pub fn fill(&mut self, frames: usize) -> (Vec<i16>, Vec<u16>) {
        self.buf.clear();
        self.buf.resize(frames * 2, 0.0);
        let agc = self.agc;
        let voices = &mut self.voices;
        let stopped = self.handler.fill_buffer_with_gain(&mut self.buf, |id, data| {
            if agc { voices.entry(*id).or_default().gain_for(data) } else { 1.0 }
        });
        let master = if self.output_muted { 0.0 } else { self.master };
        // Peak limiter: instant attack, ~50 ms release, then a soft knee so peaks never hard-clip.
        let mut g = self.limiter;
        let mut pcm = Vec::with_capacity(self.buf.len());
        for frame in self.buf.chunks_exact(2) {
            let peak = frame[0].abs().max(frame[1].abs()) * master;
            let want = if peak > LIMIT { LIMIT / peak } else { 1.0 };
            if want < g { g = want } else { g += (want - g) * 0.0004 }
            for &s in frame {
                let v = soft_clip(s * master * g);
                pcm.push((v * 32767.0) as i16);
            }
        }
        self.limiter = g;
        (pcm, stopped)
    }
}

/// Linear up to 0.9, then bends smoothly towards 1.0.
fn soft_clip(x: f32) -> f32 {
    let a = x.abs();
    if a <= 0.9 { x } else { x.signum() * (0.9 + 0.1 * ((a - 0.9) / 0.1).tanh()) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(amp: f32) -> Vec<f32> {
        (0..FRAME * 2).map(|i| amp * (i as f32 * 0.07).sin()).collect()
    }

    fn rms_out(agc: &mut VoiceAgc, amp: f32, frames: usize) -> f32 {
        let f = frame(amp);
        let mut g = 1.0;
        for _ in 0..frames { g = agc.gain_for(&f); }
        let r = (f.iter().map(|s| s * s).sum::<f32>() / f.len() as f32).sqrt();
        r * g
    }

    #[test]
    fn quiet_and_loud_voices_meet_the_target() {
        let quiet = rms_out(&mut VoiceAgc::default(), 0.03, 100); // about -33 dBFS RMS
        let loud = rms_out(&mut VoiceAgc::default(), 0.5, 100); // about -9 dBFS RMS
        for v in [quiet, loud] {
            assert!((v / AGC_TARGET - 1.0).abs() < 0.2, "level {v} vs target {AGC_TARGET}");
        }
    }

    #[test]
    fn pauses_do_not_raise_the_gain() {
        let mut agc = VoiceAgc::default();
        rms_out(&mut agc, 0.2, 50);
        let before = agc.gain;
        rms_out(&mut agc, 0.001, 200); // background hiss between words
        assert!((agc.gain - before).abs() < 0.01, "gain drifted {before} -> {}", agc.gain);
    }

    #[test]
    fn soft_clip_stays_in_range() {
        for x in [-5.0f32, -1.0, -0.95, 0.0, 0.5, 0.95, 1.0, 5.0] {
            let y = soft_clip(x);
            assert!(y.abs() <= 1.0 && (x.abs() <= 0.9) == (y == x), "{x} -> {y}");
        }
    }
}
