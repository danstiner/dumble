package me.danielstiner.dumble.mumble.protocol

import com.google.protobuf.MessageLite

/** What the session machine needs from a transport — kept narrow so tests can fake it. */
interface ControlChannel {
    fun send(type: TcpMessageType, message: MessageLite): Boolean

    /**
     * UDPTunnel carries raw bytes, not a protobuf message — see SessionStateMachine.onFrame.
     * Deliberately abstract: a default would hand a new implementation a send that silently
     * always fails, where leaving it abstract makes the compiler name the omission.
     */
    fun sendRaw(type: Int, payload: ByteArray): Boolean

    fun close()
}
