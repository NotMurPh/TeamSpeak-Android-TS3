package com.tsrewrite.client

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import uniffi.tscore.EventKind
import uniffi.tscore.LogEvent
import java.util.Locale

/**
 * Spoken announcements and the TS3 sound pack's poke and message sounds, both on the media stream.
 * While sound is muted (deafened) nothing plays except the cue for unmuting it.
 */
class SoundManager(private val context: Context, private val store: Store, private val deafened: () -> Boolean) {
    enum class Cue { CONNECTED, DISCONNECTED, POKE, PRIVATE_MESSAGE, MIC_MUTED, MIC_UNMUTED, SOUND_MUTED, SOUND_UNMUTED }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun speak(text: String) {
        if (!store.settings.value.announceEvents) return
        android.util.Log.i(TAG, "speak: $text")
        val t = tts ?: TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), null)
            }
        }.also { tts = it }
        // The newest announcement replaces the previous one, so rapid toggles do not pile up.
        if (ttsReady) t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), null)
    }

    /**
     * The TS3 sound pack's samples, played on the same stream as the spoken announcements (media,
     * speech) so they follow the same volume; the poke is levelled to typical TTS loudness.
     */
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    ).build()
    private val messageSound = pool.load(context, R.raw.ts_message, 1)
    private val pokeSound = pool.load(context, R.raw.ts_poke, 1)

    private fun play(id: Int, volume: Float, what: String) {
        if (!store.settings.value.playSounds) return
        android.util.Log.i(TAG, "sound $what")
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun event(cue: Cue, who: String?) {
        val s = store.settings.value
        // Deafened: stay silent, but still confirm the sound toggles themselves.
        if (deafened() && cue != Cue.SOUND_MUTED && cue != Cue.SOUND_UNMUTED) {
            if (cue == Cue.POKE && s.vibrateOnPoke) vibrate()
            return
        }
        when (cue) {
            Cue.CONNECTED -> speak("Connected")
            Cue.DISCONNECTED -> speak("Disconnected from server")
            // Like TS3: the sound pack says "you were poked"; messages get its soft chat blip.
            Cue.POKE -> {
                if (s.announcePokes) play(pokeSound, 0.6f, "poke")
                if (s.vibrateOnPoke) vibrate()
            }
            Cue.PRIVATE_MESSAGE -> {
                play(messageSound, 1f, "message")
                if (s.announceMessages) speak("New message from ${spoken(who)}")
            }
            Cue.MIC_MUTED -> if (s.announceSelf) speak("Microphone muted")
            Cue.MIC_UNMUTED -> if (s.announceSelf) speak("Microphone activated")
            Cue.SOUND_MUTED -> if (s.announceSelf) speak("Sound muted")
            Cue.SOUND_UNMUTED -> if (s.announceSelf) speak("Sound activated")
        }
    }

    /** A name as it should be spoken: letters, digits and simple punctuation only, no icons or art. */
    private fun spoken(name: String?): String {
        val clean = name.orEmpty().filter { it.isLetterOrDigit() || it == ' ' || it in ".-_'" }
            .replace(Regex("[._-]{2,}"), " ").replace(Regex("\\s+"), " ").trim()
        return clean.ifEmpty { "Someone" }
    }

    private fun vibrate() {
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150), -1))
    }

    /** Announces events that concern our own channel or client, like a TS3 sound pack. */
    fun log(e: LogEvent) {
        val s = store.settings.value
        if (deafened()) return
        val allowed = when (e.kind) {
            EventKind.CLIENT_CONNECTED -> s.announceJoins
            EventKind.CLIENT_DISCONNECTED, EventKind.CLIENT_TIMED_OUT -> s.announceLeaves
            EventKind.CLIENT_SWITCHED, EventKind.CLIENT_MOVED -> s.announceMoves
            EventKind.CLIENT_KICKED_FROM_CHANNEL, EventKind.CLIENT_KICKED_FROM_SERVER, EventKind.CLIENT_BANNED -> s.announceKicks
            else -> false
        }
        if (!allowed) return
        if (e.own) {
            when (e.kind) {
                EventKind.CLIENT_SWITCHED -> speak("Channel switched")
                EventKind.CLIENT_MOVED -> speak("You were moved")
                EventKind.CLIENT_KICKED_FROM_CHANNEL -> speak("You were kicked from the channel")
                EventKind.CLIENT_KICKED_FROM_SERVER -> speak("You were kicked from the server")
                EventKind.CLIENT_BANNED -> speak("You were banned")
                else -> {}
            }
            return
        }
        if (!e.ownChannel) return
        when (e.kind) {
            EventKind.CLIENT_CONNECTED, EventKind.CLIENT_SWITCHED, EventKind.CLIENT_MOVED -> speak("${spoken(e.clientName)} ${if (e.text.contains("left")) "left" else "joined"} your channel")
            EventKind.CLIENT_DISCONNECTED -> speak("${spoken(e.clientName)} disconnected")
            EventKind.CLIENT_TIMED_OUT -> speak("${spoken(e.clientName)} lost connection")
            EventKind.CLIENT_KICKED_FROM_CHANNEL -> speak("${spoken(e.clientName)} was kicked from the channel")
            EventKind.CLIENT_KICKED_FROM_SERVER -> speak("${spoken(e.clientName)} was kicked from the server")
            EventKind.CLIENT_BANNED -> speak("${spoken(e.clientName)} was banned")
            else -> {}
        }
    }

    companion object {
        private const val TAG = "Sounds"
    }
}
