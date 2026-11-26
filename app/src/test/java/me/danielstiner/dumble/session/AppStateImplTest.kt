package me.danielstiner.dumble.session

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
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.hangGuard
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.User
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.FakeControlTransport
import me.danielstiner.dumble.mumble.connection.FakeNetworkWatch
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.LocalNetworkBlockedException
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.TestTlsServer
import me.danielstiner.dumble.mumble.net.UdpTransport
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.net.sha256Hex
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.ConnectionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakePlayoutEngine
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import me.danielstiner.dumble.time.AtomicTimeSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
class AppStateImplTest {

    @get:Rule val timeout = hangGuard()

    @Test fun connectTimeoutMapsToTimeoutError() = deterministic {
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> throw SocketTimeoutException("connect timed out") } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("error") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.TIMEOUT, err.kind)
    }

    /** Retrying can't help until the user answers, and they are only asked once the session ends. */
    @Test fun aBlockOnAReconnectEndsTheSession() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            val replacement = transports.size == 1
            FakeControlTransport { _, _ -> if (replacement) throw LocalNetworkBlockedException() }.also { transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].listener!!.onClosed(IOException("reset"))

        assertSettled("the block ends the session") {
            (app.status.value as? SessionStatus.Error)?.kind == ErrorKind.LOCAL_NETWORK_BLOCKED
        }
    }

    @Test fun channelTreeSurfacesReducedFrames() = deterministic {
        lateinit var fake: FakeControlTransport
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })   // connects, stays open
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        fake.listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))

        assertSettled("channel tree") { app.channelTree.value.channels.containsKey(1) }
        assertEquals("Root", app.channelTree.value.channels[1]!!.name)
    }

    @Test fun disconnectResetsChannelTree() = deterministic {
        lateinit var fake: FakeControlTransport
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        fake.listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))
        assertSettled("channel tree") { app.channelTree.value.channels.containsKey(1) }

        app.disconnect()

        assertEquals(ChannelTree(), app.channelTree.value)
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fakes += it } })
        app.connect(MumbleEndpoint.parse("first"), "user", null)
        handshaking(app)
        fakes[0].listener!!.onFrame(TcpFrame(
            TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray(),
        ))
        fakes[0].listener!!.onFrame(TcpFrame(
            TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray(),
        ))
        assertSettled("channel tree") { app.channelTree.value.channels.containsKey(1) }
        assertSettled("messages") { app.messages.value.isNotEmpty() }

        app.connect(MumbleEndpoint.parse("second"), "user", null)

        // Sampled, not awaited: connect() clears under the same lock that bumps the generation, so
        // it has already happened when the call returns, and the same step closes the first
        // session's publisher, so it cannot write these again.
        assertEquals(ChannelTree(), app.channelTree.value)
        assertEquals(emptyList<ChatMessage>(), app.messages.value)
    }

    @Test fun messagesSurfaceFromTheSession() = deterministic {
        lateinit var fake: FakeControlTransport
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray()))

        assertSettled("messages") { app.messages.value.isNotEmpty() }
        assertEquals("yo", (app.messages.value.single() as ChatMessage.Remote).htmlBody)
    }

    @Test fun disconnectClearsMessages() = deterministic {
        lateinit var fake: FakeControlTransport
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.TextMessage.id,
            MumbleProtos.TextMessage.newBuilder().setActor(9).setMessage("yo").build().toByteArray()))
        assertSettled("messages") { app.messages.value.isNotEmpty() }

        app.disconnect()

        assertEquals(emptyList<ChatMessage>(), app.messages.value)
    }

    @Test fun sendTextRoutesToTheLiveSessionAndEchoes() = deterministic {
        lateinit var fake: FakeControlTransport
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.ServerSync.id,
            MumbleProtos.ServerSync.newBuilder().setSession(1).build().toByteArray()))
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(1).setChannelId(0).build().toByteArray()))
        connected(app)

        val ok = app.sendText("hello")

        assertTrue(ok)
        assertTrue(fake.sent.any { it.first == TcpMessageType.TextMessage })
        assertSettled("messages") { app.messages.value.isNotEmpty() }
        assertEquals("hello", (app.messages.value.single() as ChatMessage.Remote).htmlBody)
    }

    @Test fun sendTextWithNoConnectionReturnsFalse() = deterministic {
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        assertFalse(app.sendText("hi"))
    }

    /**
     * Cancelled inside the handshake, the driver never reaches its own live check: the connection
     * it published under the lock before connecting is teardown's to close.
     */
    @Test fun aDisconnectDuringTheHandshakeClosesTheLink() = deterministic {
        val gate = CompletableDeferred<Unit>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> gate.await() }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the driver must reach the handshake") { transports.firstOrNull()?.listener != null }

        app.disconnect()

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
        val app = own(AppStateImpl(pins, udpClock = clock, context = dispatcher, blocking = dispatcher) {
            FakeControlTransport { _, _ -> }.also { transports += it }
        })

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the driver is parked in the pin lookup") { parked.isCompleted }
        val wait = parked.getCompleted()
        app.disconnect()
        wait.resume(Unit)   // ignored once cancelled; a driver that survived would run on and build
        assertSettled("the cancelled driver must build nothing") { transports.isEmpty() }
    }

    @Test fun callStartsWithTheConnectionNotTheMicrophone() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)

        // No requestCapture() anywhere in this test: connecting alone must start the call.
        assertSettled("connecting must start the call with the server host") { call.starts == listOf("localhost") }
        app.disconnect()
        assertSettled("disconnecting must end the call") { call.ends == 1 }
    }

    /**
     * A connect that fails outright leaves nothing for the user to act on, unlike a trust prompt,
     * so the session is retired on the spot: the microphone must not open against it, and the
     * call ends exactly once.
     */
    @Test fun aHardConnectFailureRetiresTheSession() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> throw IOException("connection refused") }.also { transports += it } })

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("error") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.CONNECT_FAILED, err.kind)
        assertSettled("the failure must end the call") { call.ends == 1 }

        app.requestCapture()

        assertSettled("no microphone may open for a retired session") { handles.isEmpty() }
        assertEquals(1, call.ends)
        assertTrue("retire() must not reset the terminal status", app.status.value is SessionStatus.Error)
        assertSettled("retire must close the link") { transports.single().closed }
    }

    /** A trust prompt retires its session the same way; the one difference is that the prompt
     *  keeps the session aside for trustAndConnect() to reconnect from. */
    @Test fun aTrustPromptRetiresTheSessionAndCanStillBeAccepted() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val pins = InMemoryPinStore()
        val app = own(AppStateImpl(
            pins,
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { pin ->
            FakeControlTransport { _, _ -> if (pin == null) throw UntrustedCertificateException("ab12") }
                .also { transports += it }
        })
        val endpoint = MumbleEndpoint.parse("localhost")

        app.connect(endpoint, "user", null)
        assertSettled("awaiting trust") { app.status.value is SessionStatus.AwaitingTrust }
        assertSettled("the prompt must end the call") { call.ends == 1 }

        app.requestCapture()

        assertSettled("no microphone may open while a prompt is up") { handles.isEmpty() }
        assertSettled("the stopped handshake's transport is closed") { transports.single().closed }
        assertTrue(app.status.value is SessionStatus.AwaitingTrust)

        app.trustAndConnect()
        handshaking(app)
        assertEquals("ab12", pins.get(endpoint.address))
        assertEquals(1, call.ends)
    }

    /** trustAndConnect() writes the pin, then connects; a Cancel landing before that connect must
     *  still be the user's last word, and leaves the pin, which was a decision of its own. */
    @Test fun aCancelRightAfterTrustStaysCancelled() = deterministic {
        val call = FakeVoiceCall()
        val pins = InMemoryPinStore()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            pins, call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { pin ->
            FakeControlTransport { _, _ -> if (pin == null) throw UntrustedCertificateException("ab12") }
                .also { transports += it }
        })
        val endpoint = MumbleEndpoint.parse("localhost")
        app.connect(endpoint, "user", null)
        assertSettled("awaiting trust") { app.status.value is SessionStatus.AwaitingTrust }

        app.trustAndConnect()
        app.cancelTrust()

        assertSettled("the cancel must stand") { app.status.value == SessionStatus.Idle }
        assertEquals(1, call.starts.size)
        assertEquals(1, transports.size)
        assertEquals("the pin Trust saved", "ab12", pins.get(endpoint.address))
    }

    /** The generation is the UI's handle on "which call": one per connect(), whatever session id
     *  the server hands out. */
    @Test fun connectedCarriesTheSessionsGeneration() = deterministic {
        val fakes = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fakes += it } })
        val sync = TcpFrame(TcpMessageType.ServerSync.id,
            MumbleProtos.ServerSync.newBuilder().setSession(5).build().toByteArray())

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        fakes[0].listener!!.onFrame(sync)
        val first = connected(app)

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        fakes[1].listener!!.onFrame(sync)
        val second = connected(app)

        assertEquals(5, first.sessionId)
        assertEquals(5, second.sessionId)
        assertNotEquals(first.gen, second.gen)
    }

    /** A late callback from a call we already ended must not resurrect a dead session. */
    @Test fun holdDeliveredAfterDisconnectRebuildsNothing() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, startPump = startPump,
            call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        app.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }
        val gen = call.startedGens.single()

        app.disconnect()
        assertSettled("disconnect must end the call") { call.ends == 1 }
        call.resumeFor(gen)

        assertSettled("no engine may be built for a dead session") { handles.size == 1 }
    }

    /**
     * An end for the prior call would reach the platform before the new start, closing a headset's
     * call link only to reopen it and dropping the route the user picked.
     */
    @Test fun connectingOverALiveConnectionSupersedesThePriorCall() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("first"), "user", null)
        handshaking(app)

        app.connect(MumbleEndpoint.parse("second"), "user", null)
        handshaking(app)

        assertEquals(listOf("first", "second"), call.starts)
        assertSettled("the prior call must be superseded, not ended") { call.ends == 0 }

        app.disconnect()
        assertSettled("disconnecting must end the call once") { call.ends == 1 }
    }

    /** Denied microphone, or an engine that would not open: receive still needs the call's service. */
    @Test fun captureThatCannotOpenLeavesTheCallRunning() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { null }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        app.requestCapture()
        // No engine, so push-to-talk is inert rather than a crash.
        app.setTransmitting(true)
        assertSettled("the call must survive an engine that would not open") { call.ends == 0 }
    }

    private fun routes(vararg types: AudioRoute.Type, current: AudioRoute.Type? = null): AudioRoutes {
        val available = types.map { AudioRoute("id-$it", it) }
        return AudioRoutes(available, current?.let { c -> available.first { it.type == c } })
    }

    /** The platform's answer is the only source; nothing local invents a route. */
    @Test fun routesFromTheLiveCallReachTheUi() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        call.emitRoutes(routes(AudioRoute.Type.EARPIECE, AudioRoute.Type.SPEAKER, current = AudioRoute.Type.EARPIECE))

        assertSettled("routes must reach the flow") { app.audioRoutes.value.available.size == 2 }
        assertEquals(AudioRoute.Type.EARPIECE, app.audioRoutes.value.current?.type)
    }

    /**
     * The platform does not fence a callback already in flight, so a superseded call can still
     * report routes; the liveness check is what stops it repainting the live one's control.
     */
    @Test fun routesFromASupersededCallAreDropped() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        val staleGen = call.startedGens.first()

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        call.emitRoutesFor(staleGen, routes(AudioRoute.Type.BLUETOOTH, current = AudioRoute.Type.BLUETOOTH))

        assertEquals(AudioRoutes(), app.audioRoutes.value)
    }

    /** Every other published flow is cleared on disconnect; a survivor here would paint the next
     *  session's control with the last one's headset. */
    @Test fun disconnectClearsTheRoutes() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        call.emitRoutes(routes(AudioRoute.Type.SPEAKER, current = AudioRoute.Type.SPEAKER))
        assertSettled("routes must reach the flow first") { app.audioRoutes.value.current != null }

        app.disconnect()

        assertSettled("teardown must clear the routes") { app.audioRoutes.value == AudioRoutes() }
    }

    /**
     * A session that fails ends its call, and the call's state goes with it: a hold or a route
     * update published before the end, or landing after it, must not paint the error screen.
     */
    @Test fun aFailedSessionsCallLeavesNothingPublished() = deterministic {
        val call = FakeVoiceCall()
        val refused = CompletableDeferred<Unit>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> refused.await() } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val gen = call.startedGens.single()
        call.emitRoutes(routes(AudioRoute.Type.SPEAKER, current = AudioRoute.Type.SPEAKER))
        call.hold()
        assertSettled("the call's hold and routes must be published while connecting") {
            app.callHeld.value && app.audioRoutes.value != AudioRoutes()
        }

        refused.completeExceptionally(IOException("connection refused"))
        assertSettled("error") { app.status.value is SessionStatus.Error }
        assertFalse("the end clears the hold", app.callHeld.value)
        assertEquals("the end clears the routes", AudioRoutes(), app.audioRoutes.value)

        call.holdFor(gen)
        call.emitRoutesFor(gen, routes(AudioRoute.Type.EARPIECE, current = AudioRoute.Type.EARPIECE))

        assertSettled("a late hold must not raise the banner") { !app.callHeld.value }
        assertEquals("late routes must be dropped", AudioRoutes(), app.audioRoutes.value)
    }

    @Test fun selectingARouteReachesTheCall() = deterministic {
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle() }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        app.requestAudioRoute("id-SPEAKER")

        assertSettled("the pick must reach the call") { call.routeRequests == listOf("id-SPEAKER") }
    }

    /** Polls under a wall-clock bound. Only for the tests that must run real threads — the TLS
     *  and loopback-UDP tests — and never inside [deterministic]: a converted test drives the
     *  scheduler and asserts (`assertSettled`). */
    private suspend fun awaitOnRealThreads(message: String, timeout: Duration = 5.seconds, cond: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!cond() && deadline.hasNotPassedNow()) delay(10)
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

    /**
     * The point of the split: the platform call, the receiver and the capture session all belong
     * to the session and ride through a reconnect; only the connection is rebuilt.
     */
    @Test fun aDeadLinkIsReplacedUnderTheSameSession() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val playout = FakePlayoutEngine()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(),
            newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, startPump = startPump,
            newPlayout = { playout }, call = call,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(app)
        assertEquals(1, first.sessionId)
        app.requestCapture()
        assertSettled("capture must open on the first link") { handles.size == 1 }

        transports[0].listener!!.onClosed(IOException("reset"))

        assertSettled("reconnecting") { app.status.value == SessionStatus.Reconnecting(first.gen, 1) }
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(SessionStatus.Connected(first.gen, 2), connected(app))

        assertEquals("one platform call for the whole session", 1, call.starts.size)
        assertEquals("the call must not end across a reconnect", 0, call.ends)
        assertFalse("the receiver must ride through the reconnect", playout.destroyed)
        assertFalse("the capture session must ride through the reconnect", handles.single().stopped)
        assertEquals("one engine for the whole session", 1, handles.size)
        assertTrue("the dead link must be closed", transports[0].closed)
    }

    /** A connection that never synchronized is the connect failing, and a connect is not
     *  retried. */
    @Test fun theFirstLinkIsNotRetried() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        transportAt(transports, 0).listener!!.onClosed(IOException("reset"))

        assertSettled("error") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("no replacement for a link that never came up", 1, transports.size)
    }

    /** A kick is the server's decision: the session ends with the reason, and no replacement is
     *  opened, or the kicked user would be back inside a second. */
    @Test fun aKickEndsTheSessionInsteadOfRejoining() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val transport = startedTransportAt(transports, 0)
        transport.listener!!.onFrame(serverSync(1))
        connected(app)

        transport.listener!!.onFrame(userRemove(session = 1, reason = "spam"))
        transport.listener!!.onClosed(IOException("reset"))

        assertSettled("kicked") { app.status.value == SessionStatus.Error(ErrorKind.KICKED, "spam") }
        assertSettled("the call ends") { call.ends == 1 }
        assertEquals("no replacement after a kick", 1, transports.size)
    }

    /**
     * Losing the network the connection is on closes it instead of waiting for the socket to
     * notice, and the call rides through.
     */
    @Test fun losingTheLinksNetworkReplacesItAtOnce() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(app)

        network.lose("wifi")

        assertSettled("the live link is closed on the loss") { transports[0].closed }
        // Our close unblocks the reader, whose exit is what reports the death.
        transports[0].listener!!.onClosed(IOException("closed"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(SessionStatus.Connected(first.gen, 2), connected(app))
        assertEquals("the call must not end across a loss", 0, call.ends)
    }

    /** A default that merely moves, the old network still up, is not a death: with a LAN server
     *  on a WiFi that lost its uplink, that connection is the only one that reaches the server. */
    @Test fun aNewDefaultLeavesAWorkingLinkAlone() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(app)

        network.switchTo("cell")

        assertSettled("a link on a network still up stays") { !transports[0].closed }
        assertEquals(first, app.status.value)
        assertEquals(1, transports.size)
    }

    /** Changes a session never saw are not deaths: the first connection is stamped at its dial. */
    @Test fun changesBeforeConnectingAreNotADeath() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        network.lose("wifi")
        network.switchTo("cell")
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        assertSettled("changes before the dial are not a death") { !transports[0].closed }
        assertEquals(1, transports.size)
    }

    /** A switch while the first connection is still handshaking, its network staying up, is
     *  nothing. */
    @Test fun aSwitchDuringTheFirstHandshakeLeavesTheLinkAlone() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val release = CompletableDeferred<Unit>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> release.await() }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the handshake is in flight") { transports.size == 1 && transports[0].listener != null }

        network.switchTo("cell")
        release.complete(Unit)

        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        assertSettled("the change during the handshake was not a loss") { !transports[0].closed }
    }

    /**
     * A loss while the first connection is still handshaking is caught once it is wired, the
     * collector filtering on the connection's own stamp, and ends as the connect failing: the first
     * connection is never retried, and a network that is gone is a failed connect.
     */
    @Test fun aLossDuringTheFirstHandshakeIsTheConnectFailing() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val release = CompletableDeferred<Unit>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> release.await() }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("the handshake is in flight") { transports.size == 1 && transports[0].listener != null }

        network.lose("wifi")
        release.complete(Unit)

        assertSettled("the link is closed as soon as it is wired") { transports[0].closed }
        transports[0].listener!!.onClosed(IOException("closed"))
        assertSettled("error") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("never retried", 1, transports.size)
    }

    /** The replacement is stamped with its own network, and closed when that one goes. */
    @Test fun losingTheReplacementsNetworkClosesItToo() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch()
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))   // an ordinary death
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)

        network.lose("wifi")

        assertSettled("the replacement is closed on the loss") { transports[1].closed }
        transports[1].listener!!.onClosed(IOException("closed"))
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        assertEquals(3, connected(app).sessionId)
    }

    /** No default to stamp a connection with means any change is taken as its loss. */
    @Test fun aLinkDialedWithNoDefaultIsClosedOnAnyChange() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val network = FakeNetworkWatch(default = null)
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = {}, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

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
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = { waits += it }, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)

        network.lose("wifi")

        assertSettled("the replacement is closed on the loss") { transports[1].closed }
        transports[1].listener!!.onClosed(IOException("closed"))
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        assertEquals(3, connected(app).sessionId)
        assertEquals(listOf(Duration.ZERO, Duration.ZERO), waits.toList())
    }

    /** A change during a backoff wait ends the wait, and the attempt after it is 0. */
    @Test fun aNetworkChangeCutsTheBackoffShort() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val waits = CopyOnWriteArrayList<Duration>()
        val network = FakeNetworkWatch()
        var refuse = false
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, networkWatch = network,
            context = dispatcher, blocking = dispatcher,
            // Real waits never return here: only the change can end one.
            sleep = { d -> waits += d; if (d > Duration.ZERO) awaitCancellation() },
        ) { FakeControlTransport { _, _ -> if (refuse) throw IOException("refused") }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(app)
        refuse = true
        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("attempt 0 refused, attempt 1 waiting") { waits.toList() == listOf(0.seconds, 1.seconds) }
        assertEquals("nothing moves while the wait holds", 2, transports.size)

        refuse = false
        elapse(2.seconds)   // past the floor: a change inside a second of the dial keeps the rung
        network.switchTo("cell")

        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(2))
        assertEquals(SessionStatus.Connected(first.gen, 2), connected(app))
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, sleep = { waits += it }, networkWatch = network,
            context = dispatcher, blocking = dispatcher,
        ) {
            val hangs = transports.size == 1   // the first replacement blocks until closed, like a dead interface
            val holder = arrayOfNulls<FakeControlTransport>(1)
            FakeControlTransport { _, _ ->
                if (hangs) { while (!holder[0]!!.closed) delay(10); throw IOException("aborted") }
            }.also { holder[0] = it; transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("the attempt is in flight") { transports.size == 2 && transports[1].listener != null }
        elapse(2.seconds)

        network.switchTo("cell")
        elapse(10.milliseconds)   // the fake transport's own poll loop notices the close and aborts

        assertSettled("the attempt in flight is abandoned") { transports[1].closed }
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(2))
        assertEquals(SessionStatus.Connected(first.gen, 2), connected(app))
        assertEquals("attempt 0 again on the new network", listOf(Duration.ZERO, Duration.ZERO), waits.toList())
    }

    /** The floor: a change inside a second of the last dial keeps the rung, so a flapping
     *  default cannot turn the backoff into a connect storm. */
    @Test fun aChangeRightAfterADialKeepsTheRung() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val waits = CopyOnWriteArrayList<Duration>()
        val network = FakeNetworkWatch()
        val app = own(AppStateImpl(
            InMemoryPinStore(), sleep = { waits += it }, networkWatch = network,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            val hangs = transports.size == 1
            val holder = arrayOfNulls<FakeControlTransport>(1)
            FakeControlTransport { _, _ ->
                if (hangs) { while (!holder[0]!!.closed) delay(10); throw IOException("aborted") }
            }.also { holder[0] = it; transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("the attempt is in flight") { transports.size == 2 && transports[1].listener != null }

        network.switchTo("cell")   // the clock has not moved since the dial
        elapse(10.milliseconds)   // the fake transport's own poll loop notices the close and aborts

        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, sleep = { }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))

        startedTransportAt(transports, 1).listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.UsernameInUse))
        transportAt(transports, 2)   // retried
        assertTrue("still reconnecting after a UsernameInUse", app.status.value is SessionStatus.Reconnecting)

        transports[2].listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.WrongServerPW))
        assertSettled("error") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.WRONG_SERVER_PASSWORD, err.kind)
        assertSettled("giving up ends the call") { call.ends == 1 }
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
            sleep = { d -> waits += d; delay(d) },
        ) {
            // The first transport connects; every replacement is refused.
            val first = transports.isEmpty()
            FakeControlTransport { _, _ -> if (!first) throw IOException("refused") }.also { transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))

        // 0+1+2+4+8+16+30+30+29 = 120 s: the ninth 30 s wait would overrun, so it is clamped to
        // the 29 s left and spent on one last attempt, which lands exactly on the deadline.
        elapse(120.seconds)
        assertSettled("gave up") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.DISCONNECTED, err.kind)
        assertEquals("could not get back to the server", err.detail)
        assertEquals(listOf(0, 1, 2, 4, 8, 16, 30, 30, 29).map { it.seconds }, waits.toList())
        assertSettled("giving up ends the call") { call.ends == 1 }
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
            sleep = { d -> waits += d; delay(d) },
        ) {
            val reachable = transports.isEmpty() || spent() >= 120.seconds
            FakeControlTransport { _, _ -> if (!reachable) throw IOException("refused") }
                .also { transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))

        elapse(121.seconds)   // the whole ladder, refused at every rung until the budget is spent
        assertSettled("an attempt is made after the ladder would have given up") {
            spent() >= 120.seconds && transports.size >= 9 &&
                transports.last().sent.any { it.first == TcpMessageType.Version }
        }
        transports.last().listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
        assertEquals("the call must survive the whole outage", 0, call.ends)
    }

    /**
     * The budget is two minutes of reconnecting, not two minutes of wall clock: a path that comes
     * up for a while and dies again short of healthy would otherwise spend the deadline while the
     * user was connected and talking, and then be told it had been reconnecting for two minutes.
     */
    @Test fun timeSpentConnectedIsGivenBackToTheDeadline() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
            // Long enough that the ping ticker never ends a connection during a 29 s connected
            // stretch; the connections here die by the resets below, not by ping age.
            pingIntervalMs = 3_600_000L,
        ) {
            // Two replacements come up and die again short of healthy; everything after is refused.
            val refuse = transports.size >= 3
            FakeControlTransport { _, _ -> if (refuse) throw IOException("refused") }.also { transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].listener!!.onClosed(IOException("reset"))
        for ((rung, session) in listOf(0.seconds to 2, 1.seconds to 3)) {
            elapse(rung)   // short of healthy, each loss is another rung of the same outage
            startedTransportAt(transports, session - 1).listener!!.onFrame(serverSync(session))
            assertEquals(session, connected(app).sessionId)
            elapse(29.seconds)                     // connected, and short of healthy either way
            assertEquals("nothing else ended the link", session, transports.size)
            transports[session - 1].listener!!.onClosed(IOException("reset"))
        }

        // Two minutes of reconnecting on top of the 58 s spent connected: the give-up is at 178 s.
        elapse(118.seconds)   // t = 177: the clamped last wait is still running
        assertSettled("still inside the given-back budget") { app.status.value is SessionStatus.Reconnecting }
        elapse(1.seconds)     // t = 178: the last attempt is refused and the budget is spent
        assertSettled("gave up") { app.status.value is SessionStatus.Error }
    }

    /**
     * Our own ghost is reaped inside the window; past it the name belongs to someone else, and the
     * server's reason is the one thing worth telling the user — not a timeout two minutes later.
     */
    @Test fun aNameStillHeldPastTheGhostWindowIsFinal() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))

        // Every replacement is rejected as it dials, so the clock moves only in the ladder's own
        // waits, with no transport open to meet the 15 s handshake deadline. The rungs land the
        // sixth rejection at 31 s, inside the 45 s window, and the seventh at 61 s, past it.
        val rungs = listOf(0, 1, 2, 4, 8, 16, 30).map { it.seconds }
        for (i in 1..7) {
            elapse(rungs[i - 1])
            startedTransportAt(transports, i).listener!!.onFrame(reject(MumbleProtos.Reject.RejectType.UsernameInUse))
        }

        assertSettled("final rejection") { app.status.value is SessionStatus.Error }
        val err = app.status.value as SessionStatus.Error
        assertEquals(ErrorKind.USERNAME_TAKEN, err.kind)
        assertEquals("no attempt after the name proved to be someone else's", 8, transports.size)
    }

    /** A connection that stayed synchronized 30 s was a working path; its loss is a new outage, not
     *  another failure of the one being retried, so the ladder starts over. */
    @Test fun losingAHealthyLinkStartsTheLadderOver() = deterministic {
        val waits = CopyOnWriteArrayList<Duration>()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
            // Long enough that the ping ticker never ages out the 31 s "healthy" wait below; this
            // test is about the reconnect ladder, not the ping ticker.
            pingIntervalMs = 3_600_000L,
            sleep = { d -> waits += d; delay(d) },
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onClosed(IOException("reset"))          // unhealthy: opens the incident at rung 0
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
        transports[1].listener!!.onClosed(IOException("reset"))          // unhealthy again: rung 1
        elapse(1.seconds)   // rung 1's own wait
        startedTransportAt(transports, 2).listener!!.onFrame(serverSync(3))
        assertEquals(3, connected(app).sessionId)

        // the third connection becomes healthy
        elapse(31.seconds)
        transports[2].listener!!.onClosed(IOException("reset"))
        transportAt(transports, 3)

        assertEquals(listOf(0.seconds, 1.seconds, 0.seconds), waits.toList())
    }

    /** The log is the session's: what the old connection received stays, by identity, and the new
     *  connection's messages append to it. */
    @Test fun chatSurvivesAReconnect() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onFrame(textFrom(9, "before"))
        assertSettled("first message") { app.messages.value.isNotEmpty() }
        val before = app.messages.value.single()

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
        transports[1].listener!!.onFrame(textFrom(9, "after"))

        assertSettled("both messages") { app.messages.value.size == 2 }
        val log = app.messages.value
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        app.setMuted(true)
        assertEquals("the first link carries the mute", listOf(true), transports[0].selfMutes())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)

        assertSettled("the replacement must be told the session is muted") {
            transports[1].selfMutes() == listOf(true)
        }
        app.setMuted(false)
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        app.setSelfDeaf(true)
        assertEquals("the first link carries the deafen", listOf(true to true), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)

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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        app.setMuted(true)
        app.setSelfDeaf(true)

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
        assertSettled("the replacement must be told both") { transports[1].selfStates() == listOf(true to true) }

        app.setSelfDeaf(false)
        assertSettled("undeafening keeps the mute the user set") {
            transports[1].selfStates() == listOf(true to true, false to true)
        }
    }

    /**
     * A tap inside the outage reaches no live connection at all: the dead connection's machine
     * refuses to send and keeps its old state. The session is what remembers the ask, so the
     * replacement is told what the user last wanted — an unmute as much as a mute, or the swap
     * would quietly put the microphone back where the user had just taken it from.
     */
    @Test fun anUnmuteDuringTheOutageIsCarriedNotReverted() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        app.setMuted(true)
        assertEquals(listOf(false to true), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("reconnecting") { app.status.value is SessionStatus.Reconnecting }
        app.setMuted(false)                       // nothing live to carry it

        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
        assertEquals("the replacement must not be muted again", emptyList<Pair<Boolean, Boolean>>(), transports[1].selfStates())
    }

    /** The same the other way: a deafen taken during the outage reaches the replacement. */
    @Test fun aDeafenDuringTheOutageReachesTheReplacement() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].listener!!.onClosed(IOException("reset"))
        assertSettled("reconnecting") { app.status.value is SessionStatus.Reconnecting }
        app.setSelfDeaf(true)

        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
        assertSettled("the replacement must be told the session is deafened") {
            transports[1].selfStates() == listOf(true to true)
        }
    }

    /**
     * The swap publishes the replacement's own tree before the driver publishes its Connected, so
     * the UI never reads the new session id against the dead connection's tree — where our own row
     * is missing and a mute on it would read as gone.
     */
    @Test fun theReplacementsTreeIsPublishedBeforeItsConnected() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        fun ourRow(session: Int) = TcpFrame(
            TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(session).setChannelId(0).setSelfMute(true)
                .build().toByteArray(),
        )
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(ourRow(1))
        transports[0].listener!!.onFrame(serverSync(1))
        connected(app)

        // Unconfined, the collector runs inside the publish itself: a row seeded a task later
        // would be missing here, where a collector that ran afterwards would see it.
        var rowAtConnected: User? = null
        scope.backgroundScope.launch(Dispatchers.Unconfined) {
            app.status.collect {
                if (it is SessionStatus.Connected && it.sessionId == 2) rowAtConnected = app.channelTree.value.users[2]
            }
        }

        transports[0].listener!!.onClosed(IOException("reset"))
        // The replacement's own handshake: its rows land before its ServerSync, as murmur sends them.
        startedTransportAt(transports, 1).listener!!.onFrame(ourRow(2))
        transports[1].listener!!.onFrame(serverSync(2))

        assertEquals(2, connected(app).sessionId)
        assertNotNull("our row must be readable the moment Connected lands", rowAtConnected)
        assertTrue("and carry what the replacement was told", rowAtConnected!!.selfMute)
    }

    /**
     * A mute tapped while the socket is already gone reaches no server, so the row that freezes at
     * the connection's death still reads unmuted. The gate is shut all the same, and it is the gate
     * the replacement is told from: seeded back from that echo, the user would come back with the
     * server, the row and the control saying unmuted while nothing left the device.
     */
    @Test fun aMuteTheDyingLinkNeverCarriedIsStillWhatTheReplacementIsTold() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        fun ourRow(session: Int) = TcpFrame(
            TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(session).setChannelId(0).build().toByteArray(),
        )
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(ourRow(1))
        transports[0].listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].close()                      // the socket is gone; the state machine has yet to hear
        app.setMuted(true)
        assertEquals("the wire refused it", emptyList<Pair<Boolean, Boolean>>(), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)
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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        app.setSelfDeaf(true)
        app.setSelfDeaf(false)
        app.setSelfDeaf(false)

        val undeafens = transports[0].selfStates().drop(1)
        assertEquals(2, undeafens.size)
        undeafens.forEach { assertEquals("every undeafen clears both", false to false, it) }
    }

    /** The same break from the other side: a repeated deafen must not take its own first ask's
     *  mute for the user's, or the undeafen keeps a mute the user never set. */
    @Test fun deafenTappedTwiceStillUnmutesOnUndeafen() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        app.setSelfDeaf(true)
        app.setSelfDeaf(true)
        app.setSelfDeaf(false)

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
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].close()
        app.setMuted(true)
        app.setSelfDeaf(true)
        app.setSelfDeaf(false)
        assertEquals("nothing reached the wire", emptyList<Pair<Boolean, Boolean>>(), transports[0].selfStates())

        transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(2, connected(app).sessionId)

        assertSettled("the replacement is told the mute, and only the mute") {
            transports[1].selfStates() == listOf(false to true)
        }
    }

    /**
     * The server's certificate changed while we were rebuilding the connection. The prompt is the
     * one a fresh connect gives: the session is retired behind it — no microphone opens against it,
     * and the call ends — but kept aside, so accepting the new pin reconnects.
     */
    @Test fun aTrustPromptOnAReconnectRetiresTheSessionAndCanStillBeAccepted() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val pins = InMemoryPinStore()
        val endpoint = MumbleEndpoint.parse("localhost")
        pins.put(endpoint.address, "aa")
        val app = own(AppStateImpl(
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
        app.connect(endpoint, "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].listener!!.onClosed(IOException("reset"))

        assertSettled("pin mismatch") { app.status.value == SessionStatus.PinMismatch("aa", "bb") }
        assertSettled("the prompt must end the call") { call.ends == 1 }
        assertSettled("the dead link and its refused replacement are both closed") {
            transports.size == 2 && transports.all { it.closed }
        }

        app.requestCapture()
        assertSettled("no microphone may open while a prompt is up") { handles.isEmpty() }

        app.trustAndConnect()

        assertSettled("accepting the new certificate reconnects") { transports.size == 3 }
        assertEquals("bb", pins.get(endpoint.address))
    }

    /** A connection whose pings go unanswered is replaced, not waited out: on a dead path the
     *  socket may never say so. */
    @Test fun anUnresponsiveLinkIsReplaced() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        val first = connected(app)

        elapse(16.seconds)   // three ping intervals unanswered; the third tick ends the connection

        assertEquals(SessionStatus.Reconnecting(first.gen, 1), app.status.value)
        assertTrue("the unresponsive link is closed", transports[0].closed)
        startedTransportAt(transports, 1).listener!!.onFrame(serverSync(2))
        assertEquals(SessionStatus.Connected(first.gen, 2), connected(app))
        assertEquals("the call rides through", 0, call.ends)
    }

    /** The gate follows what the session asks for: a deafen shuts it, deafen forcing a mute, and
     *  the undeafen that lifts that mute reopens it. */
    @Test fun theGateFollowsADeafen() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } },
            startPump = startPump, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        app.requestCapture()   // voice activity is the default mode, so the gate opens with the engine
        assertSettled("the gate opens") { handles.size == 1 && handles[0].gateOpen }

        app.setSelfDeaf(true)
        assertSettled("deafened is muted, so the gate shuts") { !handles[0].gateOpen }
        app.setSelfDeaf(false)
        assertSettled("the undeafen lifts the mute it set, and the gate with it") { handles[0].gateOpen }
    }

    /** A mute tapped while deafened, inside the echo window where the control still reads
     *  unmuted, is the user's own: the undeafen keeps it on the wire, and the gate shut with it. */
    @Test fun aMuteTappedWhileDeafenedOutlivesTheUndeafen() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } },
            startPump = startPump, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        app.requestCapture()
        assertSettled("the gate opens") { handles.size == 1 && handles[0].gateOpen }

        app.setSelfDeaf(true)
        app.setMuted(true)
        app.setSelfDeaf(false)

        assertSettled("undeafened on the wire, and still muted") { transports[0].selfStates().lastOrNull() == (false to true) }
        assertSettled("and the gate agrees") { !handles[0].gateOpen }
    }

    /**
     * A session that dies on its own reaches no disconnect() and supersedes no prior session, so
     * it has to stop its own receiver: left running, the playback thread wakes at 100 Hz with an
     * open AudioTrack for as long as the error screen stays up.
     */
    @Test fun aFailedSessionReleasesTheReceiver() = deterministic {
        lateinit var fake: FakeControlTransport
        val playout = FakePlayoutEngine()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newPlayout = { playout }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("handshaking") { app.status.value is SessionStatus.Handshaking }

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
        assertSettled("speaking") { app.speakingSessions.value.isNotEmpty() }

        // The server rejects the login: a terminal state nobody asked for.
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))
        assertSettled("rejected") { app.status.value is SessionStatus.Error }
        assertEquals(ErrorKind.AUTH_REJECTED, (app.status.value as SessionStatus.Error).kind)

        assertSettled("a failed session must destroy the playout engine") { playout.destroyed }
        // The error must survive the teardown — it is what the user is looking at.
        assertTrue("retire() must not reset the terminal status", app.status.value is SessionStatus.Error)
    }

    @Test fun speakingSessionsPopulateThenClearOnDisconnect() = deterministic {
        lateinit var fake: FakeControlTransport
        val playout = FakePlayoutEngine()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newPlayout = { playout }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("handshaking") { app.status.value is SessionStatus.Handshaking }

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
        assertSettled("speaking") { app.speakingSessions.value == setOf(9) }
        assertTrue("a live speaker must start the stream", playout.started)

        app.disconnect()
        assertEquals("disconnect() zeroes the flow under the lock", emptySet<Int>(), app.speakingSessions.value)
        // The assertion above alone is vacuous as a lifecycle test: disconnect() writes the flow
        // synchronously and closing the publisher blocks any later receiver-originated write, so
        // deleting the stop() from Session.close() would still pass it. This is what actually
        // proves the receiver was released.
        assertSettled("teardown must destroy the playout engine") { playout.destroyed }
    }

    // Voice is additive: a socket that cannot be opened must cost the session nothing but UDP.
    @Test fun aSocketThatCannotOpenLeavesTheSessionHealthyAndTunneled() = deterministic {
        val playout = FakePlayoutEngine()
        lateinit var fake: FakeControlTransport
        val app = own(AppStateImpl(
            InMemoryPinStore(), newPlayout = { playout }, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            // Unresolved, so DatagramChannel.connect refuses it locally: no DNS, no network.
            FakeControlTransport { _, _ -> }.apply { remote = InetSocketAddress.createUnresolved("nowhere.invalid", 1) }.also { fake = it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        assertSettled("handshaking") { app.status.value is SessionStatus.Handshaking }
        fake.listener!!.onFrame(keyExchange())
        fake.listener!!.onFrame(serverSync())
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UserState.id,
            MumbleProtos.UserState.newBuilder().setSession(1).setChannelId(0).build().toByteArray()))
        connected(app)
        assertSettled("the receiver's poll started") { playout.startAttempts.get() > 0 }

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, audioPacket(session = 9)))

        assertSettled("tunneled audio still reaches the engine") { playout.offered.isNotEmpty() }
        assertEquals(9, playout.offered.first().session)
    }

    // A driver that cannot be cancelled where it stands: each fake below calls disconnect() from
    // inside a call that returns without suspending, so the return is not a cancellation point
    // and the superseded driver runs on to its own live check, every run.
    // aDisconnectDuringTheHandshakeClosesTheLink and aDisconnectDuringThePinLookupCancelsTheDriver
    // pin the other half: cancelled at a suspension, the driver never gets there.

    @Test fun aSupersededHandshakeDoesNotClobberIdle() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        lateinit var app: AppStateImpl
        app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> app.disconnect() }.also { transports += it } })

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)

        assertSettled("the superseded link must be closed") { transports.single().closed }
        assertEquals("no clobber", SessionStatus.Idle, app.status.value)
        assertEquals(ChannelTree(), app.channelTree.value)
    }

    /**
     * connect() publishes the session synchronously, but its connection is built on the driver
     * after the pin lookup, so a disconnect() landing while that lookup is still in flight must
     * find the session already retired. No teardown can reach a connection that was never
     * published, so the driver's own live check after the lookup is what closes it.
     */
    @Test fun aDisconnectDuringThePinLookupClosesTheLinkItBuilt() = deterministic {
        lateinit var app: AppStateImpl
        val pins = object : PinStore {
            override suspend fun get(key: String): String? { app.disconnect(); return null }
            override suspend fun put(key: String, fingerprint: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val engines = AtomicInteger()
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        app = own(AppStateImpl(
            pins, newPlayout = { engines.incrementAndGet(); FakePlayoutEngine() },
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)

        assertSettled("a superseded session must close the transport it built") {
            transports.size == 1 && transports.single().closed
        }
        assertEquals("no receiver should ever start on this path", 0, engines.get())
    }

    /** Disconnecting mid-reconnect takes the replacement in flight with it, along with the session. */
    @Test fun disconnectWhileReconnectingClosesTheReplacement() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val call = FakeVoiceCall()
        lateinit var app: AppStateImpl
        app = own(AppStateImpl(
            InMemoryPinStore(), call = call, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) {
            val replacement = transports.isNotEmpty()
            FakeControlTransport { _, _ -> if (replacement) app.disconnect() }.also { transports += it }
        })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)

        transports[0].listener!!.onClosed(IOException("reset"))

        assertSettled("both links must be closed") { transports.size == 2 && transports.all { it.closed } }
        assertEquals(SessionStatus.Idle, app.status.value)
        assertEquals("the call ends once", 1, call.ends)
    }

    /**
     * The freeze: from its close, nothing the dead connection still reduces may reach the UI, or
     * the ghost kick's UserRemove would read as "you left". The close is what the guard reads, and
     * it lands before any replacement is swapped in.
     */
    @Test fun aFrameTheDeadLinkReducesAfterItsCloseNeverReachesTheTree() = deterministic {
        val transports = CopyOnWriteArrayList<FakeControlTransport>()
        val app = own(AppStateImpl(
            InMemoryPinStore(), udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(transports, 0).listener!!.onFrame(serverSync(1))
        connected(app)
        transports[0].listener!!.onFrame(TcpFrame(TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Root").build().toByteArray()))
        assertSettled("the tree") { app.channelTree.value.channels.containsKey(1) }

        transports[0].listener!!.onClosed(IOException("reset"))
        // The replacement connects, then waits in replaceConnection for a ServerSync this test
        // never sends: the dead connection is closed and nothing has been swapped in.
        startedTransportAt(transports, 1)
        assertTrue(app.status.value is SessionStatus.Reconnecting)
        assertTrue("the dead link must be closed", transports[0].closed)
        transports[0].listener!!.onFrame(TcpFrame(TcpMessageType.ChannelState.id,
            MumbleProtos.ChannelState.newBuilder().setChannelId(2).setName("Late").build().toByteArray()))

        assertSettled("a frozen link must not reach the tree") { !app.channelTree.value.channels.containsKey(2) }
    }

    /**
     * The whole transmit path end to end, since every piece of it is new wiring: the service comes
     * up before the engine is opened, the pump reaches the wire, the gate follows push-to-talk, and
     * teardown releases all three. Asserting on `sentRaw` rather than on the sender means the test
     * cannot pass with a pump that was built but never started.
     */
    @Test fun requestCaptureRunsTheSendPathAndDisconnectReleasesIt() = deterministic {
        lateinit var fake: FakeControlTransport
        val handle = FakeCaptureHandle(blocking = false)
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { handle }, call = call, startPump = startPump,
            captureClock = clock, udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), frameNumber = 7, terminator = false))
        app.requestCapture()

        assertSettled("the pump must reach the wire") { fake.sentRaw.isNotEmpty() }
        val (type, payload) = fake.sentRaw.first()
        assertEquals(TcpMessageType.UDPTunnel, type)
        // Leading 0 is the UDP audio type byte the tunnel carries ahead of the Audio protobuf.
        assertEquals(0, payload[0].toInt())

        app.setTransmitting(true)
        assertSettled("push-to-talk must open the gate") { handle.gateOpen }

        app.disconnect()
        assertSettled("teardown must destroy the engine") { handle.destroyed }
        assertTrue("teardown must stop the pump", handle.stopped)
        assertEquals("teardown must end the call", 1, call.ends)
    }

    /**
     * A hold tears the capture session down instead of merely closing the gate, and a resume builds
     * a fresh one. This is the design's substitute for gating the reopen backoff on focus state: proving
     * the rebuilt session reaches the wire is what makes the substitution honest, since a teardown
     * that could not come back would be worse than the backoff it replaced.
     */
    @Test fun holdTearsTheSessionDownAndResumeRebuildsIt() = deterministic {
        lateinit var fake: FakeControlTransport
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } },
            call = call, startPump = startPump, captureClock = clock,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { fake = it } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)

        app.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }
        handles[0].script(FakeCaptureHandle.Step.Frame(byteArrayOf(1), frameNumber = 1, terminator = false))
        assertSettled("the first session must reach the wire") { fake.sentRaw.size == 1 }

        call.hold()
        assertSettled("a hold must destroy the engine") { handles[0].destroyed }
        assertTrue("a hold must stop the pump", handles[0].stopped)

        call.resume()
        assertSettled("resuming must build a new engine") { handles.size == 2 }
        handles[1].script(FakeCaptureHandle.Step.Frame(byteArrayOf(2), frameNumber = 2, terminator = false))
        assertSettled("the rebuilt session must reach the wire") { fake.sentRaw.size == 2 }

        app.disconnect()
        assertSettled("disconnect must release the rebuilt engine") { handles[1].destroyed }
        assertEquals("disconnect must end the call", 1, call.ends)
    }

    /** The counters reach the flow from the live session's pump and leave with the session. */
    @Test fun theCaptureCountersFollowTheSession() = deterministic {
        val handle = FakeCaptureHandle(blocking = false)
        handle.stats = CaptureStats(
            encodedPackets = 0, encodeErrors = 0, encodeMicrosMean = 400, encodeMicrosMax = 900,
            ringOverruns = 0, skippedSamples = 0, streamOverruns = 0, framesPerBurst = 96,
            droppedFrames = 0, inputLatencyMillis = 12.0,
        )
        var opens = 0
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { opens++; handle }, call = FakeVoiceCall(),
            startPump = startPump, captureClock = clock,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
        ) { FakeControlTransport { _, _ -> } })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        handshaking(app)
        app.requestCapture()
        assertSettled("the engine must open") { opens == 1 }

        elapse(2.seconds)   // the pump's stats period; the poll after it reads the counters
        assertEquals(12.0, app.captureStats.value?.inputLatencyMillis)

        app.disconnect()
        assertSettled("teardown must clear the counters") { app.captureStats.value == null }
    }

    // ---- Real threads, by design. These run on runBlocking and the default dispatchers with
    // awaitOnRealThreads, because the scheduler cannot reach them:
    //   a real TLS server — firstContactAwaitsTrustThenPinsAndReachesHandshaking, below
    //   a loopback DatagramSocket and MumbleUdpTransport's reader thread — the `peer` tests in
    //     the UDP section
    // Every other test drives the scheduler and never calls awaitOnRealThreads.

    @Test fun firstContactAwaitsTrustThenPinsAndReachesHandshaking() = runBlocking {
        val srv = TestTlsServer()
        srv.start()
        try {
            val pins = InMemoryPinStore()
            val app = AppStateImpl(pins) { pin ->
                MumbleTcpTransport(
                    expectedPin = pin,
                    // First contact must be rejected by the authority path so MumbleTrustManager
                    // falls through to UntrustedCertificateException — never accept-everything.
                    trustDelegate = TestTlsServer.rejectingTrustManager(),
                    hostNameVerifier = HostnameVerifier { _, _ -> true },
                )
            }
            val endpoint = MumbleEndpoint.parse("localhost", srv.port)

            app.connect(endpoint, "user", null)

            val awaiting = withTimeout(5_000) {
                app.status.first { it is SessionStatus.AwaitingTrust }
            } as SessionStatus.AwaitingTrust
            assertEquals(sha256Hex(srv.leafCertificate.encoded), awaiting.fingerprint)

            app.trustAndConnect()

            withTimeout(5_000) { app.status.first { it is SessionStatus.Handshaking } }
            assertEquals(sha256Hex(srv.leafCertificate.encoded), pins.get(endpoint.address))

            app.disconnect()
        } finally {
            srv.close()
        }
    }

    // ---- the UDP voice socket ------------------------------------------------------------
    //
    // MumbleUdpTransportTest owns what the socket does. Here a FakeUdp stands in for it and the
    // tests pin the app state's wiring, on the scheduler. The tests with a `peer` run the real
    // socket end to end instead, against a loopback peer keyed as the server: our client_nonce is
    // its decrypt seed, its server_nonce ours.

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

    /** A connection's UDP socket: records what the app state asks of it, and keeps the listener the
     *  app state built, so the test can play the server's side. */
    private class FakeUdp(val crypt: CryptState, val listener: MumbleUdpTransport.Listener) : UdpTransport {
        var openedOn: InetSocketAddress? = null
        var closed = false
        /** Per ping sent, whether the cipher was keyed when it went. */
        val pings = mutableListOf<Boolean>()
        val sent = mutableListOf<ByteArray>()
        /** Refuses every datagram, as a socket whose route has gone does. */
        var refusing = false

        override fun open(address: InetSocketAddress) { openedOn = address }
        // MumbleUdpTransport.send's refusals as well, so no test sees voice leave where the real one refuses.
        override fun send(plaintext: ByteArray, len: Int): Boolean {
            if (openedOn == null || closed || !crypt.isValid() || refusing) return false
            sent += plaintext.copyOf(len)
            return true
        }
        override fun sendPing(): Boolean {
            pings += crypt.isValid()
            return true
        }
        override fun close() { closed = true }
    }

    private val serverAddress = InetSocketAddress.createUnresolved("mumble.invalid", 64738)

    // Each connection's control transport and UDP socket, in connection order, for the tests that
    // build their app state from the two factories below.
    private val controlTransports = mutableListOf<FakeControlTransport>()
    private val udps = mutableListOf<FakeUdp>()

    private val fakeUdps = { crypt: CryptState, listener: MumbleUdpTransport.Listener ->
        FakeUdp(crypt, listener).also { udps += it }
    }
    /** Aimed at [serverAddress], so every connection opens its socket. */
    private val aimedAtServer = { _: String? ->
        FakeControlTransport { _, _ -> }.apply { remote = serverAddress }.also { controlTransports += it }
    }

    private fun Rig.udpAppState(newPlayout: () -> VoiceReceiver.PlayoutEngine? = { null }) = own(AppStateImpl(
        InMemoryPinStore(), newPlayout = newPlayout, udpClock = clock, context = dispatcher, blocking = dispatcher,
        newUdp = fakeUdps, newTransport = aimedAtServer,
    ))

    /** A loopback UDP peer: records every plaintext it could open, and answers each datagram
     *  with whatever [reply] makes of that plaintext (null for none). */
    private class UdpPeer(private val crypt: CryptState, private val reply: (ByteArray?) -> ByteArray? = { null }) {
        val channel: DatagramChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val address get() = channel.localAddress as InetSocketAddress
        val opened = LinkedBlockingQueue<ByteArray>()
        init {
            thread(isDaemon = true) {
                val wire = ByteBuffer.allocate(2048)
                val plain = ByteArray(2048)
                try {
                    while (true) {
                        wire.clear()
                        val addr = channel.receive(wire) ?: break
                        val len = crypt.decrypt(wire.array(), wire.position(), plain)
                        val packet = if (len >= 0) plain.copyOf(len).also { opened.add(it) } else null
                        reply(packet)?.let { channel.send(ByteBuffer.wrap(it), addr) }
                    }
                } catch (_: Exception) {
                }
            }
        }
        fun close() = channel.close()
    }

    /** The readers alive right now, by identity: a test judges only the ones it started, so a
     *  reader an earlier test left dying, or leaked, cannot skew it. */
    private fun readers(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.name == "dumble-udp-recv" }.toSet()
    private fun readersSince(before: Set<Thread>) = (readers() - before).size

    private fun audioPacket(session: Int) = byteArrayOf(0) + MumbleUdpProtos.Audio.newBuilder()
        .setSenderSession(session).setOpusData(ByteString.copyFrom(byteArrayOf(1))).build().toByteArray()

    private suspend fun connectToHandshaking(app: AppStateImpl) {
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { app.status.first { it is SessionStatus.Handshaking } }
    }

    private fun serverSync() = TcpFrame(TcpMessageType.ServerSync.id,
        MumbleProtos.ServerSync.newBuilder().setSession(1).build().toByteArray())

    private fun fakeAimedAt(peer: UdpPeer, into: (FakeControlTransport) -> Unit) = { _: String? ->
        FakeControlTransport { _, _ -> }.apply { remote = peer.address }.also(into)
    }

    @Test fun keyingPingsTheServerOverUdp() = deterministic {
        val app = udpAppState()
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val fake = startedTransportAt(controlTransports, 0)
        val udp = udps.single()
        assertEquals("aimed at the control connection's own remote", serverAddress, udp.openedOn)
        assertEquals("no ping before the key", emptyList<Boolean>(), udp.pings)

        fake.listener!!.onFrame(keyExchange())

        assertEquals("keying pings at once, on the cipher it keyed", listOf(true), udp.pings)
    }

    // The reason the socket lands with receive wired: a listener who has never transmitted
    // gets their downlink over UDP from the first ping on, and would otherwise hear nothing.
    @Test fun inboundUdpAudioReachesThePlayoutEngine() = deterministic {
        val playout = FakePlayoutEngine()
        val app = udpAppState(newPlayout = { playout })
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(controlTransports, 0)
        assertSettled("the receiver's poll started") { playout.startAttempts.get() > 0 }

        // In a buffer longer than the packet, as the socket's is: the length bounds it.
        val packet = audioPacket(session = 9)
        udps.single().listener.onVoicePacket(packet + ByteArray(8), packet.size)

        assertEquals(9, playout.offered.single().session)
    }

    @Test fun aStalledDecryptAsksTheServerForItsCounter() = deterministic {
        val app = udpAppState()
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val fake = startedTransportAt(controlTransports, 0)

        udps.single().listener.requestCryptResync()

        val request = fake.sent.single { it.first == TcpMessageType.CryptSetup }.second
        assertEquals(MumbleProtos.CryptSetup.getDefaultInstance(), request)
    }

    @Test fun disconnectClosesTheUdpSocket() = runBlocking {
        val peer = UdpPeer(serverCrypt())
        val before = readers()
        val app = AppStateImpl(InMemoryPinStore(), newTransport = fakeAimedAt(peer) {})
        connectToHandshaking(app)
        awaitOnRealThreads("the socket opens with the connection") { readersSince(before) == 1 }

        app.disconnect()

        awaitOnRealThreads("disconnect must close the socket and end its reader") { readersSince(before) == 0 }
        peer.close()
    }

    @Test fun aSessionThatFailsOnItsOwnClosesTheUdpSocket() = deterministic {
        val app = udpAppState()
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val fake = startedTransportAt(controlTransports, 0)

        fake.listener!!.onFrame(TcpFrame(TcpMessageType.Reject.id,
            MumbleProtos.Reject.newBuilder().setReason("nope").build().toByteArray()))

        assertSettled("rejected") { app.status.value is SessionStatus.Error }
        assertTrue("a retired session must not leak its socket", udps.single().closed)
    }

    @Test fun aSupersededSessionClosesItsUdpSocket() = deterministic {
        val app = udpAppState()
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(controlTransports, 0)

        app.connect(MumbleEndpoint.parse("localhost"), "user", null)

        startedTransportAt(controlTransports, 1)
        assertEquals("the superseded session's socket closed, the live one's open", listOf(true, false), udps.map { it.closed })
        app.disconnect()
        assertSettled("and none after disconnect") { udps.all { it.closed } }
    }

    // ---- which transport carries our voice ------------------------------------------------

    /** A peer that answers every ping it can open, and records everything. */
    private fun answeringPeer(): UdpPeer {
        val server = serverCrypt()
        return UdpPeer(server) { plain ->
            if (plain == null || plain[0] != 1.toByte()) null
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
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val left = 5.seconds - started.elapsedNow()
            val packet = peer.opened.poll(left.inWholeMilliseconds, TimeUnit.MILLISECONDS) ?: return null
            if (packet[0] == 0.toByte()) return packet
        }
    }

    /** The path end to end: the keying ping's reply promotes, and the pump's next frame leaves on
     *  the datagram socket, never as a tunnel frame. */
    @Test fun anAnsweredPingPutsOurVoiceOnUdp() = runBlocking {
        val peer = answeringPeer()
        val handle = FakeCaptureHandle()
        lateinit var fake: FakeControlTransport
        val app = AppStateImpl(
            InMemoryPinStore(), newCapture = { handle }, udpClock = udpClock,
            newTransport = fakeAimedAt(peer) { fake = it },
        )
        connectToHandshaking(app)
        udpClock += 1.seconds
        fake.listener!!.onFrame(keyExchange())
        awaitOnRealThreads("the answered ping promotes") { app.voicePath.value.onUdp }
        assertNotNull("with its round trip", app.voicePath.value.roundTrip)

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), frameNumber = 7, terminator = false))
        app.requestCapture()

        val packet = awaitAudio(peer)
        assertNotNull("the frame must reach the peer over UDP", packet)
        assertArrayEquals(byteArrayOf(1, 2, 3), opusOf(packet!!))
        assertTrue("and never the tunnel", fake.sentRaw.none { it.first == TcpMessageType.UDPTunnel })
        app.disconnect()
        assertEquals("cleared with every other flow", VoicePath.State(), app.voicePath.value)
        peer.close()
    }

    /** What other clients read about our path: once a reply is accepted, the next TCP ping
     *  reports the UDP leg. */
    @Test fun theTcpPingReportsTheUdpLegOnceAReplyIsAccepted() = deterministic {
        val app = udpAppState()
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val fake = startedTransportAt(controlTransports, 0)
        udps.single().listener.onPingReply(20.milliseconds)
        fake.listener!!.onFrame(serverSync(1))   // the ping loop starts with the session

        elapse(ConnectionStateMachine.PING_INTERVAL_MS.milliseconds)

        val ping = fake.sent.last { it.first == TcpMessageType.Ping }.second as MumbleProtos.Ping
        assertEquals("the accepted reply, counted", 1, ping.udpPackets)
        assertEquals("and averaged", 20f, ping.udpPingAvg, 0f)
    }

    /**
     * Which transport carries our voice: silence demotes on the socket's report, with a tunneled
     * ping to pull the downlink back; the next frame goes through the tunnel; two replies bring
     * voice back to the socket; and a datagram the socket refuses goes through the tunnel in the
     * same call, and demotes.
     */
    @Test fun silenceMovesVoiceBackToTheTunnelAndRepliesBringItBack() = deterministic {
        val handle = FakeCaptureHandle(blocking = false)
        val app = own(AppStateImpl(
            InMemoryPinStore(), newCapture = { handle }, startPump = startPump, captureClock = clock,
            udpClock = clock, context = dispatcher, blocking = dispatcher,
            newUdp = fakeUdps, newTransport = aimedAtServer,
        ))
        app.connect(MumbleEndpoint.parse("localhost"), "user", null)
        val fake = startedTransportAt(controlTransports, 0)
        val udp = udps.single()
        fun tunneled() = fake.sentRaw.filter { it.first == TcpMessageType.UDPTunnel }.map { it.second[0] }
        fun speak() = handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(9), frameNumber = 1, terminator = false))
        fake.listener!!.onFrame(keyExchange())   // the socket refuses voice on an unkeyed cipher
        udp.listener.onPingReply(20.milliseconds)
        app.requestCapture()
        assertSettled("promoted") { app.voicePath.value.onUdp }

        udp.listener.onPingsUnanswered()

        assertSettled("the report demotes") { app.voicePath.value == VoicePath.State() }
        assertEquals("with a tunneled ping, so no peer hears a blip", listOf<Byte>(1), tunneled())
        speak()
        assertSettled("the next frame goes through the tunnel") { tunneled() == listOf<Byte>(1, 0) }

        udp.listener.onPingReply(20.milliseconds)
        assertSettled("one reply is not enough after a demote") { !app.voicePath.value.onUdp }
        udp.listener.onPingReply(20.milliseconds)
        assertSettled("two replies bring voice back") { app.voicePath.value.onUdp }
        speak()
        assertSettled("on the socket") { udp.sent.size == 1 }

        udp.refusing = true
        speak()
        assertSettled("a refused datagram goes through the tunnel in the same call") { tunneled() == listOf<Byte>(1, 0, 0) }
        assertFalse("and demotes", app.voicePath.value.onUdp)
    }
}
