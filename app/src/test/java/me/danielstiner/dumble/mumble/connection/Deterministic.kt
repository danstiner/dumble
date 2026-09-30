@file:OptIn(ExperimentalCoroutinesApi::class)

package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.VoiceSender
import me.danielstiner.dumble.time.elapse
import org.junit.Assert.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A converted test: everything the connection runs is on one StandardTestDispatcher, so
 * nothing moves between two statements unless the test drives the scheduler, and `delay`
 * is virtual. The rule is drive, then assert — never await. runTest's own final drain
 * (`advanceUntilIdleOr`) would otherwise run the handshake deadline, the ping death and the
 * whole reconnect ladder on any connection left up, so every connection is built through
 * [Rig.own] and torn down here.
 */
internal fun deterministic(body: suspend Rig.() -> Unit) = runTest {
    val rig = Rig(this)
    try {
        rig.body()
    } finally {
        rig.owned.forEach { it.disconnect() }
        rig.settle()
    }
}

internal class Rig(val scope: TestScope) {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)

    /** One clock for `delay` and `markNow`: the scheduler's. */
    val clock: TimeSource.WithComparableMarks = scope.testScheduler.timeSource
    val owned = mutableListOf<MumbleConnection>()
    private val pumps = mutableListOf<VoiceSender>()

    fun own(conn: MumbleConnection): MumbleConnection = conn.also { owned += it }

    /** Pass as `startPump`: the connection's capture pumps then run only when [settle] steps them,
     *  so their handles must never block a poll — `FakeCaptureHandle(blocking = false)`. */
    val startPump: (VoiceSender) -> Unit = { pumps += it }

    /**
     * Runs everything queued at this instant, including what that work queues, and gives every
     * live pump one poll. A pump that ends is followed through — its exit handled, whatever that
     * opens polled in turn — until a round ends none. One poll per round, so a test scripts one
     * outcome per settle.
     */
    fun settle() {
        scope.runCurrent()
        while (pumps.isNotEmpty()) {
            val ended = pumps.filterNot { it.step() }
            pumps -= ended
            scope.runCurrent()
            if (ended.isEmpty()) return
        }
    }

    /** Settles, then asserts. */
    fun assertSettled(what: String, cond: () -> Boolean) {
        settle()
        assertTrue(what, cond())
    }

    /** Virtual time passes; every timer due in it fires, in order, and then everything settles. */
    fun elapse(d: Duration) {
        scope.elapse(d)
        settle()
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
