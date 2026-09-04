package me.danielstiner.dumble.mumble.net

import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.time.AtomicTimeSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every test talks to a real loopback peer keyed as the server would be: the transport's
 * encrypt seed is the peer's decrypt seed and the reverse, so a packet that arrives readable at
 * either end has crossed the whole path. Time is the test's to move: the throttle's quiet period
 * is the real five seconds, jumped over rather than waited out.
 */
class MumbleUdpTransportTest {
    private val key = ByteArray(16) { it.toByte() }
    private val ourNonce = ByteArray(16) { (0x40 + it).toByte() }
    private val theirNonce = ByteArray(16) { (0x80 + it).toByte() }

    private fun ourCrypt() = CryptState().apply { setKeys(key, ourNonce, theirNonce) }
    private fun theirCrypt() = CryptState().apply { setKeys(key, theirNonce, ourNonce) }

    private class Recorder : MumbleUdpTransport.Listener {
        val packets = LinkedBlockingQueue<ByteArray>()
        val buffers = LinkedBlockingQueue<ByteArray>()
        val replies = LinkedBlockingQueue<Duration>()
        val resyncs = AtomicInteger()
        val silences = AtomicInteger()
        override fun onVoicePacket(buf: ByteArray, len: Int) {
            packets.add(buf.copyOf(len))
            buffers.add(buf)
        }
        override fun onPingReply(roundTrip: Duration) { replies.add(roundTrip) }
        override fun requestCryptResync() { resyncs.incrementAndGet() }
        override fun onPingsUnanswered() { silences.incrementAndGet() }
    }

    /** A bound loopback socket whose thread answers each datagram with what [reply] returns. */
    private class Peer(reply: (ByteArray, Int) -> ByteArray?) {
        val channel: DatagramChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val address get() = channel.localAddress as InetSocketAddress
        val from = LinkedBlockingQueue<SocketAddress>()
        init {
            thread(isDaemon = true) {
                val wire = ByteBuffer.allocate(2048)
                try {
                    while (true) {
                        wire.clear()
                        val addr = channel.receive(wire) ?: break
                        from.add(addr)
                        val out = reply(wire.array(), wire.position()) ?: continue
                        channel.send(ByteBuffer.wrap(out), addr)
                    }
                } catch (_: Exception) {
                }
            }
        }
        fun close() = channel.close()
    }

    /** A peer that opens each datagram with [crypt] and seals the same plaintext back, after
     *  [beforeEcho] — which runs on the peer's thread, so it is ordered before the echo lands. */
    private fun echoPeer(crypt: CryptState = theirCrypt(), beforeEcho: () -> Unit = {}) = Peer { wire, n ->
        val plain = ByteArray(2048)
        val len = crypt.decrypt(wire, n, plain)
        if (len < 0) null else {
            beforeEcho()
            ByteArray(len + CryptState.HEADER_LEN).also { crypt.encrypt(plain, len, it) }
        }
    }

    private val clock = AtomicTimeSource()
    private fun quietPeriodPasses() { clock += 6.seconds }

    private fun transport(listener: MumbleUdpTransport.Listener, crypt: CryptState = ourCrypt()) =
        MumbleUdpTransport(crypt, listener, clock)

    private fun open(listener: MumbleUdpTransport.Listener, peer: Peer, crypt: CryptState = ourCrypt()) =
        transport(listener, crypt).apply { open(peer.address) }

    private fun awaitRecvThreads(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (recvThreads() != expected && System.nanoTime() < deadline) Thread.sleep(10)
    }

    private fun recvThreads() = Thread.getAllStackTraces().keys.count { it.name == "dumble-udp-recv" }

    @Test fun packetsRoundTripThroughOneReusedBuffer() {
        val peer = echoPeer()
        val rec = Recorder()
        val transport = open(rec, peer)

        assertTrue(transport.send(byteArrayOf(0, 1, 2, 3), 4))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), rec.packets.poll(3, TimeUnit.SECONDS))
        assertTrue(transport.send(byteArrayOf(0, 5), 2))
        assertArrayEquals("a shorter packet after a longer one", byteArrayOf(0, 5), rec.packets.poll(3, TimeUnit.SECONDS))

        assertSame("one receive buffer for the life of the socket", rec.buffers.poll(), rec.buffers.poll())
        transport.close()
        peer.close()
    }

    @Test fun sendRefusesWithoutASocketOrAKey() {
        val unopened = transport(Recorder())
        assertFalse("not open", unopened.send(byteArrayOf(0), 1))

        val peer = echoPeer()
        val unkeyed = open(Recorder(), peer, crypt = CryptState())
        assertFalse("open but unkeyed", unkeyed.send(byteArrayOf(0), 1))
        unkeyed.close()

        val closed = open(Recorder(), peer)
        closed.close()
        assertFalse("closed", closed.send(byteArrayOf(0), 1))
        peer.close()
    }

    // Murmur answers over UDP, whichever path our voice is on, only a ping with neither
    // extended-information field: `request_extended_information` we set ourselves, and
    // `contains_additional_information` the server derives from `server_version_v2 != 0`
    // (MumbleProtocol.cpp), so the second must be checked as well as the first.
    @Test fun aPingCarriesOnlyItsTimestamp() {
        val theirs = theirCrypt()
        val pings = LinkedBlockingQueue<Pair<Byte, MumbleUdpProtos.Ping>>()
        val peer = Peer { wire, n ->
            val plain = ByteArray(2048)
            val len = theirs.decrypt(wire, n, plain)
            if (len > 0) pings.add(plain[0] to MumbleUdpProtos.Ping.parser().parseFrom(plain, 1, len - 1))
            null
        }
        val transport = open(Recorder(), peer)
        clock += 250.milliseconds

        assertTrue(transport.sendPing())

        val (type, ping) = pings.poll(3, TimeUnit.SECONDS)!!
        assertEquals(1.toByte(), type)
        assertEquals("nanoseconds since the transport was built", 250_000_000L, ping.timestamp)
        assertFalse(ping.requestExtendedInformation)
        assertEquals(0L, ping.serverVersionV2)
        transport.close()
        peer.close()
    }

    @Test fun aPingReplyIsDatedByItsEchoAndNeverReachesVoice() {
        val peer = echoPeer(beforeEcho = { clock += 7.milliseconds })   // in flight for 7 ms
        val rec = Recorder()
        val transport = open(rec, peer)

        clock += 250.milliseconds   // off the floor sendPing puts under a zero stamp
        assertTrue(transport.sendPing())

        assertEquals("dated by the echoed stamp", 7.milliseconds, rec.replies.poll(3, TimeUnit.SECONDS))
        assertNull(rec.packets.poll(200, TimeUnit.MILLISECONDS))
        transport.close()
        peer.close()
    }

    // Each ping judges the one before it, so the report comes with the third ping of a silence
    // and not again until a reply has re-armed it. A socket that never opened has no server
    // bound to it and reports nothing.
    @Test fun twoUnansweredPingsAreReportedOncePerOutage() {
        val theirs = theirCrypt()
        // Both read on the peer's thread. The flag is flipped only once the peer has handled
        // every ping sent under its old value, or a late answer would re-arm the judgement.
        val answer = AtomicBoolean(false)
        val handled = AtomicInteger()
        val peer = Peer { wire, n ->
            val plain = ByteArray(2048)
            val len = theirs.decrypt(wire, n, plain)
            val reply = if (!answer.get() || len < 0) null else ByteArray(len + CryptState.HEADER_LEN).also { theirs.encrypt(plain, len, it) }
            handled.incrementAndGet()
            reply
        }
        fun awaitHandled(n: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (handled.get() < n && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("the peer handled every ping so far", n, handled.get())
        }
        val rec = Recorder()
        val transport = open(rec, peer)

        repeat(2) { assertTrue(transport.sendPing()) }
        assertEquals("the second ping judges the first: one unanswered", 0, rec.silences.get())
        assertTrue(transport.sendPing())
        assertEquals("the third judges the second: two in a row", 1, rec.silences.get())
        repeat(3) { assertTrue(transport.sendPing()) }
        assertEquals("no storm while it stays silent", 1, rec.silences.get())

        awaitHandled(6)
        answer.set(true)
        assertTrue(transport.sendPing())
        assertNotNull("a reply", rec.replies.poll(3, TimeUnit.SECONDS))
        awaitHandled(7)
        answer.set(false)
        repeat(3) { assertTrue(transport.sendPing()) }
        assertEquals("re-armed by the reply, so a second outage reports again", 2, rec.silences.get())
        transport.close()
        peer.close()

        val quiet = Recorder()
        val unopened = transport(quiet)
        repeat(4) { assertFalse(unopened.sendPing()) }
        assertEquals("never opened, never bound, nothing to report", 0, quiet.silences.get())
    }

    /** A peer that answers every datagram with [len] bytes no key opens. */
    private fun garbagePeer(len: Int = 20) = Peer { _, _ -> ByteArray(len) { (it * 7).toByte() } }

    // The server's one cap is on the wire size of what it accepts and on the packet it seals:
    // uplink, 1020 of packet fits under it; downlink, its datagrams run to 1028 and must arrive
    // whole. Anything larger was truncated by the read and must not count as a failed decrypt,
    // or a stream of them would ask for a resync every quiet period.
    @Test fun theLargestPacketsBothWaysArriveAndAnOversizedDatagramIsIgnored() {
        val theirs = theirCrypt()
        val downlink = ByteArray(1024) { it.toByte() }.also { it[0] = 0 }
        val peer = Peer { wire, n ->
            if (theirs.decrypt(wire, n, ByteArray(2048)) < 0) null
            else ByteArray(1028).also { theirs.encrypt(downlink, downlink.size, it) }
        }
        val rec = Recorder()
        val transport = open(rec, peer)
        val uplink = ByteArray(1020) { it.toByte() }.also { it[0] = 0 }

        assertTrue(transport.send(uplink, uplink.size))

        assertArrayEquals("the peer opened ours and we opened its", downlink, rec.packets.poll(3, TimeUnit.SECONDS))
        transport.close()
        peer.close()

        val big = garbagePeer(len = 1100)
        val rec2 = Recorder()
        val transport2 = open(rec2, big)
        quietPeriodPasses()
        assertTrue(transport2.send(byteArrayOf(0), 1))
        Thread.sleep(100)

        assertEquals("a truncated datagram is not a decrypt failure", 0, rec2.resyncs.get())
        transport2.close()
        big.close()
    }

    @Test fun unopenableDatagramsRequestOneResyncPerQuietPeriod() {
        val peer = garbagePeer()
        val rec = Recorder()
        val transport = open(rec, peer)
        quietPeriodPasses()   // the grace runs from construction

        assertTrue(transport.send(byteArrayOf(0), 1))
        Thread.sleep(100)
        assertTrue(transport.send(byteArrayOf(0), 1))
        Thread.sleep(100)

        assertEquals("two failures inside one quiet period, one request", 1, rec.resyncs.get())
        quietPeriodPasses()
        assertTrue(transport.send(byteArrayOf(0), 1))
        Thread.sleep(100)
        assertEquals("and one more once it has passed", 2, rec.resyncs.get())
        transport.close()
        peer.close()
    }

    @Test fun aFailureInsideTheGracePeriodRequestsNothing() {
        val peer = garbagePeer()
        val rec = Recorder()
        val transport = open(rec, peer)

        assertTrue(transport.send(byteArrayOf(0), 1))
        Thread.sleep(100)

        assertEquals(0, rec.resyncs.get())
        transport.close()
        peer.close()
    }

    @Test fun aGoodDatagramRestartsTheGracePeriod() {
        val theirs = theirCrypt()
        var garbage = false
        val peer = Peer { wire, n ->
            if (garbage) ByteArray(20) { (it * 7).toByte() }
            else {
                val plain = ByteArray(2048)
                val len = theirs.decrypt(wire, n, plain)
                if (len < 0) null else ByteArray(len + CryptState.HEADER_LEN).also { theirs.encrypt(plain, len, it) }
            }
        }
        val rec = Recorder()
        val transport = open(rec, peer)
        quietPeriodPasses()
        assertTrue(transport.send(byteArrayOf(0), 1))
        assertNotNull("the good echo", rec.packets.poll(3, TimeUnit.SECONDS))
        garbage = true

        assertTrue(transport.send(byteArrayOf(0), 1))
        Thread.sleep(100)

        assertEquals("a failure right after a success is not a lost counter", 0, rec.resyncs.get())
        transport.close()
        peer.close()
    }

    @Test fun closeEndsTheReaderAndOpenAfterCloseIsRefused() {
        val peer = Peer { _, _ -> null }   // never answers, so the read genuinely blocks
        val baseline = recvThreads()
        val transport = open(Recorder(), peer)
        awaitRecvThreads(baseline + 1)

        transport.close()

        awaitRecvThreads(baseline)
        assertEquals("closing the channel ends the reader", baseline, recvThreads())
        val late = transport(Recorder()).apply { close() }
        late.open(peer.address)
        Thread.sleep(50)
        assertEquals("a close that raced the open wins", baseline, recvThreads())
        assertFalse(late.send(byteArrayOf(0), 1))
        peer.close()
    }

    // The other way the reader ends: not our close() but the socket failing under it. Provoked by
    // interrupting the thread, which the channel reports as a closed-by-interrupt IOException —
    // the same branch a revoked fd takes. The socket must go with the reader, or it leaks.
    @Test fun aReaderThatDiesClosesTheSocket() {
        val peer = Peer { _, _ -> null }
        val before = Thread.getAllStackTraces().keys.filter { it.name == "dumble-udp-recv" }.toSet()
        val transport = open(Recorder(), peer)
        awaitRecvThreads(before.size + 1)
        val reader = Thread.getAllStackTraces().keys.single { it.name == "dumble-udp-recv" && it !in before }

        reader.interrupt()

        reader.join(3_000)
        assertFalse(reader.isAlive)
        assertFalse("the socket goes with its reader", transport.send(byteArrayOf(0), 1))
        peer.close()
    }

    // A connected socket is told, as PortUnreachableException, when an ICMP says nobody is
    // listening; a firewall that refuses UDP says the same. Neither ends the session's UDP:
    // the port may come back, and the ping keeps asking.
    @Test fun aPortUnreachableLeavesTheSocketListening() {
        val first = echoPeer()
        val port = first.address.port
        val rec = Recorder()
        val transport = open(rec, first)
        assertTrue(transport.send(byteArrayOf(0, 1), 2))
        assertNotNull(rec.packets.poll(3, TimeUnit.SECONDS))
        first.close()
        Thread.sleep(50)

        transport.send(byteArrayOf(0, 2), 2)   // draws the ICMP
        Thread.sleep(100)
        transport.send(byteArrayOf(0, 3), 2)   // may fail as the ICMP's own report
        Thread.sleep(100)

        val again = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", port)) }
        val wire = ByteBuffer.allocate(64)
        assertTrue(transport.send(byteArrayOf(0, 4), 2))
        again.socket().soTimeout = 3000
        val got = again.receive(wire)
        assertNotNull("the same socket reaches a peer that came back on the port", got)
        transport.close()
        again.close()
    }
}
