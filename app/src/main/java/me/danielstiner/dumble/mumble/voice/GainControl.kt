package me.danielstiner.dumble.mumble.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Post-RNNoise makeup gain (transmit path). Adapts a smoothed linear gain toward a target RMS so
 * speech lands at a consistent loudness regardless of device / platform-AGC strength. Applied per
 * 10 ms sub-frame IN PLACE, AFTER RNNoise denoise and BEFORE Opus encode. The transmit gate still
 * decides WHEN to send (from the RNNoise probability, computed pre-gain); this only sets HOW LOUD.
 *
 * Mirrors mainline Mumble's Speex AGC (which also runs after RNNoise): adapt only while speaking —
 * freeze during non-speech, because RNNoise passes very-quiet frames at unity gain, so a running
 * gain would otherwise ramp room noise up; asymmetric rate limiting (raise gain slowly, lower it
 * fast); bidirectional (boost quiet speech, trim hot speech); soft-knee tanh limiter after the gain
 * (mirroring [AudioMixer]) so a loud burst can't clip.
 *
 * Single-thread (send thread). Live-tunable fields use the same plain-var convention as
 * [TransmitGate.openLevel] (32-bit reads/writes are atomic on the JVM).
 * See docs/superpowers/specs/2026-07-16-automatic-gain-control-design.md.
 */
class GainControl(
    /** Target speech RMS in dBFS (referenced to full-scale 32768, matching the eval harness). */
    var targetDbFs: Float = DEFAULT_TARGET_DBFS,
    var enabled: Boolean = true,
    var maxGainDb: Float = 30f,
    var minGainDb: Float = -12f,
    var increaseRateDbPerSec: Float = 12f,   // slow up   (Mumble: +12 dB/s while speaking)
    var decreaseRateDbPerSec: Float = 60f,   // fast down (Mumble: -60 dB/s)
    var adaptSpeechThreshold: Float = 0.5f,  // RNNoise prob to count a sub-frame as speech
    var limiterThreshDbFs: Float = -1.94f,   // matches AudioMixer knee (0.8 * full scale)
) {
    /** Current smoothed linear gain (send-thread state). Exposed read-only for tests/diagnostics. */
    var gain: Float = 1f
        private set

    /**
     * Apply gain to [n] samples at [pcm]+[off], adapting from this sub-frame's RNNoise [speechProb].
     *
     * When [enabled] is false this early-returns, so [gain] is frozen at its last value and carries
     * over on re-enable (then re-adapts, falling at [decreaseRateDbPerSec]). Intended for the on/off
     * A/B toggle — this carry-over is deliberate, not a bug.
     */
    fun process(pcm: ShortArray, off: Int, n: Int, speechProb: Float) {
        if (!enabled) return

        val minGain = dbToRatio(minGainDb)
        val maxGain = dbToRatio(maxGainDb)

        // Adapt only while speaking; freeze otherwise (RNNoise silence-bypass caveat).
        if (speechProb >= adaptSpeechThreshold) {
            val rms = rms(pcm, off, n)
            if (rms > 1f) {
                val targetRms = FULL_SCALE * dbToRatio(targetDbFs)
                val desired = (targetRms / rms).coerceIn(minGain, maxGain)
                gain = rateLimit(gain, desired, n)
            }
        }

        val limit = FULL_SCALE * dbToRatio(limiterThreshDbFs)
        for (i in off until off + n) {
            val limited = softLimit(pcm[i] * gain, limit)
            // defensive rail; softLimit already caps at ±CEILING
            pcm[i] = limited.coerceIn(-CEILING, CEILING).toInt().toShort()
        }
    }

    /** Move [current] gain toward [desired] by at most this sub-frame's dB step (asymmetric). */
    private fun rateLimit(current: Float, desired: Float, n: Int): Float {
        val subframeSec = n.toFloat() / SAMPLE_RATE
        val curDb = ratioToDb(current)
        val desDb = ratioToDb(desired)
        val maxStepDb = if (desDb > curDb) increaseRateDbPerSec * subframeSec
                        else decreaseRateDbPerSec * subframeSec
        val steppedDb = curDb + (desDb - curDb).coerceIn(-maxStepDb, maxStepDb)
        return dbToRatio(steppedDb)
    }

    private fun rms(pcm: ShortArray, off: Int, n: Int): Float {
        var sumSq = 0.0
        for (i in off until off + n) { val s = pcm[i].toDouble(); sumSq += s * s }
        return sqrt(sumSq / n).toFloat()
    }

    /** tanh soft knee above [limit], mirroring AudioMixer.finalizeMix. */
    private fun softLimit(x: Float, limit: Float): Float {
        val ax = abs(x)
        if (ax <= limit) return x
        val over = ax - limit
        val comp = limit + (CEILING - limit) * tanh(over / (CEILING - limit))
        return if (x < 0) -comp else comp
    }

    private fun dbToRatio(db: Float): Float = 10f.pow(db / 20f)
    private fun ratioToDb(x: Float): Float = if (x < 1e-6f) -120f else 20f * log10(x)

    companion object {
        const val DEFAULT_TARGET_DBFS = -18f
        private const val FULL_SCALE = 32768f
        /** tanh asymptote / hard rail: the largest representable positive Short (mirrors AudioMixer). */
        private const val CEILING = FULL_SCALE - 1f
    }
}
