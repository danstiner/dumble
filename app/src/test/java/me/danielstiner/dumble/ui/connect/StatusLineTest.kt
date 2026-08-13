package me.danielstiner.dumble.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StatusLineTest {

    @Test fun bothSegmentsPresent() =
        assertEquals("Connected · 12:34 · 4 ms", statusLine(754, 4.1.milliseconds, 0.seconds))

    /** No pong has arrived yet: omit the segment rather than showing a placeholder. */
    @Test fun pingOmittedUntilFirstPong() = assertEquals("Connected · 12:34", statusLine(754, null, 0.seconds))

    @Test fun durationOmittedBeforeConnectIsRecorded() = assertEquals("Connected", statusLine(null, null, 0.seconds))

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
        assertEquals("Connected · 12:34 · 4 ms", statusLine(754, 4.1.milliseconds, 1.seconds))

    /**
     * The round trip is dropped, not kept: it was last true an outage ago, and a stale latency
     * beside a dead link is exactly the readout that misleads during a device gate.
     */
    @Test fun degradedReplacesTheRoundTripRatherThanJoiningIt() =
        assertEquals("Connected · 12:34 · no response", statusLine(754, 4.1.milliseconds, SessionStateMachine.DEGRADED_PING_AGE))

    @Test fun degradedShowsWithNoRoundTripEverRecorded() =
        assertEquals("Connected · 12:34 · no response", statusLine(754, null, SessionStateMachine.DEGRADED_PING_AGE))
}
