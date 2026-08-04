package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.flow.StateFlow
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.protocol.ServerVersion

/** The coordinator surface the UI depends on — narrow so the ViewModel can be tested with a fake. */
interface Connection {
    val status: StateFlow<ConnectionStatus>
    val serverVersion: StateFlow<ServerVersion?>
    val roundTripMillis: StateFlow<Double?>
    val channelTree: StateFlow<ChannelTree>
    val messages: StateFlow<List<ChatMessage>>
    val speakingSessions: StateFlow<Set<Int>>
    fun connect(endpoint: MumbleEndpoint, username: String, password: String?)
    fun trustAndConnect()
    fun cancelTrust()
    fun disconnect()
    fun sendText(text: String): Boolean

    /**
     * Raise "a capture session is wanted" on the live connection — a level, not an open. The
     * session is built only while the connection is live and the platform is not holding the call,
     * and it is rebuilt whenever that becomes true again. Call with RECORD_AUDIO granted; safe to
     * repeat, a no-op with nothing connected.
     */
    fun requestCapture()

    /**
     * Push-to-talk. Opening the gate also asks for capture, exactly as [requestCapture] does, so a
     * press recovers a session a terminal engine failure or a hold took away — but asynchronously,
     * so the press that rebuilds is not the press that transmits. The intent is remembered either
     * way: a session built while the button is still down comes up transmitting.
     */
    fun setTransmitting(on: Boolean)
}
