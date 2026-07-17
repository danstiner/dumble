package me.danielstiner.dumble.mumble.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** Per-10ms-sub-frame speech level in 0f..1f. Stateful (adaptive). Single-thread (send thread). */
interface VadDetector {
    fun level(pcm: ShortArray, off: Int, n: Int): Float

    /** Reset any streaming state on a capture discontinuity (start / unmute / mode change). Default no-op. */
    fun reset() {}
}

/**
 * Energy VAD with an adaptive noise floor. The returned level is how far the sub-frame's
 * RMS sits above a slowly-tracked background floor, mapped over [marginDb]. The floor updates
 * only when the sub-frame is NOT speech-like (its level is within the margin), so sustained
 * speech never inflates it — this reproduces Mumble's "don't adapt during speech" without any
 * gate-state coupling.
 *
 * An absolute gate [absOpenDb] caps sensitivity: a sub-frame quieter than this in absolute
 * terms never reads as speech, no matter how low the floor adapted. Without it, a quiet room
 * drives the floor toward [minDb] and the relative margin then trips on typing / faint sounds.
 */
class EnergyVadDetector(
    var marginDb: Float = 15f,           // dB above floor that maps to level 1.0 (live-tunable)
    var absOpenDb: Float = -55f,         // absolute gate: frames quieter than this are never speech (live-tunable)
    private val riseCoef: Float = 0.02f, // slow: floor creeps up toward louder background
    private val fallCoef: Float = 0.3f,  // fast: floor drops toward quieter background
    private val minDb: Float = -96f,
    initialFloorDb: Float = -60f,
) : VadDetector {
    private var floorDb = initialFloorDb

    /** Current adaptive noise-floor estimate in dBFS (for diagnostics/tuning UIs). */
    val noiseFloorDb: Float get() = floorDb

    override fun level(pcm: ShortArray, off: Int, n: Int): Float {
        val db = rmsDb(pcm, off, n)
        val above = db - floorDb
        if (above < marginDb) {                                // quiet / non-speech → track floor
            val coef = if (db < floorDb) fallCoef else riseCoef
            floorDb += coef * (db - floorDb)
        }
        if (db < absOpenDb) return 0f                          // too quiet in absolute terms
        return (above / marginDb).coerceIn(0f, 1f)
    }

    private fun rmsDb(pcm: ShortArray, off: Int, n: Int): Float {
        var sumSq = 0.0
        for (i in off until off + n) { val s = pcm[i].toDouble(); sumSq += s * s }
        val rms = sqrt(sumSq / n)
        if (rms < 1.0) return minDb                            // ~digital silence
        return (20.0 * log10(rms / 32768.0)).toFloat().coerceAtLeast(minDb)
    }
}
