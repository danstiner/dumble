package me.danielstiner.dumble.mumble.net

import kotlinx.coroutines.runBlocking
import me.danielstiner.dumble.hangGuard
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch

class LocalNetworkBlockTest {
    @get:Rule val timeout = hangGuard()

    /** Only the abort can end this connect: without it this hangs, and the guard fails the test. */
    @Test
    fun aBlockAbortsTheConnectInFlight() {
        val aborted = CountDownLatch(1)
        assertThrows(LocalNetworkBlockedException::class.java) {
            runBlocking {
                connectUnlessBlocked(
                    isBlocked = { true },
                    connect = { aborted.await(); throw SocketException("Socket closed") },
                    abort = aborted::countDown,
                )
            }
        }
    }

    /** The watch never reads blocked, so this also hangs if the watch doesn't stop with the connect. */
    @Test
    fun aFailureNotBlockedKeepsItsOwnCause() {
        val timedOut = SocketTimeoutException()
        val thrown = assertThrows(SocketTimeoutException::class.java) {
            runBlocking { connectUnlessBlocked(isBlocked = { false }, connect = { throw timedOut }, abort = {}) }
        }
        assertSame(timedOut, thrown)
    }
}
