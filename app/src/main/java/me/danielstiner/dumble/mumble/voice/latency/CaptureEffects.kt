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
    private class Held(val name: String, val effect: AudioEffect, val wasEnabled: Boolean, val turnedOff: Boolean)

    /** The effects found on the session, empty where the framework offers none: the check's record
     *  of what it turned off, since [allOff] is vacuously true with nothing found. */
    val names: List<String> get() = held.map { it.name }

    /** Every effect took the disable and still reads off. A handle that loses control of its effect
     *  is told when the new owner turns it on, so this follows the effect and not only what was
     *  asked of it. */
    fun allOff(): Boolean = held.all { it.turnedOff && !it.effect.enabled }

    /** Back as found, and released. Once. False if an effect refused its state back, which it does
     *  when another handle has taken control of it: that effect may be left off. */
    fun restore(): Boolean = held.map {
        val status = runCatching { it.effect.setEnabled(it.wasEnabled) }.getOrNull()
        it.effect.release()
        status == AudioEffect.SUCCESS
    }.all { it }

    companion object {
        const val ECHO_CANCELLER = "echo canceller"
        const val NOISE_SUPPRESSOR = "noise suppressor"

        /**
         * On [sessionId], from `CaptureSessionId`, with capture open. Opening an input attaches the
         * configured pre-processing and enables it, but at a priority below these handles' (AOSP
         * `AudioPolicyEffects`), so by the source a reopen while they are held leaves the effects
         * off. Not verified on a device: [allOff] read at the end of a check is the guard.
         */
        fun disable(sessionId: Int): CaptureEffects = hold(if (sessionId > 0) find(sessionId) else emptyList())

        /** The session's effects, each on a new handle that the caller releases. */
        internal fun find(sessionId: Int): List<Pair<String, AudioEffect>> = buildList {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { add(ECHO_CANCELLER to it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { add(NOISE_SUPPRESSOR to it) }
            }
        }

        internal fun hold(found: List<Pair<String, AudioEffect>>): CaptureEffects =
            CaptureEffects(found.map { (name, effect) ->
                val wasEnabled = effect.enabled
                Held(name, effect, wasEnabled, runCatching { effect.setEnabled(false) }.getOrNull() == AudioEffect.SUCCESS)
            })
    }
}
