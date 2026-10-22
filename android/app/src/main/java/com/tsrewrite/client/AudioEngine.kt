package com.tsrewrite.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import uniffi.tscore.Denoise
import uniffi.tscore.TsClient
import uniffi.tscore.VoiceProcessor

/** What the engine feeds the microphone into and plays from. */
interface VoiceIo {
    /** 20 ms of 48 kHz mono 16-bit PCM from the microphone, already cleaned. */
    fun capture(pcm: ByteArray)
    /** 20 ms of 48 kHz stereo 16-bit PCM to play. */
    fun playback(): ByteArray
}

/** A server connection: mic to the core, mixed voices from the core. */
class ClientIo(private val client: TsClient) : VoiceIo {
    override fun capture(pcm: ByteArray) = client.pushCapture(pcm)
    override fun playback(): ByteArray = client.pullPlayback(AudioEngine.FRAME.toUInt())
}

/**
 * Where voice goes. Everything except a Bluetooth headset microphone uses Android's normal mode with
 * full-band media playback and the full-band microphone (measured on the phone: the call path cuts
 * everything above ~12 kHz); echo, noise and level are handled in software by [VoiceProcessor].
 * A Bluetooth headset mic only exists on the call link (SCO), which needs call mode.
 */
data class AudioRoute(
    val output: Output,
    /** Call mode (Bluetooth headset mic over SCO). */
    val call: Boolean,
    /** Output device for the media track (or the call link's device in call mode). */
    val device: AudioDeviceInfo?,
    /** Preferred microphone, or null for the default one. */
    val mic: AudioDeviceInfo?,
    val label: String,
    /** A wired or Bluetooth headset is connected (whether or not this route uses it). */
    val headsetPresent: Boolean = false,
) {
    // By type and address, not id: Android re-creates a Bluetooth device (new id) as its call link comes and goes.
    val key get() = "$output|$call|${device?.let { "${it.type}:${it.address}" }}|${mic?.let { "${it.type}:${it.address}" }}"

    enum class Output { SPEAKER, EARPIECE, HEADSET, BLUETOOTH, NONE }
}

/** Mic -> [VoiceProcessor] -> [VoiceIo] (48 kHz mono) and [VoiceIo] -> speaker (48 kHz stereo), 20 ms frames. */
class AudioEngine(
    private val context: Context,
    private val store: Store,
    private val io: VoiceIo,
    private val play: Boolean = true,
    /** False only measures the mic: no routing, no playback. */
    private val routed: Boolean = true,
    private val onRoute: (AudioRoute) -> Unit = {},
    /** A temporary choice of SPEAKER or EARPIECE that wins over headsets and settings; null follows them. */
    private val forced: () -> AudioRoute.Output? = { null },
) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private val threads = mutableListOf<Thread>()
    private var route: AudioRoute? = null
    private var started = false
    /** The microphone chain for the current route (shared by the capture and playback threads). */
    @Volatile private var processor: VoiceProcessor? = null

    private val refreshTask = Runnable { refresh() }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            Log.i(TAG, "devices added ${added.joinToString { "${it.type}:${it.id}" }}"); scheduleRefresh()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            Log.i(TAG, "devices removed ${removed.joinToString { "${it.type}:${it.id}" }}"); scheduleRefresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        if (routed) am.registerAudioDeviceCallback(devices, main)
        launch(pickRoute())
    }

    fun stop() {
        if (!started) return
        started = false
        if (routed) am.unregisterAudioDeviceCallback(devices)
        main.removeCallbacks(refreshTask)
        halt()
        if (routed) releaseRoute()
    }

    /** Re-applies settings and devices; re-routes when the route changed. */
    fun refresh() {
        if (!started) return
        val r = pickRoute()
        if (r.key != route?.key) { halt(); launch(r) }
    }

    // Devices appear in steps (e.g. A2DP before SCO); wait for them to settle.
    private fun scheduleRefresh() {
        main.removeCallbacks(refreshTask)
        main.postDelayed(refreshTask, 600)
    }

    private fun launch(r: AudioRoute) {
        route = r
        Log.i(TAG, "route ${r.label} (${r.key})")
        if (routed) applyRoute(r)
        onRoute(r)
        // On the call link Android already cancels echo and suppresses noise (tuned for the headset), and
        // the earbuds clean their mic themselves; stacking WebRTC's suppressor on top of that made voice
        // sound washed out, so there only RNNoise follows (one suppressor, like Discord's Krisp).
        // Elsewhere WebRTC AEC3 cancels echo, fed with exactly what is played.
        processor = VoiceProcessor(!r.call && play, if (r.call) Denoise.RN_NOISE else Denoise.BOTH, true)
            .also { it.setDelayMs(ECHO_DELAY_MS) }
        val g = ++generation
        if (play) threads += Thread({ playbackLoop(g, r) }, "ts-playback").apply { start() }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            threads += Thread({ captureLoop(g, r) }, "ts-capture").apply { start() }
        } else {
            Log.w(TAG, "no microphone permission, listen only")
        }
    }

    private fun halt() {
        generation++
        threads.forEach { it.join(1000) }
        threads.clear()
        processor?.close()
        processor = null
    }

    private fun pickRoute(): AudioRoute {
        if (!routed) return AudioRoute(AudioRoute.Output.NONE, false, null, null, "Microphone")
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val phoneMic = ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val speaker = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val earpiece = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val wired = outs.firstOrNull { it.type in WIRED }
        val wiredMic = ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val btMedia = outs.firstOrNull { it.type in BT_MEDIA }
        val btVoice = if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.firstOrNull { it.type in BT_VOICE }
            else outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val present = wired != null || btMedia != null || btVoice != null
        val s = store.settings.value

        fun phone(onSpeaker: Boolean) = AudioRoute(
            if (onSpeaker) AudioRoute.Output.SPEAKER else AudioRoute.Output.EARPIECE, false,
            if (onSpeaker) speaker else earpiece, phoneMic, if (onSpeaker) "Loudspeaker" else "Phone", present,
        )

        when (forced()) {
            AudioRoute.Output.SPEAKER -> return phone(true)
            AudioRoute.Output.EARPIECE -> return phone(false)
            else -> {}
        }
        // A Bluetooth call keeps going while the same headset is connected: its call link coming up adds
        // and removes devices, and re-routing then would drop and restart the call link, which earbuds
        // hear as hanging up and calling again (and switch their noise cancelling each time).
        val current = route
        if (current != null && current.call && s.useHeadsetMic && wired == null) {
            val address = current.device?.address
            val comm = if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices else emptyList()
            if ((outs.asList() + comm).any { it.address == address && (it.type in BT_VOICE || it.type in BT_MEDIA) }) return current
        }
        // One "Use headset mic" switch for every headset: its own mic when it has one, else the phone's.
        if (wired != null) {
            val headsetMic = s.useHeadsetMic && wiredMic != null
            return AudioRoute(AudioRoute.Output.HEADSET, false, wired, if (headsetMic) wiredMic else phoneMic,
                if (headsetMic) "Headphones" else "Headphones · phone mic", true)
        }
        // Earbuds use their own mic by default, like call apps. Earbuds treat an open mic as a call (Galaxy
        // Buds, for one, apply their call noise-cancelling settings); "Use headset mic" off leaves them playback only.
        // LE Audio earbuds go into a call like Discord does (call mode, the earbuds as communication device):
        // the phone's call voice processing then cleans their mic, which measured clearer than their plain
        // mic with our own suppressors (same LC3 32 kHz link either way). What you hear drops to call
        // quality while the mic is open; that is Bluetooth's two-way link, so "Use headset mic" off restores it.
        val leOut = outs.firstOrNull { it.type == BLE_HEADSET }
        val leMic = ins.firstOrNull { it.type == BLE_HEADSET }
        if (s.useHeadsetMic && leOut != null && leMic != null) {
            val leVoice = if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.firstOrNull { it.type == BLE_HEADSET } else null
            if (leVoice != null) return AudioRoute(AudioRoute.Output.BLUETOOTH, true, leVoice, null, "Bluetooth LE call", true)
            return AudioRoute(AudioRoute.Output.BLUETOOTH, false, leOut, leMic, "Bluetooth LE audio", true)
        }
        // Classic Bluetooth: the buds' mic only exists on the call link (SCO), and Android pauses A2DP
        // while it runs (tested: no A2DP packets go out, even with A2dpSuspended=false), so with the
        // headphone mic on, sound and mic both use the call link.
        if (btVoice != null && btVoice.type != BLE_HEADSET && (s.useHeadsetMic || btMedia == null)) {
            return AudioRoute(AudioRoute.Output.BLUETOOTH, true, btVoice, null, "Bluetooth headset", true)
        }
        if (btMedia != null || btVoice != null) {
            return AudioRoute(AudioRoute.Output.BLUETOOTH, false, btMedia, phoneMic, "Bluetooth · phone mic", true)
        }
        return phone(s.speakerphone)
    }

    @Suppress("DEPRECATION")
    private fun applyRoute(r: AudioRoute) {
        am.mode = if (r.call) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) {
            if (r.call && r.device != null) runCatching { am.setCommunicationDevice(r.device) } else am.clearCommunicationDevice()
        } else if (r.call) {
            am.startBluetoothSco(); am.isBluetoothScoOn = true
        } else if (am.isBluetoothScoOn) {
            am.isBluetoothScoOn = false; am.stopBluetoothSco()
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseRoute() {
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun captureLoop(g: Int, r: AudioRoute) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // The plain microphone is full band; the call source is only used for a Bluetooth headset mic.
        val source = if (r.call) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 4))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord", e); return
        }
        r.mic?.let { rec.preferredDevice = it }
        // On the call link, also use the phone/headset's own echo canceller and noise reduction.
        val effects = if (r.call) listOfNotNull<AudioEffect>(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null,
        ).onEach { runCatching { it.enabled = true } } else emptyList()
        val buf = ByteArray(FRAME * 2)
        try {
            rec.startRecording()
            while (generation == g) {
                var off = 0
                while (off < buf.size && generation == g) {
                    val n = rec.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off != buf.size) continue
                val p = processor
                io.capture(if (p != null && generation == g) p.capture(buf) else buf.copyOf())
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture", e)
        } finally {
            effects.forEach { it.release() }
            runCatching { rec.stop() }; rec.release()
        }
    }

    private fun playbackLoop(g: Int, r: AudioRoute) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val format = AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val usage = if (r.call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, FRAME * 4 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Live voice: the fast output path, not the deep (high latency) media buffer.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (!r.call) r.device?.let { track.preferredDevice = it }
        try {
            track.play()
            while (generation == g) {
                val pcm = io.playback()
                // The echo canceller needs to hear exactly what goes to the speaker.
                processor?.takeIf { generation == g }?.render(pcm, 2u)
                track.write(pcm, 0, pcm.size) // blocks; paces the loop at real time
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback", e)
        } finally {
            runCatching { track.stop() }; track.release()
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val RATE = 48000
        const val FRAME = 960
        /** Render-to-capture delay hint for AEC3 (it also estimates the delay itself). */
        private const val ECHO_DELAY_MS = 120
        private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
        private const val BLE_HEADSET = 26 // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
        private val BT_VOICE = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, BLE_HEADSET)
        private val BT_MEDIA = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */)
    }
}
