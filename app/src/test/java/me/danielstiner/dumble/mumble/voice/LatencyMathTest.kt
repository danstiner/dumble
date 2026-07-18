package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyMathTest {
    private val rate = 48000
    private val t0 = 1_000_000_000L // arbitrary base nanoTime

    @Test fun output_basic_noExtrapolation() {
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = 4800, tsFramePosition = 2400, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(50.0, ms, 0.01)
    }

    @Test fun output_extrapolatesPresentedFramesForward() {
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = 4800, tsFramePosition = 2400, tsNanoTime = t0, nowNanos = t0 + 10_000_000, rate = rate)
        assertEquals(40.0, ms, 0.01)
    }

    @Test fun output_handles32BitWrap() {
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = (1L shl 32) + 2400, tsFramePosition = 0, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(50.0, ms, 0.01)
    }

    @Test fun output_negativeInFlightClampsToZero() {
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = 2400, tsFramePosition = 2500, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(0.0, ms, 0.0)
    }

    @Test fun input_basic() {
        val ms = LatencyMath.inputLatencyMs(
            framesRead = 4800, tsFramePosition = 4800, tsNanoTime = t0, nowNanos = t0 + 5_000_000, rate = rate)
        assertEquals(5.0, ms, 0.01)
    }

    @Test fun input_halAheadOfRead() {
        val ms = LatencyMath.inputLatencyMs(
            framesRead = 4800, tsFramePosition = 5000, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(4.1667, ms, 0.01)
    }

    @Test fun input_negativeClampsToZero() {
        val ms = LatencyMath.inputLatencyMs(
            framesRead = 4800, tsFramePosition = 4800, tsNanoTime = t0 + 5_000_000, nowNanos = t0, rate = rate)
        assertEquals(0.0, ms, 0.0)
    }
}
