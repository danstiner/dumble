package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import kotlin.time.Duration

/**
 * The top bar's second line. Session id and server version deliberately do not appear: they are
 * fixed for the whole session and diagnostic rather than call state, and `MumbleConn` already logs
 * both on every status transition.
 *
 * Pure so the hour rollover and the missing-ping case are pinned by tests rather than by looking at
 * a running app an hour into a call.
 */
internal fun statusLine(
    elapsedSeconds: Long?,
    roundTripTime: Duration?,
    voicePath: VoicePath.State,
    pingAge: Duration,
): String = buildString {
    append("Connected")
    if (elapsedSeconds != null) append(" · ").append(formatDuration(elapsedSeconds))
    // Either the latency or the outage, never both: a round trip measured before the replies
    // stopped is not evidence about a link that is currently silent.
    if (pingAge >= SessionStateMachine.DEGRADED_PING_AGE) {
        append(" · ").append("no response")
        return@buildString
    }
    // The leg carrying voice, by its label, never whichever number happens to be there: a reading
    // from the other leg would be true and still mislead.
    val (leg, roundTrip) = if (voicePath.onUdp) "UDP" to voicePath.roundTrip else "TCP" to roundTripTime
    // Rounded, not truncated: a loopback round trip under a millisecond should read 1 ms, not 0.
    if (roundTrip != null) append(" · $leg ").append("%.0f ms".format(roundTrip.inWholeMicroseconds / 1000.0))
}

/** `M:SS`, widening to `H:MM:SS` once a call passes an hour. */
internal fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
