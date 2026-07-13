package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
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

    @Test fun zeroPayloadPacketRoundTrips() {
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

        assertTrue(transport.send(ByteArray(0), 0))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var echoed: ByteArray? = null
        while (System.nanoTime() < deadline) {
            echoed = received.poll(100, TimeUnit.MILLISECONDS)
            if (echoed != null) break
        }
        assertNotNull("expected zero-payload echo within poll window", echoed)
        assertEquals(0, echoed!!.size)
        assertEquals(0, resyncs.get())

        transport.close()
        serverSock.close()
    }

    @Test fun listenerExceptionDoesNotKillReceiveLoop() {
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
        val deliveredCount = AtomicInteger()
        val received = LinkedBlockingQueue<ByteArray>()
        val transport = MumbleUdpTransport(clientCrypt, object : MumbleUdpTransport.Listener {
            override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {
                val count = deliveredCount.incrementAndGet()
                if (count == 1) throw RuntimeException("boom: simulated listener parse bug")
                received.add(buf.copyOf(len))
            }
            override fun onUdpError(e: Exception) {}
            override fun requestCryptResync() {}
        })
        transport.connect("127.0.0.1", serverSock.localPort)

        val msg1 = byteArrayOf(1, 1, 1)
        val msg2 = byteArrayOf(2, 2, 2, 2)
        assertTrue(transport.send(msg1, msg1.size))
        Thread.sleep(50)
        assertTrue(transport.send(msg2, msg2.size))

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var echoed: ByteArray? = null
        while (System.nanoTime() < deadline) {
            echoed = received.poll(100, TimeUnit.MILLISECONDS)
            if (echoed != null) break
        }
        assertNotNull("receive loop should survive a listener exception and deliver the next packet", echoed)
        assertArrayEquals(msg2, echoed)

        transport.close()
        serverSock.close()
    }
}
