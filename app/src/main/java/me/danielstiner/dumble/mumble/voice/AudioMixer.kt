package me.danielstiner.dumble.mumble.voice

import kotlin.math.tanh

/**
 * Sums mono PCM16 streams with a soft-knee limiter. Accumulate in Int so no intermediate
 * clipping occurs, then soft-limit only above THRESHOLD — normal levels stay at unity gain,
 * and double-talk compresses instead of collapsing toward the louder speaker.
 *
 * Call pattern: once per playback quantum: zero `acc`, [accumulate] each active speaker's
 * PCM into it, then [finalizeMix] once into the buffer handed to AudioTrack. The accumulator
 * cannot overflow: worst case is [MAX_SPEAKERS] full-scale streams, 2^21, against Int's 2^31.
 *
 * Desktop Mumble hard-clips its mix; we chose to round the corner with tanh instead to
 * reduce distortion when multiple speakers peak at the same instant. A broadcast-style
 * envelope limiter would add less distortion for this edge case, but for now we are avoiding
 * the added complexity in terms of code, per-mixer state, and attack/release tuning.
 */
object AudioMixer {
    // LIMIT is PCM16 full scale.
    private const val LIMIT = 32767.0
    // THRESHOLD sits ~2 dB below LIMIT. Single-stream Opus speech essentially
    // never peaks that hot, so the knee is inert in single-talk. It is meant
    // to shape the rare case of multiple hot speakers without hard limiting.
    private const val THRESHOLD = 26214.0   // 0.8 * full scale ≈ -1.9 dBFS

    fun accumulate(acc: IntArray, src: ShortArray, n: Int) {
        for (i in 0 until n) acc[i] += src[i].toInt()
    }

    fun finalizeMix(acc: IntArray, dst: ShortArray, n: Int) {
        for (i in 0 until n) {
            // Int math is safe: |acc| ≤ MAX_SPEAKERS * 2^15 = 2^21, so no overflow and no
            // -Int.MIN_VALUE negation edge.
            val x = acc[i]
            val ax = if (x < 0) -x else x
            dst[i] = if (ax <= THRESHOLD) {
                x.toShort()
            } else {
                val over = ax - THRESHOLD
                val comp = THRESHOLD + (LIMIT - THRESHOLD) * tanh(over / (LIMIT - THRESHOLD))
                (if (x < 0) -comp else comp).toInt().toShort()
            }
        }
    }
}
