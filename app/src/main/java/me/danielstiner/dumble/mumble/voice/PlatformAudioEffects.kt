package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log

/**
 * Read-only probe of the platform voice effects on a capture session — the read-only half of
 * OboeTester's StreamConfigurationView.setupEffects: isAvailable() (static) -> create(sessionId) ->
 * getEnabled() (the platform DEFAULT state). We NEVER toggle the enabled state, so the
 * VOICE_COMMUNICATION path is not perturbed; handles are held for the session and released in [close].
 *
 * Caveat: getEnabled() is the audiofx effect's self-report — it equals the HAL VOICE_COMMUNICATION
 * processing on most devices but is not guaranteed. The stage RMS is the ground truth.
 */
class PlatformAudioEffects(sessionId: Int) {
    private val aecAvail = AcousticEchoCanceler.isAvailable()
    private val agcAvail = AutomaticGainControl.isAvailable()
    private val nsAvail = NoiseSuppressor.isAvailable()

    private val aec: AudioEffect? = create("AEC") { if (aecAvail) AcousticEchoCanceler.create(sessionId) else null }
    private val agc: AudioEffect? = create("AGC") { if (agcAvail) AutomaticGainControl.create(sessionId) else null }
    private val ns: AudioEffect? = create("NS") { if (nsAvail) NoiseSuppressor.create(sessionId) else null }

    val states: List<EffectState> = listOf(
        EffectState("AEC", aecAvail, readEnabled(aec)),
        EffectState("AGC", agcAvail, readEnabled(agc)),
        EffectState("NS", nsAvail, readEnabled(ns)),
    )

    private inline fun create(kind: String, factory: () -> AudioEffect?): AudioEffect? =
        try { factory() } catch (t: Throwable) { Log.w(TAG, "create $kind failed", t); null }

    /** READ ONLY — getEnabled() via the .enabled property. Never toggle it. */
    private fun readEnabled(fx: AudioEffect?): Boolean? =
        if (fx == null) null else try { fx.enabled } catch (t: Throwable) { null }

    fun close() = listOf(aec, agc, ns).forEach { runCatching { it?.release() } }

    companion object {
        private const val TAG = "PlatformAudioEffects"
        fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        fun unprocessedSupported(am: AudioManager): String? =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
    }
}
