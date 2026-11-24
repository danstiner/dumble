package me.danielstiner.dumble.mumble.voice.latency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.FRAME_NUMBER_SAMPLES
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import me.danielstiner.dumble.mumble.voice.TRANSMIT_BITRATE
import me.danielstiner.dumble.mumble.voice.TRANSMIT_PACKET_INTERVAL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface CheckOutcome {
    data class Refused(val reason: String) : CheckOutcome
    data class Failed(val reason: String, val partial: Measurements?) : CheckOutcome
    data class Measured(val m: Measurements) : CheckOutcome
}

/** What a check measured, raw; times on the check's clock. */
data class Measurements(
    /** t0: the onset packet handed to inject. */
    val sent: ComparableTimeMark,
    /** t1's packet: our own session's first return of the onset frame, with the depth read then. */
    val returned: ArrivedPacket?,
    val captured: List<CapturedPacket>,
    val located: Located?,
    val codecDelaySamples: Int,
    val effects: List<String>, val effectsAllOff: Boolean,
    val route: AudioRoute?,
    /** At t0: UDP can be promoted mid-check, after the probe went through the tunnel. */
    val voicePath: VoicePath.State,
    val roundTripTime: Duration?, val captureStats: CaptureStats?, val playoutStats: PlayoutStats?,
)

/**
 * Test D through a server: on a session of its own, the check sends a warm spurt and then the
 * probe spurt to the server's loopback, plays the return through the speaker, and records what
 * capture hears.
 *
 * - t0: the packet carrying the probe's onset is sent.
 * - t1: it comes back under our own session, by frame number; t1 − t0 is the network round trip.
 * - t2: the onset leaves capture, found by decoding capture's packets; t2 − t1 is the phone loop.
 *
 * Capture's gate opens with the probe spurt and closes 1 s after its last packet returns, 2 s after
 * that packet was sent at the latest.
 */
class LatencyCheck(
    private val connection: Connection,
    private val host: CheckHost,
    private val probe: ProbeCodec,
    private val waitUntil: suspend (ComparableTimeMark) -> Unit = ::delayUntil,
) {
    /**
     * Runs on the caller's context; [progress] names each step for the page. Call it off the main
     * thread: it paces sends on deadlines and decodes capture in [ProbeCodec.locate].
     */
    suspend fun run(endpoint: MumbleEndpoint, fingerprint: String, progress: (String) -> Unit = {}): CheckOutcome {
        var sent: ComparableTimeMark? = null
        var pathAtOnset = VoicePath.State()
        var codec = 0
        // Filled by the session's two callbacks, on the capture pump's and the transport's threads.
        val captured = ConcurrentLinkedQueue<CapturedPacket>()
        val returned = MutableStateFlow<ArrivedPacket?>(null)
        val lastReturned = MutableStateFlow<ComparableTimeMark?>(null)
        // Ours and the spurt's last frame, known only once connected and encoded: no packet of
        // ours can arrive before both are set.
        val ourSession = AtomicLong(-1)
        val lastFrame = AtomicLong(-1)
        val check = host.connectForCheck(endpoint, USERNAME, fingerprint, { captured += it }) { packet ->
            if (packet.session.toLong() == ourSession.get()) {
                if (packet.frameNumber == ONSET_FRAME) returned.compareAndSet(null, packet)
                if (packet.frameNumber == lastFrame.get()) lastReturned.compareAndSet(null, packet.at)
            }
        } ?: return CheckOutcome.Refused("Dumble is connected to a server; disconnect first")
        val clock = check.clock

        fun measurements(sent: ComparableTimeMark, located: Located?, heard: List<CapturedPacket>): Measurements {
            val effects = check.effects
            return Measurements(
                sent = sent, returned = returned.value, captured = heard, located = located,
                codecDelaySamples = codec, effects = effects?.names.orEmpty(), effectsAllOff = effects?.allOff() ?: false,
                route = connection.audioRoutes.value.current, voicePath = pathAtOnset,
                roundTripTime = connection.roundTripTime.value, captureStats = connection.captureStats.value,
                playoutStats = connection.playoutStats.value,
            )
        }

        return try {
            progress("connecting to ${endpoint.label}")
            val answer = withTimeoutOrNull(CONNECT_TIMEOUT) {
                connection.status.first { it !is ConnectionStatus.Connecting && it !is ConnectionStatus.Handshaking }
            }
            val connected = when (answer) {
                is ConnectionStatus.Connected -> answer
                null -> throw Failure("could not connect within $CONNECT_TIMEOUT")
                is ConnectionStatus.Error ->
                    throw Failure("could not connect: ${answer.kind}" + answer.detail?.let { " ($it)" }.orEmpty())
                else -> throw Failure("the connection ended before it was up")
            }
            val session = connected.sessionId
            ourSession.set(session.toLong())
            abortingOn(connected) {
                val self = within("our user did not appear on the server") {
                    connection.channelTree.mapNotNull { it.users[session] }.first()
                }
                if (connection.selfState.value.muted || self.mute || self.suppress || self.selfMute) {
                    return@abortingOn CheckOutcome.Refused(
                        "muted or suppressed on the server: Murmur drops loopback audio from such a user",
                    )
                }

                progress("taking the speaker")
                val speaker = within("no built-in speaker") {
                    connection.audioRoutes.mapNotNull { routes ->
                        routes.available.firstOrNull { it.type == AudioRoute.Type.SPEAKER }
                    }.first()
                }
                connection.requestAudioRoute(speaker.id)
                within("the speaker route was not taken") {
                    connection.audioRoutes.first { it.current?.type == AudioRoute.Type.SPEAKER }
                }
                // A cleared route is the session ending, which the status watcher reports.
                abortOn(connection.audioRoutes, "the audio route left the speaker (did a headset connect?)") {
                    it.current != null && it.current.type != AudioRoute.Type.SPEAKER
                }

                val warm = probe.encodeProbeSpurt(0, WARM_LEAD, ZERO, TRANSMIT_BITRATE)
                val spurt = probe.encodeProbeSpurt(PROBE_PEAK, PROBE_LEAD, PROBE_TAIL, TRANSMIT_BITRATE)
                codec = probe.codecDelaySamples(TRANSMIT_BITRATE)
                if (warm.isEmpty() || spurt.size <= ONSET_PACKET || codec < 0) {
                    throw Failure("the probe could not be encoded")
                }
                val last = SPURT_START + TRANSMIT_PACKET_INTERVAL * spurt.lastIndex
                lastFrame.set(frameAt(last))

                progress("opening the microphone")
                // The status watcher misses a link replaced between two reads of an equal status.
                abortOn(check.ended, "the connection to the server ended or was replaced during the check") { it }
                connection.requestCapture()
                val unopened = "capture did not open within $CAPTURE_TIMEOUT: is the microphone permission granted, " +
                    "or is another app using the microphone?"
                within(unopened, CAPTURE_TIMEOUT) { check.captureOpen.first { it } }
                abortOn(check.captureOpen, "capture closed during the check") { !it }

                progress("sending the probe")
                val epoch = clock.markNow()
                suspend fun send(packets: List<ByteArray>, start: Duration, before: (Int) -> Unit = {}) {
                    for ((i, packet) in packets.withIndex()) {
                        val at = start + TRANSMIT_PACKET_INTERVAL * i
                        waitUntil(epoch + at)
                        before(i)
                        check.inject(frameAt(at), packet, terminator = i == packets.lastIndex)
                    }
                }
                send(warm, ZERO)
                send(spurt, SPURT_START) { m ->
                    if (m == 0) check.setGateOpen(true)
                    if (m == ONSET_PACKET) {
                        sent = clock.markNow()
                        pathAtOnset = connection.voicePath.value
                    }
                }

                progress("listening for the probe")
                val cap = epoch + last + RETURN_TIMEOUT
                val back = withTimeoutOrNull(-cap.elapsedNow()) { lastReturned.filterNotNull().first() }
                if (back != null) waitUntil(minOf(back + LISTEN_AFTER_RETURN, cap))
                check.setGateOpen(false)
                if (returned.value == null) throw Failure("the probe did not come back from the server")
                val heard = captured.toList()
                CheckOutcome.Measured(measurements(checkNotNull(sent), probe.locate(heard, codec), heard))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is Failure) e.message else "the check failed unexpectedly: $e"
            CheckOutcome.Failed(reason, sent?.let { measurements(it, located = null, captured.toList()) })
        } finally {
            // The session is the check's own: ending it puts back everything the check changed.
            connection.disconnect()
        }
    }

    /** Runs [steps] beside watchers that end the check once the status leaves [connected] or a call
     *  takes the microphone; [steps] adds its own with [abortOn]. */
    private suspend fun abortingOn(
        connected: ConnectionStatus.Connected,
        steps: suspend CoroutineScope.() -> CheckOutcome,
    ): CheckOutcome = coroutineScope {
        abortOn(connection.status, "the connection to the server ended") { it != connected }
        abortOn(connection.callHeld, "another call took the microphone") { it }
        steps().also { coroutineContext.cancelChildren() }
    }

    private fun <T> CoroutineScope.abortOn(flow: Flow<T>, reason: String, ended: suspend (T) -> Boolean) {
        launch {
            flow.first(ended)
            throw Failure(reason)
        }
    }

    /** Ends a check with [message] as its reason. Not a CancellationException: thrown by a watcher,
     *  it has to cancel the steps rather than end quietly. */
    private class Failure(override val message: String) : Exception(message)

    private companion object {
        const val USERNAME = "dumble-latency"
        val CONNECT_TIMEOUT = 10.seconds
        val STEP_TIMEOUT = 2.seconds
        val CAPTURE_TIMEOUT = 5.seconds
        const val PROBE_PEAK = 1640
        /** Plus the 250 ms probe, sent silent (LatencyProbe.h): the 1 s warm spurt. */
        val WARM_LEAD = 750.milliseconds
        val PROBE_LEAD = 200.milliseconds
        val PROBE_TAIL = 200.milliseconds
        val SPURT_START = 2.seconds
        val RETURN_TIMEOUT = 2.seconds
        val LISTEN_AFTER_RETURN = 1.seconds

        /** The packet carrying the probe's onset, which marks t0. */
        val ONSET_PACKET = (PROBE_LEAD / TRANSMIT_PACKET_INTERVAL).toInt()
            .also { require(TRANSMIT_PACKET_INTERVAL * it == PROBE_LEAD) { "PROBE_LEAD must be whole packets" } }
        val ONSET_FRAME = frameAt(SPURT_START + TRANSMIT_PACKET_INTERVAL * ONSET_PACKET)

        // A wall clock, as the desktop client's: the gap between the spurts advances it, so the
        // playout's jitter estimator rebases on the probe spurt rather than reading it as a second late.
        fun frameAt(at: Duration): Long = (at / 1.seconds * SAMPLE_RATE / FRAME_NUMBER_SAMPLES).roundToLong()

        suspend fun <T : Any> within(failure: String, timeout: Duration = STEP_TIMEOUT, await: suspend () -> T): T =
            withTimeoutOrNull(timeout) { await() } ?: throw Failure(failure)
    }
}

/** The check's pacing: [delay] until [deadline], read fresh, so a late send never delays the next. */
internal suspend fun delayUntil(deadline: ComparableTimeMark) = delay(-deadline.elapsedNow())
