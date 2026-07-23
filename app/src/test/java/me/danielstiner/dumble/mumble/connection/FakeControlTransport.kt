package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.MessageLite
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.protocol.TcpMessageType

/** Test transport whose connect() behaviour the test controls: block, throw, or return. */
class FakeControlTransport(
    private val onConnect: suspend (host: String, port: Int) -> Unit,
) : MumbleControlTransport {
    @Volatile var closed = false; private set
    @Volatile var listener: MumbleControlTransport.Listener? = null

    override suspend fun connect(host: String, port: Int, listener: MumbleControlTransport.Listener) {
        this.listener = listener
        onConnect(host, port)
    }
    override fun send(type: TcpMessageType, message: MessageLite): Boolean = !closed
    override fun close() { closed = true }
}
