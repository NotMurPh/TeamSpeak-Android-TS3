//! Microphone processing like Discord/Meet (WebRTC audio processing, via the pure-Rust `sonora` port),
//! fully in software so it is the same on every phone and keeps the full 48 kHz band:
//!
//! high-pass -> AEC3 echo cancellation (fed with exactly what is played) -> noise suppression ->
//! AGC2 adaptive gain with limiter.

use std::sync::{Arc, Mutex};

use nnnoiseless::DenoiseState;
use sonora::config::{
    AdaptiveDigital, EchoCanceller, GainController2, HighPassFilter, MaxProcessingRate, NoiseSuppression,
    NoiseSuppressionLevel, Pipeline,
};
use sonora::{AudioProcessing, Config, StreamConfig};

/// 10 ms at 48 kHz, the frame size of both WebRTC and RNNoise.
const N: usize = 480;

/// Which noise suppressor runs between echo cancellation and gain control.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Denoise {
    Off,
    /// WebRTC's spectral suppressor (stationary noise: fans, hum, hiss).
    WebRtc,
    /// RNNoise (learned; also typing and other non-stationary noise).
    RnNoise,
    /// WebRTC first, then RNNoise.
    Both,
}

struct Inner {
    /// High-pass + AEC3 (+ WebRTC NS when selected).
    front: AudioProcessing,
    rnn: Option<Box<DenoiseState<'static>>>,
    /// AGC2 adaptive digital gain + limiter.
    agc: Option<AudioProcessing>,
    voice: f32,
    rnn_in: [f32; N],
    rnn_out: [f32; N],
}

fn stream() -> StreamConfig {
    StreamConfig::new(48000, 1)
}

fn pipeline() -> Pipeline {
    // The default internal rate is 32 kHz, which would cut everything above 16 kHz.
    Pipeline { maximum_internal_processing_rate: MaxProcessingRate::Rate48kHz, ..Default::default() }
}

fn build(echo: bool, denoise: Denoise, auto_gain: bool) -> Inner {
    let webrtc_ns = matches!(denoise, Denoise::WebRtc | Denoise::Both);
    let front = AudioProcessing::builder()
        .config(Config {
            pipeline: pipeline(),
            high_pass_filter: Some(HighPassFilter { apply_in_full_band: true }),
            echo_canceller: echo.then(EchoCanceller::default),
            noise_suppression: webrtc_ns.then(|| NoiseSuppression { level: NoiseSuppressionLevel::High, ..Default::default() }),
            ..Default::default()
        })
        .capture_config(stream())
        .render_config(StreamConfig::new(48000, 1))
        .build();
    let agc = auto_gain.then(|| AudioProcessing::builder()
        .config(Config {
            pipeline: pipeline(),
            gain_controller2: Some(GainController2 {
                adaptive_digital: Some(AdaptiveDigital {
                    // Speech peaks a few dB under full scale; grow at most 6 dB/s so it never pumps.
                    headroom_db: 5.0,
                    max_gain_db: 30.0,
                    initial_gain_db: 10.0,
                    max_gain_change_db_per_second: 6.0,
                    max_output_noise_level_dbfs: -55.0,
                }),
                ..Default::default()
            }),
            ..Default::default()
        })
        .capture_config(stream())
        .build());
    let rnn = matches!(denoise, Denoise::RnNoise | Denoise::Both).then(DenoiseState::new);
    Inner { front, rnn, agc, voice: 0.0, rnn_in: [0.0; N], rnn_out: [0.0; N] }
}

/// The microphone chain. Feed what is played with [`VoiceProcessor::render`] and the microphone
/// with [`VoiceProcessor::capture`], both 48 kHz 16-bit little-endian.
#[derive(uniffi::Object)]
pub struct VoiceProcessor {
    inner: Mutex<Inner>,
}

#[uniffi::export]
impl VoiceProcessor {
    #[uniffi::constructor]
    pub fn new(echo_cancel: bool, denoise: Denoise, auto_gain: bool) -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(build(echo_cancel, denoise, auto_gain)) })
    }

    /// Time from a frame being passed to `render` until its sound reaches `capture` (ms).
    pub fn set_delay_ms(&self, ms: i32) {
        let _ = self.inner.lock().unwrap().front.set_stream_delay_ms(ms);
    }

    /// The far end: interleaved stereo (or mono when `channels` is 1) as it is written to the speaker.
    pub fn render(&self, pcm: Vec<u8>, channels: u8) {
        let ch = channels.max(1) as usize;
        let samples: Vec<f32> = pcm.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0).collect();
        let mono: Vec<f32> = samples.chunks_exact(ch).map(|f| f.iter().sum::<f32>() / ch as f32).collect();
        let mut s = self.inner.lock().unwrap();
        let mut out = [0f32; N];
        for frame in mono.chunks_exact(N) {
            let _ = s.front.process_render_f32(&[frame], &mut [&mut out[..]]);
        }
    }

    /// Processes mono microphone samples; the output has the same length.
    pub fn capture(&self, pcm: Vec<u8>) -> Vec<u8> {
        let mut s = self.inner.lock().unwrap();
        let mut out = pcm.clone();
        for (src, dst) in pcm.chunks_exact(N * 2).zip(out.chunks_exact_mut(N * 2)) {
            let mut a = [0f32; N];
            for (i, b) in src.chunks_exact(2).enumerate() {
                a[i] = i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0;
            }
            let mut b = [0f32; N];
            let _ = s.front.process_capture_f32(&[&a], &mut [&mut b[..]]);
            let Inner { rnn, rnn_in, rnn_out, voice, agc, .. } = &mut *s;
            if let Some(rnn) = rnn {
                for i in 0..N { rnn_in[i] = b[i] * 32768.0; }
                *voice = rnn.process_frame(rnn_out, rnn_in);
                for i in 0..N { b[i] = rnn_out[i] / 32768.0; }
            }
            match agc {
                Some(agc) => { let _ = agc.process_capture_f32(&[&b], &mut [&mut a[..]]); }
                None => a = b,
            }
            for (i, d) in dst.chunks_exact_mut(2).enumerate() {
                let v = (a[i] * 32767.0).clamp(-32768.0, 32767.0) as i16;
                d.copy_from_slice(&v.to_le_bytes());
            }
        }
        out
    }

    /// Speech probability of the last frame (RNNoise only).
    pub fn voice_probability(&self) -> f32 {
        self.inner.lock().unwrap().voice
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_wav(path: &str) -> Vec<i16> {
        let wav = std::fs::read(path).unwrap();
        let data = wav.windows(4).position(|w| w == b"data").unwrap() + 8;
        wav[data..].chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect()
    }

    fn write_wav(path: &str, s: &[i16]) {
        let mut v = Vec::new();
        let n = (s.len() * 2) as u32;
        v.extend_from_slice(b"RIFF"); v.extend_from_slice(&(36 + n).to_le_bytes()); v.extend_from_slice(b"WAVEfmt ");
        v.extend_from_slice(&16u32.to_le_bytes()); v.extend_from_slice(&1u16.to_le_bytes()); v.extend_from_slice(&1u16.to_le_bytes());
        v.extend_from_slice(&48000u32.to_le_bytes()); v.extend_from_slice(&96000u32.to_le_bytes());
        v.extend_from_slice(&2u16.to_le_bytes()); v.extend_from_slice(&16u16.to_le_bytes());
        v.extend_from_slice(b"data"); v.extend_from_slice(&n.to_le_bytes());
        for x in s { v.extend_from_slice(&x.to_le_bytes()); }
        std::fs::write(path, v).unwrap();
    }

    /// Writes processed versions of `TS_SPEECH_WAV` (+ fan noise) for each denoiser into `TS_OUT_DIR`.
    #[test]
    #[ignore]
    fn render_variants() {
        let (Ok(src), Ok(dir)) = (std::env::var("TS_SPEECH_WAV"), std::env::var("TS_OUT_DIR")) else { return };
        let clean = read_wav(&src);
        let mut seed = 12345u32;
        let mut lp = 0f32;
        let noisy: Vec<i16> = clean.iter().map(|&c| {
            seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
            let w = (seed >> 16) as i16 as f32 / 32768.0;
            lp += (w - lp) * 0.05;
            c.saturating_add(((lp * 6.0 + w * 0.3) * 0.02 * 32768.0) as i16)
        }).collect();
        write_wav(&format!("{dir}/in_noisy.wav"), &noisy);
        // A quiet talker (20 dB down) should come out at the same level once AGC2 has adapted.
        let quiet: Vec<i16> = noisy.iter().map(|&x| (x as f32 * 0.1) as i16).collect();
        let p = VoiceProcessor::new(true, Denoise::Both, true);
        let mut out = Vec::new();
        for _ in 0..3 {
            out.clear();
            for f in quiet.chunks_exact(960) {
                p.render(vec![0u8; 960 * 4], 2);
                let o = p.capture(f.iter().flat_map(|x| x.to_le_bytes()).collect());
                out.extend(o.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
            }
        }
        write_wav(&format!("{dir}/out_quiet.wav"), &out);
        for (name, d) in [("off", Denoise::Off), ("webrtc", Denoise::WebRtc), ("rnnoise", Denoise::RnNoise), ("both", Denoise::Both)] {
            let p = VoiceProcessor::new(true, d, true);
            let mut out = Vec::new();
            for f in noisy.chunks_exact(960) {
                p.render(vec![0u8; 960 * 4], 2);
                let o = p.capture(f.iter().flat_map(|x| x.to_le_bytes()).collect());
                out.extend(o.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
            }
            write_wav(&format!("{dir}/out_{name}.wav"), &out);
        }
    }
}

#[cfg(test)]
mod double_talk {
    use super::*;

    /// Simulated loudspeaker echo (delay + decaying reflections) of a far-end talker, with a near-end
    /// talker speaking over part of it. Writes `dt_in.wav`, `dt_out.wav` and the near-end alone.
    #[test]
    #[ignore]
    fn echo_and_double_talk() {
        let (Ok(src), Ok(dir)) = (std::env::var("TS_SPEECH_WAV"), std::env::var("TS_OUT_DIR")) else { return };
        let wav = std::fs::read(&src).unwrap();
        let data = wav.windows(4).position(|w| w == b"data").unwrap() + 8;
        let speech: Vec<f32> = wav[data..].chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0).collect();
        let len = speech.len();
        // Far end: the speech; near end: the same speech shifted by 9 s and quieter (another "person").
        let far = speech.clone();
        let near: Vec<f32> = (0..len).map(|i| if i >= 48000 * 9 { speech[i - 48000 * 9] * 0.5 } else { 0.0 }).collect();
        // Echo path: 40 ms delay, -6 dB direct, a few decaying reflections.
        let mut seed = 99u32;
        let mut ir = vec![0f32; 48000 / 10];
        for (i, t) in ir.iter_mut().enumerate().skip(1920) {
            seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
            let r = ((seed >> 16) as i16 as f32) / 32768.0;
            *t = r * 0.25 * (-((i - 1920) as f32) / 600.0).exp();
        }
        ir[1920] = 0.5;
        let mut mic = vec![0f32; len];
        for (n, m) in mic.iter_mut().enumerate() {
            let mut e = 0f32;
            for (k, h) in ir.iter().enumerate().skip(1920) {
                if *h != 0.0 && n >= k { e += far[n - k] * h; }
            }
            *m = e + near[n];
        }
        let to16 = |v: &[f32]| -> Vec<i16> { v.iter().map(|x| (x * 32767.0).clamp(-32768.0, 32767.0) as i16).collect() };
        let w = |name: &str, v: &[i16]| {
            let mut b = Vec::new();
            let n = (v.len() * 2) as u32;
            b.extend_from_slice(b"RIFF"); b.extend_from_slice(&(36 + n).to_le_bytes()); b.extend_from_slice(b"WAVEfmt ");
            b.extend_from_slice(&16u32.to_le_bytes()); b.extend_from_slice(&1u16.to_le_bytes()); b.extend_from_slice(&1u16.to_le_bytes());
            b.extend_from_slice(&48000u32.to_le_bytes()); b.extend_from_slice(&96000u32.to_le_bytes());
            b.extend_from_slice(&2u16.to_le_bytes()); b.extend_from_slice(&16u16.to_le_bytes());
            b.extend_from_slice(b"data"); b.extend_from_slice(&n.to_le_bytes());
            for x in v { b.extend_from_slice(&x.to_le_bytes()); }
            std::fs::write(format!("{dir}/{name}"), b).unwrap();
        };
        let (far16, mic16) = (to16(&far), to16(&mic));
        w("dt_near.wav", &to16(&near));
        w("dt_in.wav", &mic16);
        let p = VoiceProcessor::new(true, Denoise::Both, true);
        p.set_delay_ms(40);
        let mut out = Vec::new();
        for (f, m) in far16.chunks_exact(960).zip(mic16.chunks_exact(960)) {
            p.render(f.iter().flat_map(|x| x.to_le_bytes()).collect(), 1);
            let o = p.capture(m.iter().flat_map(|x| x.to_le_bytes()).collect());
            out.extend(o.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
        }
        w("dt_out.wav", &out);
    }
}
