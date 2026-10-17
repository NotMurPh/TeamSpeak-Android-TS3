//! Microphone noise suppression: RNNoise (via nnnoiseless) followed by a voice-gated residual
//! attenuator that quietens what RNNoise leaves between words (hiss, keyboard, breathing).

use std::sync::{Arc, Mutex};

use nnnoiseless::DenoiseState;

const N: usize = DenoiseState::FRAME_SIZE; // 480 samples = 10 ms at 48 kHz
/// Gain applied when no voice is detected (about -18 dB).
const FLOOR: f32 = 0.125;
/// 10 ms frames kept fully open after voice ends, so word endings are not cut.
const HOLD_FRAMES: u32 = 25;
/// Voice probability above which a frame counts as speech.
const VOICE: f32 = 0.6;

struct State {
    rnn: Box<DenoiseState<'static>>,
    input: [f32; N],
    output: [f32; N],
    gain: f32,
    hold: u32,
    voice: f32,
    first: bool,
}

/// Denoises 48 kHz mono 16-bit PCM. Not tied to a connection, so the mic test can use it too.
#[derive(uniffi::Object)]
pub struct Denoiser {
    state: Mutex<State>,
}

#[uniffi::export]
impl Denoiser {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(State {
                rnn: DenoiseState::new(),
                input: [0.0; N],
                output: [0.0; N],
                gain: 1.0,
                hold: 0,
                voice: 0.0,
                first: true,
            }),
        })
    }

    /// Cleans little-endian i16 samples; the output has the same length. Whole 10 ms blocks are
    /// processed, a trailing partial block passes through unchanged.
    pub fn process(&self, pcm: Vec<u8>) -> Vec<u8> {
        let mut s = self.state.lock().unwrap();
        let mut out = pcm.clone();
        for (src, dst) in pcm.chunks_exact(N * 2).zip(out.chunks_exact_mut(N * 2)) {
            for (i, b) in src.chunks_exact(2).enumerate() {
                s.input[i] = i16::from_le_bytes([b[0], b[1]]) as f32;
            }
            let State { rnn, input, output, .. } = &mut *s;
            let p = rnn.process_frame(output, input);
            s.voice = p;
            // RNNoise fades in over its first frame; drop it rather than emit a click.
            if s.first {
                s.first = false;
                s.output = [0.0; N];
            }
            if p >= VOICE {
                s.hold = HOLD_FRAMES;
            } else {
                s.hold = s.hold.saturating_sub(1);
            }
            let target = if s.hold > 0 { 1.0 } else { FLOOR };
            // Open within ~5 ms, close over ~60 ms; ramp per sample to avoid zipper noise.
            let rate = if target > s.gain { 0.2 } else { 0.015 };
            let mut g = s.gain;
            for (i, d) in dst.chunks_exact_mut(2).enumerate() {
                if i % 48 == 0 {
                    g += (target - g) * rate;
                }
                let v = (s.output[i] * g).clamp(-32768.0, 32767.0) as i16;
                d.copy_from_slice(&v.to_le_bytes());
            }
            s.gain = g;
        }
        out
    }

    /// Speech probability (0..1) of the last processed block.
    pub fn voice_probability(&self) -> f32 {
        self.state.lock().unwrap().voice
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn to_bytes(v: &[i16]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_le_bytes()).collect()
    }

    fn rms(v: &[i16]) -> f64 {
        (v.iter().map(|&x| (x as f64).powi(2)).sum::<f64>() / v.len().max(1) as f64).sqrt()
    }

    fn db(x: f64) -> f64 {
        20.0 * (x / 32768.0).max(1e-9).log10()
    }

    /// Fan-like noise: low-passed white noise plus some hiss.
    fn noise(n: usize, level: f32) -> Vec<i16> {
        let mut seed = 12345u32;
        let mut lp = 0f32;
        (0..n)
            .map(|_| {
                seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
                let w = (seed >> 16) as i16 as f32 / 32768.0;
                lp += (w - lp) * 0.05;
                ((lp * 6.0 + w * 0.3) * level * 32768.0) as i16
            })
            .collect()
    }

    fn run(input: &[i16]) -> Vec<i16> {
        let d = Denoiser::new();
        let mut out = Vec::new();
        for f in input.chunks_exact(960) {
            let o = d.process(to_bytes(f));
            out.extend(o.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
        }
        out
    }

    #[test]
    fn background_noise_is_suppressed() {
        let n = 48000 * 3;
        let input = noise(n, 0.02);
        let out = run(&input);
        let (a, b) = (rms(&input[n / 2..]), rms(&out[n / 2..]));
        assert!(db(b) < db(a) - 15.0, "noise {:.1} dB -> {:.1} dB", db(a), db(b));
    }

    /// Speech in noise: set TS_SPEECH_WAV to a 48 kHz mono 16-bit WAV.
    #[test]
    #[ignore]
    fn speech_survives() {
        let Ok(path) = std::env::var("TS_SPEECH_WAV") else { return };
        let wav = std::fs::read(path).unwrap();
        let data = wav.windows(4).position(|w| w == b"data").unwrap() + 8;
        let clean: Vec<i16> = wav[data..].chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
        let nz = noise(clean.len(), 0.02);
        let mixed: Vec<i16> = clean.iter().zip(&nz).map(|(&c, &z)| c.saturating_add(z)).collect();
        let out = run(&mixed);
        let (mut sp_in, mut sp_out, mut si_in, mut si_out, mut ns, mut nq) = (0.0, 0.0, 0.0, 0.0, 0, 0);
        for i in (0..clean.len() / 960).skip(5) {
            let r = i * 960..(i + 1) * 960;
            if rms(&clean[r.clone()]) > 1000.0 {
                sp_in += rms(&mixed[r.clone()]); sp_out += rms(&out[r]); ns += 1;
            } else if rms(&clean[r.clone()]) < 30.0 {
                si_in += rms(&mixed[r.clone()]); si_out += rms(&out[r]); nq += 1;
            }
        }
        let (sp_in, sp_out, si_in, si_out) = (sp_in / ns as f64, sp_out / ns as f64, si_in / nq as f64, si_out / nq as f64);
        eprintln!("speech {:.1} -> {:.1} dB, pauses {:.1} -> {:.1} dB ({ns} speech, {nq} pause frames)", db(sp_in), db(sp_out), db(si_in), db(si_out));
        assert!(db(sp_out) > db(sp_in) - 3.0);
        assert!(db(si_out) < db(si_in) - 20.0);
    }
}
