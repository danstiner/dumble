package me.danielstiner.dumble.mumble.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * 3:1 decimator (48 kHz -> 16 kHz), stateful across calls. A windowed-sinc (Hann) anti-alias
 * low-pass at ~7 kHz precedes take-every-3rd so broadband HF noise (keyboard, hiss, fan) can't
 * alias into the speech band and inflate VAD false positives. Single-thread (send thread).
 */
class Decimator(
    numTaps: Int = 33,
    cutoffHz: Double = 7000.0,
) {
    private val taps: FloatArray = designLowpass(numTaps, cutoffHz, SAMPLE_RATE.toDouble())
    private val history = FloatArray(numTaps)
    private var histPos = 0
    private var phase = 0

    fun decimate(pcm: ShortArray, off: Int, n: Int): FloatArray {
        val out = FloatArray(n / 3)
        var oi = 0
        for (i in 0 until n) {
            history[histPos] = pcm[off + i] / 32768f
            histPos = (histPos + 1) % history.size
            if (phase == 0) out[oi++] = filter()
            phase = (phase + 1) % 3
        }
        return out
    }

    fun reset() { history.fill(0f); histPos = 0; phase = 0 }

    private fun filter(): Float {
        var acc = 0f
        var idx = histPos - 1
        for (k in taps.indices) {
            if (idx < 0) idx += history.size
            acc += taps[k] * history[idx]
            idx--
        }
        return acc
    }

    private companion object {
        fun designLowpass(numTaps: Int, cutoffHz: Double, fs: Double): FloatArray {
            val fc = cutoffHz / fs
            val mid = (numTaps - 1) / 2.0
            val h = FloatArray(numTaps)
            var sum = 0.0
            for (k in 0 until numTaps) {
                val x = k - mid
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val hann = 0.5 - 0.5 * kotlin.math.cos(2 * PI * k / (numTaps - 1))
                val v = sinc * hann
                h[k] = v.toFloat(); sum += v
            }
            for (k in 0 until numTaps) h[k] = (h[k] / sum).toFloat()
            return h
        }
    }
}
