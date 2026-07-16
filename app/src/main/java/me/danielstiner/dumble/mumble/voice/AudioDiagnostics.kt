package me.danielstiner.dumble.mumble.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** One platform voice effect's state. [enabled] null = available-but-unknown (probe threw). */
data class EffectState(val kind: String, val available: Boolean, val enabled: Boolean?)

/** Static capture-session info read once at capture start (Android probe fills it). */
data class CaptureInfo(val effects: List<EffectState>, val deviceModel: String)

/**
 * Read-only transmit-path diagnostics: platform effect state + stage loudness. Levels are dBFS RMS
 * (ref 32768, matching the eval harness). post-denoise is DERIVED from post-gain minus the makeup
 * gain (they differ only by the gain, except when the limiter is active — acceptable for a HUD).
 */
data class AudioDiagnostics(
    val effects: List<EffectState> = emptyList(),
    val deviceModel: String = "",
    val unprocessedSupported: String? = null,
    val connected: Boolean = false,
    val rawDbFs: Float = Float.NEGATIVE_INFINITY,
    val postGainDbFs: Float = Float.NEGATIVE_INFINITY,
    val agcGainDb: Float = 0f,
    val vadProb: Float = 0f,
) {
    /** Post-RNNoise (pre-gain) level, derived. */
    val postDenoiseDbFs: Float
        get() = if (postGainDbFs.isFinite()) postGainDbFs - agcGainDb else postGainDbFs

    /** How much RNNoise attenuated the platform-handed signal (raw − post-denoise). */
    val rnnoiseAttenuationDb: Float
        get() = if (rawDbFs.isFinite() && postDenoiseDbFs.isFinite()) rawDbFs - postDenoiseDbFs else Float.NaN
}

/** RMS of [n] samples at [pcm]+[off] as dBFS (ref 32768); floors at −120 for near-silence. */
fun rmsDbFs(pcm: ShortArray, off: Int, n: Int): Float {
    var sumSq = 0.0
    for (i in off until off + n) { val s = pcm[i].toDouble(); sumSq += s * s }
    val rms = sqrt(sumSq / n)
    if (rms < 1.0) return -120f
    return (20.0 * log10(rms / 32768.0)).toFloat()
}
