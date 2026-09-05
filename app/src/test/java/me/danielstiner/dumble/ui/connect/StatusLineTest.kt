package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StatusLineTest {
    private val tunneled = VoicePath.State()
    private val onUdp = VoicePath.State(onUdp = true, roundTrip = 4.1.milliseconds)

    @Test fun bothSegmentsPresent() =
        assertEquals("Connected · 12:34 · TCP 4 ms", statusLine(754, 4.1.milliseconds, tunneled, 0.seconds))

    @Test fun theUdpLegShowsItsOwnRoundTrip() =
        assertEquals("Connected · 12:34 · UDP 4 ms", statusLine(754, 99.milliseconds, onUdp, 0.seconds))

    /** The number follows the label, never nullness: a reply that arrived while still tunneled is
     *  not the TCP round trip. */
    @Test fun aUdpReplyWhileTunneledDoesNotReplaceTheTcpRoundTrip() {
        val stillTunneled = VoicePath.State(onUdp = false, roundTrip = 4.1.milliseconds)
        assertEquals("Connected · 12:34 · TCP 99 ms", statusLine(754, 99.milliseconds, stillTunneled, 0.seconds))
    }

    /** No pong has arrived yet: omit the segment rather than showing a placeholder. */
    @Test fun pingOmittedUntilFirstPong() = assertEquals("Connected · 12:34", statusLine(754, null, tunneled, 0.seconds))

    @Test fun durationOmittedBeforeConnectIsRecorded() = assertEquals("Connected", statusLine(null, null, tunneled, 0.seconds))

    @Test fun secondsPadButMinutesDoNot() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9))
        assertEquals("1:00", formatDuration(60))
        assertEquals("59:59", formatDuration(3599))
    }

    /** The format bug that only appears an hour into a call, so it is pinned rather than inspected. */
    @Test fun rollsOverToHoursAtOneHour() {
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("1:01:01", formatDuration(3661))
        assertEquals("10:00:00", formatDuration(36000))
    }

    /** A clock correction can move the wall clock backwards mid-call; never render a negative. */
    @Test fun negativeElapsedClampsToZero() = assertEquals("0:00", formatDuration(-5))

    /** Below the threshold nothing changes — one missed echo is a slow reply, not an outage. */
    @Test fun oneMissedEchoStillReadsNormally() =
        assertEquals("Connected · 12:34 · TCP 4 ms", statusLine(754, 4.1.milliseconds, tunneled, 1.seconds))

    /**
     * The round trip is dropped, not kept, on either leg: it was last true an outage ago, and a
     * stale latency beside a dead link is exactly the readout that misleads during a device gate.
     */
    @Test fun degradedReplacesTheRoundTripRatherThanJoiningIt() =
        assertEquals("Connected · 12:34 · no response", statusLine(754, 4.1.milliseconds, onUdp, SessionStateMachine.DEGRADED_PING_AGE))

    @Test fun degradedShowsWithNoRoundTripEverRecorded() =
        assertEquals("Connected · 12:34 · no response", statusLine(754, null, tunneled, SessionStateMachine.DEGRADED_PING_AGE))
}
