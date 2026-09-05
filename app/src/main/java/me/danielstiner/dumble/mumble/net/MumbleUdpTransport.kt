package me.danielstiner.dumble.mumble.net

import android.os.Process
import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.time.BootTimeSource
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The UDP voice channel: a connected [DatagramChannel] with [CryptState] at both edges. One
 * thread blocks in read, opens each datagram, and hands the plaintext to the listener by its
 * first byte. Sending has no thread of its own: callers seal in line, serialised on the one send
 * buffer, and [DatagramChannel.write] is thread-safe.
 *
 * Built inert: [open] connects and starts the reader, [close] ends both. Nothing here chooses a
 * path or ends the session. A socket that never opens costs nothing, since the server never
 * learns an address. One that stops reaching us after the first ping registered us is worse,
 * because the server keeps sending there, so unanswered pings are reported to the owner.
 */
class MumbleUdpTransport(
    private val crypt: CryptState,
    private val listener: Listener,
    /**
     * Counts sleep, as `BootTimeSource` explains: a reply that crossed a doze must read as old,
     * and a decrypt failing after one must ask for a resync at once. Injected because the
     * Android clock reads zero off-device.
     */
    private val clock: TimeSource.WithComparableMarks = BootTimeSource,
) {
    interface Listener {
        /** A decrypted voice datagram, `[u8 type][protobuf]`. [buf] is reused the moment this
         *  returns, and [len], not `buf.size`, bounds the packet. */
        fun onVoicePacket(buf: ByteArray, len: Int)

        /** The server answered a ping sent [roundTrip] ago, dated by its own echo of the stamp. */
        fun onPingReply(roundTrip: Duration)

        /** Two pings in a row unanswered, each judged when the next went out. Once per outage,
         *  and never for a socket that did not open. */
        fun onPingsUnanswered()

        /** Datagrams have failed to open for a quiet period with none succeeding: our counter is
         *  lost, and only the server can say where it is. At most once per quiet period. */
        fun requestCryptResync()
    }

    // Written under this object's monitor; read lock-free by send().
    @Volatile private var channel: DatagramChannel? = null
    @Volatile private var closed = false

    /** What the ping stamps count from: the wire wants a number and the clock gives marks. */
    private val origin = clock.markNow()

    // Set by the reader when a reply lands, consumed by the next ping's judgement.
    @Volatile private var answeredSincePing = true   // the first ping has no predecessor
    private var unanswered = 0

    // Heap-backed so array() is the cipher's destination. The server checks the wire cap before
    // it strips the header, so the largest packet we can seal is four bytes under it.
    private val sendBuf = ByteBuffer.allocate(MAX_PACKET_LEN)
    @Volatile private var sendFailureLogged = false

    // Reader-confined. A failed decrypt asks for nothing before this; every good datagram and
    // every request push it out by a quiet period, upstream's throttle on both ends
    // (ServerHandler::udpReady, Server::checkDecrypt) in one number.
    private var quietUntil = origin + RESYNC_QUIET

    /**
     * Connects to [address] and starts the reader. Throws if the socket cannot be made or on a
     * second call; a [close] that came first wins. [address] should be the control connection's
     * own remote: the server holds crypt state only for the session at that address, and a name
     * can resolve differently twice.
     */
    fun open(address: InetSocketAddress) {
        check(channel == null) { "open() twice" }
        val ch = DatagramChannel.open()
        try {
            ch.connect(address)
        } catch (t: Throwable) {
            runCatching { ch.close() }
            throw t
        }
        synchronized(this) {
            if (closed) {
                runCatching { ch.close() }
                return
            }
            channel = ch
        }
        Thread({ receiveLoop(ch) }, "dumble-udp-recv").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop(ch: DatagramChannel) {
        // The capture pump's priority: above UI and IO work, below the Oboe callback. A late
        // datagram is only jitter for the queue to absorb. Set here because it applies to the
        // calling thread.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        // One byte past the largest datagram the server sends, so an oversized one can be told
        // from a full one. Decrypt works in place, and the listener copies what it keeps.
        val buf = ByteBuffer.allocate(MAX_DATAGRAM_LEN + 1)
        var unreachableLogged = false
        while (true) {
            buf.clear()
            val n = try {
                ch.read(buf)
            } catch (e: PortUnreachableException) {
                // An ICMP from a port with nobody behind it, which a connected socket surfaces
                // where desktop's unconnected one never sees it. The path may heal and the ping
                // keeps probing, so keep listening.
                if (!unreachableLogged) {
                    unreachableLogged = true
                    Log.w(TAG, "server port unreachable; still listening")
                }
                continue
            } catch (e: IOException) {
                // Our own close() unblocking the read, or a socket gone for good.
                if (!closed) Log.w(TAG, "receive failed; UDP voice is off for this session", e)
                close()
                return
            }
            // A listener that throws must not take the reader down with it: the socket would
            // stay open with nothing reading it, and voice would go quiet with no log.
            runCatching { received(buf.array(), n) }
                .onFailure { Log.e(TAG, "listener failed on a datagram", it) }
        }
    }

    private fun received(datagram: ByteArray, n: Int) {
        // The header plus at least the type byte, and no more than the server sends: past that
        // the read truncated it, and the tag would fail for no fault of the counter's. Unkeyed
        // is skipped for the same reason.
        if (n <= CryptState.HEADER_LEN || n > MAX_DATAGRAM_LEN || !crypt.isValid()) return
        val len = crypt.decrypt(datagram, n, datagram)
        val now = clock.markNow()
        if (len < 0) {
            if (now < quietUntil) return
            quietUntil = now + RESYNC_QUIET
            listener.requestCryptResync()
            return
        }
        quietUntil = now + RESYNC_QUIET
        if (datagram[0] != UDP_TYPE_PING) {
            listener.onVoicePacket(datagram, len)
            return
        }
        val echoed = try {
            MumbleUdpProtos.Ping.parser().parseFrom(datagram, 1, len - 1).timestamp
        } catch (e: InvalidProtocolBufferException) {
            return
        }
        // Never ours: sendPing floors the stamp at 1, and an empty reply parses as 0.
        if (echoed == 0L) return
        val sentAt = origin + echoed.nanoseconds
        answeredSincePing = true
        listener.onPingReply(now - sentAt)
    }

    /**
     * Seals and sends `plaintext[0, len)` from any thread. False with no open socket, an unkeyed
     * cipher, or a failed send; a route gone from under us is the case that matters, and the
     * caller decides what it means. Throws only for a packet past the wire cap, a caller bug.
     */
    fun send(plaintext: ByteArray, len: Int): Boolean {
        val ch = channel ?: return false
        if (closed || !crypt.isValid()) return false
        synchronized(sendBuf) {
            return try {
                sendBuf.clear()
                val datagramLen = crypt.encrypt(plaintext, len, sendBuf.array())
                sendBuf.limit(datagramLen)
                ch.write(sendBuf) == datagramLen
            } catch (e: PortUnreachableException) {
                false   // the reader already logged it
            } catch (e: IOException) {
                if (!sendFailureLogged) {
                    sendFailureLogged = true
                    Log.w(TAG, "send failed", e)
                }
                false
            }
        }
    }

    /**
     * A connectivity ping: the timestamp and nothing else, the one shape Murmur answers over UDP
     * whichever path our voice is on (`Server.cpp`, the `force` reply to a ping with neither
     * extended-information field), so a reply proves both directions at once. The stamp comes
     * back verbatim, so a reply dates itself. Floored at 1: a zero serialises to nothing, and
     * the server refuses a packet of the type byte alone.
     */
    fun sendPing(): Boolean {
        if (channel != null && previousPingWentUnanswered()) listener.onPingsUnanswered()
        val stamp = (clock.markNow() - origin).inWholeNanoseconds.coerceAtLeast(1)
        val body = MumbleUdpProtos.Ping.newBuilder().setTimestamp(stamp).build().toByteArray()
        val packet = ByteArray(body.size + 1)
        packet[0] = UDP_TYPE_PING
        body.copyInto(packet, 1)
        return send(packet, packet.size)
    }

    /** Pings are an interval apart, so by the time one goes out the previous one's reply has had
     *  its chance. Exactly the second miss in a row reports, so an outage reports once. */
    @Synchronized
    private fun previousPingWentUnanswered(): Boolean {
        unanswered = if (answeredSincePing) 0 else unanswered + 1
        answeredSincePing = false
        return unanswered == UNANSWERED_TO_REPORT
    }

    /** Idempotent, any thread. Closing the channel ends the reader; nothing waits for it. */
    fun close() {
        val ch = synchronized(this) {
            if (closed) return
            closed = true
            channel
        }
        runCatching { ch?.close() }
    }

    companion object {
        private const val TAG = "MumbleUdpTransport"

        /**
         * The server's one cap (`Mumble::Protocol::MAX_UDP_PACKET_SIZE`): on the wire size of
         * what it accepts, checked before the header comes off, and on what it seals, to which
         * the header is then added.
         */
        private const val MAX_PACKET_LEN = 1024
        private const val MAX_DATAGRAM_LEN = MAX_PACKET_LEN + CryptState.HEADER_LEN
        private val RESYNC_QUIET = 5.seconds
        private const val UDP_TYPE_PING: Byte = 1
        private const val UNANSWERED_TO_REPORT = 2
    }
}
