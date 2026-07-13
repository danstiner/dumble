package me.danielstiner.dumble.mumble.voice

import kotlin.math.tanh

/**
 * Sums mono PCM16 streams with a soft-knee limiter. Each remote stream is already AGC-normalized
 * to near full-scale by the far end, so a naive hard-sum clips during double-talk and collapses
 * toward the louder speaker. We accumulate in Int (no intermediate clip) and soft-limit only the
 * region above THRESHOLD, keeping normal levels at unity gain. Playback-thread only.
 */
object AudioMixer {
    private const val LIMIT = 32767.0
    private const val THRESHOLD = 26214.0   // 0.8 * full scale — unity below, soft knee above

    /** Accumulate one mono PCM16 source into [acc] (Int, no clipping). */
    fun accumulate(acc: IntArray, src: ShortArray, n: Int) {
        for (i in 0 until n) acc[i] += src[i].toInt()
    }

    /** Soft-limit [acc] into [dst] (Int16): unity below THRESHOLD, smooth tanh knee above. */
    fun finalizeMix(acc: IntArray, dst: ShortArray, n: Int) {
        for (i in 0 until n) {
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
