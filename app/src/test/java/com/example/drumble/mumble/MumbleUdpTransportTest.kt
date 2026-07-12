package com.example.drumble.mumble

import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.net.MumbleUdpTransport
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MumbleUdpTransportTest {
    private val key = ByteArray(16) { it.toByte() }
    private val nA = ByteArray(16) { (0x40 + it).toByte() }
    private val nB = ByteArray(16) { (0x80 + it).toByte() }

    @Test fun encryptedRoundTripViaEchoServer() {
        val serverCrypt = CryptState().apply { setKeys(key, nB, nA) }
        val serverSock = DatagramSocket(0)
        val serverThread = Thread {
            val wire = ByteArray(2048); val plain = ByteArray(2048); val out = ByteArray(2048)
            try {
                while (true) {
                    val p = DatagramPacket(wire, wire.size)
                    serverSock.receive(p)
                    val n = serverCrypt.decrypt(wire, p.length, plain)
                    if (n < 0) continue
                    val m = serverCrypt.encrypt(plain, n, out)
                    serverSock.send(DatagramPacket(out, m, p.address, p.port))
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }

        val clientCrypt = CryptState().apply { setKeys(key, nA, nB) }
        val received = LinkedBlockingQueue<ByteArray>()
        val resyncs = AtomicInteger()
        val transport = MumbleUdpTransport(clientCrypt, object : MumbleUdpTransport.Listener {
            override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {
                received.add(buf.copyOf(len))
                assertTrue(arrivalNanos > 0)
            }
            override fun onUdpError(e: Exception) {}
            override fun requestCryptResync() { resyncs.incrementAndGet() }
        })
        transport.connect("127.0.0.1", serverSock.localPort)

        val msg = byteArrayOf(0, 9, 8, 7, 6)
        assertTrue(transport.send(msg, msg.size))
        val echoed = received.poll(3, TimeUnit.SECONDS)
        assertNotNull(echoed); assertArrayEquals(msg, echoed)
        assertEquals(0, resyncs.get())

        transport.close()
        serverSock.close()
    }

    @Test fun sendFailsBeforeCryptReady() {
        val transport = MumbleUdpTransport(CryptState(), object : MumbleUdpTransport.Listener {
            override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {}
            override fun onUdpError(e: Exception) {}
            override fun requestCryptResync() {}
        })
        assertFalse(transport.send(byteArrayOf(1), 1))
    }
}
