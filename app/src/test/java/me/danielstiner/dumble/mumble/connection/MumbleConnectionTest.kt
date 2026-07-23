package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.TestTlsServer
import me.danielstiner.dumble.mumble.net.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
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
            assertEquals(sha256Hex(srv.leafCertificate.encoded), pins.get(endpoint.pinKey))

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
}
