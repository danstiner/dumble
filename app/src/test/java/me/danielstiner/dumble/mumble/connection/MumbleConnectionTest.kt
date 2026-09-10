package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.TestTlsServer
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.net.sha256Hex
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakePlayoutEngine
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.time.AtomicTimeSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        val arrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) {
            // Blocks the thread with no dispatcher change, so unlike the real handshake's
            // withContext(IO) the return is not a cancellation point: the stale driver runs on
            // to its own live check every time. Holds one Default worker while parked.
            FakeControlTransport { _, _ -> arrived.complete(Unit); release.await() }
                .also { transports += it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { arrived.await() }               // the driver is inside the handshake
        conn.disconnect()                                     // bumps the generation
        assertEquals(ConnectionStatus.Idle, conn.status.value)
        release.countDown()   // stale driver finishes its handshake late; the live check closes what it built
        delay(100)                                            // let the stale driver run to its live check
        assertEquals(ConnectionStatus.Idle, conn.status.value)  // guard held: no clobber
        awaitTrue("the superseded link must be closed") { transports.single().closed }
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
        // the retired session cannot write these again.
        assertEquals(ChannelTree(), conn.channelTree.value)
        assertEquals(emptyList<ChatMessage>(), conn.messages.value)

        conn.disconnect()
    }

    @Test fun aSupersededSessionLeavesChannelTreeEmpty() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val conn = MumbleConnection(InMemoryPinStore()) {
            // Blocks the thread with no dispatcher change, so the return is not a cancellation
            // point and the stale driver runs on to its live check every time.
            FakeControlTransport { _, _ -> arrived.complete(Unit); release.await() }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { arrived.await() }   // the driver is inside the handshake
        conn.disconnect()          // bumps the generation
        release.countDown()   // stale driver finishes its handshake late; the live check closes what it built
        delay(100)   // let the stale driver run to its live check
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
        awaitEngineBuilt(playout)
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
     * A session that dies on its own reaches no disconnect() and supersedes no prior session, so
     * before retire() nothing tore the session down and the playback thread outlived it — waking
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
        awaitEngineBuilt(playout)
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
     * connect() publishes the session synchronously, but its link is built on the driver after the
     * pin lookup, so a disconnect() landing while that lookup is still suspended must find the
     * session already retired. No teardown can reach a link that was never published, so the
     * driver's own live check after the lookup is what closes it.
     *
     * Deterministic rather than racy: the lookup holds the driver on a latch that blocks the
     * thread rather than suspending, so disconnect() always wins and the cancel cannot land at a
     * resume; the driver returns from the lookup instead of dying inside it, which is the case
     * the check exists for.
     */
    @Test fun aDisconnectDuringThePinLookupClosesTheLinkItBuilt() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val pins = object : PinStore {
            override suspend fun get(key: String): String? { arrived.complete(Unit); release.await(); return null }
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
        withTimeout(5_000) { arrived.await() }   // the driver is inside the lookup
        conn.disconnect()
        release.countDown()

        awaitTrue("a superseded session must close the transport it built") {
            transports.size == 1 && transports.all { it.closed }
        }
        assertEquals("no receiver should ever start on this path", 0, engines.get())
    }

    /**
     * The driver lives on the session's scope, so a disconnect() cancels it wherever it waits.
     * Held inside a cancellable pin lookup it dies there and never builds a transport.
     */
    @Test fun aDisconnectDuringThePinLookupCancelsTheDriver() = runBlocking {
        val parked = CompletableDeferred<CancellableContinuation<Unit>>()
        val pins = object : PinStore {
            // Handed out only once the driver is suspended here, so the cancel that follows lands
            // on this very wait rather than on a driver still on its way to it.
            override suspend fun get(key: String): String? {
                suspendCancellableCoroutine { parked.complete(it) }
                return null
            }
            override suspend fun put(key: String, fingerprint: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(pins) { FakeControlTransport { _, _ -> }.also { transports += it } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val wait = withTimeout(5_000) { parked.await() }
        conn.disconnect()
        wait.resume(Unit)   // ignored once cancelled; a driver that survived would run on and build
        delay(200)

        assertTrue("the cancelled driver must build nothing", transports.isEmpty())
    }

    /**
     * Cancelled inside the handshake, the driver never reaches its own live check: the link it
     * published under the lock before connecting is teardown's to close.
     */
    @Test fun aDisconnectDuringTheHandshakeClosesTheLink() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) {
            FakeControlTransport { _, _ -> gate.await() }.also { transports += it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        awaitTrue("the driver must reach the handshake") { transports.firstOrNull()?.listener != null }

        conn.disconnect()

        awaitTrue("teardown must close the link the driver published") { transports.single().closed }
        gate.complete(Unit)   // inert: the wait was cancelled
        Unit
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

    /** The counters reach the flow from the live session's pump and leave with the session. */
    @Test fun theCaptureCountersFollowTheSession() = runBlocking {
        val handle = FakeCaptureHandle()
        handle.stats = CaptureStats(
            encodedPackets = 0, encodeErrors = 0, encodeMicrosMean = 400, encodeMicrosMax = 900,
            ringOverruns = 0, skippedSamples = 0, streamOverruns = 0, framesPerBurst = 96,
            droppedFrames = 0, inputLatencyMillis = 12.0,
        )
        val conn = MumbleConnection(InMemoryPinStore(), newCapture = { handle }, call = FakeVoiceCall()) {
            FakeControlTransport { _, _ -> }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()

        // The pump reads the counters after a poll returns, two seconds apart; keep it polling.
        awaitTrue("the counters must reach the flow", timeoutMillis = 6_000) {
            handle.script(FakeCaptureHandle.Step.Retry)
            conn.captureStats.value != null
        }
        assertEquals(12.0, conn.captureStats.value!!.inputLatencyMillis)

        conn.disconnect()
        awaitTrue("teardown must clear the counters") { conn.captureStats.value == null }
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

    /**
     * A connect that fails outright leaves nothing for the user to act on, unlike a trust prompt,
     * so the session is retired on the spot: the microphone must not open against it, and the
     * call ends exactly once, as a failure.
     */
    @Test fun aHardConnectFailureRetiresTheSession() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> throw IOException("connection refused") }.also { transports += it } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.CONNECT_FAILED, err.kind)
        awaitTrue("the failure must end the call") { call.ends == 1 }

        conn.requestCapture()
        delay(200)

        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        assertTrue("no microphone may open for a retired session", handles.isEmpty())
        assertTrue("retire() must not reset the terminal status", conn.status.value is ConnectionStatus.Error)
        awaitTrue("retire must close the link") { transports.single().closed }
        conn.disconnect()
    }

    /** A trust prompt retires its session the same way; the one difference is that the prompt
     *  keeps the session aside for trustAndConnect() to reconnect from. */
    @Test fun aTrustPromptRetiresTheSessionAndCanStillBeAccepted() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val pins = InMemoryPinStore()
        val conn = MumbleConnection(
            pins,
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { pin ->
            FakeControlTransport { _, _ -> if (pin == null) throw UntrustedCertificateException("ab12") }
                .also { transports += it }
        }
        val endpoint = MumbleEndpoint.parse("localhost")

        conn.connect(endpoint, "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.AwaitingTrust } }
        awaitTrue("the prompt must end the call") { call.ends == 1 }

        conn.requestCapture()
        delay(200)

        assertTrue("no microphone may open while a prompt is up", handles.isEmpty())
        awaitTrue("the stopped handshake's transport is closed") { transports.single().closed }
        assertTrue(conn.status.value is ConnectionStatus.AwaitingTrust)

        conn.trustAndConnect()
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        assertEquals("ab12", pins.get(endpoint.address))
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        conn.disconnect()
    }

    /** The generation is the UI's handle on "which call": one per connect(), whatever session id
     *  the server hands out. */
    @Test fun connectedCarriesTheSessionsGeneration() = runBlocking {
        val fakes = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { fakes += it } }
        val sync = TcpFrame(TcpMessageType.ServerSync.id,
            MumbleProtos.ServerSync.newBuilder().setSession(5).build().toByteArray())

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fakes[0].listener!!.onFrame(sync)
        val first = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } } as ConnectionStatus.Connected

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fakes[1].listener!!.onFrame(sync)
        val second = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } } as ConnectionStatus.Connected

        assertEquals(5, first.sessionId)
        assertEquals(5, second.sessionId)
        assertNotEquals(first.gen, second.gen)
        conn.disconnect()
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
        assertEquals("no engine may be built for a dead session", 1, handles.size)
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

    /**
     * Handshaking is published by sm.start(); receiver.start() — which builds the engine — runs
     * several statements later, and a packet arriving before it is dropped by design (there is
     * nothing yet to queue into). Delivering a tunneled frame on the status alone therefore races
     * that gap: measured at one lost packet in 40 runs, which is the CI flake. The poll's first
     * start() call is the earliest observable proof the engine exists.
     */
    private suspend fun awaitEngineBuilt(playout: FakePlayoutEngine) =
        awaitTrue("the receiver's poll never started") { playout.startAttempts.get() > 0 }

    private suspend fun awaitTrue(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(message, cond())
    }

    private fun serverSync(session: Int) = TcpFrame(
        TcpMessageType.ServerSync.id,
        MumbleProtos.ServerSync.newBuilder().setSession(session).build().toByteArray(),
    )

    private fun reject(type: MumbleProtos.Reject.RejectType) = TcpFrame(
        TcpMessageType.Reject.id,
        MumbleProtos.Reject.newBuilder().setType(type).setReason(type.name).build().toByteArray(),
    )

    private fun textFrom(actor: Int, body: String) = TcpFrame(
        TcpMessageType.TextMessage.id,
        MumbleProtos.TextMessage.newBuilder().setActor(actor).setMessage(body).build().toByteArray(),
    )

    /** The nth transport the connection built, once it exists and has connected. */
    private suspend fun transportAt(transports: List<FakeControlTransport>, index: Int): FakeControlTransport {
        awaitTrue("transport $index must be built and connected") {
            transports.size > index && transports[index].listener != null
        }
        return transports[index]
    }

    /**
     * The nth transport once its state machine has started, which the handshake's Version on the
     * wire is the proof of. `listener` is set at the top of connect(), several steps before
     * start(), and a ServerSync fed in that window is dropped by its compare-and-set on
     * Handshaking, leaving the link to look like one that never synchronized.
     */
    private suspend fun startedTransportAt(transports: List<FakeControlTransport>, index: Int): FakeControlTransport {
        val transport = transportAt(transports, index)
        awaitTrue("transport $index must have started its handshake") {
            transport.sent.any { it.first == TcpMessageType.Version }
        }
        return transport
    }

    /**
     * The point of the split: the platform call, the receiver and the capture session all belong
     * to the session and ride through a relink; only the link is rebuilt.
     */
    @Test fun aDeadLinkIsReplacedUnderTheSameSession() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val playout = FakePlayoutEngine()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            newPlayout = { playout }, call = call,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } } as ConnectionStatus.Connected
        assertEquals(1, first.sessionId)
        conn.requestCapture()
        awaitTrue("capture must open on the first link") { handles.size == 1 }

        transports[0].listener!!.onClosed(IOException("reset"))

        val reconnecting = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Reconnecting } } as ConnectionStatus.Reconnecting
        assertEquals(ConnectionStatus.Reconnecting(first.gen, 1), reconnecting)
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        val second = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } } as ConnectionStatus.Connected
        assertEquals(ConnectionStatus.Connected(first.gen, 2), second)

        assertEquals("one platform call for the whole session", 1, call.starts.size)
        assertEquals("the call must not end across a relink", 0, call.ends)
        assertFalse("the receiver must ride through the relink", playout.destroyed)
        assertFalse("the capture session must ride through the relink", handles.single().stopped)
        assertEquals("one engine for the whole session", 1, handles.size)
        assertTrue("the dead link must be closed", transports[0].closed)
        conn.disconnect()
    }

    /** A link that never synchronized is the connect failing, and a connect is not retried. */
    @Test fun theFirstLinkIsNotRetried() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { transports += it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        transportAt(transports, 0).listener!!.onClosed(IOException("reset"))

        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        delay(200)
        assertEquals("no replacement for a link that never came up", 1, transports.size)
    }

    /**
     * The one rejection a retry can fix is a ghost of ourselves still holding the name, which
     * Murmur reaps inside the deadline; every other rejection is final.
     */
    @Test fun usernameInUseRetriesAndOtherRejectsGiveUp() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        // The ladder's waits are not what this pins; skipped so the rung after the retry costs no
        // real second.
        val conn = MumbleConnection(InMemoryPinStore(), call = call, sleep = { }) {
            FakeControlTransport { _, _ -> }.also { transports += it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onClosed(IOException("reset"))

        startedTransportAt(transports, 1).listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.UsernameInUse))
        transportAt(transports, 2)   // retried
        assertTrue("still reconnecting after a UsernameInUse", conn.status.value is ConnectionStatus.Reconnecting)

        transports[2].listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.WrongServerPW))
        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.AUTH_REJECTED, err.kind)
        awaitTrue("giving up ends the call") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        delay(200)
        assertEquals("no attempt after a final rejection", 3, transports.size)
    }

    /**
     * The ladder and the deadline, with the clock driven by the waits themselves: each requested
     * sleep advances the test clock by exactly that much and returns, so the sequence of waits is
     * the whole story and the run takes no real time.
     */
    @Test fun replacementsThatKeepFailingGiveUpAtTheDeadline() = runBlocking {
        val clock = AtomicTimeSource()
        val waits = CopyOnWriteArrayList<Duration>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), call = call, udpClock = clock,
            sleep = { d -> waits += d; clock += d },
        ) {
            // The first transport connects; every replacement is refused.
            val first = transports.isEmpty()
            FakeControlTransport { _, _ -> if (!first) throw IOException("refused") }.also { transports += it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onClosed(IOException("reset"))

        val err = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } } as ConnectionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("reconnect gave up after 2 min", err.detail)
        // 0+1+2+4+8+16+30+30 = 91 s used; the next 30 s wait would end at 121 s, past the 120 s deadline.
        assertEquals(listOf(0, 1, 2, 4, 8, 16, 30, 30).map { it.seconds }, waits.toList())
        awaitTrue("giving up ends the call") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
    }

    /** A link that stayed synchronized 30 s was a working path; its loss is a new outage, not
     *  another failure of the one being retried, so the ladder starts over. */
    @Test fun losingAHealthyLinkStartsTheLadderOver() = runBlocking {
        val clock = AtomicTimeSource()
        val waits = CopyOnWriteArrayList<Duration>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(
            InMemoryPinStore(), udpClock = clock, sleep = { d -> waits += d; clock += d },
        ) { FakeControlTransport { _, _ -> }.also { transports += it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onClosed(IOException("reset"))          // unhealthy: opens the incident at rung 0
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected && it.sessionId == 2 } }
        transports[1].listener!!.onClosed(IOException("reset"))          // unhealthy again: rung 1
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected && it.sessionId == 3 } }

        clock += 31.seconds                                              // the third link becomes healthy
        transports[2].listener!!.onClosed(IOException("reset"))
        transportAt(transports, 3)

        assertEquals(listOf(0.seconds, 1.seconds, 0.seconds), waits.toList())
        conn.disconnect()
    }

    /** The log is the session's: what the old link received stays, by identity, and the new
     *  link's messages append to it. */
    @Test fun chatSurvivesARelink() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { transports += it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onFrame(textFrom(9, "before"))
        val before = withTimeout(5_000) { conn.messages.first { it.isNotEmpty() } }.single()

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected && it.sessionId == 2 } }
        transports[1].listener!!.onFrame(textFrom(9, "after"))

        val log = withTimeout(5_000) { conn.messages.first { it.size == 2 } }
        assertTrue("the carried message is the same instance", log[0] === before)
        assertEquals("after", (log[1] as ChatMessage.Remote).htmlBody)
        conn.disconnect()
    }

    /** The self_mute flag of every UserState this transport sent, in order. */
    private fun FakeControlTransport.selfMutes() = sent
        .filter { it.first == TcpMessageType.UserState }
        .map { (it.second as MumbleProtos.UserState).selfMute }

    /**
     * Mute is the session's and the gate stays shut across the swap, but the replacement's wire
     * state starts fresh: untold, the server and the row it echoes back would show the user
     * unmuted while nothing leaves the device.
     */
    @Test fun selfMuteSurvivesARelink() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = MumbleConnection(InMemoryPinStore()) { FakeControlTransport { _, _ -> }.also { transports += it } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }

        conn.setMuted(true)
        assertEquals("the first link carries the mute", listOf(true), transports[0].selfMutes())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected && it.sessionId == 2 } }

        awaitTrue("the replacement must be told the session is muted") {
            transports[1].selfMutes() == listOf(true)
        }
        conn.setMuted(false)
        awaitTrue("and the tap that lifts it must reach the same link") {
            transports[1].selfMutes() == listOf(true, false)
        }
        conn.disconnect()
    }

    /** Hanging up mid-relink is a hang-up: the replacement in flight goes with the session. */
    @Test fun disconnectWhileReconnectingClosesTheReplacement() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val release = CountDownLatch(1)
        val call = FakeVoiceCall()
        val conn = MumbleConnection(InMemoryPinStore(), call = call) {
            val first = transports.isEmpty()
            // The replacement blocks the thread inside its handshake, like the real one.
            FakeControlTransport { _, _ -> if (!first) release.await() }.also { transports += it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onClosed(IOException("reset"))
        transportAt(transports, 1)   // inside its handshake

        conn.disconnect()

        assertEquals(ConnectionStatus.Idle, conn.status.value)
        release.countDown()
        awaitTrue("both links must be closed") { transports.all { it.closed } }
        awaitTrue("the call ends once, as a hang-up") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.USER), call.endReasons)
    }

    /**
     * The server's certificate changed while we were rebuilding the link. The prompt is the one a
     * fresh connect gives: the session is retired behind it — no microphone opens against it, and
     * the call ends as a failure — but kept aside, so accepting the new pin reconnects.
     */
    @Test fun aTrustPromptOnARelinkRetiresTheSessionAndCanStillBeAccepted() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val pins = InMemoryPinStore()
        val endpoint = MumbleEndpoint.parse("localhost")
        pins.put(endpoint.address, "aa")
        val conn = MumbleConnection(
            pins,
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) {
            // Only the replacement is refused; the third transport is the one trustAndConnect builds.
            val replacement = transports.size == 1
            FakeControlTransport { _, _ ->
                if (replacement) throw PinMismatchException(stored = "aa", presented = "bb")
            }.also { transports += it }
        }
        conn.connect(endpoint, "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }

        transports[0].listener!!.onClosed(IOException("reset"))

        val prompt = withTimeout(5_000) { conn.status.first { it is ConnectionStatus.PinMismatch } }
        assertEquals(ConnectionStatus.PinMismatch("aa", "bb"), prompt)
        awaitTrue("the prompt must end the call") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        awaitTrue("the dead link and its refused replacement are both closed") {
            transports.size == 2 && transports.all { it.closed }
        }

        conn.requestCapture()
        delay(200)
        assertTrue("no microphone may open while a prompt is up", handles.isEmpty())

        conn.trustAndConnect()

        awaitTrue("accepting the new certificate reconnects") { transports.size == 3 }
        assertEquals("bb", pins.get(endpoint.address))
        conn.disconnect()
    }

    /**
     * The freeze: from its close, nothing the dead link still reduces may reach the UI, or the
     * ghost kick's UserRemove would read as "you left". The close is what the guard reads, and it
     * lands before any replacement exists.
     */
    @Test fun aFrameTheDeadLinkReducesAfterItsCloseNeverReachesTheTree() = runBlocking {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val release = CountDownLatch(1)
        val conn = MumbleConnection(InMemoryPinStore()) {
            val first = transports.isEmpty()
            FakeControlTransport { _, _ -> if (!first) release.await() }.also { transports += it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onFrame(TcpFrame(TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray()))
        withTimeout(5_000) { conn.channelTree.first { it.channels.containsKey(1) } }

        transports[0].listener!!.onClosed(IOException("reset"))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Reconnecting } }
        // The driver closes the dead link before it builds any replacement, and close() raises the
        // flag before it hands the socket to IO, so a closed transport proves the flag is up.
        awaitTrue("the dead link must be closed") { transports[0].closed }
        transports[0].listener!!.onFrame(TcpFrame(TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(2).setName("Late").build().toByteArray()))
        delay(200)

        assertFalse("a frozen link must not reach the tree", conn.channelTree.value.channels.containsKey(2))
        release.countDown()
        conn.disconnect()
    }

    // ---- the UDP voice socket ------------------------------------------------------------
    //
    // The fake control transport only names where the socket should aim, so a loopback peer
    // keyed as the server — our client_nonce is its decrypt seed, its server_nonce our decrypt
    // seed — stands in for Murmur's UDP side. Nothing below sends a UDPTunnel frame.

    private val cryptKey = ByteArray(16) { it.toByte() }
    private val clientNonce = ByteArray(16) { (0x40 + it).toByte() }
    private val serverNonce = ByteArray(16) { (0x80 + it).toByte() }

    private fun serverCrypt() = CryptState().apply { setKeys(cryptKey, serverNonce, clientNonce) }

    private fun keyExchange() = TcpFrame(
        TcpMessageType.CryptSetup.id,
        MumbleProtos.CryptSetup.newBuilder()
            .setKey(ByteString.copyFrom(cryptKey))
            .setClientNonce(ByteString.copyFrom(clientNonce))
            .setServerNonce(ByteString.copyFrom(serverNonce))
            .build().toByteArray(),
    )

    /** A loopback UDP peer: records who wrote to it and every plaintext it could open, and
     *  answers each datagram with whatever [reply] makes of that plaintext (null for none). */
    private class UdpPeer(private val crypt: CryptState, private val reply: (ByteArray?) -> ByteArray? = { null }) {
        val channel: DatagramChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val address get() = channel.localAddress as InetSocketAddress
        val from = LinkedBlockingQueue<SocketAddress>()
        val opened = LinkedBlockingQueue<ByteArray>()
        init {
            thread(isDaemon = true) {
                val wire = ByteBuffer.allocate(2048)
                val plain = ByteArray(2048)
                try {
                    while (true) {
                        wire.clear()
                        val addr = channel.receive(wire) ?: break
                        from.add(addr)
                        val len = crypt.decrypt(wire.array(), wire.position(), plain)
                        val packet = if (len >= 0) plain.copyOf(len).also { opened.add(it) } else null
                        reply(packet)?.let { channel.send(ByteBuffer.wrap(it), addr) }
                    }
                } catch (_: Exception) {
                }
            }
        }
        fun sendTo(addr: SocketAddress, plaintext: ByteArray) {
            val out = ByteArray(plaintext.size + CryptState.HEADER_LEN)
            val n = crypt.encrypt(plaintext, plaintext.size, out)
            channel.send(ByteBuffer.wrap(out, 0, n), addr)
        }
        fun close() = channel.close()
    }

    /** The readers alive right now, by identity: a test judges only the ones it started, so a
     *  reader an earlier test left dying, or leaked, cannot skew it. */
    private fun readers(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.name == "dumble-udp-recv" }.toSet()
    private fun readersSince(before: Set<Thread>) = (readers() - before).size

    private fun audioPacket(session: Int) = byteArrayOf(0) + MumbleUdpProtos.Audio.newBuilder()
        .setSenderSession(session).setOpusData(ByteString.copyFrom(byteArrayOf(1))).build().toByteArray()

    private suspend fun connectToHandshaking(conn: MumbleConnection) {
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
    }

    private fun serverSync() = TcpFrame(TcpMessageType.ServerSync.id,
        MumbleProtos.ServerSync.newBuilder().setSession(1).build().toByteArray())

    private fun fakeAimedAt(peer: UdpPeer, into: (FakeControlTransport) -> Unit) = { _: String? ->
        FakeControlTransport { _, _ -> }.apply { remote = peer.address }.also(into)
    }

    @Test fun keyingPingsTheServerOverUdp() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) { fake = it })
        connectToHandshaking(conn)

        fake.listener!!.onFrame(keyExchange())

        val plain = peer.opened.poll(5, TimeUnit.SECONDS)
        assertNotNull("keying must send a ping the server can open", plain)
        assertEquals("a ping, not audio", 1.toByte(), plain!![0])
        conn.disconnect()
        peer.close()
    }

    // The reason the socket lands with receive wired: a listener who has never transmitted
    // gets their downlink over UDP from the first ping on, and would otherwise hear nothing.
    @Test fun inboundUdpAudioReachesThePlayoutEngine() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val playout = FakePlayoutEngine()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), newPlayout = { playout }, newTransport = fakeAimedAt(peer) { fake = it })
        connectToHandshaking(conn)
        fake.listener!!.onFrame(keyExchange())
        val us = peer.from.poll(5, TimeUnit.SECONDS)
        assertNotNull("the ping registers our address", us)
        awaitEngineBuilt(playout)

        peer.sendTo(us!!, audioPacket(session = 9))

        awaitTrue("UDP audio must reach the engine") { playout.offered.isNotEmpty() }
        assertEquals(9, playout.offered.first().session)
        conn.disconnect()
        peer.close()
    }

    @Test fun aStalledDecryptAsksTheServerForItsCounter() = runBlocking {
        val peer = UdpPeer(serverCrypt()) { ByteArray(24) { (it * 7).toByte() } }   // answers with junk
        val clock = AtomicTimeSource()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(
            InMemoryPinStore(), udpClock = clock, pingIntervalMs = 200,
            newTransport = fakeAimedAt(peer) { fake = it },
        )
        connectToHandshaking(conn)
        fake.listener!!.onFrame(keyExchange())
        assertNotNull(peer.opened.poll(5, TimeUnit.SECONDS))   // its junk answer lands inside the grace
        delay(100)
        assertEquals(0, fake.sent.count { it.first == TcpMessageType.CryptSetup })
        clock += 6.seconds   // the quiet period passes

        fake.listener!!.onFrame(serverSync())   // starts the ticker; its next ping draws junk past the grace

        awaitTrue("a failed decrypt past the quiet period must ask for a resync") {
            fake.sent.any { it.first == TcpMessageType.CryptSetup }
        }
        val request = fake.sent.last { it.first == TcpMessageType.CryptSetup }.second
        assertEquals(MumbleProtos.CryptSetup.getDefaultInstance(), request)
        conn.disconnect()
        peer.close()
    }

    /**
     * The wiring of the transport's unanswered-ping report: a peer that opens our pings but
     * never answers them. Keying sends the first; the ticker's first tick judges it and sends
     * the second, and its second tick judges that and reports. On a shortened interval, so the
     * two ticks pass in well under a second.
     */
    @Test fun twoUnansweredPingsSendATunneledPingToPullTheDownlinkBack() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), pingIntervalMs = 200, newTransport = fakeAimedAt(peer) { fake = it })
        connectToHandshaking(conn)
        fake.listener!!.onFrame(keyExchange())
        assertNotNull(peer.opened.poll(5, TimeUnit.SECONDS))
        fake.listener!!.onFrame(serverSync())
        assertTrue("nothing tunneled yet", fake.sentRaw.none { it.first == TcpMessageType.UDPTunnel })

        awaitTrue("the tick after the second unanswered ping must tunnel a ping") {
            fake.sentRaw.any { it.first == TcpMessageType.UDPTunnel }
        }

        val frame = fake.sentRaw.first { it.first == TcpMessageType.UDPTunnel }.second
        assertEquals("a ping, so no peer hears a blip", 1.toByte(), frame[0])
        conn.disconnect()
        peer.close()
    }

    /** The other half: a peer that answers keeps the report armed and nothing is ever tunneled. */
    @Test fun answeredPingsNeverTunnelAnything() = runBlocking {
        val peer = answeringPeer()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), pingIntervalMs = 100, newTransport = fakeAimedAt(peer) { fake = it })
        connectToHandshaking(conn)
        fake.listener!!.onFrame(keyExchange())
        fake.listener!!.onFrame(serverSync())

        awaitTrue("several pings answered") { peer.opened.size >= 5 }

        assertTrue(fake.sentRaw.none { it.first == TcpMessageType.UDPTunnel })
        conn.disconnect()
        peer.close()
    }

    @Test fun disconnectClosesTheUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) {})
        connectToHandshaking(conn)
        awaitTrue("the socket opens with the connection") { readersSince(before) == 1 }

        conn.disconnect()

        awaitTrue("disconnect must close the socket and end its reader") { readersSince(before) == 0 }
        peer.close()
    }

    @Test fun aSessionThatFailsOnItsOwnClosesTheUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) { fake = it })
        connectToHandshaking(conn)
        awaitTrue("the socket opens with the connection") { readersSince(before) == 1 }

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))

        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } }
        awaitTrue("a retired session must not leak its socket") { readersSince(before) == 0 }
        peer.close()
    }

    @Test fun aSupersededSessionClosesItsUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) {})
        connectToHandshaking(conn)
        awaitTrue("the socket opens with the connection") { readersSince(before) == 1 }
        val first = (readers() - before).single()

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)

        awaitTrue("the superseded session's reader exits") { !first.isAlive }
        awaitTrue("one live session, one socket") { readersSince(before) == 1 }
        conn.disconnect()
        awaitTrue("and none after disconnect") { readersSince(before) == 0 }
        peer.close()
    }

    // Voice is additive: a socket that cannot be opened must cost the session nothing but UDP.
    @Test fun aSocketThatCannotOpenLeavesTheSessionHealthyAndTunneled() = runBlocking {
        val playout = FakePlayoutEngine()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), newPlayout = { playout }) {
            // Unresolved, so DatagramChannel.connect refuses it locally: no DNS, no network.
            FakeControlTransport { _, _ -> }.apply { remote = InetSocketAddress.createUnresolved("nowhere.invalid", 1) }.also { fake = it }
        }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        fake.listener!!.onFrame(keyExchange())
        fake.listener!!.onFrame(serverSync())
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(1).setChannelId(0).build().toByteArray()))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        awaitEngineBuilt(playout)

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, audioPacket(session = 9)))

        awaitTrue("tunneled audio still reaches the engine") { playout.offered.isNotEmpty() }
        assertEquals(9, playout.offered.first().session)
        conn.disconnect()
    }

    // ---- which transport carries our voice ------------------------------------------------

    /** A peer that answers every ping it can open while [answer] is set, and records everything. */
    private fun answeringPeer(answer: AtomicBoolean = AtomicBoolean(true)): UdpPeer {
        val server = serverCrypt()
        return UdpPeer(server) { plain ->
            if (plain == null || plain[0] != 1.toByte() || !answer.get()) null
            else ByteArray(plain.size + CryptState.HEADER_LEN).also { server.encrypt(plain, plain.size, it) }
        }
    }

    // The boot clock reads a constant on the JVM, which floors every stamp to 1 ns and dates every
    // reply a nanosecond before its ping; a clock the test moves off the origin gives real ones.
    private val udpClock = AtomicTimeSource()

    private fun opusOf(packet: ByteArray) =
        MumbleUdpProtos.Audio.parser().parseFrom(packet, 1, packet.size - 1).opusData.toByteArray()

    /** The first audio packet the peer opens; its pings land on the same queue. */
    private fun awaitAudio(peer: UdpPeer): ByteArray? {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val packet = peer.opened.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return null
            if (packet[0] == 0.toByte()) return packet
        }
    }

    /** The path end to end: the keying ping's reply promotes, and the pump's next frame leaves on
     *  the datagram socket, never as a tunnel frame. */
    @Test fun anAnsweredPingPutsOurVoiceOnUdp() = runBlocking {
        val peer = answeringPeer()
        val handle = FakeCaptureHandle()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(
            InMemoryPinStore(), newCapture = { handle }, udpClock = udpClock,
            newTransport = fakeAimedAt(peer) { fake = it },
        )
        connectToHandshaking(conn)
        udpClock += 1.seconds
        fake.listener!!.onFrame(keyExchange())
        awaitTrue("the answered ping promotes") { conn.voicePath.value.onUdp }
        assertNotNull("with its round trip", conn.voicePath.value.roundTrip)

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), frameNumber = 7, terminator = false))
        conn.requestCapture()

        val packet = awaitAudio(peer)
        assertNotNull("the frame must reach the peer over UDP", packet)
        assertArrayEquals(byteArrayOf(1, 2, 3), opusOf(packet!!))
        assertTrue("and never the tunnel", fake.sentRaw.none { it.first == TcpMessageType.UDPTunnel })
        conn.disconnect()
        assertEquals("cleared with every other flow", VoicePath.State(), conn.voicePath.value)
        peer.close()
    }

    /** What other clients read about our path: once a reply is accepted, the next TCP ping
     *  reports the UDP leg. The fake clock stands still, so the round trip reads 0 and only the
     *  count says the leg was fed. */
    @Test fun theTcpPingReportsTheUdpLegOnceAReplyIsAccepted() = runBlocking {
        val peer = answeringPeer()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(
            InMemoryPinStore(), newCapture = { null }, udpClock = udpClock, pingIntervalMs = 100,
            newTransport = fakeAimedAt(peer) { fake = it },
        )
        connectToHandshaking(conn)
        udpClock += 1.seconds
        fake.listener!!.onFrame(keyExchange())
        awaitTrue("the answered ping promotes") { conn.voicePath.value.onUdp }
        fake.listener!!.onFrame(serverSync())   // the ping loop starts with the session
        awaitTrue("the next ping must report the UDP leg") {
            fake.sent.any { (type, m) -> type == TcpMessageType.Ping && (m as MumbleProtos.Ping).udpPackets >= 1 }
        }
        conn.disconnect()
        peer.close()
    }

    /**
     * Demotion and recovery through the real ticker on a short interval: silence demotes on the
     * transport's report, the next frame goes through the tunnel with the label and its number
     * cleared together, and two replies bring voice back.
     */
    @Test fun silenceMovesVoiceBackToTheTunnelAndRepliesBringItBack() = runBlocking {
        val answer = AtomicBoolean(true)
        val peer = answeringPeer(answer)
        val handle = FakeCaptureHandle()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(
            InMemoryPinStore(), newCapture = { handle }, udpClock = udpClock, pingIntervalMs = 100,
            newTransport = fakeAimedAt(peer) { fake = it },
        )
        connectToHandshaking(conn)
        udpClock += 1.seconds
        fake.listener!!.onFrame(keyExchange())
        awaitTrue("promoted") { conn.voicePath.value.onUdp }
        conn.requestCapture()
        fake.listener!!.onFrame(serverSync())
        answer.set(false)

        awaitTrue("the report of two unanswered pings demotes") { !conn.voicePath.value.onUdp }
        assertEquals(VoicePath.State(), conn.voicePath.value)
        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(9), frameNumber = 1, terminator = false))
        awaitTrue("the next frame goes through the tunnel") {
            fake.sentRaw.any { it.first == TcpMessageType.UDPTunnel && it.second[0] == 0.toByte() }
        }

        answer.set(true)
        awaitTrue("two replies re-promote") { conn.voicePath.value.onUdp }
        conn.disconnect()
        peer.close()
    }
}
