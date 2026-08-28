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
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.TestTlsServer
import me.danielstiner.dumble.mumble.net.sha256Hex
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakePlayoutEngine
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier

class MumbleConnectionTest {

    @Test fun firstContactAwaitsTrustThenPinsAndReachesHandshaking() = runBlocking {
        val srv = TestTlsServer()
        srv.start()
        try {
            val pins = InMemoryPinStore()
            val conn = MumbleConnection(pins) { pin ->
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
            assertEquals(sha256Hex(srv.leafCertificate.encoded), pins.get(endpoint.address))

            conn.disconnect()
        } finally {
            srv.close()
        }
    }

    @Test fun connectTimeoutMapsToTimeoutError() = runBlocking {
        val conn = MumbleConnection(InMemoryPinStore()) {
            FakeControlTransport { _, _ -> throw SocketTimeoutException("connect timed out") }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.TIMEOUT, err.kind)
    }

    @Test fun aSupersededHandshakeDoesNotClobberIdle() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val conn = MumbleConnection(InMemoryPinStore()) {
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
        val conn = MumbleConnection(InMemoryPinStore()) {
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
        val conn = MumbleConnection(InMemoryPinStore()) {
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

    /**
     * The other half of [disconnectResetsChannelTree], and the one nothing covered: connect() clears
     * the same flows disconnect() does, and only the shared helper keeps the two in step. Connecting
     * straight over a live connection is an ordinary path — the Connect button with a different host
     * typed in — and anything left behind is the previous server's channels and chat rendered under
     * the new server's name.
     */
    @Test fun connectingOverALiveConnectionClearsThePriorSessionsState() = runBlocking {
        val fakes = mutableListOf<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) {
            FakeControlTransport { _, _ -> }.also { fakes += it }
        }
        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fakes[0].listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))
        fakes[0].listener!!.onFrame(TcpFrame(
            TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray(),
        ))
        withTimeout(5_000) { conn.channelTree.first { it.channels.containsKey(1) } }
        withTimeout(5_000) { conn.messages.first { it.isNotEmpty() } }

        conn.connect(MumbleEndpoint.parse("second"), "user", null)

        // Sampled, not awaited: connect() clears under the same lock that bumps the generation, so
        // it has already happened when the call returns, and every publish helper is gen-checked so
        // the retired attempt cannot write these again.
        assertEquals(ChannelTree(), conn.channelTree.value)
        assertEquals(emptyList<ChatMessage>(), conn.messages.value)

        conn.disconnect()
    }

    @Test fun aSupersededAttemptLeavesChannelTreeEmpty() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val conn = MumbleConnection(InMemoryPinStore()) {
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
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { fake = it } }
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
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { fake = it } }
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
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { fake = it } }
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
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> } }
        assertFalse(conn.sendText("hi"))
    }

    @Test fun speakingSessionsPopulateThenClearOnDisconnect() = runBlocking {
        lateinit var fake: FakeControlTransport
        val playout = FakePlayoutEngine()
        val conn = MumbleConnection(InMemoryPinStore(), newPlayout = { playout }) {
            FakeControlTransport { _, _ -> }.also { fake = it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        // The engine's answer to the packet below: session 9 holds a slot and is producing.
        playout.liveSessions = setOf(9)
        playout.audibleSessions = setOf(9)
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .build()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))

        // speakingSessions updates on the receiver's poll; the flow collect that republishes it
        // is another hop, so wait on the value rather than sampling it.
        val speaking = withTimeout(5_000) { conn.speakingSessions.first { it.isNotEmpty() } }
        assertEquals(setOf(9), speaking)
        awaitTrue("the packet must reach the engine") { playout.offered.isNotEmpty() }
        awaitTrue("a live speaker must start the stream") { playout.started }

        // disconnect() zeroes the flow under the same lock as every other reset, so this is
        // deterministic even though the receiver's own stop() is handed to a coroutine.
        conn.disconnect()
        assertEquals(emptySet<Int>(), conn.speakingSessions.value)

        // The assertion above alone is vacuous as a lifecycle test: disconnect() writes the flow
        // synchronously and the generation guard blocks any later receiver-originated write, so
        // deleting the stop() from teardown() would still pass it. This is what actually proves
        // the receiver was released. stop() is handed to a coroutine, hence the poll.
        awaitTrue("teardown must destroy the playout engine") { playout.destroyed }
    }

    /**
     * A session that dies on its own reaches no disconnect() and supersedes no prior attempt, so
     * before retire() nothing tore the attempt down and the playback thread outlived it — waking
     * at 100 Hz with an open AudioTrack for as long as the error screen stayed up.
     */
    @Test fun aFailedSessionReleasesTheReceiver() = runBlocking {
        lateinit var fake: FakeControlTransport
        val playout = FakePlayoutEngine()
        val conn = MumbleConnection(InMemoryPinStore(), newPlayout = { playout }) {
            FakeControlTransport { _, _ -> }.also { fake = it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        // Reach the receiver first, so there is a live playout and a started stream to release.
        playout.liveSessions = setOf(9)
        playout.audibleSessions = setOf(9)
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .build()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        withTimeout(5_000) { conn.speakingSessions.first { it.isNotEmpty() } }

        // The server rejects the login: a terminal state nobody asked for.
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))

        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.AUTH_REJECTED, err.kind)

        // Awaited for the same reason as in the disconnect test: stop() runs on a coroutine.
        awaitTrue("a failed session must destroy the playout engine") { playout.destroyed }
        // The error must survive the teardown — it is what the user is looking at.
        assertTrue("retire() must not reset the terminal status", conn.status.value is ConnectionStatus.Error)
    }

    /**
     * connect() bumps `attempt` synchronously but builds the attempt inside a coroutine, so a
     * disconnect() landing while the pin lookup is still suspended leaves an attempt that was
     * constructed and never published. Nothing else can reach it — it was never in `current`, so no
     * teardown path knows about it — which makes the guard at the top of that coroutine its only
     * exit, and releasing the transport there its own responsibility.
     *
     * Deterministic rather than racy: the gate holds the coroutine inside pinStore.get(), which is
     * upstream of the publish, so disconnect() always wins.
     */
    @Test fun anAttemptSupersededBeforePublishReleasesItsTransport() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val pins = object : PinStore {
            override suspend fun get(key: String): String? { gate.await(); return null }
            override suspend fun put(key: String, fingerprint: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val engines = AtomicInteger()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(
            pins,
            newPlayout = { engines.incrementAndGet(); FakePlayoutEngine() },
        ) {
            FakeControlTransport { _, _ -> }.also { transports += it }
        }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        conn.disconnect()
        gate.complete(Unit)

        awaitTrue("superseded attempt left its transport open") {
            transports.size == 1 && transports.all { it.closed }
        }
        assertEquals("no receiver should ever start on this path", 0, engines.get())
    }

    /** Polls a condition a background coroutine will satisfy, rather than sleeping a fixed time. */
    /**
     * The whole transmit path end to end, since every piece of it is new wiring: the service comes
     * up before the engine is opened, the pump reaches the wire, the gate follows push-to-talk, and
     * teardown releases all three. Asserting on `sentRaw` rather than on the sender means the test
     * cannot pass with a pump that was built but never started.
     */
    @Test fun requestCaptureRunsTheSendPathAndDisconnectReleasesIt() = runBlocking {
        lateinit var fake: FakeControlTransport
        val handle = FakeCaptureHandle()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { handle },
            call = call,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), frameNumber = 7, terminator = false))
        conn.requestCapture()

        awaitTrue("the pump must reach the wire") { fake.sentRaw.isNotEmpty() }
        val (type, payload) = fake.sentRaw.first()
        assertEquals(TcpMessageType.UDPTunnel, type)
        // Leading 0 is the UDP audio type byte the tunnel carries ahead of the Audio protobuf.
        assertEquals(0, payload[0].toInt())

        // setTransmitting only reaches the engine once the sender is published, which happens on
        // the same coroutine that opened it — so this runs after the awaits above, not before.
        conn.setTransmitting(true)
        awaitTrue("push-to-talk must open the gate") { handle.gateOpen }

        conn.disconnect()
        awaitTrue("teardown must stop the pump") { handle.stopped }
        awaitTrue("teardown must destroy the engine") { handle.destroyed }
        awaitTrue("teardown must end the call") { call.ends == 1 }
    }

    /**
     * The call belongs to the connection, not to the microphone. Before this, the foreground service
     * was only ever started from requestCapture(), so denying RECORD_AUDIO left the session with no
     * service at all — and receive silently reverted to working only while the activity was visible.
     */
    @Test fun callStartsWithTheConnectionNotTheMicrophone() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { null },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)

        // No requestCapture() anywhere in this test: connecting alone must register the call.
        awaitTrue("connecting must start the call with the server host") {
            call.starts == listOf("localhost")
        }
        conn.disconnect()
        awaitTrue("disconnecting must end the call") { call.ends == 1 }
        assertEquals("a hang-up is the user's doing", listOf(VoiceCall.Reason.USER), call.endReasons)
    }

    /**
     * A session that dies on us is not a hang-up. The platform records a different disconnect cause
     * for each, and reporting a server failure as LOCAL would claim the user ended a call they did
     * not.
     */
    @Test fun aFailedConnectEndsTheCallAsFailureNotHangUp() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), call = call,
        ) { FakeControlTransport { _, _ -> throw SocketTimeoutException("connect timed out") } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)

        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } }
        awaitTrue("a failed handshake must end the call") { call.ends == 1 }
        assertEquals(
            "a handshake that never produced a session is not a hang-up",
            listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons,
        )
    }

    /** The system ending the call (a cellular call taking over) must take the session down with it. */
    @Test fun aSystemEndedCallDisconnectsTheSession() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { null },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        call.endedBySystem()

        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Idle } }
        Unit
    }

    /**
     * A hold tears the capture session down instead of merely closing the gate, and a resume builds
     * a fresh one. This is the design's substitute for gating the reopen backoff on focus state: proving
     * the rebuilt session reaches the wire is what makes the substitution honest, since a teardown
     * that could not come back would be worse than the backoff it replaced.
     */
    @Test fun holdTearsTheSessionDownAndResumeRebuildsIt() = runBlocking {
        lateinit var fake: FakeControlTransport
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        conn.requestCapture()
        awaitTrue("the engine must open") { handles.size == 1 }
        handles[0].script(FakeCaptureHandle.Step.Frame(byteArrayOf(1), frameNumber = 1, terminator = false))
        awaitTrue("the first session must reach the wire") { fake.sentRaw.size == 1 }

        call.hold()
        awaitTrue("a hold must stop the pump") { handles[0].stopped }
        awaitTrue("a hold must destroy the engine") { handles[0].destroyed }

        call.resume()
        awaitTrue("resuming must build a new engine") { handles.size == 2 }
        handles[1].script(FakeCaptureHandle.Step.Frame(byteArrayOf(2), frameNumber = 2, terminator = false))
        awaitTrue("the rebuilt session must reach the wire") { fake.sentRaw.size == 2 }

        conn.disconnect()
        awaitTrue("disconnect must end the call") { call.ends == 1 }
        awaitTrue("disconnect must release the rebuilt engine") { handles[1].destroyed }
    }

    /** A late callback from a call we already ended must not resurrect a dead session. */
    @Test fun holdDeliveredAfterDisconnectRebuildsNothing() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the engine must open") { handles.size == 1 }

        conn.disconnect()
        awaitTrue("disconnect must end the call") { call.ends == 1 }
        call.resume()
        delay(200)
        assertEquals("no engine may be built for a dead attempt", 1, handles.size)
    }

    /**
     * Connecting over a live connection must release the platform call it replaces. start() used to
     * overwrite liveGen without ending anything, after which end(priorGen) was a no-op forever — the
     * OS kept a call whose onDisconnect is wired to disconnect(), so hanging up the ghost from
     * system UI tore down the session that replaced it.
     */
    @Test fun connectingOverALiveConnectionEndsThePriorCall() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { null },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        assertEquals(listOf("first", "second"), call.starts)
        awaitTrue("the superseded call must end") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.USER), call.endReasons)

        conn.disconnect()
        awaitTrue("disconnecting must end the second call") { call.ends == 2 }
    }

    /** Denied microphone, or an engine that would not open: receive still needs the call's service. */
    @Test fun captureThatCannotOpenLeavesTheCallRunning() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { null },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        conn.requestCapture()
        // No engine, so push-to-talk is inert rather than a crash.
        conn.setTransmitting(true)
        delay(200)
        assertEquals("the call must survive an engine that would not open", 0, call.ends)
        conn.disconnect()
    }

    private fun routes(vararg types: AudioRoute.Type, current: AudioRoute.Type? = null): AudioRoutes {
        val available = types.map { AudioRoute("id-$it", it) }
        return AudioRoutes(available, current?.let { c -> available.first { it.type == c } })
    }

    /** The platform's answer is the only source; nothing local invents a route. */
    @Test fun routesFromTheLiveCallReachTheUi() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        call.emitRoutes(routes(AudioRoute.Type.EARPIECE, AudioRoute.Type.SPEAKER, current = AudioRoute.Type.EARPIECE))

        awaitTrue("routes must reach the flow") { conn.audioRoutes.value.available.size == 2 }
        assertEquals(AudioRoute.Type.EARPIECE, conn.audioRoutes.value.current?.type)
    }

    /**
     * A collector inside a cancelled addCall block can still emit — cancellation is asynchronous —
     * so the generation guard is what stops a dead call repainting the live one's control.
     */
    @Test fun routesFromASupersededCallAreDropped() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        val staleGen = call.startedGens.first()

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        call.emitRoutesFor(staleGen, routes(AudioRoute.Type.BLUETOOTH, current = AudioRoute.Type.BLUETOOTH))

        assertEquals(AudioRoutes(), conn.audioRoutes.value)
    }

    /** Every other published flow is cleared on retire; a survivor here would paint the next
     *  session's control with the last one's headset. */
    @Test fun disconnectClearsTheRoutes() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        call.emitRoutes(routes(AudioRoute.Type.SPEAKER, current = AudioRoute.Type.SPEAKER))
        awaitTrue("routes must reach the flow first") { conn.audioRoutes.value.current != null }

        conn.disconnect()

        awaitTrue("teardown must clear the routes") { conn.audioRoutes.value == AudioRoutes() }
    }

    /** The UI carries no generation, so the connection has to supply the live one. */
    @Test fun selectingARouteAddressesTheLiveCall() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        conn.requestAudioRoute("id-SPEAKER")

        awaitTrue("the pick must reach the call") { call.routeRequests == listOf("id-SPEAKER") }
    }

    @Test fun selectingARouteWithNothingConnectedIsANoOp() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.requestAudioRoute("id-SPEAKER")

        assertEquals(emptyList<String>(), call.routeRequests)
    }

    private suspend fun awaitTrue(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(message, cond())
    }
}
