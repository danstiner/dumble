package com.example.drumble.mumble

import com.example.drumble.mumble.voice.SyntheticVoiceSource
import org.junit.Assert.*
import org.junit.Test

class SyntheticVoiceSourceTest {
    @Test fun emitsSequentialFramesOnCadence() {
        val src = SyntheticVoiceSource(frameIntervalNanos = 1_000_000L)
        src.start()
        val frames = (0 until 10).mapNotNull { src.nextOutgoingFrame(50_000_000L) }
        src.stop()
        assertEquals(10, frames.size)
        assertEquals((0L until 10L).toList(), frames.map { it.frameNumber })
        assertTrue(frames.all { it.length >= 8 })
    }

    @Test fun statsFromEmbeddedTimestamps() {
        val src = SyntheticVoiceSource(frameIntervalNanos = 1_000_000L)
        fun payload(sendNanos: Long): ByteArray {
            val p = ByteArray(40)
            for (i in 0 until 8) p[i] = (sendNanos ushr ((7 - i) * 8)).toByte()
            return p
        }
        src.onIncomingFrame(payload(1_000_000_000L), 0, 40, 0L, 1, 1_015_000_000L)
        assertEquals(15.0, src.stats.value.lastRttMs, 0.1)
        assertEquals(1L, src.stats.value.received)
        src.onIncomingFrame(payload(2_000_000_000L), 0, 40, 2L, 1, 2_020_000_000L)
        assertEquals(1L, src.stats.value.lost)
        src.onIncomingFrame(payload(1_500_000_000L), 0, 40, 1L, 1, 2_030_000_000L)
        assertEquals(0L, src.stats.value.lost)
        assertEquals(3L, src.stats.value.received)
    }

    @Test fun timeoutReturnsNullWhenStopped() {
        val src = SyntheticVoiceSource(frameIntervalNanos = 1_000_000L)
        assertNull(src.nextOutgoingFrame(1_000_000L))
    }
}
