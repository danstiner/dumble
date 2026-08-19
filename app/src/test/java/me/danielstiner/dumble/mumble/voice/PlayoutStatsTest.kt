package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayoutStatsTest {

    private fun stats(targets: Map<Int, Int>) = PlayoutStats(
        latencyMs = null, underruns = null, concealedGaps = 0, droppedPackets = 0,
        shrunkPackets = 0, catchUpPackets = 0, bufferedSamples = emptyMap(),
        targetSamples = targets,
    )

    @Test fun targetConvertsSamplesToMillis() {
        assertEquals(80, stats(mapOf(7 to 80 * 48)).targetMillis(7))
    }

    /**
     * A speaker the engine has retired is absent, not zero: zero is a real target the estimator
     * can never publish, and rendering it would claim a measurement we do not have.
     */
    @Test fun anAbsentSpeakerHasNoTarget() {
        assertNull(stats(mapOf(7 to 80 * 48)).targetMillis(8))
        assertNull(stats(emptyMap()).targetMillis(7))
    }
}
