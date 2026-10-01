package me.danielstiner.dumble.mumble.net

import me.danielstiner.dumble.hangGuard
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.time.AtomicTimeSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the transport makes of a datagram — opening it, dating a ping reply, throttling resyncs,
 * judging unanswered pings — is tested by handing [MumbleUdpTransport.received] datagrams the
 * server's end of the cipher sealed, with no socket and no thread. What the socket does — the
 * round trip, the one receive buffer, the size limits, the reader's life — goes through a real
 * loopback peer keyed as the server would be: the transport's encrypt seed is the peer's decrypt
 * seed and the reverse, so a packet that arrives readable at either end has crossed the whole
 * path. Time is the test's to move: the throttle's quiet period is the real five seconds, jumped
 * over rather than waited out.
 */
class MumbleUdpTransportTest {
    @get:Rule val timeout = hangGuard()

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
        // The buffer first: a test wakes on packets and reads buffers right after, so the
        // other order let it read an empty queue between the two adds (four CI failures).
        override fun onVoicePacket(buf: ByteArray, len: Int) {
            buffers.add(buf)
            packets.add(buf.copyOf(len))
        }
        override fun onPingReply(roundTrip: Duration) { replies.add(roundTrip) }
        override fun requestCryptResync() { resyncs.incrementAndGet() }
        override fun onPingsUnanswered() { silences.incrementAndGet() }
    }

    /** A loopback socket bound to [port] (any, if 0) whose thread answers each datagram with what
     *  [reply] returns. */
    private class Peer(port: Int, reply: (ByteArray, Int) -> ByteArray?) {
        val channel: DatagramChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", port)) }
        val address get() = channel.localAddress as InetSocketAddress
        val from = LinkedBlockingQueue<SocketAddress>()
        private val reader = thread(isDaemon = true) {
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

        /** Returns with the port free: a receive in progress can hold the socket past the channel's
         *  close, until the reader has left it. */
        fun close() {
            channel.close()
            reader.join()
        }
    }

    // Closed after every test, passed or failed, so no socket or reader outlives it.
    private val toClose = mutableListOf<() -> Unit>()

    @After fun closeEverything() {
        toClose.forEach { runCatching(it) }
    }

    private fun peer(port: Int = 0, reply: (ByteArray, Int) -> ByteArray?) =
        Peer(port, reply).also { toClose += it::close }

    private fun silentPeer() = peer { _, _ -> null }

    /**
     * A peer that opens each datagram and seals the same plaintext back; given [garbage], a
     * one-byte packet is answered instead with that many bytes no key opens. It answers in order
     * and the reader reads in order, so once a later echo has arrived, the garbage before it has
     * been read.
     */
    private fun echoPeer(garbage: Int = 0): Peer {
        val theirs = theirCrypt()
        return peer { wire, n ->
            val plain = ByteArray(2048)
            val len = theirs.decrypt(wire, n, plain)
            when {
                len < 0 -> null
                len == 1 && garbage > 0 -> ByteArray(garbage) { (it * 7).toByte() }
                else -> ByteArray(len + CryptState.HEADER_LEN).also { theirs.encrypt(plain, len, it) }
            }
        }
    }

    private val clock = AtomicTimeSource()
    private fun quietPeriodPasses() { clock += 6.seconds }

    private fun transport(listener: MumbleUdpTransport.Listener, crypt: CryptState = ourCrypt()) =
        MumbleUdpTransport(crypt, listener, clock)

    private fun open(listener: MumbleUdpTransport.Listener, peer: Peer, crypt: CryptState = ourCrypt()) =
        transport(listener, crypt).apply { open(peer.address) }.also { toClose += it::close }

    // The server's end of the cipher, for the tests that hand the transport its datagrams.
    private val server = theirCrypt()

    /** [plaintext], sealed by the server, handed over as the reader would hand it. */
    private fun MumbleUdpTransport.receivesSealed(plaintext: ByteArray) {
        val datagram = ByteArray(plaintext.size + CryptState.HEADER_LEN)
        received(datagram, server.encrypt(plaintext, plaintext.size, datagram))
    }

    private fun MumbleUdpTransport.receivesGarbage() = received(ByteArray(20) { (it * 7).toByte() }, 20)

    /** A ping's plaintext: the type byte, then [stamp] as the only field. */
    private fun ping(stamp: Long) =
        byteArrayOf(1) + MumbleUdpProtos.Ping.newBuilder().setTimestamp(stamp).build().toByteArray()

    private fun recvThreads() = Thread.getAllStackTraces().keys.filter { it.name == "dumble-udp-recv" }.toSet()

    /** The reader [open] started, alive from the moment it returned. By identity, not by count:
     *  readers that earlier tests closed may still be draining in this JVM. */
    private fun readerSince(before: Set<Thread>) = (recvThreads() - before).single()

    @Test fun packetsRoundTripThroughOneReusedBuffer() {
        val rec = Recorder()
        val transport = open(rec, echoPeer())

        assertTrue(transport.send(byteArrayOf(0, 1, 2, 3), 4))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), rec.packets.take())
        assertTrue(transport.send(byteArrayOf(0, 5), 2))
        assertArrayEquals("a shorter packet after a longer one", byteArrayOf(0, 5), rec.packets.take())

        assertSame("one receive buffer for the life of the socket", rec.buffers.poll(), rec.buffers.poll())
    }

    @Test fun sendRefusesWithoutASocketOrAKey() {
        val unopened = transport(Recorder())
        assertFalse("not open", unopened.send(byteArrayOf(0), 1))

        val peer = echoPeer()
        val unkeyed = open(Recorder(), peer, crypt = CryptState())
        assertFalse("open but unkeyed", unkeyed.send(byteArrayOf(0), 1))

        val closed = open(Recorder(), peer)
        closed.close()
        assertFalse("closed", closed.send(byteArrayOf(0), 1))
    }

    // Murmur answers over UDP, whichever path our voice is on, only a ping with neither
    // extended-information field: `request_extended_information` we set ourselves, and
    // `contains_additional_information` the server derives from `server_version_v2 != 0`
    // (MumbleProtocol.cpp), so the second must be checked as well as the first.
    @Test fun aPingCarriesOnlyItsTimestamp() {
        val theirs = theirCrypt()
        val pings = LinkedBlockingQueue<Pair<Byte, MumbleUdpProtos.Ping>>()
        val peer = peer { wire, n ->
            val plain = ByteArray(2048)
            val len = theirs.decrypt(wire, n, plain)
            if (len > 0) pings.add(plain[0] to MumbleUdpProtos.Ping.parser().parseFrom(plain, 1, len - 1))
            null
        }
        val transport = open(Recorder(), peer)
        clock += 250.milliseconds

        assertTrue(transport.sendPing())

        val (type, ping) = pings.take()
        assertEquals(1.toByte(), type)
        assertEquals("nanoseconds since the transport was built", 250_000_000L, ping.timestamp)
        assertFalse(ping.requestExtendedInformation)
        assertEquals(0L, ping.serverVersionV2)
    }

    @Test fun aPingReplyIsDatedByItsEchoAndNeverReachesVoice() {
        val rec = Recorder()
        val transport = transport(rec)
        // A stamp counts from the transport's construction, as aPingCarriesOnlyItsTimestamp pins.
        val sentAt = 250.milliseconds
        clock += sentAt + 7.milliseconds   // the reply lands 7 ms after its ping left

        transport.receivesSealed(ping(stamp = sentAt.inWholeNanoseconds))

        assertEquals("dated by the echoed stamp", listOf(7.milliseconds), rec.replies.toList())
        assertTrue("never voice", rec.packets.isEmpty())
    }

    // Each ping judges the one before it, so the report comes with the third ping of a silence
    // and not again until a reply has re-armed it. A socket that never opened has no server
    // bound to it and reports nothing.
    @Test fun twoUnansweredPingsAreReportedOncePerOutage() {
        val rec = Recorder()
        // Opened, since only an open socket reports; the peer never answers, and the one reply
        // below is handed over directly.
        val transport = open(rec, silentPeer())

        repeat(2) { assertTrue(transport.sendPing()) }
        assertEquals("the second ping judges the first: one unanswered", 0, rec.silences.get())
        assertTrue(transport.sendPing())
        assertEquals("the third judges the second: two in a row", 1, rec.silences.get())
        repeat(3) { assertTrue(transport.sendPing()) }
        assertEquals("no storm while it stays silent", 1, rec.silences.get())

        assertTrue(transport.sendPing())
        transport.receivesSealed(ping(stamp = 1))   // any stamp but zero is one of ours
        assertEquals("a reply", 1, rec.replies.size)
        repeat(3) { assertTrue(transport.sendPing()) }
        assertEquals("re-armed by the reply, so a second outage reports again", 2, rec.silences.get())

        val quiet = Recorder()
        val unopened = transport(quiet)
        repeat(4) { assertFalse(unopened.sendPing()) }
        assertEquals("never opened, never bound, nothing to report", 0, quiet.silences.get())
    }

    // The server's one cap is on the wire size of what it accepts and on the packet it seals:
    // uplink, 1020 of packet fits under it; downlink, its datagrams run to 1028 and must arrive
    // whole. Anything larger was truncated by the read and must not count as a failed decrypt,
    // or a stream of them would ask for a resync every quiet period.
    @Test fun theLargestPacketsBothWaysArriveAndAnOversizedDatagramIsIgnored() {
        val theirs = theirCrypt()
        val downlink = ByteArray(1024) { it.toByte() }.also { it[0] = 0 }
        val peer = peer { wire, n ->
            if (theirs.decrypt(wire, n, ByteArray(2048)) < 0) null
            else ByteArray(1028).also { theirs.encrypt(downlink, downlink.size, it) }
        }
        val rec = Recorder()
        val transport = open(rec, peer)
        val uplink = ByteArray(1020) { it.toByte() }.also { it[0] = 0 }

        assertTrue(transport.send(uplink, uplink.size))

        assertArrayEquals("the peer opened ours and we opened its", downlink, rec.packets.take())

        val rec2 = Recorder()
        val transport2 = open(rec2, echoPeer(garbage = 1100))
        quietPeriodPasses()
        assertTrue(transport2.send(byteArrayOf(0), 1))      // answered with 1100 bytes
        assertTrue(transport2.send(byteArrayOf(0, 1), 2))   // answered with its echo, read after them
        rec2.packets.take()

        assertEquals("a truncated datagram is not a decrypt failure", 0, rec2.resyncs.get())
    }

    @Test fun unopenableDatagramsRequestOneResyncPerQuietPeriod() {
        val rec = Recorder()
        val transport = transport(rec)
        quietPeriodPasses()   // the grace runs from construction

        transport.receivesGarbage()
        transport.receivesGarbage()

        assertEquals("two failures inside one quiet period, one request", 1, rec.resyncs.get())
        quietPeriodPasses()
        transport.receivesGarbage()
        assertEquals("and one more once it has passed", 2, rec.resyncs.get())
    }

    @Test fun aFailureInsideTheGracePeriodRequestsNothing() {
        val rec = Recorder()

        transport(rec).receivesGarbage()

        assertEquals(0, rec.resyncs.get())
    }

    @Test fun aGoodDatagramRestartsTheGracePeriod() {
        val rec = Recorder()
        val transport = transport(rec)
        quietPeriodPasses()
        transport.receivesSealed(byteArrayOf(0))
        assertEquals("the good datagram", 1, rec.packets.size)

        transport.receivesGarbage()

        assertEquals("a failure right after a success is not a lost counter", 0, rec.resyncs.get())
    }

    @Test fun closeEndsTheReaderAndOpenAfterCloseIsRefused() {
        val peer = silentPeer()   // never answers, so the read genuinely blocks
        val before = recvThreads()
        val transport = open(Recorder(), peer)
        val reader = readerSince(before)

        transport.close()

        reader.join()   // a close that failed to end the reader would hang here, for the guard to report
        val late = transport(Recorder()).apply { close() }
        val beforeLate = recvThreads()
        late.open(peer.address)
        assertEquals("a close that raced the open wins", emptySet<Thread>(), recvThreads() - beforeLate)
        assertFalse(late.send(byteArrayOf(0), 1))
    }

    // The other way the reader ends: not our close() but the socket failing under it. Provoked by
    // interrupting the thread, which the channel reports as a closed-by-interrupt IOException —
    // the same branch a revoked fd takes. The socket must go with the reader, or it leaks.
    @Test fun aReaderThatDiesClosesTheSocket() {
        val before = recvThreads()
        val transport = open(Recorder(), silentPeer())
        val reader = readerSince(before)

        reader.interrupt()

        reader.join()
        assertFalse("the socket goes with its reader", transport.send(byteArrayOf(0), 1))
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
        rec.packets.take()
        first.close()

        transport.send(byteArrayOf(0, 2), 2)   // draws the ICMP
        // Time for the reader, rather than the next send, to take the ICMP. No event marks that
        // (its only trace is a log line), so a slow run exercises the send's handling instead.
        Thread.sleep(100)
        // The port comes back before that send, so it either takes the ICMP or arrives, never
        // drawing another for the asserted send to take: the test cannot fail for want of time.
        val theirs = theirCrypt()
        val arrived = LinkedBlockingQueue<List<Byte>>()
        peer(port = port) { wire, n ->
            val plain = ByteArray(2048)
            val len = theirs.decrypt(wire, n, plain)
            if (len >= 0) arrived.put(plain.copyOf(len).toList())
            null
        }
        transport.send(byteArrayOf(0, 3), 2)

        assertTrue(transport.send(byteArrayOf(0, 4), 2))
        // The same socket reaches a peer that came back on the port. Waits for the asserted send
        // itself: the one before it usually lands first.
        while (arrived.take() != listOf<Byte>(0, 4)) Unit
    }
}
