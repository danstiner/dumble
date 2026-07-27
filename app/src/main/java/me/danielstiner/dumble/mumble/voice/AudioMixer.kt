package me.danielstiner.dumble.mumble.voice

import kotlin.math.tanh

/**
 * Sums mono PCM16 streams with a soft-knee limiter. Accumulate in Int so no intermediate
 * clipping occurs, then soft-limit only above THRESHOLD — normal levels stay at unity gain,
 * and double-talk compresses instead of collapsing toward the louder speaker.
 * Playback-thread only.
 */
object AudioMixer {
    // LIMIT is forced: PCM16 full scale. THRESHOLD is a judgement call — 0.8 full scale is where
    // the tanh knee starts trading a little colour for never hard-clipping on double-talk. It is a
    // heuristic, not a measured optimum, and it has not been checked by ear. Note the knee is
    // unconditional, so a single loud speaker is shaped too (30000 -> 29628, -0.11 dB) even though
    // one PCM16 stream cannot clip on its own.
    private const val LIMIT = 32767.0
    private const val THRESHOLD = 26214.0   // 0.8 * full scale

    fun accumulate(acc: IntArray, src: ShortArray, n: Int) {
        for (i in 0 until n) acc[i] += src[i].toInt()
    }

    fun finalizeMix(acc: IntArray, dst: ShortArray, n: Int) {
        for (i in 0 until n) {
            // Widened because -Int.MIN_VALUE is still Int.MIN_VALUE: the magnitude would stay
            // negative, slip past the threshold test, and truncate to 0 — silence exactly where the
            // mix is loudest. Overflowing the accumulator takes ~65k concurrent full-scale
            // speakers, so this is cheap insurance against a branch that cannot be tested for.
            val x = acc[i].toLong()
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
