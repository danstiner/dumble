package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Short in-call audio cues via [ToneGenerator] on the voice-call stream, so they mix into the call
 * audio (earpiece / BT / speaker). Lazily opens the generator; [release] frees it when the call ends
 * (re-created on the next cue). ToneGenerator construction can throw when audio resources are scarce,
 * so it's guarded — a failed cue must never take down a call.
 *
 * All public methods are @Synchronized: chat() fires from the TCP-reader thread while join()/leave()
 * fire from the sessionScope collector, so unsynchronized lazy-init could construct (and leak) two
 * generators, and release() could race a play. tones() is only called from inside those methods.
 */
class SoundCues {
    private var gen: ToneGenerator? = null

    private fun tones(): ToneGenerator? = gen ?: runCatching {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME)
    }.onFailure { Log.w(TAG, "ToneGenerator init failed", it) }.getOrNull()?.also { gen = it }

    @Synchronized fun join() { tones()?.startTone(ToneGenerator.TONE_PROP_ACK) }   // ascending blip
    @Synchronized fun leave() { tones()?.startTone(ToneGenerator.TONE_PROP_NACK) } // descending blip
    @Synchronized fun chat() { tones()?.startTone(ToneGenerator.TONE_PROP_BEEP) }  // soft beep
    @Synchronized fun release() { runCatching { gen?.release() }; gen = null }

    companion object { private const val TAG = "SoundCues"; private const val VOLUME = 80 } // 0..100
}
