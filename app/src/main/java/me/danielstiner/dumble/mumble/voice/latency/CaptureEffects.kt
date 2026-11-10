package me.danielstiner.dumble.mumble.voice.latency

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor

/**
 * The framework's echo canceller and noise suppressor turned off on Dumble's capture session for a
 * latency check, and put back by [restore]. With them on, a phone cancels its own speaker out of
 * its microphone and the check cannot hear its probe (measured: correlation 0.12–0.37 on, 0.92
 * off). Only the framework's software effects are reachable here: a canceller inside a device's
 * audio HAL is not, and there the check reports "not detected".
 */
class CaptureEffects private constructor(private val held: List<Held>) {
    private class Held(val name: String, val effect: AudioEffect, val wasEnabled: Boolean)

    /** The effects found on the session, empty where the framework offers none: the check's record
     *  of what it turned off, since [allOff] is vacuously true with nothing found. */
    val names: List<String> get() = held.map { it.name }

    fun allOff(): Boolean = held.all { !it.effect.enabled }

    /** Back as found, and released. Once. */
    fun restore() {
        held.forEach {
            runCatching { it.effect.setEnabled(it.wasEnabled) }
            it.effect.release()
        }
    }

    companion object {
        const val ECHO_CANCELLER = "echo canceller"
        const val NOISE_SUPPRESSOR = "noise suppressor"

        /**
         * On [sessionId], from `CaptureSessionId`. Call with capture open: opening an input attaches
         * the configured pre-processing and enables it (AOSP `AudioPolicyEffects`; not verified on a
         * device), so a disable before the stream opens, or a reopen mid-check, is undone and
         * [allOff] read at the end of a check shows it.
         */
        fun disable(sessionId: Int): CaptureEffects {
            val held = buildList {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)?.let { add(Held(ECHO_CANCELLER, it, it.enabled)) }
                }
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(sessionId)?.let { add(Held(NOISE_SUPPRESSOR, it, it.enabled)) }
                }
            }
            held.forEach { runCatching { it.effect.setEnabled(false) } }
            return CaptureEffects(held)
        }
    }
}
