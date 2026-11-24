package me.danielstiner.dumble.mumble.voice.latency

import kotlinx.coroutines.flow.StateFlow
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

    /** True while a capture stream is open on the session. */
    val captureOpen: StateFlow<Boolean>

    /** True once the check can go no further: the session ended, or its link was replaced by one
     *  that never saw the start of the check's numbering. */
    val ended: StateFlow<Boolean>

    /** One prebuilt Opus packet to the server's loopback target; false if no link took it. Any
     *  thread. */
    fun inject(frameNumber: Long, opusData: ByteArray, terminator: Boolean): Boolean

    fun setGateOpen(open: Boolean)

    /** Ends the session and leaves the connection idle. Does nothing once another session or a
     *  trust prompt has taken its place: that one is the user's. */
    fun end()
}
