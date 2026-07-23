package me.danielstiner.dumble.mumble.net

import me.danielstiner.dumble.mumble.protocol.ControlChannel
import me.danielstiner.dumble.mumble.protocol.TcpFrame

/**
 * What the connection coordinator needs from a transport: the blocking connect plus the send/close of
 * [ControlChannel]. Kept narrow so a test can supply a controllable fake for the races the real socket
 * cannot reproduce deterministically (supersede mid-handshake, connect timeout).
 */
interface MumbleControlTransport : ControlChannel {
    suspend fun connect(host: String, port: Int, listener: Listener)

    interface Listener {
        /** Invoked on the single reader coroutine, so implementations need no locking. */
        fun onFrame(f: TcpFrame)
        /** Fires exactly once, only if the connection was ever published. null cause = local close. */
        fun onClosed(cause: Throwable?)
    }
}
