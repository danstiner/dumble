package me.danielstiner.dumble.mumble.protocol

import com.google.protobuf.MessageLite

/** What the session machine needs from a transport — kept narrow so tests can fake it. */
interface ControlChannel {
    fun send(type: TcpMessageType, message: MessageLite): Boolean

    /**
     * UDPTunnel carries raw bytes, not a protobuf message — see SessionStateMachine.onFrame.
     * Defaulted to false rather than left abstract: SessionStateMachineTest's FakeChannel and
     * FailingChannel implement ControlChannel directly to exercise the version/auth handshake and
     * never send audio, so they have no reason to know this method exists.
     */
    fun sendRaw(type: Int, payload: ByteArray): Boolean = false

    fun close()
}
