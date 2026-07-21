package me.danielstiner.dumble.mumble.integration

import me.danielstiner.dumble.mumble.model.MumbleModel
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.net.*
import me.danielstiner.dumble.mumble.protocol.*
import me.danielstiner.dumble.mumble.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Live-server integration (USER-GATE). Skipped unless MUMBLE_TEST_SERVER is set, so it
 * never runs in the normal unit-test build. Point it at any Mumble server >= 1.5:
 *
 *   MUMBLE_TEST_SERVER=host [MUMBLE_TEST_PORT=64738] [MUMBLE_TEST_PASSWORD=pw] \
 *     ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.integration.LiveServerIntegrationTest"
 *
 * Uses the loopback voice target (31): the server echoes our synthetic frames back to us
 * only — nothing is forwarded to the channel, so other users on a shared server are undisturbed.
 */
class LiveServerIntegrationTest {
    private val host: String? = System.getenv("MUMBLE_TEST_SERVER")
    private val port: Int = System.getenv("MUMBLE_TEST_PORT")?.toIntOrNull() ?: 64738
    private val password: String? = System.getenv("MUMBLE_TEST_PASSWORD")

    @Before fun requiresServer() = assumeTrue("set MUMBLE_TEST_SERVER to run", host != null)

    private class Harness(
        val host: String,
        val port: Int,
        val password: String?,
        forceTcp: Boolean,
    ) {
        val crypt = CryptState()
        val model = MumbleModel()
        val tcp = MumbleTcpTransport(InMemoryPinStore())
        val selector = TransportSelector(forceTcp)
        val synthetic = SyntheticVoiceSource()
        @Volatile var udp: MumbleUdpTransport? = null
        lateinit var sm: SessionStateMachine
        lateinit var voice: VoiceTransport
        // Probe hooks (UserStats verification): spy on inbound control frames + note server-initiated close.
        @Volatile var frameSpy: ((TcpFrame) -> Unit)? = null
        @Volatile var closed = false
        @Volatile var closedCause: Throwable? = null

        init {
            voice = VoiceTransport(
                engine = synthetic,
                modeProvider = { selector.mode },
                udpSend = { b, n -> udp?.send(b, n) ?: false },
                tunnelSend = { b, n -> tcp.sendRaw(TcpMessageType.UDPTunnel, b, n) },
                onUdpPing = { ts, ar -> selector.onUdpPong((ar - ts) / 1e6) },
            )
            sm = SessionStateMachine(tcp, model, crypt, object : SessionStateMachine.Events {
                override fun onCryptReady() {
                    udp = MumbleUdpTransport(crypt, object : MumbleUdpTransport.Listener {
                        override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) =
                            voice.onPlaintext(buf, len, arrivalNanos)
                        override fun onUdpError(e: Exception) {}
                        override fun requestCryptResync() = sm.requestCryptResync()
                    }).also { it.connect(host, port) }
                    voice.start()
                }
                override fun onTcpRtt(rttMs: Double) = selector.onTcpRtt(rttMs)
                override fun onTunneledVoice(p: ByteArray, len: Int, ar: Long) = voice.onPlaintext(p, len, ar)
                override fun onTextMessage(actor: Int, message: String) {}
            })
        }

        suspend fun run(username: String, label: String) {
            tcp.connect(host, port, object : MumbleTcpTransport.Listener {
                override fun onFrame(frame: TcpFrame) = sm.onFrame(frame)
                override fun onClosed(cause: Throwable?) {}
            })
            sm.start(username, password)
            withTimeout(10_000) { sm.state.first { it is ConnectionState.Synchronized } }
            assertTrue("channel tree should be non-empty after ServerSync",
                model.state.value.channels.isNotEmpty())
            // Drive a few control pings so the selector has stats to evaluate.
            repeat(3) { sm.sendPing(); delay(1_000) }
            // Wait for the loopback echo to return enough frames to measure.
            withTimeout(20_000) { synthetic.stats.first { it.received >= 100 } }
            val st = synthetic.stats.value
            println("LOOPBACK[$label] mode=${selector.mode} $st")
            assertTrue("avg RTT sane: ${st.avgRttMs}", st.avgRttMs in 0.0..250.0)
        }

        /** Control-channel connect + wait for Synchronized (no voice loopback). For the UserStats probe. */
        suspend fun connectAndSync(username: String) {
            tcp.connect(host, port, object : MumbleTcpTransport.Listener {
                override fun onFrame(frame: TcpFrame) { frameSpy?.invoke(frame); sm.onFrame(frame) }
                override fun onClosed(cause: Throwable?) { closed = true; closedCause = cause }
            })
            sm.start(username, password)
            withTimeout(10_000) { sm.state.first { it is ConnectionState.Synchronized } }
        }

        fun shutdown() { voice.stop(); udp?.close(); sm.disconnectLocal() }
    }

    /**
     * PROBE (spec "Open verification"): does a NON-admin client receive another user's ping via
     * UserStats, and does per-user polling trip Murmur's flood protection? Answers both against a real
     * server. Not a pass/fail feature test — it prints its findings; the assert only guards Q1.
     */
    @Test fun userStatsProbe() = runBlocking {
        val a = Harness(host!!, port, password, forceTcp = false)
        val b = Harness(host!!, port, password, forceTcp = false)
        val responses = java.util.concurrent.CopyOnWriteArrayList<MumbleProtos.UserStats>()
        a.frameSpy = { frame ->
            if (frame.type == TcpMessageType.UserStats.id)
                responses.add(MumbleProtos.UserStats.parseFrom(frame.payload))
        }
        try {
            a.connectAndSync("drumble-probe-a")
            b.connectAndSync("drumble-probe-b")
            // Drive pings on both, self-reporting the measured RTT (the real fix path) so the server
            // accrues each client's ping — Q1 then shows a real non-zero peer ping end-to-end.
            repeat(5) {
                val at = a.selector.stats.value; val bt = b.selector.stats.value
                a.sm.sendPing(at.tcpRttMs.takeIf { it >= 0 }?.toFloat(), at.udpRttMs.takeIf { it >= 0 }?.toFloat())
                b.sm.sendPing(bt.tcpRttMs.takeIf { it >= 0 }?.toFloat(), bt.udpRttMs.takeIf { it >= 0 }?.toFloat())
                delay(1_000)
            }

            val aSelf = a.model.state.value.sessionId
            val bUser = a.model.state.value.users.values.firstOrNull { it.name == "drumble-probe-b" }
                ?: a.model.state.value.users.values.firstOrNull { it.session != aSelf }
            assertNotNull("A must see B in its user list: names=${a.model.state.value.users.values.map { it.name }}", bUser)
            val bSession = bUser!!.session
            val req = MumbleProtos.UserStats.newBuilder().setSession(bSession).setStatsOnly(true).build()
            val reqBytes = req.toByteArray()

            // --- Q1: non-admin requests a PEER's stats_only UserStats ---
            responses.clear()
            assertTrue("sendRaw(UserStats) accepted", a.tcp.sendRaw(TcpMessageType.UserStats, reqBytes, reqBytes.size))
            val resp = withTimeoutOrNull(5_000) {
                var r: MumbleProtos.UserStats? = null
                while (r == null) { r = responses.firstOrNull { it.session == bSession }; if (r == null) delay(100) }
                r
            }
            println("USERSTATS-PROBE Q1 self=$aSelf bSession=$bSession gotReply=${resp != null}")
            if (resp != null) println("USERSTATS-PROBE Q1 statsOnly=${resp.statsOnly} " +
                "hasTcpPing=${resp.hasTcpPingAvg()} tcpPingAvg=${resp.tcpPingAvg} " +
                "hasUdpPing=${resp.hasUdpPingAvg()} udpPingAvg=${resp.udpPingAvg} " +
                "hasFromClient=${resp.hasFromClient()} hasCerts=${resp.certificatesCount > 0}")

            // --- Q2: flood — hammer requests at the peer, watch for throttle/disconnect ---
            val burst = 60
            responses.clear()
            repeat(burst) { a.tcp.sendRaw(TcpMessageType.UserStats, reqBytes, reqBytes.size) }
            delay(3_000)
            println("USERSTATS-PROBE Q2 afterBurst=$burst aClosed=${a.closed} cause=${a.closedCause} " +
                "state=${a.sm.state.value::class.simpleName} repliesToBurst=${responses.size}")

            // --- Q3: prove the mechanism — server echoes each client's SELF-REPORTED ping ---
            // B self-reports a sentinel tcp/udp ping in a raw Ping; A re-queries B's UserStats.
            val ping = MumbleProtos.Ping.newBuilder()
                .setTimestamp(System.nanoTime()).setTcpPingAvg(42.0f).setUdpPingAvg(43.0f).build()
            val pingBytes = ping.toByteArray()
            b.tcp.sendRaw(TcpMessageType.Ping, pingBytes, pingBytes.size)
            delay(1_500)
            responses.clear()
            a.tcp.sendRaw(TcpMessageType.UserStats, reqBytes, reqBytes.size)
            val resp3 = withTimeoutOrNull(5_000) {
                var r: MumbleProtos.UserStats? = null
                while (r == null) { r = responses.firstOrNull { it.session == bSession }; if (r == null) delay(100) }
                r
            }
            println("USERSTATS-PROBE Q3 selfReport bSent(tcp=42.0,udp=43.0) -> peerSees " +
                "tcp=${resp3?.tcpPingAvg} udp=${resp3?.udpPingAvg}")

            assertNotNull("Q1: non-admin client should receive a UserStats reply for a peer", resp)
        } finally { a.shutdown(); b.shutdown() }
    }

    @Test fun textMessageProbe() = runBlocking {
        val a = Harness(host!!, port, password, forceTcp = false)
        val b = Harness(host!!, port, password, forceTcp = false)
        val aGot = java.util.concurrent.CopyOnWriteArrayList<MumbleProtos.TextMessage>()
        val bGot = java.util.concurrent.CopyOnWriteArrayList<MumbleProtos.TextMessage>()
        a.frameSpy = { f -> if (f.type == TcpMessageType.TextMessage.id) aGot.add(MumbleProtos.TextMessage.parseFrom(f.payload)) }
        b.frameSpy = { f -> if (f.type == TcpMessageType.TextMessage.id) bGot.add(MumbleProtos.TextMessage.parseFrom(f.payload)) }
        try {
            a.connectAndSync("chat-probe-a")
            b.connectAndSync("chat-probe-b")
            delay(500)
            val aSelf = a.model.state.value.sessionId!!
            val aChannel = a.model.state.value.users[aSelf]!!.channelId
            val msg = MumbleProtos.TextMessage.newBuilder().addChannelId(aChannel).setMessage("hello from a").build()
            aGot.clear(); bGot.clear()
            a.tcp.sendRaw(TcpMessageType.TextMessage, msg.toByteArray(), msg.toByteArray().size)
            delay(2_000)
            val bMsg = bGot.firstOrNull { it.message == "hello from a" }
            println("CHAT-PROBE aChannel=$aChannel aSelf=$aSelf bReceived=${bMsg != null} " +
                "bActor=${bMsg?.actor} echoToSender=${aGot.any { it.message == "hello from a" }}")
            assertNotNull("B (same channel) must receive A's message", bMsg)
        } finally { a.shutdown(); b.shutdown() }
    }

    @Test fun udpLoopback() = runBlocking {
        val h = Harness(host!!, port, password, forceTcp = false)
        try {
            h.run("drumble-it-udp", "udp")
            assertEquals(VoiceTransportMode.UDP, h.selector.mode)
        } finally { h.shutdown() }
    }

    @Test fun forcedTcpTunnelLoopback() = runBlocking {
        val h = Harness(host!!, port, password, forceTcp = true)
        try {
            h.run("drumble-it-tcp", "tcp-tunnel")
            assertEquals(VoiceTransportMode.TCP_TUNNEL, h.selector.mode)
        } finally { h.shutdown() }
    }
}
