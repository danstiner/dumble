package me.danielstiner.dumble.mumble.protocol

import com.google.protobuf.MessageLite

/** What the session machine needs from a transport — kept narrow so tests can fake it. */
interface ControlChannel {
    fun send(type: TcpMessageType, message: MessageLite): Boolean

    /** For payloads that are not protobuf — UDPTunnel frames carry raw UDP packet bytes. */
    fun sendRaw(type: TcpMessageType, payload: ByteArray): Boolean

    fun close()
}
