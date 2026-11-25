package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.PinStore
import kotlin.time.TimeSource

/** The connection's side of a latency check. */
interface CheckHost {
    /**
     * Connects a session that is a latency check's own for its whole life. Trust is decided as
     * for any connect, with [pins] in place of the app's pin store: the server's pin is read
     * there and a trust prompt's answer written there, so the user's pins never learn a
     * throwaway server. A certificate [pins] does not hold ends the session in a trust prompt.
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
        pins: PinStore,
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
