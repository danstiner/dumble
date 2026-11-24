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
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.User
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.ui.connect.FakeConnection
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

class LatencyCheckTest {

    /** The connection refuses a check's session while the user has one: nothing to end. */
    @Test fun aCheckRefusesWhileDumbleIsConnected() = runTest {
        val rig = Rig(this)
        rig.host.grants = false
        assertEquals(CheckOutcome.Refused(CONNECTED), rig.start().getCompleted())
        assertEquals(0, rig.conn.disconnectCalls)
    }

    @Test fun aRejectedOrMismatchedConnectFailsWithItsReason() = runTest {
        val answers = mapOf(
            ConnectionStatus.Error(ErrorKind.CONNECT_FAILED, "certificate ff is not the one given") to
                "could not connect: CONNECT_FAILED (certificate ff is not the one given)",
            ConnectionStatus.PinMismatch("aa", "ff") to ENDED,
            ConnectionStatus.Idle to ENDED,
        )
        for ((answer, reason) in answers) {
            val rig = Rig(this)
            rig.conn.onConnect = { rig.conn.status.value = answer }
            val outcome = rig.start()
            assertEquals(CheckOutcome.Failed(reason, null), outcome.getCompleted())
            assertEquals(listOf(ENDPOINT to FINGERPRINT), rig.host.connects)
        }

        val rig = Rig(this)
        rig.conn.onConnect = { rig.conn.status.value = ConnectionStatus.Connecting }
        val outcome = rig.start()
        advance(10.seconds - 1.milliseconds)
        assertFalse(outcome.isCompleted)
        advance(1.milliseconds)
        assertEquals(CheckOutcome.Failed("could not connect within 10s", null), outcome.getCompleted())
    }

    @Test fun aCheckRefusesWhenMutedOrSuppressed() = runTest {
        // Self-mute as asked for (selfState) is a row in everyEndingDisconnects.
        for (self in listOf(SELF.copy(mute = true), SELF.copy(suppress = true), SELF.copy(selfMute = true))) {
            val rig = Rig(this)
            rig.conn.channelTree.value = ChannelTree(users = mapOf(SESSION to self))
            assertEquals("$self", CheckOutcome.Refused(MUTED), rig.start().getCompleted())
            assertEquals("$self", 0, rig.conn.requestCaptureCalls)
        }
    }

    @Test fun theCheckTakesTheSpeakerRoute() = runTest {
        val rig = Rig(this)
        rig.conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), current = EARPIECE)
        val outcome = rig.start()
        assertEquals(listOf(SPEAKER.id), rig.conn.routeRequests)
        assertEquals(0, rig.conn.requestCaptureCalls)
        rig.conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), current = SPEAKER)
        runCurrent()
        assertEquals(1, rig.conn.requestCaptureCalls)
        // Cleared, as a session ending clears it: the status watcher's, not a route change.
        rig.conn.audioRoutes.value = AudioRoutes()
        advance(1.seconds)
        assertFalse(outcome.isCompleted)
        advance(5.seconds)
        assertTrue(outcome.getCompleted() is CheckOutcome.Measured)
    }

    @Test fun captureHasFiveSecondsToOpen() = runTest {
        val rig = Rig(this)
        rig.host.captureOpen.value = false
        val outcome = rig.start()
        advance(5.seconds - 1.milliseconds)
        rig.host.captureOpen.value = true
        advance(5.seconds)
        assertTrue(outcome.getCompleted() is CheckOutcome.Measured)
    }

    @Test fun packetsGoOutOnAbsoluteDeadlines() = runTest {
        val rig = Rig(this)
        var waits = 0
        rig.waitUntil = { deadline ->
            delayUntil(deadline)
            if (++waits == 6) delay(15.milliseconds)
        }
        val outcome = rig.start()
        advance(5.seconds)
        assertTrue(outcome.getCompleted() is CheckOutcome.Measured)

        val warm = List(50) { n ->
            Sent(2L * n, "warm $n", n == 49, 20.milliseconds * n + if (n == 5) 15.milliseconds else ZERO)
        }
        val spurt = List(33) { m -> Sent(200L + 2 * m, "probe $m", m == 32, 2.seconds + 20.milliseconds * m) }
        val sent = rig.host.injects.map { Sent(it.frame, it.opus.decodeToString(), it.terminator, it.at - rig.origin) }
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
            rig.host.echo = echo
            val outcome = rig.start()
            advance(2500.milliseconds)
            val heard = CapturedPacket(250, byteArrayOf(1), rig.clock.markNow())
            rig.host.captured(heard)
            // Capture's pump keeps polling while locate decodes.
            rig.probe.onLocate = { rig.host.captured(CapturedPacket(400, byteArrayOf(2), rig.clock.markNow())) }
            advance(3.seconds)

            val m = (outcome.getCompleted() as CheckOutcome.Measured).m
            assertEquals("$echo", listOf(true to 2.seconds, false to closes), rig.gate())
            assertEquals("$echo", List(50) { false } + List(33) { true }, rig.host.injects.map { it.gateOpen })
            assertEquals("$echo", listOf(heard), m.captured)
            assertEquals("$echo", listOf(listOf(heard) to CODEC_DELAY), rig.probe.searches)
            assertEquals("$echo", rig.probe.located, m.located)
        }
    }

    @Test fun theReturnIsTheOnsetPacketFromOurOwnSession() = runTest {
        val rig = Rig(this)
        rig.host.echo = null
        val stats = CaptureStats(50, 0, 400, 900, 0, 0, 0, 96, 0, 12.0)
        rig.conn.apply {
            roundTripTime.value = 40.milliseconds
            voicePath.value = VoicePath.State(onUdp = true, roundTrip = 35.milliseconds)
            captureStats.value = stats
            emitDepths(mapOf(SESSION to 960))
        }
        val outcome = rig.start()
        fun arrive(session: Int, frame: Long, queued: Int) =
            ArrivedPacket(session, frame, rig.clock.markNow(), queued, 0, 0).also(rig.host.arrived)

        advance(2300.milliseconds)
        rig.conn.voicePath.value = VoicePath.State()
        arrive(OTHER, 220, 1)
        arrive(SESSION, 218, 2)
        val ours = arrive(SESSION, 220, 3)
        arrive(SESSION, 220, 4)
        advance(400.milliseconds)
        arrive(OTHER, 264, 5)
        advance(100.milliseconds)
        arrive(SESSION, 264, 6)
        advance(1.seconds)

        val expected = Measurements(
            sent = rig.origin + 2200.milliseconds, returned = ours, captured = emptyList(), located = rig.probe.located,
            codecDelaySamples = CODEC_DELAY, effects = listOf("echo canceller"), effectsAllOff = true,
            route = SPEAKER, voicePath = VoicePath.State(onUdp = true, roundTrip = 35.milliseconds),
            roundTripTime = 40.milliseconds, captureStats = stats, playoutStats = rig.conn.playoutStats.value,
        )
        assertEquals(CheckOutcome.Measured(expected), outcome.getCompleted())
        assertEquals(false to 3800.milliseconds, rig.gate().last())
    }

    @Test fun aMissingReturnTimesOut() = runTest {
        val rig = Rig(this)
        rig.host.echo = null
        val outcome = rig.start()
        advance(4640.milliseconds - 1.milliseconds)
        assertFalse(outcome.isCompleted)
        advance(1.milliseconds)

        val failed = outcome.getCompleted() as CheckOutcome.Failed
        assertEquals("the probe did not come back from the server", failed.reason)
        assertEquals(rig.origin + 2200.milliseconds, failed.partial?.sent)
        assertNull(failed.partial?.returned)
        assertEquals(false to 4640.milliseconds, rig.gate().last())

        // The onset back but never the spurt's last packet: still measured, once the cap passes.
        val late = Rig(this)
        late.host.echo = null
        val measured = late.start()
        advance(2300.milliseconds)
        val ours = ArrivedPacket(SESSION, 220, late.clock.markNow(), 0, 0, 0).also(late.host.arrived)
        advance(2340.milliseconds)
        assertEquals(ours, (measured.getCompleted() as CheckOutcome.Measured).m.returned)
        assertEquals(false to 4640.milliseconds, late.gate().last())
    }

    @Test fun aHoldOrRouteChangeOrDisconnectAbortsTheCheck() = runTest {
        val cases = listOf<Pair<String, Rig.() -> Unit>>(
            "another call took the microphone" to { conn.callHeld.value = true },
            "the audio route left the speaker (did a headset connect?)" to {
                conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), current = EARPIECE)
            },
            "the connection to the server ended" to { conn.status.value = ConnectionStatus.Reconnecting(1, SESSION) },
            "the connection to the server ended" to { conn.status.value = ConnectionStatus.Connected(1, OTHER) },
            "the connection to the server ended or was replaced during the check" to { host.ended.value = true },
            "capture closed during the check" to { host.captureOpen.value = false },
        )
        for ((reason, change) in cases) {
            val rig = Rig(this)
            val outcome = rig.start()
            advance(2500.milliseconds)
            rig.change()
            runCurrent()

            val failed = outcome.getCompleted() as CheckOutcome.Failed
            assertEquals(reason, failed.reason)
            assertEquals(reason, rig.origin + 2200.milliseconds, failed.partial?.sent)
            assertEquals(reason, 50 + 26, rig.host.injects.size)
        }
    }

    @Test fun everyEndingDisconnects() = runTest {
        class Ending(val ending: String, val setup: Rig.() -> Unit, val atOneSecond: Rig.(Deferred<CheckOutcome>) -> Unit = {})
        val rejected = ConnectionStatus.Error(ErrorKind.SERVER_FULL, null)
        val unencoded = "the probe could not be encoded"
        val endings = listOf(
            Ending("measured", {}),
            Ending("could not connect: SERVER_FULL", { conn.onConnect = { conn.status.value = rejected } }),
            Ending("could not connect within 10s", { conn.onConnect = { conn.status.value = ConnectionStatus.Connecting } }),
            Ending("our user did not appear on the server", { conn.channelTree.value = ChannelTree() }),
            Ending("refused: $MUTED", { conn.setMuted(true) }),
            Ending("no built-in speaker", { conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE), EARPIECE) }),
            Ending("the speaker route was not taken", {
                conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), EARPIECE)
            }),
            Ending(
                "capture did not open within 5s: is the microphone permission granted, or is another app using the microphone?",
                { host.captureOpen.value = false },
            ),
            Ending(unencoded, { probe.codecDelay = -1 }),
            Ending(unencoded, { probe.warmPackets = 0 }),
            Ending(unencoded, { probe.spurtPackets = 10 }),
            Ending("the probe did not come back from the server", { host.echo = null }),
            Ending("another call took the microphone", {}, { conn.callHeld.value = true }),
            Ending("the check failed unexpectedly: java.lang.IllegalStateException: decoder gone", {
                probe.onLocate = { error("decoder gone") }
            }),
            Ending("cancelled", {}, { it.cancel() }),
        )
        for ((i, ending) in endings.withIndex()) {
            val rig = Rig(this).apply(ending.setup)
            val outcome = rig.start()
            advance(1.seconds)
            ending.atOneSecond(rig, outcome)
            advance(10.seconds)

            val actual = if (outcome.isCancelled) "cancelled" else when (val o = outcome.getCompleted()) {
                is CheckOutcome.Measured -> "measured"
                is CheckOutcome.Failed -> o.reason
                is CheckOutcome.Refused -> "refused: ${o.reason}"
            }
            assertEquals("row $i", ending.ending, actual)
            assertEquals("row $i", 1, rig.conn.disconnectCalls)
        }
    }

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private class Rig(private val scope: TestScope) {
        val clock = scope.testScheduler.timeSource
        val origin = clock.markNow()
        val conn = FakeConnection().apply {
            onConnect = { status.value = ConnectionStatus.Connected(1, SESSION) }
            channelTree.value = ChannelTree(users = mapOf(SESSION to SELF))
            audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), current = SPEAKER)
        }
        val host = FakeCheckHost(scope) { conn.onConnect() }
        val probe = FakeProbeCodec()
        var waitUntil: suspend (ComparableTimeMark) -> Unit = ::delayUntil

        /** Each gate change, at its time since [origin]. */
        fun gate() = host.gateChanges.map { (open, at) -> open to at - origin }

        fun start(): Deferred<CheckOutcome> =
            scope.async { LatencyCheck(conn, host, probe, waitUntil).run(ENDPOINT, FINGERPRINT) }
                .also { scope.runCurrent() }
    }

    private data class Sent(val frame: Long, val payload: String, val terminator: Boolean, val at: Duration)

    private companion object {
        const val SESSION = FakeCheckHost.SESSION
        const val OTHER = 8
        const val CODEC_DELAY = 312
        val ENDPOINT = MumbleEndpoint.parse("murmur.test")
        val FINGERPRINT = "ab".repeat(32)
        val EARPIECE = AudioRoute("1", AudioRoute.Type.EARPIECE)
        val SPEAKER = AudioRoute("2", AudioRoute.Type.SPEAKER)
        const val ENDED = "the connection ended before it was up"
        const val CONNECTED = "Dumble is connected to a server; disconnect first"
        const val MUTED = "muted or suppressed on the server: Murmur drops loopback audio from such a user"
        val SELF = User(
            SESSION, "dumble-latency", 0, mute = false, deaf = false, selfMute = false, selfDeaf = false, suppress = false,
        )
    }
}

/** The connection's side of a check and the session it connects: each injected packet comes back
 *  from the server after [echo]. */
class FakeCheckHost(private val scope: TestScope, private val onConnect: () -> Unit) : CheckHost, CheckSession {
    class Injected(
        val frame: Long, val opus: ByteArray, val terminator: Boolean, val at: ComparableTimeMark, val gateOpen: Boolean,
    )

    override val clock = scope.testScheduler.timeSource
    var grants = true
    var echo: Duration? = 30.milliseconds
    val connects = mutableListOf<Pair<MumbleEndpoint, String>>()
    var captured: (CapturedPacket) -> Unit = {}; private set
    var arrived: (ArrivedPacket) -> Unit = {}; private set
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

    override fun connectForCheck(
        endpoint: MumbleEndpoint,
        username: String,
        fingerprint: String,
        captured: (CapturedPacket) -> Unit,
        arrived: (ArrivedPacket) -> Unit,
    ): CheckSession? {
        if (!grants) return null
        connects += endpoint to fingerprint
        this.captured = captured
        this.arrived = arrived
        onConnect()
        return this
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
