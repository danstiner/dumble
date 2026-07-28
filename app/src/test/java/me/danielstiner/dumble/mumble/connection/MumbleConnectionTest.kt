package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.TestTlsServer
import me.danielstiner.dumble.mumble.net.sha256Hex
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.FakeAudioOut
import me.danielstiner.dumble.mumble.voice.FakeOpusCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.HostnameVerifier

class MumbleConnectionTest {

    @Test fun firstContactAwaitsTrustThenPinsAndReachesHandshaking() = runBlocking {
        val srv = TestTlsServer()
        srv.start()
        try {
            val pins = InMemoryPinStore()
            val conn = MumbleConnection(pins, FakeOpusCodec(), { FakeAudioOut() }) { pin ->
                MumbleTcpTransport(
                    expectedPin = pin,
                    // First contact must be rejected by the authority path so MumbleTrustManager
                    // falls through to UntrustedCertificateException — never accept-everything.
                    trustDelegate = TestTlsServer.rejectingTrustManager(),
                    hostNameVerifier = HostnameVerifier { _, _ -> true },
                )
            }
            val endpoint = MumbleEndpoint.parse("localhost", srv.port)

            conn.connect(endpoint, "user", null)

            val awaiting = withTimeout(5_000) {
                conn.status.first { it is ConnectionStatus.AwaitingTrust }
            } as ConnectionStatus.AwaitingTrust
            assertEquals(sha256Hex(srv.leafCertificate.encoded), awaiting.fingerprint)

            conn.trustAndConnect()

            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            assertEquals(sha256Hex(srv.leafCertificate.encoded), pins.get(endpoint.pinKey))

            conn.disconnect()
        } finally {
            srv.close()
        }
    }

    @Test fun connectTimeoutMapsToTimeoutError() = runBlocking {
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) {
            FakeControlTransport { _, _ -> throw SocketTimeoutException("connect timed out") }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.TIMEOUT, err.kind)
    }

    @Test fun aSupersededHandshakeDoesNotClobberIdle() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) {
            FakeControlTransport { _, _ -> gate.await() }     // blocks mid-"handshake"
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it == ConnectionStatus.Connecting } }
        conn.disconnect()                                     // bumps the attempt generation
        assertEquals(ConnectionStatus.Idle, conn.status.value)
        gate.complete(Unit)                                   // stale attempt resumes and returns
        delay(100)                                            // let the stale coroutine run to completion
        assertEquals(ConnectionStatus.Idle, conn.status.value)  // guard held: no clobber
    }

    @Test fun channelTreeSurfacesReducedFrames() = runBlocking {
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) {
            FakeControlTransport { _, _ -> }.also { fake = it }   // connects, stays open
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        fake.listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))

        val tree = withTimeout(5_000) { conn.channelTree.first { it.channels.containsKey(1) } }
        assertEquals("Root", tree.channels[1]!!.name)
        conn.disconnect()
    }

    @Test fun disconnectResetsChannelTree() = runBlocking {
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) {
            FakeControlTransport { _, _ -> }.also { fake = it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fake.listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))
        withTimeout(5_000) { conn.channelTree.first { it.channels.containsKey(1) } }

        conn.disconnect()

        assertEquals(ChannelTree(), conn.channelTree.value)
    }

    @Test fun aSupersededAttemptLeavesChannelTreeEmpty() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) {
            FakeControlTransport { _, _ -> gate.await() }   // blocks mid-handshake
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it == ConnectionStatus.Connecting } }
        conn.disconnect()          // bumps the attempt generation
        gate.complete(Unit)        // stale attempt resumes and is torn down
        delay(100)
        assertEquals(ChannelTree(), conn.channelTree.value)
    }

    @Test fun messagesSurfaceFromTheSession() = runBlocking {
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) { FakeControlTransport { _, _ -> }.also { fake = it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray()))

        val msgs = withTimeout(5_000) { conn.messages.first { it.isNotEmpty() } }
        assertEquals("yo", (msgs.single() as ChatMessage.Remote).htmlBody)
        conn.disconnect()
    }

    @Test fun disconnectClearsMessages() = runBlocking {
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) { FakeControlTransport { _, _ -> }.also { fake = it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray()))
        withTimeout(5_000) { conn.messages.first { it.isNotEmpty() } }

        conn.disconnect()

        assertEquals(emptyList<ChatMessage>(), conn.messages.value)
    }

    @Test fun sendTextRoutesToTheLiveSessionAndEchoes() = runBlocking {
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) { FakeControlTransport { _, _ -> }.also { fake = it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.ServerSync.id,
            MumbleProtos.ServerSync.newBuilder().setSession(1).build().toByteArray()))
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(1).setChannelId(0).build().toByteArray()))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }

        val ok = conn.sendText("hello")

        assertTrue(ok)
        assertTrue(fake.sent.any { it.first == TcpMessageType.TextMessage })
        val msgs = withTimeout(5_000) { conn.messages.first { it.isNotEmpty() } }
        assertEquals("hello", (msgs.single() as ChatMessage.Remote).htmlBody)
        conn.disconnect()
    }

    @Test fun sendTextWithNoConnectionReturnsFalse() = runBlocking {
        val conn = MumbleConnection(InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() }) { FakeControlTransport { _, _ -> } }
        assertFalse(conn.sendText("hi"))
    }

    @Test fun speakingSessionsPopulateThenClearOnDisconnect() = runBlocking {
        lateinit var fake: FakeControlTransport
        val codec = FakeOpusCodec()
        val out = FakeAudioOut()
        val conn = MumbleConnection(InMemoryPinStore(), codec, { out }) {
            FakeControlTransport { _, _ -> }.also { fake = it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        // One 60 ms packet satisfies VoiceReceiver's 60 ms prebuffer (PREBUFFER_SAMPLES) in a
        // single frame, so the playout starts producing on the very next tick.
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(FakeOpusCodec.packet(6)))
            .build()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))

        // speakingSessions updates on the playback thread; the flow collect that republishes it
        // is another hop, so wait on the value rather than sampling it.
        val speaking = withTimeout(5_000) { conn.speakingSessions.first { it.isNotEmpty() } }
        assertEquals(setOf(9), speaking)

        // disconnect() zeroes the flow under the same lock as every other reset, so this is
        // deterministic even though the receiver's own stop() is handed to a coroutine.
        conn.disconnect()
        assertEquals(emptySet<Int>(), conn.speakingSessions.value)

        // The assertion above alone is vacuous as a lifecycle test: disconnect() writes the flow
        // synchronously and the generation guard blocks any later receiver-originated write, so
        // deleting the stop() from teardown() would still pass it. These are what actually prove
        // the receiver was released. stop() is handed to a coroutine, hence the poll.
        awaitTrue("teardown must close the AudioOut") { out.closed }
        assertEquals("teardown must close every decoder", codec.decodersCreated, codec.decodersClosed)
    }

    /**
     * A session that dies on its own reaches no disconnect() and supersedes no prior attempt, so
     * before retire() nothing tore the attempt down and the playback thread outlived it — waking
     * at 100 Hz with an open AudioTrack for as long as the error screen stayed up.
     */
    @Test fun aFailedSessionReleasesTheReceiver() = runBlocking {
        lateinit var fake: FakeControlTransport
        val codec = FakeOpusCodec()
        val out = FakeAudioOut()
        val conn = MumbleConnection(InMemoryPinStore(), codec, { out }) {
            FakeControlTransport { _, _ -> }.also { fake = it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        // Reach the receiver first, so there is a live decoder and an open output to release.
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(FakeOpusCodec.packet(6)))
            .build()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        withTimeout(5_000) { conn.speakingSessions.first { it.isNotEmpty() } }

        // The server rejects the login: a terminal state nobody asked for.
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))

        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.AUTH_REJECTED, err.kind)

        awaitTrue("a failed session must close the AudioOut") { out.closed }
        assertEquals("a failed session must close every decoder", codec.decodersCreated, codec.decodersClosed)
        // The error must survive the teardown — it is what the user is looking at.
        assertTrue("retire() must not reset the terminal status", conn.status.value is ConnectionStatus.Error)
    }

    /** Polls a condition a background coroutine will satisfy, rather than sleeping a fixed time. */
    private suspend fun awaitTrue(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(message, cond())
    }
}
