package com.tsrewrite.client

import android.content.Context
import kotlin.math.log10

/**
 * Plays the microphone back so the user can hear themselves, through the same engine, routing, echo
 * cancellation and noise suppression as a real connection; reports the level.
 */
class MicTest(private val context: Context, private val store: Store) : VoiceIo {
    @Volatile var levelDb = -100f
        private set
    private var engine: AudioEngine? = null
    @Volatile private var silent = false
    private val queue = ArrayDeque<ByteArray>()

    /** `silent` only measures the level (for the voice activation meter). */
    fun start(silent: Boolean = false) {
        if (engine != null) return
        this.silent = silent
        synchronized(queue) { queue.clear() }
        engine = AudioEngine(context, store, this, play = !silent, routed = !silent).also { it.start() }
    }

    fun stop() {
        engine?.stop()
        engine = null
        levelDb = -100f
    }

    /** Applies changed settings (e.g. echo cancellation toggled while testing). */
    fun refresh() = engine?.refresh()

    override fun capture(pcm: ByteArray) {
        val gain = store.settings.value.micGain
        val out = ByteArray(pcm.size)
        var sum = 0.0
        for (i in 0 until pcm.size / 2) {
            val s = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
            val v = (s * gain).coerceIn(-32768f, 32767f).toInt()
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
            sum += (v / 32768.0) * (v / 32768.0)
        }
        levelDb = (10 * log10(maxOf(sum / (pcm.size / 2), 1e-10))).toFloat()
        if (silent) return
        synchronized(queue) {
            queue.addLast(out)
            while (queue.size > 4) queue.removeFirst() // keep the delay short
        }
    }

    override fun playback(): ByteArray {
        val mono = synchronized(queue) { queue.removeFirstOrNull() } ?: return ByteArray(AudioEngine.FRAME * 4)
        val stereo = ByteArray(mono.size * 2)
        for (i in 0 until mono.size / 2) {
            stereo[4 * i] = mono[2 * i]; stereo[4 * i + 1] = mono[2 * i + 1]
            stereo[4 * i + 2] = mono[2 * i]; stereo[4 * i + 3] = mono[2 * i + 1]
        }
        return stereo
    }
}
