package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyMathTest {

    private fun latency(
        framesWritten: Long, tsFramePosition: Long, ageMs: Long = 0, maxStaleMs: Long = 1_000,
    ) = LatencyMath.outputLatencyMs(
        framesWritten = framesWritten,
        tsFramePosition = tsFramePosition,
        tsNanoTime = 0,
        nowNanos = ageMs * 1_000_000,
        rate = SAMPLE_RATE,
        maxStaleNanos = maxStaleMs * 1_000_000,
    )

    @Test
    fun inFlightFramesBecomeMillis() {
        // A fresh timestamp needs no extrapolation: 480 frames unplayed is exactly one frame.
        assertEquals(10.0, latency(framesWritten = 48_000, tsFramePosition = 47_520)!!, 1e-9)
    }

    @Test
    fun anOldTimestampIsExtrapolatedForward() {
        // The timestamp is 10 ms stale, so the device has played another 480 frames since it was
        // taken and only 480 of the 960 unaccounted frames are really still in flight. Without the
        // extrapolation this reports 20 ms, biased by the age of the reading rather than the depth
        // of the buffer.
        assertEquals(10.0, latency(framesWritten = 48_480, tsFramePosition = 47_520, ageMs = 10)!!, 1e-9)
    }

    @Test
    fun aWrappedFramePositionStaysSmall() {
        // AudioTrack reports the low 32 bits, so after ~24.8 hours of playback the position wraps
        // while our own count keeps climbing. Naive subtraction yields ~2^32 frames — over a day of
        // latency — which is worse than no reading at all because it looks like a real number.
        val written = (1L shl 32) + 480
        assertEquals(6.666, latency(written, tsFramePosition = 160)!!, 1e-3)
    }

    @Test
    fun overshootClampsToZero() {
        // Extrapolating past a position that has since stalled puts "presented" ahead of "written".
        // Unclamped this is a large positive latency, because the negative difference is what the
        // wrap reinterpretation turns into a near-2^32 value. 5 ms of overshoot is rounding drift,
        // under the frame the rejection below keys on, so it still clamps.
        assertEquals(0.0, latency(framesWritten = 48_000, tsFramePosition = 48_000, ageMs = 5)!!, 1e-9)
    }

    @Test
    fun aFrozenPositionFromAnInterSpurtGapIsRejected() {
        // The case the staleness bound alone does not catch. A 300 ms conversational pause is well
        // inside the 1 s bound, but the track stalled through it, so extrapolating its frozen
        // position forward puts "presented" 300 ms past "written". Clamping would publish that as a
        // confident 0.0 ms — and for a short spurt, which samples only once at its end, that bogus
        // reading would be the only latency the spurt ever reports.
        assertNull(latency(framesWritten = 48_000, tsFramePosition = 48_000, ageMs = 300))
    }

    @Test
    fun aStaleTimestampIsRejected() {
        // After an inter-spurt gap AudioFlinger can hand back the last pre-gap reading.
        // Extrapolating it forward puts "presented" far past "written", which the clamp would
        // publish as a confident 0.0 ms — a wrong number that looks like a real one.
        assertNull(latency(framesWritten = 48_000, tsFramePosition = 47_520, ageMs = 5_000))
    }

    @Test
    fun aTimestampFromTheFutureIsRejected() {
        // AudioTimestamp.nanoTime is when the frame was presented or is committed to be presented,
        // so a slightly negative age is legitimately reachable when the two reads are not atomic.
        // We reject all negatives rather than permitting small ones because losing an occasional
        // reading costs nothing, while failing to reject a badly mismatched timebase would produce
        // a confident wrong number.
        assertNull(
            LatencyMath.outputLatencyMs(
                framesWritten = 48_000, tsFramePosition = 47_520, tsNanoTime = 5_000_000_000,
                nowNanos = 0, rate = SAMPLE_RATE, maxStaleNanos = 1_000_000_000,
            ),
        )
    }
}
