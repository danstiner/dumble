package me.danielstiner.dumble.mumble.voice.latency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
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
 * Test D through a server: connects a session of its own, takes the speaker, opens capture, and
 * runs a [ProbeExchange] on it, ending the check if the session, the route or capture changes
 * under it.
 */
class LatencyCheck(
    private val connection: Connection,
    private val host: CheckHost,
    private val probe: ProbeCodec,
    private val waitUntil: suspend (ComparableTimeMark) -> Unit = ::delayUntil,
) {
    /** Runs on the caller's context, which must not be the main thread ([ProbeExchange.run]);
     *  [progress] names each step for the page. */
    suspend fun run(endpoint: MumbleEndpoint, fingerprint: String, progress: (String) -> Unit = {}): CheckOutcome {
        val exchange = ProbeExchange(probe, waitUntil)
        var pathAtOnset = VoicePath.State()
        val check = host.connectForCheck(endpoint, USERNAME, fingerprint, exchange::onCaptured, exchange::onArrived)
            ?: return CheckOutcome.Refused("Dumble is connected to a server; disconnect first")

        fun measurements(sent: ComparableTimeMark, located: Located?): Measurements {
            val effects = check.effects
            return Measurements(
                sent = sent, returned = exchange.returned, captured = exchange.captured, located = located,
                codecDelaySamples = exchange.codecDelaySamples,
                effects = effects?.names.orEmpty(), effectsAllOff = effects?.allOff() ?: false,
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
                null -> throw CheckFailure("could not connect within $CONNECT_TIMEOUT")
                is ConnectionStatus.Error ->
                    throw CheckFailure("could not connect: ${answer.kind}" + answer.detail?.let { " ($it)" }.orEmpty())
                else -> throw CheckFailure("the connection ended before it was up")
            }
            val session = connected.sessionId
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

                progress("opening the microphone")
                // The status watcher misses a link replaced between two reads of an equal status.
                abortOn(check.ended, "the connection to the server ended or was replaced during the check") { it }
                connection.requestCapture()
                val unopened = "capture did not open within $CAPTURE_TIMEOUT: is the microphone permission granted, " +
                    "or is another app using the microphone?"
                within(unopened, CAPTURE_TIMEOUT) { check.captureOpen.first { it } }
                abortOn(check.captureOpen, "capture closed during the check") { !it }

                val located = exchange.run(check, session, progress) { pathAtOnset = connection.voicePath.value }
                CheckOutcome.Measured(measurements(checkNotNull(exchange.sent), located))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is CheckFailure) e.message else "the check failed unexpectedly: $e"
            CheckOutcome.Failed(reason, exchange.sent?.let { measurements(it, located = null) })
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
            throw CheckFailure(reason)
        }
    }

    private companion object {
        const val USERNAME = "dumble-latency"
        val CONNECT_TIMEOUT = 10.seconds
        val STEP_TIMEOUT = 2.seconds
        val CAPTURE_TIMEOUT = 5.seconds

        suspend fun <T : Any> within(failure: String, timeout: Duration = STEP_TIMEOUT, await: suspend () -> T): T =
            withTimeoutOrNull(timeout) { await() } ?: throw CheckFailure(failure)
    }
}
