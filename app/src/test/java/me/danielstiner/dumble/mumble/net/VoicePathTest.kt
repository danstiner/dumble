package me.danielstiner.dumble.mumble.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VoicePathTest {
    private val reply = 30.milliseconds
    private val tunneled = VoicePath.State()

    @Test fun voiceStartsTunneledAndOneReplyPromotesIt() {
        val path = VoicePath()
        assertEquals(tunneled, path.state.value)
        path.onPingAnswered(reply)
        assertEquals(VoicePath.State(onUdp = true, roundTrip = reply), path.state.value)
    }

    // A reply is dated by the stamp the server echoes, which is the only matching there is: one
    // that reads negative, or older than any ping still worth answering, is not ours.
    @Test fun anImplausibleReplyIsNotEvidence() {
        val path = VoicePath()
        path.onPingAnswered((-1).milliseconds)
        path.onPingAnswered(31.seconds)
        assertEquals("neither promotes nor is published", tunneled, path.state.value)
    }

    @Test fun theUnansweredReportDemotesAndClearsTheRoundTripInOneEmission() {
        val path = VoicePath()
        path.onPingAnswered(reply)
        path.onPingsUnanswered()
        assertEquals(tunneled, path.state.value)
    }

    @Test fun aRefusedDatagramDemotesAtOnce() {
        val path = VoicePath()
        path.onPingAnswered(reply)
        path.onSendFailed()
        assertEquals(tunneled, path.state.value)
    }

    // The asymmetry that keeps a path answering half its pings from flapping: after a demote,
    // two replies with no outage between them.
    @Test fun rePromotionTakesTwoRepliesAndAnOutageBetweenThemStartsOver() {
        val path = VoicePath()
        path.onPingAnswered(reply)
        path.onSendFailed()
        path.onPingAnswered(reply)
        assertEquals(
            "one reply after a demote publishes its number under the tunnel label",
            VoicePath.State(onUdp = false, roundTrip = reply), path.state.value,
        )
        path.onPingsUnanswered()
        path.onPingAnswered(reply)
        assertFalse("the outage reset the count", path.state.value.onUdp)
        path.onPingAnswered(reply)
        assertTrue(path.state.value.onUdp)
    }

    @Test fun thereIsNoCapOnDemotions() {
        val path = VoicePath()
        path.onPingAnswered(reply)
        repeat(5) { outage ->
            path.onPingsUnanswered()
            path.onPingAnswered(reply)
            path.onPingAnswered(reply)
            assertTrue("promoted again after outage ${outage + 1}", path.state.value.onUdp)
        }
    }

    @Test fun whileTunneledAReportOrAFailureChangesNothing() {
        val path = VoicePath()
        path.onPingsUnanswered()
        path.onSendFailed()
        assertEquals(tunneled, path.state.value)
        path.onPingAnswered(reply)
        assertTrue("never demoted, so the first promotion still takes one reply", path.state.value.onUdp)
    }
}
