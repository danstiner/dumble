package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.User
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MumbleConnectionTest {

    @Test fun connectTimeoutMapsToTimeoutError() = deterministic {
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> throw SocketTimeoutException("connect timed out") } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("error") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.TIMEOUT, err.kind)
    }

    @Test fun channelTreeSurfacesReducedFrames() = deterministic {
        lateinit var fake: FakeControlTransport
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })   // connects, stays open
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        fake.listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))

        assertSettled("channel tree") { conn.channelTree.value.channels.containsKey(1) }
        assertEquals("Root", conn.channelTree.value.channels[1]!!.name)
    }

    @Test fun disconnectResetsChannelTree() = deterministic {
        lateinit var fake: FakeControlTransport
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        fake.listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))
        assertSettled("channel tree") { conn.channelTree.value.channels.containsKey(1) }

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
    @Test fun connectingOverALiveConnectionClearsThePriorSessionsState() = deterministic {
        val fakes = mutableListOf<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fakes += it } })
        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        handshaking(conn)
        fakes[0].listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))
        fakes[0].listener!!.onFrame(TcpFrame(
            TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray(),
        ))
        assertSettled("channel tree") { conn.channelTree.value.channels.containsKey(1) }
        assertSettled("messages") { conn.messages.value.isNotEmpty() }

        conn.connect(MumbleEndpoint.parse("second"), "user", null)

        // Sampled, not awaited: connect() clears under the same lock that bumps the generation, so
        // it has already happened when the call returns, and every publish helper is gen-checked so
        // the retired session cannot write these again.
        assertEquals(ChannelTree(), conn.channelTree.value)
        assertEquals(emptyList<ChatMessage>(), conn.messages.value)
    }

    @Test fun messagesSurfaceFromTheSession() = deterministic {
        lateinit var fake: FakeControlTransport
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray()))

        assertSettled("messages") { conn.messages.value.isNotEmpty() }
        assertEquals("yo", (conn.messages.value.single() as ChatMessage.Remote).htmlBody)
    }

    @Test fun disconnectClearsMessages() = deterministic {
        lateinit var fake: FakeControlTransport
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray()))
        assertSettled("messages") { conn.messages.value.isNotEmpty() }

        conn.disconnect()

        assertEquals(emptyList<ChatMessage>(), conn.messages.value)
    }

    @Test fun sendTextRoutesToTheLiveSessionAndEchoes() = deterministic {
        lateinit var fake: FakeControlTransport
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.ServerSync.id,
            MumbleProtos.ServerSync.newBuilder().setSession(1).build().toByteArray()))
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(1).setChannelId(0).build().toByteArray()))
        connected(conn)

        val ok = conn.sendText("hello")

        assertTrue(ok)
        assertTrue(fake.sent.any { it.first == TcpMessageType.TextMessage })
        assertSettled("messages") { conn.messages.value.isNotEmpty() }
        assertEquals("hello", (conn.messages.value.single() as ChatMessage.Remote).htmlBody)
    }

    @Test fun sendTextWithNoConnectionReturnsFalse() = deterministic {
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        assertFalse(conn.sendText("hi"))
    }

    /**
     * Cancelled inside the handshake, the driver never reaches its own live check: the link it
     * published under the lock before connecting is teardown's to close.
     */
    @Test fun aDisconnectDuringTheHandshakeClosesTheLink() = deterministic {
        val gate = CompletableDeferred<Unit>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> gate.await() }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the driver must reach the handshake") { transports.firstOrNull()?.listener != null }

        conn.disconnect()

        assertSettled("teardown must close the link the driver published") { transports.single().closed }
        gate.complete(Unit)   // inert: the cancelled wait ignores it
    }

    /**
     * The driver lives on the session's scope, so a disconnect() cancels it wherever it waits.
     * Held inside a cancellable pin lookup it dies there and never builds a transport.
     */
    @Test fun aDisconnectDuringThePinLookupCancelsTheDriver() = deterministic {
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
        val conn = own(MumbleConnection(pins, udpClock = clock, context = dispatcher, blocking = dispatcher) {
            FakeControlTransport { _, _ -> }.also { transports += it }
        })

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the driver is parked in the pin lookup") { parked.isCompleted }
        val wait = parked.getCompleted()
        conn.disconnect()
        wait.resume(Unit)   // ignored once cancelled; a driver that survived would run on and build
        assertSettled("the cancelled driver must build nothing") { transports.isEmpty() }
    }

    @Test fun callStartsWithTheConnectionNotTheMicrophone() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)

        // No requestCapture() anywhere in this test: connecting alone must register the call.
        assertSettled("connecting must start the call with the server host") { call.starts == listOf("localhost") }
        conn.disconnect()
        assertSettled("disconnecting must end the call") { call.ends == 1 }
        assertEquals("a hang-up is the user's doing", listOf(VoiceCall.Reason.USER), call.endReasons)
    }

    /**
     * A session that dies on us is not a hang-up. The platform records a different disconnect cause
     * for each, and reporting a server failure as LOCAL would claim the user ended a call they did
     * not.
     */
    @Test fun aFailedConnectEndsTheCallAsFailureNotHangUp() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> throw SocketTimeoutException("connect timed out") } })

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)

        assertSettled("error") { conn.status.value is ConnectionStatus.Error }
        assertSettled("a failed handshake must end the call") { call.ends == 1 }
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
    @Test fun aHardConnectFailureRetiresTheSession() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> throw IOException("connection refused") }.also { transports += it } })

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("error") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.CONNECT_FAILED, err.kind)
        assertSettled("the failure must end the call") { call.ends == 1 }

        conn.requestCapture()

        assertSettled("no microphone may open for a retired session") { handles.isEmpty() }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        assertTrue("retire() must not reset the terminal status", conn.status.value is ConnectionStatus.Error)
        assertSettled("retire must close the link") { transports.single().closed }
    }

    /** A trust prompt retires its session the same way; the one difference is that the prompt
     *  keeps the session aside for trustAndConnect() to reconnect from. */
    @Test fun aTrustPromptRetiresTheSessionAndCanStillBeAccepted() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val pins = InMemoryPinStore()
        val conn = own(MumbleConnection(
            pins,
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { pin ->
            FakeControlTransport { _, _ -> if (pin == null) throw UntrustedCertificateException("ab12") }
                .also { transports += it }
        })
        val endpoint = MumbleEndpoint.parse("localhost")

        conn.connect(endpoint, "user", null)
        assertSettled("awaiting trust") { conn.status.value is ConnectionStatus.AwaitingTrust }
        assertSettled("the prompt must end the call") { call.ends == 1 }

        conn.requestCapture()

        assertSettled("no microphone may open while a prompt is up") { handles.isEmpty() }
        assertSettled("the stopped handshake's transport is closed") { transports.single().closed }
        assertTrue(conn.status.value is ConnectionStatus.AwaitingTrust)

        conn.trustAndConnect()
        handshaking(conn)
        assertEquals("ab12", pins.get(endpoint.address))
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
    }

    /** The generation is the UI's handle on "which call": one per connect(), whatever session id
     *  the server hands out. */
    @Test fun connectedCarriesTheSessionsGeneration() = deterministic {
        val fakes = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fakes += it } })
        val sync = TcpFrame(TcpMessageType.ServerSync.id,
            MumbleProtos.ServerSync.newBuilder().setSession(5).build().toByteArray())

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        fakes[0].listener!!.onFrame(sync)
        val first = connected(conn)

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        fakes[1].listener!!.onFrame(sync)
        val second = connected(conn)

        assertEquals(5, first.sessionId)
        assertEquals(5, second.sessionId)
        assertNotEquals(first.gen, second.gen)
    }

    /** The system ending the call (a cellular call taking over) must take the session down with it. */
    @Test fun aSystemEndedCallDisconnectsTheSession() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        call.endedBySystem()

        assertSettled("idle") { conn.status.value == ConnectionStatus.Idle }
    }

    /** A late callback from a call we already ended must not resurrect a dead session. */
    @Test fun holdDeliveredAfterDisconnectRebuildsNothing() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        conn.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }

        conn.disconnect()
        assertSettled("disconnect must end the call") { call.ends == 1 }
        call.resume()

        assertSettled("no engine may be built for a dead session") { handles.size == 1 }
    }

    /**
     * Connecting over a live connection must release the platform call it replaces. start() used to
     * overwrite liveGen without ending anything, after which end(priorGen) was a no-op forever — the
     * OS kept a call whose onDisconnect is wired to disconnect(), so hanging up the ghost from
     * system UI tore down the session that replaced it.
     */
    @Test fun connectingOverALiveConnectionEndsThePriorCall() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        handshaking(conn)

        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        handshaking(conn)

        assertEquals(listOf("first", "second"), call.starts)
        assertSettled("the superseded call must end") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.USER), call.endReasons)

        conn.disconnect()
        assertSettled("disconnecting must end the second call") { call.ends == 2 }
    }

    /** Denied microphone, or an engine that would not open: receive still needs the call's service. */
    @Test fun captureThatCannotOpenLeavesTheCallRunning() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        conn.requestCapture()
        // No engine, so push-to-talk is inert rather than a crash.
        conn.setTransmitting(true)
        assertSettled("the call must survive an engine that would not open") { call.ends == 0 }
    }

    private fun routes(vararg types: AudioRoute.Type, current: AudioRoute.Type? = null): AudioRoutes {
        val available = types.map { AudioRoute("id-$it", it) }
        return AudioRoutes(available, current?.let { c -> available.first { it.type == c } })
    }

    /** The platform's answer is the only source; nothing local invents a route. */
    @Test fun routesFromTheLiveCallReachTheUi() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        call.emitRoutes(routes(AudioRoute.Type.EARPIECE, AudioRoute.Type.SPEAKER, current = AudioRoute.Type.EARPIECE))

        assertSettled("routes must reach the flow") { conn.audioRoutes.value.available.size == 2 }
        assertEquals(AudioRoute.Type.EARPIECE, conn.audioRoutes.value.current?.type)
    }

    /**
     * A collector inside a cancelled addCall block can still emit — cancellation is asynchronous —
     * so the generation guard is what stops a dead call repainting the live one's control.
     */
    @Test fun routesFromASupersededCallAreDropped() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        val staleGen = call.startedGens.first()

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        call.emitRoutesFor(staleGen, routes(AudioRoute.Type.BLUETOOTH, current = AudioRoute.Type.BLUETOOTH))

        assertEquals(AudioRoutes(), conn.audioRoutes.value)
    }

    /** Every other published flow is cleared on retire; a survivor here would paint the next
     *  session's control with the last one's headset. */
    @Test fun disconnectClearsTheRoutes() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)
        call.emitRoutes(routes(AudioRoute.Type.SPEAKER, current = AudioRoute.Type.SPEAKER))
        assertSettled("routes must reach the flow first") { conn.audioRoutes.value.current != null }

        conn.disconnect()

        assertSettled("teardown must clear the routes") { conn.audioRoutes.value == AudioRoutes() }
    }

    /** The UI carries no generation, so the connection has to supply the live one. */
    @Test fun selectingARouteAddressesTheLiveCall() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(conn)

        conn.requestAudioRoute("id-SPEAKER")

        assertSettled("the pick must reach the call") { call.routeRequests == listOf("id-SPEAKER") }
    }

    @Test fun selectingARouteWithNothingConnectedIsANoOp() = deterministic {
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })

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
        awaitOnRealThreads("the receiver's poll never started") { playout.startAttempts.get() > 0 }

    /** Polls under a wall-clock bound. Only for the tests that must run real threads — the
     *  driver-parking, TLS and loopback-UDP tests — and never inside [deterministic]: a
     *  converted test drives the scheduler and asserts (`assertSettled`). */
    private suspend fun awaitOnRealThreads(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(message, cond())
    }

    private fun serverSync(session: Int) = TcpFrame(
        TcpMessageType.ServerSync.id,
        MumbleProtos.ServerSync.newBuilder().setSession(session).build().toByteArray(),
    )

    private fun userRemove(session: Int, reason: String) = TcpFrame(
        TcpMessageType.UserRemove.id,
        MumbleProtos.UserRemove.newBuilder().setSession(session).setReason(reason).build().toByteArray(),
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
    private suspend fun transportAtOnRealThreads(transports: List<FakeControlTransport>, index: Int): FakeControlTransport {
        awaitOnRealThreads("transport $index must be built and connected") {
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
    private suspend fun startedTransportAtOnRealThreads(transports: List<FakeControlTransport>, index: Int): FakeControlTransport {
        val transport = transportAtOnRealThreads(transports, index)
        awaitOnRealThreads("transport $index must have started its handshake") {
            transport.sent.any { it.first == TcpMessageType.Version }
        }
        return transport
    }

    /**
     * A converted test: everything the connection runs is on one StandardTestDispatcher, so
     * nothing moves between two statements unless the test drives the scheduler, and `delay`
     * is virtual. The rule is drive, then assert — never await. runTest's own final drain
     * (`advanceUntilIdleOr`) would otherwise run the handshake deadline, the ping death and the
     * whole reconnect ladder on any connection left up, so every connection is built through
     * [Rig.own] and torn down here.
     */
    private fun deterministic(body: suspend Rig.() -> Unit) = runTest {
        val rig = Rig(this)
        try {
            rig.body()
        } finally {
            rig.owned.forEach { it.disconnect() }
            runCurrent()
        }
    }

    private class Rig(val scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)

        /** One clock for `delay` and `markNow`: the scheduler's. */
        val clock: TimeSource.WithComparableMarks = scope.testScheduler.timeSource
        val owned = mutableListOf<MumbleConnection>()

        fun own(conn: MumbleConnection): MumbleConnection = conn.also { owned += it }

        /** Runs everything queued at this instant, including what that work queues, then asserts. */
        fun assertSettled(what: String, cond: () -> Boolean) {
            scope.runCurrent()
            assertTrue(what, cond())
        }

        /** Virtual time passes; every timer due in it fires, in order. */
        fun elapse(d: Duration) {
            scope.advanceTimeBy(d)
            scope.runCurrent()
        }

        fun connected(conn: MumbleConnection): ConnectionStatus.Connected {
            assertSettled("connected") { conn.status.value is ConnectionStatus.Connected }
            return conn.status.value as ConnectionStatus.Connected
        }

        fun handshaking(conn: MumbleConnection) {
            assertSettled("handshaking") { conn.status.value is ConnectionStatus.Handshaking }
        }

        /** The nth transport the connection built, once it exists and has connected. */
        fun transportAt(transports: List<FakeControlTransport>, index: Int): FakeControlTransport {
            assertSettled("transport $index must be built and connected") {
                transports.size > index && transports[index].listener != null
            }
            return transports[index]
        }

        /** The nth transport once its state machine has started; the handshake's Version on the
         *  wire is the proof. A ServerSync fed before that is dropped by the compare-and-set on
         *  Handshaking. */
        fun startedTransportAt(transports: List<FakeControlTransport>, index: Int): FakeControlTransport {
            val transport = transportAt(transports, index)
            assertSettled("transport $index must have started its handshake") {
                transport.sent.any { it.first == TcpMessageType.Version }
            }
            return transport
        }
    }

    /**
     * The point of the split: the platform call, the receiver and the capture session all belong
     * to the session and ride through a reconnect; only the link is rebuilt.
     */
    @Test fun aDeadLinkIsReplacedUnderTheSameSession() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val playout = FakePlayoutEngine()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            newPlayout = { playout }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(conn)
        assertEquals(1, first.sessionId)
        conn.requestCapture()
        assertSettled("capture must open on the first link") { handles.size == 1 }

        transports[0].listener!!.onClosed(IOException("reset"))

        assertSettled("reconnecting") { conn.status.value == ConnectionStatus.Reconnecting(first.gen, 1) }
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(ConnectionStatus.Connected(first.gen, 2), connected(conn))

        assertEquals("one platform call for the whole session", 1, call.starts.size)
        assertEquals("the call must not end across a reconnect", 0, call.ends)
        assertFalse("the receiver must ride through the reconnect", playout.destroyed)
        assertFalse("the capture session must ride through the reconnect", handles.single().stopped)
        assertEquals("one engine for the whole session", 1, handles.size)
        assertTrue("the dead link must be closed", transports[0].closed)
    }

    /** A link that never synchronized is the connect failing, and a connect is not retried. */
    @Test fun theFirstLinkIsNotRetried() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        transportAt(transports, 0).listener!!.onClosed(IOException("reset"))

        assertSettled("error") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("no replacement for a link that never came up", 1, transports.size)
    }

    /** A kick is the server's decision: the session ends with the reason, and no replacement is
     *  opened, or the kicked user would be back inside a second. */
    @Test fun aKickEndsTheSessionInsteadOfRejoining() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val link = startedTransportAt(transports, 0)
        link.listener!!.onFrame(serverSync(1))
        connected(conn)

        link.listener!!.onFrame(userRemove(session = 1, reason = "spam"))
        link.listener!!.onClosed(IOException("reset"))

        assertSettled("kicked") { conn.status.value == ConnectionStatus.Error(ErrorKind.KICKED, "spam") }
        assertSettled("the call ends as a failure") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        assertEquals("no replacement after a kick", 1, transports.size)
    }

    /**
     * Losing the network the link is on closes it instead of waiting for the socket to notice,
     * and the call rides through.
     */
    @Test fun losingTheLinksNetworkReplacesItAtOnce() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(conn)

        network.lose("wifi")

        assertSettled("the live link is closed on the loss") { transports[0].closed }
        // Our close unblocks the reader, whose exit is what reports the death.
        transports[0].listener!!.onClosed(IOException("closed"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(ConnectionStatus.Connected(first.gen, 2), connected(conn))
        assertEquals("the call must not end across a loss", 0, call.ends)
    }

    /** A default that merely moves, the old network still up, is not a death: with a LAN server
     *  on a WiFi that lost its uplink, that link is the only one that reaches the server. */
    @Test fun aNewDefaultLeavesAWorkingLinkAlone() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(conn)

        network.switchTo("cell")

        assertSettled("a link on a network still up stays") { !transports[0].closed }
        assertEquals(first, conn.status.value)
        assertEquals(1, transports.size)
    }

    /** Changes a session never saw are not deaths: the first link is stamped at its dial. */
    @Test fun changesBeforeConnectingAreNotADeath() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        network.lose("wifi")
        network.switchTo("cell")
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        assertSettled("changes before the dial are not a death") { !transports[0].closed }
        assertEquals(1, transports.size)
    }

    /** A switch while the first link is still handshaking, its network staying up, is nothing. */
    @Test fun aSwitchDuringTheFirstHandshakeLeavesTheLinkAlone() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val release = CompletableDeferred<Unit>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> release.await() }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the handshake is in flight") { transports.size == 1 && transports[0].listener != null }

        network.switchTo("cell")
        release.complete(Unit)

        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        assertSettled("the change during the handshake was not a loss") { !transports[0].closed }
    }

    /**
     * A loss while the first link is still handshaking is caught once it is wired, the collector
     * filtering on the link's own stamp, and ends as the connect failing: the first link is
     * never retried, and a network that is gone is a failed connect.
     */
    @Test fun aLossDuringTheFirstHandshakeIsTheConnectFailing() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val release = CompletableDeferred<Unit>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> release.await() }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the handshake is in flight") { transports.size == 1 && transports[0].listener != null }

        network.lose("wifi")
        release.complete(Unit)

        assertSettled("the link is closed as soon as it is wired") { transports[0].closed }
        transports[0].listener!!.onClosed(IOException("closed"))
        assertSettled("error") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("never retried", 1, transports.size)
    }

    /** The replacement is stamped with its own network, and closed when that one goes. */
    @Test fun losingTheReplacementsNetworkClosesItToo() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))   // an ordinary death
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)

        network.lose("wifi")

        assertSettled("the replacement is closed on the loss") { transports[1].closed }
        transports[1].listener!!.onClosed(IOException("closed"))
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        assertEquals(3, connected(conn).sessionId)
    }

    /** No default to stamp a link with means any change is taken as its loss. */
    @Test fun aLinkDialedWithNoDefaultIsClosedOnAnyChange() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch(default = null)
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        network.switchTo("cell")

        assertSettled("closed on the first change") { transports[0].closed }
    }

    /**
     * A replacement that dies to a loss inside HEALTHY_AFTER continues the outage but starts at
     * attempt 0, the network being new information; a death on the same network climbs to 1 s.
     */
    @Test fun aLossInsideHealthyAfterStartsAtZero() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val waits = CopyOnWriteArrayList<Duration>()
        val network = FakeNetworkWatch()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = { waits += it }, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)

        network.lose("wifi")

        assertSettled("the replacement is closed on the loss") { transports[1].closed }
        transports[1].listener!!.onClosed(IOException("closed"))
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        assertEquals(3, connected(conn).sessionId)
        assertEquals(listOf(Duration.ZERO, Duration.ZERO), waits.toList())
    }

    /** A change during a backoff wait ends the wait, and the attempt after it is 0. */
    @Test fun aNetworkChangeCutsTheBackoffShort() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val waits = CopyOnWriteArrayList<Duration>()
        val network = FakeNetworkWatch()
        var refuse = false
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, networkWatch = network,
            context = dispatcher, blocking = dispatcher,
            // Real waits never return here: only the change can end one.
            sleep = { d -> waits += d; if (d > Duration.ZERO) awaitCancellation() },
        ) { FakeControlTransport { _, _ -> if (refuse) throw IOException("refused") }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(conn)
        refuse = true
        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("attempt 0 refused, attempt 1 waiting") { waits.toList() == listOf(0.seconds, 1.seconds) }
        assertEquals("nothing moves while the wait holds", 2, transports.size)

        refuse = false
        elapse(2.seconds)   // past the floor: a change inside a second of the dial keeps the rung
        network.switchTo("cell")

        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(2))
        assertEquals(ConnectionStatus.Connected(first.gen, 2), connected(conn))
        assertEquals("the wait was cut, not waited out", listOf(0.seconds, 1.seconds, 0.seconds), waits.toList())
    }

    /**
     * A change during an attempt abandons it: the attempt is bound to the network that just went,
     * and would otherwise run out its whole timeout. The next attempt is immediate, not one up.
     */
    @Test fun aNetworkChangeAbandonsTheAttemptInFlight() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val waits = CopyOnWriteArrayList<Duration>()
        val network = FakeNetworkWatch()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, sleep = { waits += it }, networkWatch = network,
            context = dispatcher, blocking = dispatcher,
        ) {
            val hangs = transports.size == 1   // the first replacement blocks until closed, like a dead interface
            val holder = arrayOfNulls<FakeControlTransport>(1)
            FakeControlTransport { _, _ ->
                if (hangs) { while (!holder[0]!!.closed) delay(10); throw IOException("aborted") }
            }.also { holder[0] = it; transports += it }
        })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("the attempt is in flight") { transports.size == 2 && transports[1].listener != null }
        elapse(2.seconds)

        network.switchTo("cell")
        elapse(10.milliseconds)   // the fake transport's own poll loop notices the close and aborts

        assertSettled("the attempt in flight is abandoned") { transports[1].closed }
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(2))
        assertEquals(ConnectionStatus.Connected(first.gen, 2), connected(conn))
        assertEquals("attempt 0 again on the new network", listOf(Duration.ZERO, Duration.ZERO), waits.toList())
    }

    /** The floor: a change inside a second of the last dial keeps the rung, so a flapping
     *  default cannot turn the backoff into a connect storm. */
    @Test fun aChangeRightAfterADialKeepsTheRung() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val waits = CopyOnWriteArrayList<Duration>()
        val network = FakeNetworkWatch()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), sleep = { waits += it }, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            val hangs = transports.size == 1
            val holder = arrayOfNulls<FakeControlTransport>(1)
            FakeControlTransport { _, _ ->
                if (hangs) { while (!holder[0]!!.closed) delay(10); throw IOException("aborted") }
            }.also { holder[0] = it; transports += it }
        })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("the attempt is in flight") { transports.size == 2 && transports[1].listener != null }

        network.switchTo("cell")   // the clock has not moved since the dial
        elapse(10.milliseconds)   // the fake transport's own poll loop notices the close and aborts

        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        assertEquals("abandoned, but the rung climbs", listOf(0.seconds, 1.seconds), waits.toList())
    }


    /**
     * The one rejection a retry can fix is a ghost of ourselves still holding the name, which
     * Murmur reaps inside the deadline; every other rejection is final.
     */
    @Test fun usernameInUseRetriesAndOtherRejectsGiveUp() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        // The ladder's waits are not what this pins, so the retry's rung is skipped outright.
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, sleep = { }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))

        startedTransportAt(transports, 1).listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.UsernameInUse))
        transportAt(transports, 2)   // retried
        assertTrue("still reconnecting after a UsernameInUse", conn.status.value is ConnectionStatus.Reconnecting)

        transports[2].listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.WrongServerPW))
        assertSettled("error") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.AUTH_REJECTED, err.kind)
        assertSettled("giving up ends the call") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        assertEquals("no attempt after a final rejection", 3, transports.size)
    }

    /**
     * The ladder and the deadline under virtual time: one elapse spans the whole outage, the
     * waits consume it in order, and the sequence of waits is the whole story.
     */
    @Test fun replacementsThatKeepFailingGiveUpAtTheDeadline() = deterministic {
        val waits = CopyOnWriteArrayList<Duration>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
            sleep = { d -> waits += d; delay(d) },
        ) {
            // The first transport connects; every replacement is refused.
            val first = transports.isEmpty()
            FakeControlTransport { _, _ -> if (!first) throw IOException("refused") }.also { transports += it }
        })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))

        // 0+1+2+4+8+16+30+30+29 = 120 s: the ninth 30 s wait would overrun, so it is clamped to
        // the 29 s left and spent on one last attempt, which lands exactly on the deadline.
        elapse(120.seconds)
        assertSettled("gave up") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("could not get back to the server", err.detail)
        assertEquals(listOf(0, 1, 2, 4, 8, 16, 30, 30, 29).map { it.seconds }, waits.toList())
        assertSettled("giving up ends the call") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
    }

    /**
     * The budget left over is spent, not thrown away: measured on a Pixel 7a, WiFi returned with
     * 28.8 s of budget left and a 30 s wait next, because the in-flight connect was bound to the
     * interface that had just died and ran out its own timeout.
     */
    @Test fun theBudgetLeftOverIsSpentOnOneLastAttempt() = deterministic {
        val waits = CopyOnWriteArrayList<Duration>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val spent = { waits.sumOf { it.inWholeSeconds }.seconds }
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
            sleep = { d -> waits += d; delay(d) },
        ) {
            val reachable = transports.isEmpty() || spent() >= 120.seconds
            FakeControlTransport { _, _ -> if (!reachable) throw IOException("refused") }
                .also { transports += it }
        })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))

        elapse(121.seconds)   // the whole ladder, refused at every rung until the budget is spent
        assertSettled("an attempt is made after the ladder would have given up") {
            spent() >= 120.seconds && transports.size >= 9 &&
                transports.last().sent.any { it.first == TcpMessageType.Version }
        }
        transports.last().listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        assertEquals("the call must survive the whole outage", 0, call.ends)
    }

    /**
     * The budget is two minutes of reconnecting, not two minutes of wall clock: a path that comes
     * up for a while and dies again short of healthy would otherwise spend the deadline while the
     * user was connected and talking, and then be told it had been reconnecting for two minutes.
     */
    @Test fun timeSpentConnectedIsGivenBackToTheDeadline() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
            // Long enough that the ping ticker never ends a link during a 29 s connected stretch;
            // the links here die by the resets below, not by ping age.
            pingIntervalMs = 3_600_000L,
        ) {
            // Two replacements come up and die again short of healthy; everything after is refused.
            val refuse = transports.size >= 3
            FakeControlTransport { _, _ -> if (refuse) throw IOException("refused") }.also { transports += it }
        })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        transports[0].listener!!.onClosed(IOException("reset"))
        for ((rung, session) in listOf(0.seconds to 2, 1.seconds to 3)) {
            elapse(rung)   // short of healthy, each loss is another rung of the same outage
            startedTransportAt(transports, session - 1).listener!!.onFrame(serverSync(session))
            assertEquals(session, connected(conn).sessionId)
            elapse(29.seconds)                     // connected, and short of healthy either way
            assertEquals("nothing else ended the link", session, transports.size)
            transports[session - 1].listener!!.onClosed(IOException("reset"))
        }

        // Two minutes of reconnecting on top of the 58 s spent connected: the give-up is at 178 s.
        elapse(118.seconds)   // t = 177: the clamped last wait is still running
        assertSettled("still inside the given-back budget") { conn.status.value is ConnectionStatus.Reconnecting }
        elapse(1.seconds)     // t = 178: the last attempt is refused and the budget is spent
        assertSettled("gave up") { conn.status.value is ConnectionStatus.Error }
    }

    /**
     * Our own ghost is reaped inside the window; past it the name belongs to someone else, and the
     * server's reason is the one thing worth telling the user — not a timeout two minutes later.
     */
    @Test fun aNameStillHeldPastTheGhostWindowIsFinal() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))

        // Every replacement is rejected as it dials, so the clock moves only in the ladder's own
        // waits, with no transport open to meet the 15 s handshake deadline. The rungs land the
        // sixth rejection at 31 s, inside the 45 s window, and the seventh at 61 s, past it.
        val rungs = listOf(0, 1, 2, 4, 8, 16, 30).map { it.seconds }
        for (i in 1..7) {
            elapse(rungs[i - 1])
            startedTransportAt(transports, i).listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.UsernameInUse))
        }

        assertSettled("final rejection") { conn.status.value is ConnectionStatus.Error }
        val err = conn.status.value as ConnectionStatus.Error
        assertEquals(ErrorKind.AUTH_REJECTED, err.kind)
        assertEquals("no attempt after the name proved to be someone else's", 8, transports.size)
    }

    /** A link that stayed synchronized 30 s was a working path; its loss is a new outage, not
     *  another failure of the one being retried, so the ladder starts over. */
    @Test fun losingAHealthyLinkStartsTheLadderOver() = deterministic {
        val waits = CopyOnWriteArrayList<Duration>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
            // Long enough that the ping ticker never ages out the 31 s "healthy" wait below; this
            // test is about the reconnect ladder, not the ping ticker.
            pingIntervalMs = 3_600_000L,
            sleep = { d -> waits += d; delay(d) },
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onClosed(IOException("reset"))          // unhealthy: opens the incident at rung 0
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        transports[1].listener!!.onClosed(IOException("reset"))          // unhealthy again: rung 1
        elapse(1.seconds)   // rung 1's own wait
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        assertEquals(3, connected(conn).sessionId)

        elapse(31.seconds)                                               // the third link becomes healthy
        transports[2].listener!!.onClosed(IOException("reset"))
        transportAt(transports, 3)

        assertEquals(listOf(0.seconds, 1.seconds, 0.seconds), waits.toList())
    }

    /** The log is the session's: what the old link received stays, by identity, and the new
     *  link's messages append to it. */
    @Test fun chatSurvivesAReconnect() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        transports[0].listener!!.onFrame(textFrom(9, "before"))
        assertSettled("first message") { conn.messages.value.isNotEmpty() }
        val before = conn.messages.value.single()

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        transports[1].listener!!.onFrame(textFrom(9, "after"))

        assertSettled("both messages") { conn.messages.value.size == 2 }
        val log = conn.messages.value
        assertTrue("the carried message is the same instance", log[0] === before)
        assertEquals("after", (log[1] as ChatMessage.Remote).htmlBody)
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
    @Test fun selfMuteSurvivesAReconnect() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        conn.setMuted(true)
        assertEquals("the first link carries the mute", listOf(true), transports[0].selfMutes())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)

        assertSettled("the replacement must be told the session is muted") {
            transports[1].selfMutes() == listOf(true)
        }
        conn.setMuted(false)
        assertSettled("and the tap that lifts it must reach the same link") {
            transports[1].selfMutes() == listOf(true, false)
        }
    }

    /** The self_deaf/self_mute pair of every UserState this transport sent, in order. */
    private fun FakeControlTransport.selfStates() = sent
        .filter { it.first == TcpMessageType.UserState }
        .map { (it.second as MumbleProtos.UserState).let { state -> state.selfDeaf to state.selfMute } }

    /**
     * The gate and the controls stay on what the session asked, but a replacement told nothing has
     * the server sending audio to a user who asked not to hear it.
     */
    @Test fun selfDeafSurvivesAReconnect() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        conn.setSelfDeaf(true)
        assertEquals("the first link carries the deafen", listOf(true to true), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)

        assertSettled("the replacement must be told the session is deafened") {
            transports[1].selfStates() == listOf(true to true)
        }
    }

    /**
     * The carried state is what the session asked for, not the wire's two fields: those cannot tell
     * a deafen's own mute from one the user set, and the next undeafen would open the microphone.
     */
    @Test fun aMuteTheUserSetOutlivesADeafenAcrossAReconnect() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        conn.setMuted(true)
        conn.setSelfDeaf(true)

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        assertSettled("the replacement must be told both") { transports[1].selfStates() == listOf(true to true) }

        conn.setSelfDeaf(false)
        assertSettled("undeafening keeps the mute the user set") {
            transports[1].selfStates() == listOf(true to true, false to true)
        }
    }

    /**
     * A tap inside the outage reaches no live link at all: the dead link's machine refuses to send
     * and keeps its old state. The session is what remembers the ask, so the replacement is told
     * what the user last wanted — an unmute as much as a mute, or the swap would quietly put the
     * microphone back where the user had just taken it from.
     */
    @Test fun anUnmuteDuringTheOutageIsCarriedNotReverted() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        conn.setMuted(true)
        assertEquals(listOf(false to true), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("reconnecting") { conn.status.value is ConnectionStatus.Reconnecting }
        conn.setMuted(false)                       // nothing live to carry it

        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        assertEquals("the replacement must not be muted again", emptyList<Pair<Boolean, Boolean>>(), transports[1].selfStates())
    }

    /** The same the other way: a deafen taken during the outage reaches the replacement. */
    @Test fun aDeafenDuringTheOutageReachesTheReplacement() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("reconnecting") { conn.status.value is ConnectionStatus.Reconnecting }
        conn.setSelfDeaf(true)

        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        assertSettled("the replacement must be told the session is deafened") {
            transports[1].selfStates() == listOf(true to true)
        }
    }

    /**
     * The swap publishes the replacement's own tree before the driver publishes its Connected, so
     * the UI never reads the new session id against the dead link's tree — where our own row is
     * missing and a mute on it would read as gone.
     */
    @Test fun theReplacementsTreeIsPublishedBeforeItsConnected() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        fun ourRow(session: Int) = TcpFrame(
            TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(session).setChannelId(0).setSelfMute(true)
                .build().toByteArray(),
        )
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(ourRow(1))
        transports[0].listener!!.onFrame(serverSync(1))
        connected(conn)

        // Unconfined, the collector runs inside the publish itself: a row seeded a task later
        // would be missing here, where a collector that ran afterwards would see it.
        var rowAtConnected: User? = null
        scope.backgroundScope.launch(Dispatchers.Unconfined) {
            conn.status.collect {
                if (it is ConnectionStatus.Connected && it.sessionId == 2) rowAtConnected = conn.channelTree.value.users[2]
            }
        }

        transports[0].listener!!.onClosed(IOException("reset"))
        // The replacement's own handshake: its rows land before its ServerSync, as murmur sends them.
        startedTransportAt(transports, 1).listener!!.onFrame(ourRow(2))
        transports[1].listener!!.onFrame(serverSync(2))

        assertEquals(2, connected(conn).sessionId)
        assertNotNull("our row must be readable the moment Connected lands", rowAtConnected)
        assertTrue("and carry what the replacement was told", rowAtConnected!!.selfMute)
    }

    /**
     * A mute tapped while the socket is already gone reaches no server, so the row that freezes at
     * the link's death still reads unmuted. The gate is shut all the same, and it is the gate the
     * replacement is told from: seeded back from that echo, the user would come back with the
     * server, the row and the control saying unmuted while nothing left the device.
     */
    @Test fun aMuteTheDyingLinkNeverCarriedIsStillWhatTheReplacementIsTold() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        fun ourRow(session: Int) = TcpFrame(
            TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(session).setChannelId(0).build().toByteArray(),
        )
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(ourRow(1))
        transports[0].listener!!.onFrame(serverSync(1))
        connected(conn)

        transports[0].close()                      // the socket is gone; the state machine has yet to hear
        conn.setMuted(true)
        assertEquals("the wire refused it", emptyList<Pair<Boolean, Boolean>>(), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)
        assertSettled("the replacement must be told the session is muted") {
            transports[1].selfStates() == listOf(false to true)
        }
    }

    /**
     * A double-tap lands inside one round trip, so the second ask arrives with the tree — and the
     * control's idea of `deafened` — unchanged, and reaches this as a repeat. A repeat that emitted
     * `self_mute=true` on an undeafen would leave the user muted with no control to clear it.
     *
     * Asserts every frame, not just the last: the bug is a differing *second* message.
     */
    @Test fun undeafenTappedTwiceSendsTheSameMessageTwice() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        conn.setSelfDeaf(true)
        conn.setSelfDeaf(false)
        conn.setSelfDeaf(false)

        val undeafens = transports[0].selfStates().drop(1)
        assertEquals(2, undeafens.size)
        undeafens.forEach { assertEquals("every undeafen clears both", false to false, it) }
    }

    /** The same break from the other side: a repeated deafen must not take its own first ask's
     *  mute for the user's, or the undeafen keeps a mute the user never set. */
    @Test fun deafenTappedTwiceStillUnmutesOnUndeafen() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        conn.setSelfDeaf(true)
        conn.setSelfDeaf(true)
        conn.setSelfDeaf(false)

        assertEquals(
            "the mute was the deafen's, so the undeafen lifts it",
            false to false,
            transports[0].selfStates().last(),
        )
    }

    /**
     * A whole sequence the wire refused — the socket is gone, the state machine has yet to hear —
     * is still the session's, and the replacement is told the end of it. The mute is the user's own,
     * so the deafen does not replace it and the undeafen leaves it standing.
     */
    @Test fun asksTheWireRefusedStillCompose() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        transports[0].close()
        conn.setMuted(true)
        conn.setSelfDeaf(true)
        conn.setSelfDeaf(false)
        assertEquals("nothing reached the wire", emptyList<Pair<Boolean, Boolean>>(), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(conn).sessionId)

        assertSettled("the replacement is told the mute, and only the mute") {
            transports[1].selfStates() == listOf(false to true)
        }
    }

    /**
     * The server's certificate changed while we were rebuilding the link. The prompt is the one a
     * fresh connect gives: the session is retired behind it — no microphone opens against it, and
     * the call ends as a failure — but kept aside, so accepting the new pin reconnects.
     */
    @Test fun aTrustPromptOnAReconnectRetiresTheSessionAndCanStillBeAccepted() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val pins = InMemoryPinStore()
        val endpoint = MumbleEndpoint.parse("localhost")
        pins.put(endpoint.address, "aa")
        val conn = own(MumbleConnection(
            pins,
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            // Only the replacement is refused; the third transport is the one trustAndConnect builds.
            val replacement = transports.size == 1
            FakeControlTransport { _, _ ->
                if (replacement) throw PinMismatchException(stored = "aa", presented = "bb")
            }.also { transports += it }
        })
        conn.connect(endpoint, "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)

        transports[0].listener!!.onClosed(IOException("reset"))

        assertSettled("pin mismatch") { conn.status.value == ConnectionStatus.PinMismatch("aa", "bb") }
        assertSettled("the prompt must end the call") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons)
        assertSettled("the dead link and its refused replacement are both closed") {
            transports.size == 2 && transports.all { it.closed }
        }

        conn.requestCapture()
        assertSettled("no microphone may open while a prompt is up") { handles.isEmpty() }

        conn.trustAndConnect()

        assertSettled("accepting the new certificate reconnects") { transports.size == 3 }
        assertEquals("bb", pins.get(endpoint.address))
    }

    /** A link whose pings go unanswered is replaced, not waited out: on a dead path the socket
     *  may never say so. */
    @Test fun anUnresponsiveLinkIsReplaced() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(conn)

        elapse(16.seconds)   // three ping intervals unanswered; the third tick ends the link

        assertEquals(ConnectionStatus.Reconnecting(first.gen, 1), conn.status.value)
        assertTrue("the unresponsive link is closed", transports[0].closed)
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(ConnectionStatus.Connected(first.gen, 2), connected(conn))
        assertEquals("the call rides through", 0, call.ends)
    }

    /** The gate follows what the session asks for: a deafen shuts it, deafen forcing a mute, and
     *  the undeafen that lifts that mute reopens it. */
    @Test fun theGateFollowsADeafen() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle().also { handles += it } },
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        conn.requestCapture()   // voice activity is the default mode, so the gate opens with the engine
        assertSettled("the gate opens") { handles.size == 1 && handles[0].gateOpen }

        conn.setSelfDeaf(true)
        assertSettled("deafened is muted, so the gate shuts") { !handles[0].gateOpen }
        conn.setSelfDeaf(false)
        assertSettled("the undeafen lifts the mute it set, and the gate with it") { handles[0].gateOpen }
    }

    /** A mute tapped while deafened, inside the echo window where the control still reads
     *  unmuted, is the user's own: the undeafen keeps it on the wire, and the gate shut with it. */
    @Test fun aMuteTappedWhileDeafenedOutlivesTheUndeafen() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle().also { handles += it } },
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(conn)
        conn.requestCapture()
        assertSettled("the gate opens") { handles.size == 1 && handles[0].gateOpen }

        conn.setSelfDeaf(true)
        conn.setMuted(true)
        conn.setSelfDeaf(false)

        assertSettled("undeafened on the wire, and still muted") { transports[0].selfStates().lastOrNull() == (false to true) }
        assertSettled("and the gate agrees") { !handles[0].gateOpen }
    }

    /**
     * A session that dies on its own reaches no disconnect() and supersedes no prior session, so
     * before retire() nothing tore the session down and the playback thread outlived it — waking
     * at 100 Hz with an open AudioTrack for as long as the error screen stayed up.
     */
    @Test fun aFailedSessionReleasesTheReceiver() = deterministic {
        lateinit var fake: FakeControlTransport
        val playout = FakePlayoutEngine()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newPlayout = { playout }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("handshaking") { conn.status.value is ConnectionStatus.Handshaking }

        // Reach the receiver first, so there is a live playout and a started stream to release.
        playout.liveSessions = setOf(9)
        playout.audibleSessions = setOf(9)
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .build()
        assertSettled("the receiver's poll started") { playout.startAttempts.get() > 0 }
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        elapse(50.milliseconds)   // VoiceReceiver.POLL_MILLIS: the next poll reads the engine's speaking set
        assertSettled("speaking") { conn.speakingSessions.value.isNotEmpty() }

        // The server rejects the login: a terminal state nobody asked for.
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))
        assertSettled("rejected") { conn.status.value is ConnectionStatus.Error }
        assertEquals(ErrorKind.AUTH_REJECTED, (conn.status.value as ConnectionStatus.Error).kind)

        assertSettled("a failed session must destroy the playout engine") { playout.destroyed }
        // The error must survive the teardown — it is what the user is looking at.
        assertTrue("retire() must not reset the terminal status", conn.status.value is ConnectionStatus.Error)
    }

    @Test fun speakingSessionsPopulateThenClearOnDisconnect() = deterministic {
        lateinit var fake: FakeControlTransport
        val playout = FakePlayoutEngine()
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newPlayout = { playout }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("handshaking") { conn.status.value is ConnectionStatus.Handshaking }

        // The engine's answer to the packet below: session 9 holds a slot and is producing.
        playout.liveSessions = setOf(9)
        playout.audibleSessions = setOf(9)
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .build()
        assertSettled("the receiver's poll started") { playout.startAttempts.get() > 0 }
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        assertSettled("the packet must reach the engine") { playout.offered.isNotEmpty() }

        elapse(50.milliseconds)   // VoiceReceiver.POLL_MILLIS: the next poll reads the engine's speaking set
        assertSettled("speaking") { conn.speakingSessions.value == setOf(9) }
        assertTrue("a live speaker must start the stream", playout.started)

        conn.disconnect()
        assertEquals("disconnect() zeroes the flow under the lock", emptySet<Int>(), conn.speakingSessions.value)
        // The assertion above alone is vacuous as a lifecycle test: disconnect() writes the flow
        // synchronously and the generation guard blocks any later receiver-originated write, so
        // deleting the stop() from teardown() would still pass it. This is what actually proves
        // the receiver was released.
        assertSettled("teardown must destroy the playout engine") { playout.destroyed }
    }

    // Voice is additive: a socket that cannot be opened must cost the session nothing but UDP.
    @Test fun aSocketThatCannotOpenLeavesTheSessionHealthyAndTunneled() = deterministic {
        val playout = FakePlayoutEngine()
        lateinit var fake: FakeControlTransport
        val conn = own(MumbleConnection(
            InMemoryPinStore(), newPlayout = { playout }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            // Unresolved, so DatagramChannel.connect refuses it locally: no DNS, no network.
            FakeControlTransport { _, _ -> }.apply { remote = InetSocketAddress.createUnresolved("nowhere.invalid", 1) }.also { fake = it }
        })
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("handshaking") { conn.status.value is ConnectionStatus.Handshaking }
        fake.listener!!.onFrame(keyExchange())
        fake.listener!!.onFrame(serverSync())
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(1).setChannelId(0).build().toByteArray()))
        connected(conn)
        assertSettled("the receiver's poll started") { playout.startAttempts.get() > 0 }

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, audioPacket(session = 9)))

        assertSettled("tunneled audio still reaches the engine") { playout.offered.isNotEmpty() }
        assertEquals(9, playout.offered.first().session)
    }

    // ---- Real threads, by design. Everything below runs on runBlocking and the default
    // dispatchers with awaitOnRealThreads, because the scheduler cannot reach it:
    //   parking the driver on a latch or a continuation the test releases —
    //     aSupersededHandshakeDoesNotClobberIdle, aSupersededSessionLeavesChannelTreeEmpty,
    //     aDisconnectDuringThePinLookupClosesTheLinkItBuilt,
    //     disconnectWhileReconnectingClosesTheReplacement, aFrameTheDeadLinkReducesAfterItsCloseNeverReachesTheTree
    //   a real TLS server — firstContactAwaitsTrustThenPinsAndReachesHandshaking
    //   the pump's own thread — frames on the wire or self-speaking: requestCaptureRunsTheSendPathAndDisconnectReleasesIt,
    //     holdTearsTheSessionDownAndResumeRebuildsIt — and its own clock — theCaptureCountersFollowTheSession
    //   a loopback DatagramSocket and MumbleUdpTransport's reader thread — the eleven `peer` tests
    // A converted test lives above this line and never calls awaitOnRealThreads.

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
        awaitOnRealThreads("the superseded link must be closed") { transports.single().closed }
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

        awaitOnRealThreads("a superseded session must close the transport it built") {
            transports.size == 1 && transports.all { it.closed }
        }
        assertEquals("no receiver should ever start on this path", 0, engines.get())
    }

    /** Hanging up mid-reconnect is a hang-up: the replacement in flight goes with the session. */
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
        startedTransportAtOnRealThreads(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onClosed(IOException("reset"))
        transportAtOnRealThreads(transports, 1)   // inside its handshake

        conn.disconnect()

        assertEquals(ConnectionStatus.Idle, conn.status.value)
        release.countDown()
        awaitOnRealThreads("both links must be closed") { transports.all { it.closed } }
        awaitOnRealThreads("the call ends once, as a hang-up") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.USER), call.endReasons)
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
        startedTransportAtOnRealThreads(transports, 0).listener!!.onFrame(serverSync(1))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Connected } }
        transports[0].listener!!.onFrame(TcpFrame(TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray()))
        withTimeout(5_000) { conn.channelTree.first { it.channels.containsKey(1) } }

        transports[0].listener!!.onClosed(IOException("reset"))
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Reconnecting } }
        // The driver closes the dead link before it builds any replacement, and close() raises the
        // flag before it hands the socket to IO, so a closed transport proves the flag is up.
        awaitOnRealThreads("the dead link must be closed") { transports[0].closed }
        transports[0].listener!!.onFrame(TcpFrame(TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(2).setName("Late").build().toByteArray()))
        delay(200)

        assertFalse("a frozen link must not reach the tree", conn.channelTree.value.channels.containsKey(2))
        release.countDown()
        conn.disconnect()
    }

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

    /**
     * The whole transmit path end to end, since every piece of it is new wiring: the service comes
     * up before the engine is opened, the pump reaches the wire, the gate follows push-to-talk, and
     * teardown releases all three. Asserting on `sentRaw` rather than on the sender means the test
     * cannot pass with a pump that was built but never started.
     *
     * Real threads: `sentRaw` is written from the pump's own thread, not the scheduler — nothing
     * drives it to run just because the test calls runCurrent().
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

        awaitOnRealThreads("the pump must reach the wire") { fake.sentRaw.isNotEmpty() }
        val (type, payload) = fake.sentRaw.first()
        assertEquals(TcpMessageType.UDPTunnel, type)
        // Leading 0 is the UDP audio type byte the tunnel carries ahead of the Audio protobuf.
        assertEquals(0, payload[0].toInt())

        // setTransmitting only reaches the engine once the sender is published, which happens on
        // the same coroutine that opened it — so this runs after the awaits above, not before.
        conn.setTransmitting(true)
        awaitOnRealThreads("push-to-talk must open the gate") { handle.gateOpen }

        conn.disconnect()
        awaitOnRealThreads("teardown must stop the pump") { handle.stopped }
        awaitOnRealThreads("teardown must destroy the engine") { handle.destroyed }
        awaitOnRealThreads("teardown must end the call") { call.ends == 1 }
    }

    /**
     * A hold tears the capture session down instead of merely closing the gate, and a resume builds
     * a fresh one. This is the design's substitute for gating the reopen backoff on focus state: proving
     * the rebuilt session reaches the wire is what makes the substitution honest, since a teardown
     * that could not come back would be worse than the backoff it replaced.
     *
     * Real threads: same as [requestCaptureRunsTheSendPathAndDisconnectReleasesIt] — `sentRaw` is
     * pump-produced.
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
        awaitOnRealThreads("the engine must open") { handles.size == 1 }
        handles[0].script(FakeCaptureHandle.Step.Frame(byteArrayOf(1), frameNumber = 1, terminator = false))
        awaitOnRealThreads("the first session must reach the wire") { fake.sentRaw.size == 1 }

        call.hold()
        awaitOnRealThreads("a hold must stop the pump") { handles[0].stopped }
        awaitOnRealThreads("a hold must destroy the engine") { handles[0].destroyed }

        call.resume()
        awaitOnRealThreads("resuming must build a new engine") { handles.size == 2 }
        handles[1].script(FakeCaptureHandle.Step.Frame(byteArrayOf(2), frameNumber = 2, terminator = false))
        awaitOnRealThreads("the rebuilt session must reach the wire") { fake.sentRaw.size == 2 }

        conn.disconnect()
        awaitOnRealThreads("disconnect must end the call") { call.ends == 1 }
        awaitOnRealThreads("disconnect must release the rebuilt engine") { handles[1].destroyed }
    }

    /**
     * The counters reach the flow from the live session's pump and leave with the session.
     *
     * Real threads: pump-produced — the stats are read on the pump's own clock.
     */
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
        awaitOnRealThreads("the counters must reach the flow", timeoutMillis = 6_000) {
            handle.script(FakeCaptureHandle.Step.Retry)
            conn.captureStats.value != null
        }
        assertEquals(12.0, conn.captureStats.value!!.inputLatencyMillis)

        conn.disconnect()
        awaitOnRealThreads("teardown must clear the counters") { conn.captureStats.value == null }
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

        awaitOnRealThreads("UDP audio must reach the engine") { playout.offered.isNotEmpty() }
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

        awaitOnRealThreads("a failed decrypt past the quiet period must ask for a resync") {
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

        awaitOnRealThreads("the tick after the second unanswered ping must tunnel a ping") {
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

        awaitOnRealThreads("several pings answered") { peer.opened.size >= 5 }

        assertTrue(fake.sentRaw.none { it.first == TcpMessageType.UDPTunnel })
        conn.disconnect()
        peer.close()
    }

    @Test fun disconnectClosesTheUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) {})
        connectToHandshaking(conn)
        awaitOnRealThreads("the socket opens with the connection") { readersSince(before) == 1 }

        conn.disconnect()

        awaitOnRealThreads("disconnect must close the socket and end its reader") { readersSince(before) == 0 }
        peer.close()
    }

    @Test fun aSessionThatFailsOnItsOwnClosesTheUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        lateinit var fake: FakeControlTransport
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) { fake = it })
        connectToHandshaking(conn)
        awaitOnRealThreads("the socket opens with the connection") { readersSince(before) == 1 }

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))

        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Error } }
        awaitOnRealThreads("a retired session must not leak its socket") { readersSince(before) == 0 }
        peer.close()
    }

    @Test fun aSupersededSessionClosesItsUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        val conn = MumbleConnection(InMemoryPinStore(), newTransport = fakeAimedAt(peer) {})
        connectToHandshaking(conn)
        awaitOnRealThreads("the socket opens with the connection") { readersSince(before) == 1 }
        val first = (readers() - before).single()

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)

        awaitOnRealThreads("the superseded session's reader exits") { !first.isAlive }
        awaitOnRealThreads("one live session, one socket") { readersSince(before) == 1 }
        conn.disconnect()
        awaitOnRealThreads("and none after disconnect") { readersSince(before) == 0 }
        peer.close()
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
        awaitOnRealThreads("the answered ping promotes") { conn.voicePath.value.onUdp }
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
        awaitOnRealThreads("the answered ping promotes") { conn.voicePath.value.onUdp }
        fake.listener!!.onFrame(serverSync())   // the ping loop starts with the session
        awaitOnRealThreads("the next ping must report the UDP leg") {
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
        awaitOnRealThreads("promoted") { conn.voicePath.value.onUdp }
        conn.requestCapture()
        fake.listener!!.onFrame(serverSync())
        answer.set(false)

        awaitOnRealThreads("the report of two unanswered pings demotes") { !conn.voicePath.value.onUdp }
        assertEquals(VoicePath.State(), conn.voicePath.value)
        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(9), frameNumber = 1, terminator = false))
        awaitOnRealThreads("the next frame goes through the tunnel") {
            fake.sentRaw.any { it.first == TcpMessageType.UDPTunnel && it.second[0] == 0.toByte() }
        }

        answer.set(true)
        awaitOnRealThreads("two replies re-promote") { conn.voicePath.value.onUdp }
        conn.disconnect()
        peer.close()
    }
}
