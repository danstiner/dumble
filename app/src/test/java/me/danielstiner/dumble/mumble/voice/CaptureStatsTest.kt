package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatsTest {

    private val sample = CaptureStats(
        encodedPackets = 500, encodeErrors = 0, encodeMicrosMean = 310, encodeMicrosMax = 1_900,
        ringOverruns = 2, skippedSamples = 960, streamOverruns = 4, framesPerBurst = 192,
        droppedFrames = 1,
    )

    /**
     * Pinned because the value of this line is being greppable across builds: it is read off a
     * device by a human, and silently renaming a key breaks every saved search and comparison.
     */
    @Test fun summaryNamesEveryCounter() {
        assertEquals(
            "capture: packets=500 encodeErr=0 encode=310us/1900us burst=192 " +
                "streamOverruns=4 ringOverruns=2 skipped=960 sendDropped=1",
            sample.summary(),
        )
    }

    /** droppedFrames belongs to the sender, so the reader leaves it at zero for the caller. */
    @Test fun theSenderOwnsDroppedFrames() {
        assertEquals(7, sample.copy(droppedFrames = 7).droppedFrames)
        assertTrue(sample.copy(droppedFrames = 7).summary().endsWith("sendDropped=7"))
    }
}
