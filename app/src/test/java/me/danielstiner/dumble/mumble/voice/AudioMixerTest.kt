package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixerTest {
    @Test fun accumulateSumsWithoutClipping() {
        val acc = IntArray(2)
        AudioMixer.accumulate(acc, shortArrayOf(30000, -30000), 2)
        AudioMixer.accumulate(acc, shortArrayOf(30000, -30000), 2)
        assertEquals(60000, acc[0])
        assertEquals(-60000, acc[1])
    }
    @Test fun finalizeUnityBelowThreshold() {
        val dst = ShortArray(1)
        AudioMixer.finalizeMix(intArrayOf(20000), dst, 1)
        assertEquals(20000, dst[0].toInt())
    }
    @Test fun finalizeSoftLimitsAboveThresholdNoHardRail() {
        val dst = ShortArray(2)
        AudioMixer.finalizeMix(intArrayOf(60000, -60000), dst, 2)
        // compressed above the 0.8 knee, both present, never exceeds Int16 range
        assertTrue(dst[0] > 26214 && dst[0] <= Short.MAX_VALUE)
        assertTrue(dst[1] < -26214 && dst[1] >= Short.MIN_VALUE)
    }
    @Test fun finalizeMonotonicNoOverflow() {
        val dst = ShortArray(1)
        AudioMixer.finalizeMix(intArrayOf(500000), dst, 1)   // extreme sum stays in range
        assertTrue(dst[0] <= Short.MAX_VALUE && dst[0] > 26214)
    }
}
