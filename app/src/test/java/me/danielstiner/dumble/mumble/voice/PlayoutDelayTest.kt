package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.protocol.UserStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class PlayoutDelayTest {

    private fun playout(depths: Map<Int, Int>, latencyMs: Double?) = PlayoutStats(
        latencyMs = latencyMs, underruns = null, concealedGaps = 0, droppedPackets = 0,
        shrunkPackets = 0, catchUpPackets = 0, bufferedSamples = depths, targetSamples = emptyMap(),
    )

    private fun stats(tcp: Float? = null, udp: Float? = null) =
        UserStats(7, tcp, udp, null, null, null)

    /** Both pings are round trips to the server, and audio is relayed, so each hop is half. */
    @Test fun theNetworkIsHalfOfEachPing() {
        val d = PlayoutDelay.of(7, playout(mapOf(7 to 30 * 48), 20.0), stats(tcp = 8f),
                                6.milliseconds)
        assertEquals(7.milliseconds, d.network)
        assertEquals(30.milliseconds, d.jitterBuffer)
        assertEquals(20.milliseconds, d.audioOutput)
        assertEquals(57.milliseconds, d.total)
    }

    /**
     * The UDP leg wins when the server has one, matching how UserStats picks its jitter: murmur
     * only holds a UDP ping for a peer whose voice actually travels that way.
     */
    @Test fun theLegCarryingVoiceIsTheOneMeasured() {
        val d = PlayoutDelay.of(7, null, stats(tcp = 40f, udp = 10f), 4.milliseconds)
        assertEquals(7.milliseconds, d.network)
    }

    /** A drained queue is a real 0, its audio in the track; a speaker with no slot is absent. */
    @Test fun theJitterBufferIsTheDepthNowNotThePrebuffer() {
        val drained = playout(mapOf(7 to 0), null)
        assertEquals(ZERO, PlayoutDelay.of(7, drained, null, null).jitterBuffer)
        assertNull(PlayoutDelay.of(8, drained, null, null).jitterBuffer)
    }

    /** A step with no reading is skipped, not zeroed: zero would claim the step is free. */
    @Test fun anAbsentStepShrinksTheEstimateRatherThanClaimingItIsFree() {
        val d = PlayoutDelay.of(7, playout(emptyMap(), 20.0), null, null)
        assertNull(d.network)
        assertNull(d.jitterBuffer)
        assertEquals(20.milliseconds, d.total)
    }

    /** Half a path is not a path: one missing round trip leaves the network unread, not halved. */
    @Test fun theNetworkNeedsBothRoundTrips() {
        assertNull(PlayoutDelay.of(7, null, stats(tcp = 8f), null).network)
        assertNull(PlayoutDelay.of(7, null, null, 6.milliseconds).network)
    }

    /** Nothing readable at all is no estimate, rather than a confident zero. */
    @Test fun noReadingsAtAllMeansNoTotal() {
        assertNull(PlayoutDelay.of(7, null, null, null).total)
    }
}
