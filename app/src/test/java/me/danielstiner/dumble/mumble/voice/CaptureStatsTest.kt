package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatsTest {

    private val sample = CaptureStats(
        encodedPackets = 500, encodeErrors = 0, encodeMicrosMean = 310, encodeMicrosMax = 1_900,
        ringOverruns = 2, skippedSamples = 960, streamOverruns = 4, framesPerBurst = 192,
        droppedFrames = 1, inputLatencyMillis = 22.5,
    )

    /**
     * Pinned because the value of this line is being greppable across builds: it is read off a
     * device by a human, and silently renaming a key breaks every saved search and comparison.
     */
    @Test fun summaryNamesEveryCounter() {
        assertEquals(
            "capture: packets=500 encodeErr=0 encode=310us/1900us burst=192 " +
                "streamOverruns=4 ringOverruns=2 skipped=960 sendDropped=1 inputLatency=22.5ms",
            sample.summary(),
        )
    }

    /** droppedFrames belongs to the sender, so the reader leaves it at zero for the caller. */
    @Test fun theSenderOwnsDroppedFrames() {
        assertEquals(7, sample.copy(droppedFrames = 7).droppedFrames)
        assertTrue(sample.copy(droppedFrames = 7).summary().contains("sendDropped=7"))
    }

    /** A stream that cannot answer reads as absent, not as a confident 0 ms — a latency no input
     *  path can produce. */
    @Test fun anAbsentLatencyReadsAsNotAvailable() {
        assertTrue(sample.copy(inputLatencyMillis = null).summary().endsWith("inputLatency=n/a"))
    }

    /** The tenth is the point: a low-latency input path is single-digit milliseconds. Value
     *  chosen off a rounding tie, so this pins the tenth and not a rounding mode. */
    @Test fun latencyKeepsItsTenth() {
        assertTrue(sample.copy(inputLatencyMillis = 4.24).summary().endsWith("inputLatency=4.2ms"))
    }
}
