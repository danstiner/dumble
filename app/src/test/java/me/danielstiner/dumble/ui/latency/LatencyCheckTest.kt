@file:OptIn(ExperimentalCoroutinesApi::class)

package me.danielstiner.dumble.ui.latency

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import me.danielstiner.dumble.mumble.voice.latency.ArrivedPacket
import me.danielstiner.dumble.mumble.voice.latency.CallVolume
import me.danielstiner.dumble.mumble.voice.latency.CapturedPacket
import me.danielstiner.dumble.mumble.voice.latency.CheckHost
import me.danielstiner.dumble.mumble.voice.latency.CheckOutcome
import me.danielstiner.dumble.mumble.voice.latency.CheckSession
import me.danielstiner.dumble.mumble.voice.latency.FakeCheckSession
import me.danielstiner.dumble.mumble.voice.latency.FakeProbeCodec
import me.danielstiner.dumble.mumble.voice.latency.Heard
import me.danielstiner.dumble.mumble.voice.latency.Measurements
import me.danielstiner.dumble.ui.connect.FakeConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LatencyCheckTest {

    /** The connection refuses a check's session while the user has one: nothing to end. */
    @Test fun aCheckRefusesWhileDumbleIsConnected() = runTest {
        val rig = Rig(this)
        rig.host.grants = false
        assertEquals(CheckOutcome.Refused(CONNECTED), rig.start().getCompleted())
        assertEquals(0, rig.session.ends)
        assertEquals(0, rig.conn.disconnectCalls)
    }

    @Test fun aRejectedOrMismatchedConnectFailsWithItsReason() = runTest {
        val answers = mapOf(
            ConnectionStatus.Error(ErrorKind.CONNECT_FAILED, "certificate ff is not the one given") to
                "could not connect: CONNECT_FAILED (certificate ff is not the one given)",
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
        advance(5.seconds)
        rig.conn.status.value = ConnectionStatus.Handshaking
        advance(5.seconds - 1.milliseconds)
        assertFalse(outcome.isCompleted)
        advance(1.milliseconds)
        assertEquals(CheckOutcome.Failed("could not connect within 10s", null), outcome.getCompleted())
    }

    /** The status is the connection's: Connected may be a session the user opened meanwhile. */
    @Test fun aSessionReplacedWhileConnectingIsNotActedOn() = runTest {
        val rig = Rig(this)
        rig.conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), current = EARPIECE)
        rig.conn.onConnect = {
            rig.session.ended.value = true
            rig.conn.status.value = ConnectionStatus.Connected(2, OTHER)
        }
        assertEquals(CheckOutcome.Failed(REPLACED, null), rig.start().getCompleted())
        assertEquals(emptyList<String>(), rig.conn.routeRequests)
        assertEquals("only the check's own session is ended", 0, rig.conn.disconnectCalls)
    }

    @Test fun aCheckRefusesWhenMutedOrSuppressed() = runTest {
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
        val measured = outcome.getCompleted() as CheckOutcome.Measured
        assertEquals("read once the speaker was taken", CallVolume(2, 15), measured.measurements.callVolume)
    }

    @Test fun captureHasFiveSecondsToOpenAndTheOtherStepsTwo() = runTest {
        val rig = Rig(this)
        rig.session.captureOpen.value = false
        val outcome = rig.start()
        advance(5.seconds - 1.milliseconds)
        rig.session.captureOpen.value = true
        advance(5.seconds)
        assertTrue(outcome.getCompleted() is CheckOutcome.Measured)

        val slow = mapOf<String, Pair<Duration, Rig.() -> Unit>>(
            UNOPENED to (5.seconds to { session.captureOpen.value = false }),
            "no built-in speaker" to (2.seconds to { conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE), EARPIECE) }),
        )
        for ((reason, case) in slow) {
            val (timeout, setup) = case
            val late = Rig(this).apply(setup)
            val failed = late.start()
            advance(timeout - 1.milliseconds)
            assertFalse(reason, failed.isCompleted)
            advance(1.milliseconds)
            assertEquals(CheckOutcome.Failed(reason, null), failed.getCompleted())
        }
    }

    @Test fun aMeasuredCheckCarriesWhatTheExchangeAndTheConnectionReport() = runTest {
        val rig = Rig(this)
        rig.session.echo = null
        val stats = CaptureStats(50, 0, 400, 900, 0, 0, 0, 96, 0, 12.0)
        rig.conn.apply {
            roundTripTime.value = 40.milliseconds
            voicePath.value = VoicePath.State(onUdp = true, roundTrip = 35.milliseconds)
            captureStats.value = stats
            emitDepths(mapOf(SESSION to 960))
        }
        val outcome = rig.start()

        advance(2300.milliseconds)
        rig.conn.voicePath.value = VoicePath.State()
        // Through the two callbacks the check connected its session with.
        val ours = ArrivedPacket(SESSION, 220, rig.clock.markNow(), 960).also(rig.session.arrived)
        rig.host.captured(CapturedPacket(250, byteArrayOf(1), rig.clock.markNow()))
        advance(400.milliseconds)
        rig.session.arrived(ArrivedPacket(SESSION, 264, rig.clock.markNow(), 0))
        advance(1.seconds)

        val expected = Measurements(
            sent = rig.origin + 2200.milliseconds, returned = ours,
            // The fake codec's match, a second into the one packet capture handed over at 2.3 s.
            heard = Heard(rig.origin + 3300.milliseconds, 9000.0, 300.0),
            codecDelaySamples = CODEC_DELAY, effects = listOf("echo canceller"), effectsAllOff = true,
            route = SPEAKER, callVolume = CallVolume(2, 15),
            voicePath = VoicePath.State(onUdp = true, roundTrip = 35.milliseconds),
            roundTripTime = 40.milliseconds, captureStats = stats, playoutStats = rig.conn.playoutStats.value,
        )
        assertEquals(CheckOutcome.Measured(expected), outcome.getCompleted())
    }

    @Test fun aHoldOrRouteChangeOrDisconnectAbortsTheCheck() = runTest {
        val cases = listOf<Pair<String, Rig.() -> Unit>>(
            "another call took the microphone" to { conn.callHeld.value = true },
            "the audio route left the speaker (did a headset connect?)" to {
                conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), current = EARPIECE)
            },
            "the connection to the server ended" to { conn.status.value = ConnectionStatus.Reconnecting(1, SESSION) },
            "the connection to the server ended" to { conn.status.value = ConnectionStatus.Connected(1, OTHER) },
            REPLACED to { session.ended.value = true },
            "capture closed during the check" to { session.captureOpen.value = false },
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
            assertEquals("the route the probe played through", SPEAKER, failed.partial?.route)
            assertEquals(reason, 50 + 26, rig.session.injects.size)
        }
    }

    @Test fun everyEndingEndsTheSession() = runTest {
        class Ending(val ending: String, val setup: Rig.() -> Unit, val atOneSecond: Rig.(Deferred<CheckOutcome>) -> Unit = {})
        val rejected = ConnectionStatus.Error(ErrorKind.SERVER_FULL, null)
        val endings = listOf(
            Ending("measured", {}),
            Ending("could not connect: SERVER_FULL", { conn.onConnect = { conn.status.value = rejected } }),
            Ending("could not connect within 10s", { conn.onConnect = { conn.status.value = ConnectionStatus.Connecting } }),
            Ending("our user did not appear on the server", { conn.channelTree.value = ChannelTree() }),
            Ending("refused: $MUTED", { conn.channelTree.value = ChannelTree(users = mapOf(SESSION to SELF.copy(mute = true))) }),
            Ending("no built-in speaker", { conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE), EARPIECE) }),
            Ending("the speaker route was not taken", {
                conn.audioRoutes.value = AudioRoutes(listOf(EARPIECE, SPEAKER), EARPIECE)
            }),
            Ending(UNOPENED, { session.captureOpen.value = false }),
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
            // A cancelled check reports nothing: a result would be written as a failure.
            assertEquals("row $i", outcome.isCancelled, rig.returned == null)
            assertEquals("row $i", 1, rig.session.ends)
            assertEquals("row $i", 0, rig.conn.disconnectCalls)
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
        val session = FakeCheckSession(scope)
        val host = FakeCheckHost(session) { conn.onConnect() }
        val probe = FakeProbeCodec()

        /** What run returned; null while it runs and when it was cancelled. */
        var returned: CheckOutcome? = null
            private set

        /** The fake volume is the current route's id, so a test can tell on which route it was read. */
        fun start(): Deferred<CheckOutcome> =
            scope.async {
                val check = LatencyCheck(conn, host, probe) { CallVolume(conn.audioRoutes.value.current?.id?.toInt() ?: 0, 15) }
                check.run(ENDPOINT, FINGERPRINT).also { returned = it }
            }
                .also { scope.runCurrent() }
    }

    private companion object {
        const val SESSION = FakeCheckSession.SESSION
        const val OTHER = 8
        const val CODEC_DELAY = 312
        val ENDPOINT = MumbleEndpoint.parse("murmur.test")
        val FINGERPRINT = "ab".repeat(32)
        val EARPIECE = AudioRoute("1", AudioRoute.Type.EARPIECE)
        val SPEAKER = AudioRoute("2", AudioRoute.Type.SPEAKER)
        const val ENDED = "the connection ended before it was up"
        const val CONNECTED = "Dumble is connected to a server; disconnect first"
        const val REPLACED = "the connection to the server ended or was replaced during the check"
        const val UNOPENED =
            "capture did not open within 5s: is the microphone permission granted, or is another app using the microphone?"
        const val MUTED = "muted or suppressed on the server: Murmur drops loopback audio from such a user"
        val SELF = User(
            SESSION, "dumble-latency", 0, mute = false, deaf = false, selfMute = false, selfDeaf = false, suppress = false,
        )
    }
}

/** The connection's side of a check: connects [session] unless [grants] is off. */
class FakeCheckHost(private val session: FakeCheckSession, private val onConnect: () -> Unit) : CheckHost {
    var grants = true
    val connects = mutableListOf<Pair<MumbleEndpoint, String>>()

    /** What the check connected to hear capture's packets. */
    var captured: (CapturedPacket) -> Unit = {}; private set

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
        session.arrived = arrived
        onConnect()
        return session
    }
}
