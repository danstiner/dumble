package me.danielstiner.dumble.mumble.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.pinKey
import me.danielstiner.dumble.mumble.net.sha256Hex
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
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
        val pins = InMemoryPinStore().apply { put(pinKey(target, port), fingerprint) }

        val transport = MumbleTcpTransport(expectedPin = pins.get(pinKey(target, port)))
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
                transport.connect(host, port, object : MumbleTcpTransport.Listener {
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
