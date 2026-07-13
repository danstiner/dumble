package me.danielstiner.dumble.mumble.integration

import me.danielstiner.dumble.mumble.model.MumbleModel
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

        fun shutdown() { voice.stop(); udp?.close(); sm.disconnectLocal() }
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
