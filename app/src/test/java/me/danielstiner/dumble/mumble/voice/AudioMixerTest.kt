package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixerTest {
    @Test fun sumsSamples() {
        val dst = ShortArray(3) { 100 }
        AudioMixer.mixInto(dst, shortArrayOf(50, 50, 50), 3)
        assertEquals(150, dst[0].toInt())
    }
    @Test fun clipsPositive() {
        val dst = shortArrayOf(30000)
        AudioMixer.mixInto(dst, shortArrayOf(10000), 1)
        assertEquals(Short.MAX_VALUE.toInt(), dst[0].toInt())
    }
    @Test fun clipsNegative() {
        val dst = shortArrayOf(-30000)
        AudioMixer.mixInto(dst, shortArrayOf(-10000), 1)
        assertEquals(Short.MIN_VALUE.toInt(), dst[0].toInt())
    }
}
