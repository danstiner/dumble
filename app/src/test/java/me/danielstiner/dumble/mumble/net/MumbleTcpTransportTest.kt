package me.danielstiner.dumble.mumble.net

import kotlinx.coroutines.runBlocking
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class MumbleTcpTransportTest {

    private var server: TestTlsServer? = null

    // Kotlin rejects @Volatile on a local var, so the cross-thread flags for
    // listenerCallbacksNeverOverlap live here instead.
    @Volatile private var overlapped = false
    @Volatile private var inOnFrame = false

    @After fun tearDown() { server?.close() }

    private fun startServer(): TestTlsServer = TestTlsServer().also { server = it; it.start() }

    private fun noopListener() = object : MumbleControlTransport.Listener {
        override fun onFrame(f: TcpFrame) = Unit
        override fun onClosed(cause: Throwable?) = Unit
    }

    private fun pingMessage() = MumbleProtos.Ping.newBuilder().setTimestamp(1L).build()

    @Test
    fun deliversFramesToListener() = runBlocking {
        val srv = startServer()
        val received = CountDownLatch(1)
        var frame: TcpFrame? = null

        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) { frame = f; received.countDown() }
            override fun onClosed(cause: Throwable?) = Unit
        })

        srv.writeFrame(TcpMessageType.ServerSync.id, byteArrayOf(7, 8, 9))

        assertTrue("frame not received", received.await(5, TimeUnit.SECONDS))
        assertEquals(TcpMessageType.ServerSync.id, frame!!.type)
        assertArrayEquals(byteArrayOf(7, 8, 9), frame!!.payload)
        transport.close()
    }

    @Test
    fun pinMismatchFailsTheConnect() {
        val srv = startServer()
        val transport = MumbleTcpTransport(expectedPin = "11".repeat(32))

        val thrown = assertThrows(Exception::class.java) {
            runBlocking { transport.connect("localhost", srv.port, noopListener()) }
        }

        // Assert on the cause chain, not just "something threw": a socket timeout or any other
        // failure would otherwise satisfy this test without the pin ever being checked.
        val causes = generateSequence<Throwable>(thrown) { it.cause }.toList()
        assertTrue(
            "expected PinMismatchException in cause chain, got: ${causes.map { it::class.simpleName }}",
            causes.any { it is PinMismatchException },
        )
    }

    // Inverse of what this test used to assert. A pinned certificate is bound to the endpoint by
    // its fingerprint, so a name mismatch must NOT block it — stock Mumble certificates carry no
    // usable name at all, and requiring one made pinning useless against real servers.
    @Test
    fun pinnedConnectionSucceedsDespiteHostNameMismatch() = runBlocking {
        val srv = startServer()
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)

        transport.connect("127.0.0.1", srv.port, noopListener())

        assertTrue("pinned connection to a mismatched name should succeed", transport.isConnected)
        transport.close()
    }

    @Test
    fun closeIsIdempotentAndReportsOnce() = runBlocking {
        val srv = startServer()
        val closedCount = AtomicInteger()
        val closedFired = CountDownLatch(1)
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) = Unit
            override fun onClosed(cause: Throwable?) { closedCount.incrementAndGet(); closedFired.countDown() }
        })

        transport.close()
        transport.close()

        // Waits on the delivery rather than a guessed duration, and supplies the happens-before the
        // old plain `var` lacked entirely. Bounds the "once" claim to what is provable: by the time
        // this fires, both close() calls have returned and the reader's finally — the sole delivery
        // site — has run, so a duplicate from that path is already counted.
        assertTrue("onClosed never delivered", closedFired.await(5, TimeUnit.SECONDS))
        assertEquals(1, closedCount.get())
    }

    @Test
    fun sendAfterCloseReturnsFalse() = runBlocking {
        val srv = startServer()
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.connect("localhost", srv.port, noopListener())
        transport.close()

        assertFalse(transport.send(TcpMessageType.Ping, pingMessage()))
    }

    // Asserts a real consequence rather than a flag this test already set: if the racing connect
    // published a socket and started its pumps, a frame written afterwards would be delivered.
    @Test
    fun closeDuringConnectLeavesNoSocketDeliveringFrames() {
        val srv = startServer()
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        val connectStarted = CountDownLatch(1)
        val delivered = CountDownLatch(1)

        val t = thread {
            connectStarted.countDown()
            runCatching {
                runBlocking {
                    transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
                        override fun onFrame(f: TcpFrame) { delivered.countDown() }
                        override fun onClosed(cause: Throwable?) = Unit
                    })
                }
            }
        }
        connectStarted.await()
        transport.close()
        t.join(10_000)

        runCatching { srv.writeFrame(TcpMessageType.ServerSync.id, byteArrayOf(1)) }

        assertFalse(
            "a frame was delivered after close, so a live socket survived the race",
            delivered.await(1, TimeUnit.SECONDS),
        )
    }

    // Reproduces the concurrency defect this lock closes: onClosed must not run while onFrame is
    // still executing, or a listener written to the documented contract races its own state.
    @Test
    fun listenerCallbacksNeverOverlap() = runBlocking {
        val srv = startServer()
        val inFrame = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val closedFired = CountDownLatch(1)
        overlapped = false
        inOnFrame = false

        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) {
                inOnFrame = true
                inFrame.countDown()
                releaseFrame.await()
                inOnFrame = false
            }
            override fun onClosed(cause: Throwable?) {
                if (inOnFrame) overlapped = true
                closedFired.countDown()
            }
        })

        srv.writeFrame(TcpMessageType.ServerSync.id, byteArrayOf(1))
        assertTrue("reader never entered onFrame", inFrame.await(5, TimeUnit.SECONDS))

        // Join before releasing onFrame: the defect is close() delivering onClosed on its own
        // thread, and if it did, joining proves it has already happened. A sleep only made it
        // likely. Then wait for the real delivery — without that the assert could run before
        // onClosed ever fired and pass while proving nothing.
        val closer = thread { transport.close() }
        closer.join(5_000)
        releaseFrame.countDown()

        assertTrue("onClosed never delivered", closedFired.await(5, TimeUnit.SECONDS))
        assertFalse("onClosed ran while onFrame was still executing", overlapped)
    }

    // Same non-overlap contract, but the case listenerCallbacksNeverOverlap could not reach: the
    // listener closes the channel from inside its own onFrame — exactly what SessionStateMachine
    // does on a Reject. onClosed must still be delivered only after onFrame returns, not nested on
    // the same call stack. Deterministic, no cross-thread timing.
    @Test
    fun onClosedIsNotDeliveredNestedInsideOnFrame() = runBlocking {
        val srv = startServer()
        val insideOnFrame = AtomicBoolean(false)
        val nested = AtomicBoolean(false)
        val closedFired = CountDownLatch(1)

        lateinit var transport: MumbleTcpTransport
        transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) {
                insideOnFrame.set(true)
                transport.close()
                insideOnFrame.set(false)
            }
            override fun onClosed(cause: Throwable?) {
                if (insideOnFrame.get()) nested.set(true)
                closedFired.countDown()
            }
        })

        srv.writeFrame(TcpMessageType.ServerSync.id, byteArrayOf(1))

        assertTrue("onClosed never fired", closedFired.await(5, TimeUnit.SECONDS))
        assertFalse("onClosed was delivered nested inside onFrame", nested.get())
    }

    // A reader error must tear the socket down itself, not merely notify. Before the fix the reader's
    // catch only reported, so after a server drop the socket and coroutines leaked while isConnected
    // still read true. No external close() is called here — the reader owns its own cleanup.
    @Test
    fun aReaderErrorTearsDownTheTransportWithoutAnExternalClose() = runBlocking {
        val srv = startServer()
        val cause = AtomicReference<Throwable?>()
        val closedFired = CountDownLatch(1)
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) = Unit
            override fun onClosed(c: Throwable?) { cause.set(c); closedFired.countDown() }
        })

        srv.close()   // server drops the connection; the client's blocked read fails

        assertTrue("the reader error never surfaced as onClosed", closedFired.await(5, TimeUnit.SECONDS))
        assertNotNull("a dropped connection must report a cause, not a clean close", cause.get())
        assertFalse("the reader error must tear the socket down on its own", transport.isConnected)
    }

    // A write failure must surface to the listener as itself, not as the generic socket-closed
    // exception the reader raises once the failed write tears the socket down. The seam forces the
    // writer to fail deterministically while the reader is legitimately parked in its read.
    @Test
    fun aWriteFailureIsReportedAsItsOwnCause() = runBlocking {
        val srv = startServer()
        val writeError = java.io.IOException("forced write failure")
        val cause = AtomicReference<Throwable?>()
        val closedFired = CountDownLatch(1)
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.TESTONLY_beforeWrite = { throw writeError }
        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) = Unit
            override fun onClosed(c: Throwable?) { cause.set(c); closedFired.countDown() }
        })

        // Any frame triggers a write, which the seam turns into the failure above.
        transport.send(TcpMessageType.Ping, pingMessage())

        assertTrue("a write failure never surfaced as onClosed", closedFired.await(5, TimeUnit.SECONDS))
        assertSame("onClosed reported the reader's exception, not the write failure", writeError, cause.get())
    }

    // Finding 6, made deterministic by the seam. Publish and close are mutually exclusive on one
    // lock, so the race collapses to who wins it. The seam forces "close wins": connect must abort
    // its publish. Asserts frame non-delivery rather than isConnected — isConnected is dominated by
    // `closed`, so a wrongly-published live socket would still read not-connected and hide the bug.
    @Test
    fun closeWinningThePublishRaceLeavesNoSocketDeliveringFrames() = runBlocking {
        val srv = startServer()
        val delivered = CountDownLatch(1)
        val transport = MumbleTcpTransport(expectedPin = srv.certSha256)
        transport.TESTONLY_beforePublish = { thread { transport.close() }.join() }

        transport.connect("localhost", srv.port, object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) { delivered.countDown() }
            override fun onClosed(cause: Throwable?) = Unit
        })

        // Only a live socket with running pumps could deliver this; the winning close must prevent it.
        runCatching { srv.writeFrame(TcpMessageType.ServerSync.id, byteArrayOf(1)) }

        assertFalse(
            "close won the race but a frame was delivered — a live socket survived publish",
            delivered.await(1, TimeUnit.SECONDS),
        )
    }

    /** Stands in for a certificate authority: the fixture server is self-signed, so without this
     *  every test takes the pinned path and the authority branch is never reached. */
    private fun acceptEverything() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Records what it was asked, so a test can prove the verifier ran and on which host. */
    private class RecordingVerifier(private val result: Boolean) : HostnameVerifier {
        @Volatile var askedFor: String? = null
        override fun verify(hostname: String, session: SSLSession): Boolean {
            askedFor = hostname
            return result
        }
    }

    // These three pin the whole host-name policy. The platform verifier is deliberately not used:
    // on a plain Java virtual machine its default rejects unconditionally, which silently satisfied
    // the rejection case here no matter what the production code did.
    @Test
    fun authorityValidatedCertificateMustMatchTheHostName() {
        val srv = startServer()
        val verifier = RecordingVerifier(result = false)
        val transport = MumbleTcpTransport(null, acceptEverything(), verifier)

        val thrown = assertThrows(Exception::class.java) {
            runBlocking { transport.connect("127.0.0.1", srv.port, noopListener()) }
        }

        val causes = generateSequence<Throwable>(thrown) { it.cause }.toList()
        assertTrue(
            "expected a host name rejection, got: ${causes.map { it::class.simpleName }}",
            causes.any { it is java.security.cert.CertificateException },
        )
        assertEquals("verifier was not consulted for the dialled host", "127.0.0.1", verifier.askedFor)
    }

    @Test
    fun authorityValidatedCertificateIsAcceptedWhenTheHostNameMatches() = runBlocking {
        val srv = startServer()
        val verifier = RecordingVerifier(result = true)
        val transport = MumbleTcpTransport(null, acceptEverything(), verifier)

        transport.connect("localhost", srv.port, noopListener())

        assertTrue("a verified host name should connect", transport.isConnected)
        assertEquals(TrustOutcome.CaValid, transport.trustOutcome)
        assertEquals("localhost", verifier.askedFor)
        transport.close()
    }

    // The other half of the split: a pinned certificate is bound to the endpoint by its fingerprint,
    // so the name must not be consulted at all. Stock Mumble certificates carry no usable name.
    @Test
    fun pinnedConnectionNeverConsultsHostNameVerification() = runBlocking {
        val srv = startServer()
        val verifier = RecordingVerifier(result = false)
        val transport = MumbleTcpTransport(srv.certSha256, platformTrustManager(), verifier)

        transport.connect("127.0.0.1", srv.port, noopListener())

        assertTrue(transport.isConnected)
        assertEquals(TrustOutcome.Pinned, transport.trustOutcome)
        assertNull("pinned path must not consult host name verification", verifier.askedFor)
        transport.close()
    }
}
