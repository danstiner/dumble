package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class PlayoutStatsTest {

    private fun stats(buffered: Map<Int, Int>) = PlayoutStats(
        latencyMs = null, underruns = null, concealedGaps = 0, droppedPackets = 0,
        shrunkPackets = 0, catchUpPackets = 0, bufferedSamples = buffered, targetSamples = emptyMap(),
    )

    @Test fun depthConvertsSamplesToTime() {
        assertEquals(80.milliseconds, stats(mapOf(7 to 80 * 48)).depth(7))
    }

    /**
     * A speaker the engine has retired is absent, not zero: zero is a real depth — a drained
     * queue — and rendering it would claim a measurement we do not have.
     */
    @Test fun anAbsentSpeakerHasNoDepth() {
        assertNull(stats(mapOf(7 to 80 * 48)).depth(8))
        assertNull(stats(emptyMap()).depth(7))
    }
}
