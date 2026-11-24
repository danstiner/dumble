@file:OptIn(ExperimentalCoroutinesApi::class)

package me.danielstiner.dumble.mumble.voice.latency

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProbeExchangeTest {

    @Test fun packetsGoOutOnAbsoluteDeadlines() = runTest {
        var waits = 0
        val rig = Rig(this) { deadline ->
            delayUntil(deadline)
            if (++waits == 6) delay(15.milliseconds)
        }
        val outcome = rig.start()
        advance(5.seconds)
        assertTrue(outcome.getCompleted().isSuccess)

        val warm = List(50) { n ->
            Sent(2L * n, "warm $n", n == 49, 20.milliseconds * n + if (n == 5) 15.milliseconds else ZERO)
        }
        val spurt = List(33) { m -> Sent(200L + 2 * m, "probe $m", m == 32, 2.seconds + 20.milliseconds * m) }
        val sent = rig.session.injects.map { Sent(it.frame, it.opus.decodeToString(), it.terminator, it.at - rig.origin) }
        assertEquals(warm + spurt, sent)
        assertEquals(
            listOf(
                FakeProbeCodec.Encode(0, 750.milliseconds, ZERO, 32_000),
                FakeProbeCodec.Encode(1640, 200.milliseconds, 200.milliseconds, 32_000),
            ),
            rig.probe.encodes,
        )
    }

    @Test fun theGateOpensWithTheProbeSpurtAndClosesAfterItsReturn() = runTest {
        // The last probe packet goes at 2.64 s: its return plus 1 s, capped at 2 s after its send.
        for ((echo, closes) in listOf(30.milliseconds to 3670.milliseconds, 1500.milliseconds to 4640.milliseconds)) {
            val rig = Rig(this)
            rig.session.echo = echo
            val outcome = rig.start()
            advance(2500.milliseconds)
            val packet = CapturedPacket(250, byteArrayOf(1), rig.clock.markNow())
            rig.exchange.onCaptured(packet)
            // Capture's pump keeps polling while locate decodes.
            rig.probe.onLocate = { rig.exchange.onCaptured(CapturedPacket(400, byteArrayOf(2), rig.clock.markNow())) }
            advance(3.seconds)

            assertEquals("$echo", Heard(listOf(packet), rig.probe.located), outcome.getCompleted().getOrThrow())
            assertEquals("$echo", listOf(true to 2.seconds, false to closes), rig.gate())
            assertEquals("$echo", List(50) { false } + List(33) { true }, rig.session.injects.map { it.gateOpen })
            assertEquals("$echo", listOf(listOf(packet) to CODEC_DELAY), rig.probe.searches)
        }
    }

    @Test fun theReturnIsTheOnsetPacketFromOurOwnSession() = runTest {
        val rig = Rig(this)
        rig.session.echo = null
        val outcome = rig.start()
        fun arrive(session: Int, frame: Long, queued: Int) =
            ArrivedPacket(session, frame, rig.clock.markNow(), queued, 0, 0).also(rig.exchange::onArrived)

        advance(2300.milliseconds)
        arrive(OTHER, 220, 1)
        arrive(SESSION, 218, 2)
        val ours = arrive(SESSION, 220, 3)
        arrive(SESSION, 220, 4)
        advance(400.milliseconds)
        arrive(OTHER, 264, 5)
        advance(100.milliseconds)
        arrive(SESSION, 264, 6)
        advance(1.seconds)

        assertTrue(outcome.getCompleted().isSuccess)
        assertEquals(ours, rig.exchange.returned)
        assertEquals(rig.origin + 2200.milliseconds, rig.exchange.sent)
        assertEquals("told as t0 is marked", listOf(2200.milliseconds), rig.onsets)
        assertEquals(CODEC_DELAY, rig.exchange.codecDelaySamples)
        assertEquals(false to 3800.milliseconds, rig.gate().last())
    }

    @Test fun aMissingReturnTimesOut() = runTest {
        val rig = Rig(this)
        rig.session.echo = null
        val outcome = rig.start()
        advance(4640.milliseconds - 1.milliseconds)
        assertFalse(outcome.isCompleted)
        advance(1.milliseconds)

        assertEquals("the probe did not come back from the server", outcome.getCompleted().exceptionOrNull()?.message)
        assertEquals(rig.origin + 2200.milliseconds, rig.exchange.sent)
        assertNull(rig.exchange.returned)
        assertEquals(false to 4640.milliseconds, rig.gate().last())

        // The onset back but never the spurt's last packet: still heard out, once the cap passes.
        val late = Rig(this)
        late.session.echo = null
        val heard = late.start()
        advance(2300.milliseconds)
        val ours = ArrivedPacket(SESSION, 220, late.clock.markNow(), 0, 0, 0).also(late.exchange::onArrived)
        advance(2340.milliseconds)
        assertTrue(heard.getCompleted().isSuccess)
        assertEquals(ours, late.exchange.returned)
        assertEquals(false to 4640.milliseconds, late.gate().last())
    }

    @Test fun aProbeThatCannotBeEncodedSendsNothing() = runTest {
        val broken = listOf<FakeProbeCodec.() -> Unit>({ codecDelay = -1 }, { warmPackets = 0 }, { spurtPackets = 10 })
        for ((i, breakIt) in broken.withIndex()) {
            val rig = Rig(this)
            rig.probe.breakIt()
            val failure = rig.start().getCompleted().exceptionOrNull()
            assertTrue("row $i", failure is CheckFailure)
            assertEquals("row $i", "the probe could not be encoded", failure?.message)
            assertTrue("row $i", rig.session.injects.isEmpty())
        }
    }

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private class Rig(private val scope: TestScope, waitUntil: suspend (ComparableTimeMark) -> Unit = ::delayUntil) {
        val clock = scope.testScheduler.timeSource
        val origin = clock.markNow()
        val probe = FakeProbeCodec()
        val exchange = ProbeExchange(probe, waitUntil)
        val session = FakeCheckSession(scope).also { it.arrived = exchange::onArrived }

        /** When [ProbeExchange.run] said the onset went, since [origin]. */
        val onsets = mutableListOf<Duration>()

        /** Each gate change, at its time since [origin]. */
        fun gate() = session.gateChanges.map { (open, at) -> open to at - origin }

        fun start(): Deferred<Result<Heard>> =
            scope.async { runCatching { exchange.run(session, SESSION) { onsets += clock.markNow() - origin } } }
                .also { scope.runCurrent() }
    }

    private data class Sent(val frame: Long, val payload: String, val terminator: Boolean, val at: Duration)

    private companion object {
        const val SESSION = FakeCheckSession.SESSION
        const val OTHER = 8
        const val CODEC_DELAY = 312
    }
}

/** A check's session: each injected packet comes back from the server after [echo]. */
class FakeCheckSession(private val scope: TestScope) : CheckSession {
    class Injected(
        val frame: Long, val opus: ByteArray, val terminator: Boolean, val at: ComparableTimeMark, val gateOpen: Boolean,
    )

    override val clock = scope.testScheduler.timeSource
    var echo: Duration? = 30.milliseconds

    /** What the session reports arrivals to. */
    var arrived: (ArrivedPacket) -> Unit = {}
    val injects = mutableListOf<Injected>()
    val gateChanges = mutableListOf<Pair<Boolean, ComparableTimeMark>>()
    private var gateOpen = false
    override val captureOpen = MutableStateFlow(true)
    override val ended = MutableStateFlow(false)
    override val effects: CheckEffects = object : CheckEffects {
        override val names = listOf("echo canceller")
        override fun allOff() = true
        override fun restore() = true
    }

    override fun inject(frameNumber: Long, opusData: ByteArray, terminator: Boolean): Boolean {
        injects += Injected(frameNumber, opusData, terminator, clock.markNow(), gateOpen)
        echo?.let { after ->
            scope.backgroundScope.launch {
                delay(after)
                arrived(ArrivedPacket(SESSION, frameNumber, clock.markNow(), 0, 0, 0))
            }
        }
        return true
    }

    override fun setGateOpen(open: Boolean) {
        gateOpen = open
        gateChanges += open to clock.markNow()
    }

    companion object {
        /** The session id the fake server gives a check. */
        const val SESSION = 7
    }
}

/** Packets named for what they carry, and one canned search result. */
class FakeProbeCodec : ProbeCodec {
    data class Encode(val peak: Int, val lead: Duration, val tail: Duration, val bitrate: Int)

    var codecDelay = 312
    var warmPackets = 50
    var spurtPackets = 33
    val located = Located(48_000, 9000.0, 300.0, found = true)
    val encodes = mutableListOf<Encode>()
    val searches = mutableListOf<Pair<List<CapturedPacket>, Int>>()
    var onLocate: () -> Unit = {}

    override fun encodeProbeSpurt(peak: Int, lead: Duration, tail: Duration, bitrate: Int): List<ByteArray> {
        encodes += Encode(peak, lead, tail, bitrate)
        val kind = if (peak == 0) "warm" else "probe"
        return List(if (peak == 0) warmPackets else spurtPackets) { "$kind $it".encodeToByteArray() }
    }

    override fun codecDelaySamples(bitrate: Int) = codecDelay

    override fun locate(packets: List<CapturedPacket>, codecDelaySamples: Int): Located {
        searches += packets to codecDelaySamples
        onLocate()
        return located
    }
}
