package me.danielstiner.dumble.mumble.integration

import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.sha256Hex
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Live-server integration. Skipped unless MUMBLE_TEST_SERVER is set, so ordinary unit-test runs
 * are unaffected. Continuous integration sets it to `localhost` against a service container.
 *
 *   MUMBLE_TEST_SERVER=localhost ./gradlew testDebugUnitTest \
 *     --tests "*LiveServerIntegrationTest"
 */
class LiveServerIntegrationTest {

    private val host: String? = System.getenv("MUMBLE_TEST_SERVER")
    private val port: Int = System.getenv("MUMBLE_TEST_PORT")?.toIntOrNull() ?: 64738
    private val password: String? = System.getenv("MUMBLE_TEST_PASSWORD")

    @Before fun requiresServer() = assumeTrue("set MUMBLE_TEST_SERVER to run", host != null)

    @Test
    fun handshakeReachesSynchronized() = runBlocking {
        val target = host!!
        awaitPort(target, port)

        // Trust bootstrap: the container generates a fresh self-signed certificate on every start,
        // so it is neither authority-signed nor already pinned, and a headless test has no way to
        // prompt. Do exactly what the connect interface will do — capture the digest, store it,
        // then connect for real. This keeps the production pinned path under test rather than
        // bypassing trust with an accept-everything manager.
        val fingerprint = probeLeafFingerprint(target, port)
        val endpoint = MumbleEndpoint.parse(target, port)
        val pins = InMemoryPinStore().apply { put(endpoint.address, fingerprint) }

        val transport = MumbleTcpTransport(expectedPin = pins.get(endpoint.address))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = SessionStateMachine(transport, "dumble-ci", password, scope)

        try {
            connectWithRetry(transport, target, port, session)
            session.start()

            val state = withTimeout(20_000) {
                session.state.first { it is ConnectionState.Synchronized || it is ConnectionState.Failed }
            }

            assertTrue("expected Synchronized, was $state", state is ConnectionState.Synchronized)
            assertTrue((state as ConnectionState.Synchronized).sessionId > 0)
        } finally {
            transport.close()
            scope.cancel()
        }
    }

    /**
     * MumbleUDP.proto documents target 2^5 - 1 = 31 as "server loopback": the server echoes the
     * packet straight back instead of forwarding it to a channel. That makes the whole inbound
     * wire path testable with no microphone, no encoder, and no second client.
     *
     * Asserts byte-identity, not decode — libdumble.so is built for Android ABIs only, so no JVM
     * test can call libopus. Decode is covered by the on-device gate.
     */
    @Test
    fun loopbackAudioReturnsThroughTheTunnel() = runBlocking {
        val target = host!!
        awaitPort(target, port)

        val fingerprint = probeLeafFingerprint(target, port)
        val endpoint = MumbleEndpoint.parse(target, port)
        val pins = InMemoryPinStore().apply { put(endpoint.address, fingerprint) }
        val transport = MumbleTcpTransport(expectedPin = pins.get(endpoint.address))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = SessionStateMachine(transport, "dumble-ci-voice", password, scope)

        // A single TOC byte is a legal Opus packet (RFC 6716 3.1): SILK-NB, 20 ms, mono, code 0.
        // Nothing here decodes it — the server relays audio untouched and this is a JVM test.
        val fixture = byteArrayOf(0x08)

        val echoed = CompletableDeferred<ByteArray>()
        session.audioListener = SessionStateMachine.AudioListener { payload ->
            if (payload.isNotEmpty() && payload[0].toInt() == 0) {
                val audio = MumbleUdpProtos.Audio.parseFrom(payload.copyOfRange(1, payload.size))
                if (!audio.opusData.isEmpty) echoed.complete(audio.opusData.toByteArray())
            }
        }

        try {
            connectWithRetry(transport, target, port, session)
            session.start()
            withTimeout(20_000) { session.state.first { it is ConnectionState.Synchronized } }

            val audio = MumbleUdpProtos.Audio.newBuilder()
                .setTarget(31)
                .setFrameNumber(0)
                .setOpusData(ByteString.copyFrom(fixture))
                .build()
            val packet = byteArrayOf(0) + audio.toByteArray()
            assertTrue("payload exceeds the server's 1024-byte tunnel cap", packet.size < 1024)
            assertTrue("tunnel send failed", transport.sendRaw(TcpMessageType.UDPTunnel, packet))

            val back = withTimeout(15_000) { echoed.await() }
            assertArrayEquals("loopback returned different opus bytes", fixture, back)
        } finally {
            transport.close()
            scope.cancel()
        }
    }

    /** A control connection plus the UDP socket beside it, wired as MumbleConnection wires them.
     *  Pinned straight to the probed fingerprint: the same trust path as the tests above, minus
     *  the store. */
    private inner class Client(name: String, udpListener: MumbleUdpTransport.Listener) {
        val transport = MumbleTcpTransport(expectedPin = probeLeafFingerprint(host!!, port))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = SessionStateMachine(transport, name, password, scope)
        // The boot clock reads zero on the JVM; the round trip needs a real one.
        val udp = MumbleUdpTransport(session.crypt, udpListener, TimeSource.Monotonic)

        suspend fun connect() {
            connectWithRetry(transport, host!!, port, session)
            udp.open(requireNotNull(transport.remoteAddress()))
            session.udpPing = { udp.sendPing() }
            session.start()
            withTimeout(20_000) { session.state.first { it is ConnectionState.Synchronized } }
        }

        fun close() {
            udp.close()
            transport.close()
            scope.cancel()
        }
    }

    private class Recorder : MumbleUdpTransport.Listener {
        val packets = LinkedBlockingQueue<ByteArray>()
        val replies = LinkedBlockingQueue<Duration>()
        override fun onVoicePacket(buf: ByteArray, len: Int) { packets.add(buf.copyOf(len)) }
        override fun onPingReply(roundTrip: Duration) { replies.add(roundTrip) }
        override fun onPingsUnanswered() = Unit
        override fun requestCryptResync() = Unit
    }

    /**
     * The cipher against the real thing: a ping the server could open, answered with a datagram
     * we could open. Written from the OCB2 paper rather than ported, so this is the check that the
     * two implementations agree on the wire, key schedule and nonce handling included.
     */
    @Test
    fun theUdpPingIsAnsweredOverUdp() = runBlocking {
        awaitPort(host!!, port)
        val rec = Recorder()
        val client = Client("dumble-ci-udp", rec)
        try {
            client.connect()

            val roundTrip = rec.replies.poll(15, TimeUnit.SECONDS)
            assertTrue("no UDP ping reply from the server", roundTrip != null)
            assertTrue("round trip $roundTrip", !roundTrip!!.isNegative() && roundTrip < 5.seconds)
            assertTrue("the reply must have counted as a good decrypt", client.session.crypt.stats().good >= 1)
        } finally {
            client.close()
        }
    }

    /**
     * The sequencing the socket exists for: a client that has only ever pinged — never
     * transmitted — gets its downlink over UDP, because the server's per-user flag starts set and
     * the ping registered the address. A second client talks through the tunnel; the first hears
     * it on the UDP socket, with nothing arriving through its own tunnel.
     */
    @Test
    fun aListenerWhoNeverTransmittedReceivesOverUdp() = runBlocking {
        awaitPort(host!!, port)
        val heard = Recorder()
        val listener = Client("dumble-ci-listener", heard)
        val talker = Client("dumble-ci-talker", Recorder())
        val tunneled = LinkedBlockingQueue<ByteArray>()
        listener.session.audioListener = SessionStateMachine.AudioListener { tunneled.add(it) }
        val fixture = byteArrayOf(0x08)
        try {
            listener.connect()
            assertTrue("the listener's ping must be answered first", heard.replies.poll(15, TimeUnit.SECONDS) != null)
            talker.connect()

            val audio = MumbleUdpProtos.Audio.newBuilder()
                .setFrameNumber(0)
                .setOpusData(ByteString.copyFrom(fixture))
                .build()
            assertTrue(talker.transport.sendRaw(TcpMessageType.UDPTunnel, byteArrayOf(0) + audio.toByteArray()))

            val packet = heard.packets.poll(15, TimeUnit.SECONDS)
            assertTrue("the talker's audio never arrived over UDP", packet != null)
            val received = MumbleUdpProtos.Audio.parseFrom(packet!!.copyOfRange(1, packet.size))
            assertArrayEquals(fixture, received.opusData.toByteArray())
            assertTrue("came from the talker's session", received.senderSession == (talker.session.state.value as ConnectionState.Synchronized).sessionId)
            assertTrue("nothing came through the listener's tunnel", tunneled.isEmpty())
        } finally {
            talker.close()
            listener.close()
        }
    }

    /**
     * The mechanism the connection's answer to unanswered pings rests on: the server moves a
     * client's downlink back to the tunnel on any frame that client tunnels, a ping included,
     * and does not move it out again on a further UDP ping. Same shape as the test above, then
     * one tunneled ping from the listener before the talker speaks.
     */
    @Test
    fun aTunneledPingPullsTheDownlinkBackToTheTunnel() = runBlocking {
        awaitPort(host!!, port)
        val heard = Recorder()
        val listener = Client("dumble-ci-nudger", heard)
        val talker = Client("dumble-ci-talker2", Recorder())
        val tunneled = LinkedBlockingQueue<ByteArray>()
        listener.session.audioListener = SessionStateMachine.AudioListener { tunneled.add(it) }
        val fixture = byteArrayOf(0x08)
        try {
            listener.connect()
            assertTrue(heard.replies.poll(15, TimeUnit.SECONDS) != null)
            talker.connect()
            val tunneledPing = byteArrayOf(1) + MumbleUdpProtos.Ping.newBuilder().setTimestamp(1).build().toByteArray()
            assertTrue(listener.transport.sendRaw(TcpMessageType.UDPTunnel, tunneledPing))
            assertTrue("UDP still answers after the nudge", listener.udp.sendPing())
            assertTrue(heard.replies.poll(15, TimeUnit.SECONDS) != null)

            val audio = MumbleUdpProtos.Audio.newBuilder()
                .setFrameNumber(0)
                .setOpusData(ByteString.copyFrom(fixture))
                .build()
            assertTrue(talker.transport.sendRaw(TcpMessageType.UDPTunnel, byteArrayOf(0) + audio.toByteArray()))

            val packet = tunneled.poll(15, TimeUnit.SECONDS)
            assertTrue("the talker's audio must now come through the tunnel", packet != null)
            assertArrayEquals(fixture, MumbleUdpProtos.Audio.parseFrom(packet!!.copyOfRange(1, packet.size)).opusData.toByteArray())
            assertTrue("and no longer over UDP", heard.packets.isEmpty())
        } finally {
            talker.close()
            listener.close()
        }
    }

    /** The uplink over UDP against the real thing: a datagram the talker seals is opened by the
     *  server and forwarded, here to a listener whose downlink is UDP. */
    @Test
    fun audioSentOverUdpReachesAListener() = runBlocking {
        awaitPort(host!!, port)
        val heard = Recorder()
        val listener = Client("dumble-ci-udp-listener", heard)
        val talker = Client("dumble-ci-udp-talker", Recorder())
        val fixture = byteArrayOf(0x08)
        try {
            listener.connect()
            assertTrue(heard.replies.poll(15, TimeUnit.SECONDS) != null)
            talker.connect()
            val audio = MumbleUdpProtos.Audio.newBuilder().setFrameNumber(0).setOpusData(ByteString.copyFrom(fixture)).build()
            val packet = byteArrayOf(0) + audio.toByteArray()
            assertTrue(talker.udp.send(packet, packet.size))

            val received = heard.packets.poll(15, TimeUnit.SECONDS)
            assertTrue("the talker's datagram never reached the listener", received != null)
            assertArrayEquals(fixture, MumbleUdpProtos.Audio.parseFrom(received!!.copyOfRange(1, received.size)).opusData.toByteArray())
        } finally {
            talker.close()
            listener.close()
        }
    }

    /**
     * [awaitPort] only proves the TCP port is accepting connections; the TLS listener behind it
     * can still refuse the handshake for a few seconds longer during container cold start. Retry
     * the actual connect rather than widening the plain-socket probe above.
     */
    private suspend fun connectWithRetry(
        transport: MumbleTcpTransport,
        host: String,
        port: Int,
        session: SessionStateMachine,
    ) {
        val deadline = System.currentTimeMillis() + 30_000
        var attempt = 0
        while (true) {
            attempt++
            try {
                transport.connect(host, port, object : MumbleControlTransport.Listener {
                    override fun onFrame(f: TcpFrame) = session.onFrame(f)
                    override fun onClosed(cause: Throwable?) = session.onClosed(cause)
                })
                return
            } catch (t: Throwable) {
                if (System.currentTimeMillis() >= deadline) {
                    throw AssertionError("connect to $host:$port failed after $attempt attempts", t)
                }
                Thread.sleep(1_000)
            }
        }
    }

    /** The server image declares no health check, so the port may not be accepting yet. */
    private fun awaitPort(host: String, port: Int) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }
                return
            } catch (_: Exception) {
                Thread.sleep(1_000)
            }
        }
        throw AssertionError("mumble server at $host:$port never accepted a connection")
    }

    /** One throwaway connection whose only job is to record the leaf certificate digest. */
    private fun probeLeafFingerprint(host: String, port: Int): String {
        var captured: String? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                captured = sha256Hex(chain[0].encoded)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress(host, port), 10_000)
            s.startHandshake()
        }
        return requireNotNull(captured) { "the probe did not yield a server certificate" }
    }
}
