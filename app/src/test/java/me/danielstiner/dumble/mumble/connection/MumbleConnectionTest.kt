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
import me.danielstiner.dumble.mumble.voice.FakeAudioOut
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakeOpusCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
        // Awaited, not sampled: out.close() runs in loop()'s finally, before the thread dies, while
        // stop() closes the decoders only after join() returns. Asserting straight off out.closed
        // reads that gap and fails whenever stop()'s coroutine is slow to be scheduled.
        awaitTrue("teardown must close every decoder") { codec.decodersClosed == codec.decodersCreated }
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
        // Awaited for the same reason as in the disconnect test: the output closes on the playback
        // thread's way out, the decoders only once stop() has joined it.
        awaitTrue("a failed session must close every decoder") { codec.decodersClosed == codec.decodersCreated }
        // The error must survive the teardown — it is what the user is looking at.
        assertTrue("retire() must not reset the terminal status", conn.status.value is ConnectionStatus.Error)
    }

    /**
     * A session can reach a terminal state before connect() gets as far as starting the receiver —
     * an instant auth reject, or a socket dropped right after the TLS handshake. retire() releases
     * such an attempt without bumping `attempt`, so a guard testing the generation alone still
     * passes and starts a receiver whose stop() has already run: a playback thread nothing will
     * ever stop, holding an open AudioOut until the process dies, one per failed connect.
     *
     * Whether that window is hit is pure scheduling. On an idle machine the connect coroutine wins
     * every time — against the bug this body passed 200 consecutive runs. The load threads are what
     * make it reachable, at which point it reproduced on roughly a quarter of runs.
     */
    @Test fun aSessionFailingBeforeStartLeavesNoReceiverRunning() = runBlocking {
        val stopLoad = AtomicBoolean(false)
        val load = (1..Runtime.getRuntime().availableProcessors()).map {
            Thread { var x = 0L; while (!stopLoad.get()) x += 1 }.apply { isDaemon = true; start() }
        }
        try {
            repeat(50) { run ->
                val outs = CopyOnWriteArrayList<FakeAudioOut>()
                lateinit var fake: FakeControlTransport
                val conn = MumbleConnection(
                    InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut().also { outs += it } },
                ) {
                    // Rejected from inside connect(), so the session is already Failed by the time
                    // the state collector is launched — well before the receiver would start.
                    FakeControlTransport { _, _ ->
                        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
                            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))
                    }.also { fake = it }
                }

                conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
                withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } }
                // Either ordering is fine: the guard sees the release and never starts (no output
                // is built at all), or it starts first and the teardown's stop() closes it. What
                // must never survive is an output that was opened and left open.
                awaitTrue("run $run: receiver started for a retired attempt and was never stopped") {
                    outs.all { it.closed }
                }
            }
        } finally {
            stopLoad.set(true)
            load.forEach { it.join(1_000) }
        }
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
        val outs = CopyOnWriteArrayList<FakeAudioOut>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(pins, FakeOpusCodec(), { FakeAudioOut().also { outs += it } }) {
            FakeControlTransport { _, _ -> }.also { transports += it }
        }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        conn.disconnect()
        gate.complete(Unit)

        awaitTrue("superseded attempt left its transport open") {
            transports.size == 1 && transports.all { it.closed }
        }
        assertTrue("no receiver should ever start on this path", outs.isEmpty())
    }

    /** Polls a condition a background coroutine will satisfy, rather than sleeping a fixed time. */
    /**
     * The whole transmit path end to end, since every piece of it is new wiring: the service comes
     * up before the engine is opened, the pump reaches the wire, the gate follows push-to-talk, and
     * teardown releases all three. Asserting on `sentRaw` rather than on the sender means the test
     * cannot pass with a pump that was built but never started.
     */
    @Test fun startCaptureRunsTheSendPathAndDisconnectReleasesIt() = runBlocking {
        lateinit var fake: FakeControlTransport
        val handle = FakeCaptureHandle()
        val serviceStarts = CopyOnWriteArrayList<String>()
        var serviceStops = 0
        val conn = MumbleConnection(
            InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() },
            newCapture = { handle },
            startService = { serviceStarts += it },
            stopService = { serviceStops++ },
        ) { FakeControlTransport { _, _ -> }.also { fake = it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), frameNumber = 7, terminator = false))
        conn.startCapture()

        awaitTrue("the microphone service must start with the server host") {
            serviceStarts == listOf("localhost")
        }
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
        awaitTrue("teardown must stop the service") { serviceStops == 1 }
    }

    /** Denied microphone, or an engine that would not open: receive still needs the service. */
    @Test fun captureThatCannotOpenLeavesTheServiceRunning() = runBlocking {
        val serviceStarts = CopyOnWriteArrayList<String>()
        val conn = MumbleConnection(
            InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() },
            newCapture = { null },
            startService = { serviceStarts += it },
            stopService = {},
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        conn.startCapture()
        awaitTrue("the service must start even when the engine will not open") {
            serviceStarts == listOf("localhost")
        }
        // No engine, so push-to-talk is inert rather than a crash.
        conn.setTransmitting(true)
        conn.disconnect()
    }

    private suspend fun awaitTrue(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(message, cond())
    }
}
