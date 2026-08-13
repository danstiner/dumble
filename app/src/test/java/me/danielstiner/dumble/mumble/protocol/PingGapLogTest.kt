package me.danielstiner.dumble.mumble.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.MessageLite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The gap warning produces a `Log` call and nothing else, so [ShadowLog] observes it — Robolectric
 * is already a test dependency, and this needs no seam in the product.
 *
 * What is pinned is that the gap is real elapsed time, deliberately including sleep. A gap this
 * long means we failed to ping inside the server's reap and may already have been dropped; whether
 * the cause was a doze or a starved dispatcher does not change that, so the warning does not try to
 * tell them apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PingGapLogTest {

    private class FakeChannel : ControlChannel {
        override fun send(type: TcpMessageType, message: MessageLite) = true
        override fun sendRaw(type: TcpMessageType, payload: ByteArray) = true
        override fun close() = Unit
    }

    @Before fun clearLogs() = ShadowLog.clear()

    private fun gapWarnings() =
        ShadowLog.getLogs().filter { it.msg?.contains("no ping sent for") == true }

    /** Two ticks, with the real clock moved by [realAdvance] before the second. */
    private fun tickTwice(realAdvance: kotlin.time.Duration) = runTest {
        val bootClock = TestTimeSource()
        val sm = SessionStateMachine(
            FakeChannel(), "tester", null, backgroundScope, bootClock = bootClock,
        ).apply { start() }
        sm.onFrame(
            TcpFrame(
                TcpMessageType.ServerSync.id,
                MumbleProtos.ServerSync.newBuilder().setSession(1).build().toByteArray(),
            ),
        )
        bootClock += SessionStateMachine.PING_INTERVAL_MS.milliseconds
        advanceTimeBy(SessionStateMachine.PING_INTERVAL_MS + 1)
        assertEquals("an ordinary interval must not warn", 0, gapWarnings().size)

        bootClock += realAdvance
        advanceTimeBy(SessionStateMachine.PING_INTERVAL_MS + 1)
    }

    @Test
    fun aGapPastTheThresholdIsReported() {
        tickTwice(30.seconds)

        assertEquals(
            "a gap past PING_GAP_WARN must be reported, got ${ShadowLog.getLogs().map { it.msg }}",
            1, gapWarnings().size,
        )
        // Whole milliseconds: the clock is nanosecond-backed, so a bare Duration interpolates as
        // "30.000000000s". Pinned because it regressed once when the source moved to nanos.
        val msg = gapWarnings().single().msg
        assertTrue(
            "the gap must be reported in whole milliseconds, was: $msg",
            Regex("""no ping sent for \d+ms session=\d+""").containsMatchIn(msg!!),
        )
    }

    // Sleep counts, deliberately. An earlier design measured this gap on a monotonic clock to
    // isolate a stall from a doze; that distinction was dropped because the consequence is the same
    // either way -- we did not ping, and the server may have reaped us for it.
    @Test
    fun timeAsleepCountsTowardTheGap() {
        tickTwice(30.seconds)

        assertEquals(1, gapWarnings().size)
    }
}
