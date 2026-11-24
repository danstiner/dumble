package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import kotlin.time.TimeSource

/** The connection's side of a latency check. */
interface CheckHost {
    /**
     * Connects a session that is a latency check's own for its whole life. It trusts exactly
     * [fingerprint] and records nothing: no pin, no trust prompt. [fingerprint] is the lowercase
     * hex SHA-256 of the server's leaf certificate in DER, compared exactly:
     * `openssl x509 -fingerprint -sha256` prints uppercase with colons and won't match. A different
     * certificate ends the session in an error.
     *
     * On that session capture runs push-to-talk on [CheckSession.setGateOpen] alone; every packet
     * it produces goes to [captured], on the pump thread, and none to the server; and every packet
     * the playout engine accepts is reported to [arrived], on the transport's thread. Neither
     * callback may block.
     *
     * Null, with nothing changed, while a session is connected or a trust prompt is showing: a
     * check never replaces what the user has open.
     */
    fun connectForCheck(
        endpoint: MumbleEndpoint,
        username: String,
        fingerprint: String,
        captured: (CapturedPacket) -> Unit,
        arrived: (ArrivedPacket) -> Unit,
    ): CheckSession?
}

/** A check's handle on its own session. Nothing here outlives the session. */
interface CheckSession {
    /** The clock both packets are marked on. A check marks its own sends on it too: marks from
     *  two sources cannot be subtracted. */
    val clock: TimeSource.WithComparableMarks

    fun setGateOpen(open: Boolean)
}
