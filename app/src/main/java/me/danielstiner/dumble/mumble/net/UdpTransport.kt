package me.danielstiner.dumble.mumble.net

import java.net.InetSocketAddress

/**
 * What the connection needs of a link's UDP voice socket; [MumbleUdpTransport] is the real one.
 * Narrow so a test can stand a fake in its place and run the connection's UDP wiring on virtual
 * time, without a socket or the reader thread that comes with one.
 */
interface UdpTransport {
    fun open(address: InetSocketAddress)
    fun send(plaintext: ByteArray, len: Int): Boolean
    fun sendPing(): Boolean
    fun close()
}
