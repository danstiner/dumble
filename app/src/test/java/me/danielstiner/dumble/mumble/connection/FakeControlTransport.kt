package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.MessageLite
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Test transport whose connect() behaviour the test controls: block, throw, or return. */
class FakeControlTransport(
    private val onConnect: suspend (host: String, port: Int) -> Unit,
) : MumbleControlTransport {
    @Volatile var closed = false; private set
    @Volatile var listener: MumbleControlTransport.Listener? = null
    /** Where the connection will aim its UDP socket; null, the default, means no socket. */
    @Volatile var remote: InetSocketAddress? = null
    val sent = CopyOnWriteArrayList<Pair<TcpMessageType, MessageLite>>()
    val sentRaw = CopyOnWriteArrayList<Pair<TcpMessageType, ByteArray>>()

    override suspend fun connect(host: String, port: Int, listener: MumbleControlTransport.Listener) {
        this.listener = listener
        onConnect(host, port)
    }

    override fun remoteAddress() = remote

    override fun send(type: TcpMessageType, message: MessageLite): Boolean {
        if (closed) return false
        sent += type to message
        return true
    }

    override fun sendRaw(type: TcpMessageType, payload: ByteArray): Boolean {
        if (closed) return false
        sentRaw += type to payload
        return true
    }
    override fun close() { closed = true }
}
