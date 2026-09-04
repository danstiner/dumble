package me.danielstiner.dumble.mumble.net

import me.danielstiner.dumble.mumble.protocol.ControlChannel
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import java.net.InetSocketAddress

/**
 * What the connection coordinator needs from a transport: the blocking connect plus the send/close of
 * [ControlChannel]. Kept narrow so a test can supply a controllable fake for the races the real socket
 * cannot reproduce deterministically (supersede mid-handshake, connect timeout).
 */
interface MumbleControlTransport : ControlChannel {
    suspend fun connect(host: String, port: Int, listener: Listener)

    /** The connected session's resolved remote; null before [connect] returns. The UDP voice
     *  socket aims here rather than resolving the name again: the server holds crypt state only
     *  for the session at this address, and a name can resolve differently twice. */
    fun remoteAddress(): InetSocketAddress?

    interface Listener {
        /** Invoked on the single reader coroutine, so implementations need no locking. */
        fun onFrame(f: TcpFrame)
        /** Fires exactly once, only if the connection was ever published. null cause = local close. */
        fun onClosed(cause: Throwable?)
    }
}
