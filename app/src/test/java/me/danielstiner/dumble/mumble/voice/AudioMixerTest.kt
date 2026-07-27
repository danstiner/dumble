package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixerTest {

    @Test
    fun quietAudioPassesAtUnityGain() {
        val acc = IntArray(4)
        AudioMixer.accumulate(acc, shortArrayOf(100, -100, 5000, -5000), 4)
        val out = ShortArray(4)
        AudioMixer.finalizeMix(acc, out, 4)
        assertEquals(shortArrayOf(100, -100, 5000, -5000).toList(), out.toList())
    }

    @Test
    fun doubleTalkDoesNotClip() {
        val acc = IntArray(2)
        // Two near-full-scale sources; a naive sum would wrap Int16.
        AudioMixer.accumulate(acc, shortArrayOf(30000, -30000), 2)
        AudioMixer.accumulate(acc, shortArrayOf(30000, -30000), 2)
        val out = ShortArray(2)
        AudioMixer.finalizeMix(acc, out, 2)
        assertTrue("positive excursion wrapped: ${out[0]}", out[0] > 26214)
        assertTrue("negative excursion wrapped: ${out[1]}", out[1] < -26214)
    }

    @Test
    fun extremeAccumulatorValuesStillLimitRatherThanTruncate() {
        // Int.MIN_VALUE is the one input whose magnitude cannot be taken in Int: -x is itself,
        // so a negative "magnitude" slips past the threshold test and truncates to 0 — full
        // silence at the loudest possible sample. Unreachable with real speaker counts, hence
        // the accumulator is written directly here.
        val acc = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)
        val out = ShortArray(2)
        AudioMixer.finalizeMix(acc, out, 2)
        assertTrue("negative extreme collapsed to ${out[0]}", out[0] < -32000)
        assertTrue("positive extreme collapsed to ${out[1]}", out[1] > 32000)
    }

    @Test
    fun limiterIsSymmetric() {
        val pos = IntArray(1).also {
            AudioMixer.accumulate(it, shortArrayOf(32767), 1)
            AudioMixer.accumulate(it, shortArrayOf(32767), 1)
        }
        val neg = IntArray(1).also {
            AudioMixer.accumulate(it, shortArrayOf(-32767), 1)
            AudioMixer.accumulate(it, shortArrayOf(-32767), 1)
        }
        val p = ShortArray(1).also { AudioMixer.finalizeMix(pos, it, 1) }
        val n = ShortArray(1).also { AudioMixer.finalizeMix(neg, it, 1) }
        assertEquals(p[0].toInt(), -n[0].toInt())
    }
}
