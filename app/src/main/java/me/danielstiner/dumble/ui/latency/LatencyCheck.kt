package me.danielstiner.dumble.ui.latency

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
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.latency.CallVolume
import me.danielstiner.dumble.mumble.voice.latency.CheckEffects
import me.danielstiner.dumble.mumble.voice.latency.CheckFailure
import me.danielstiner.dumble.mumble.voice.latency.CheckHost
import me.danielstiner.dumble.mumble.voice.latency.CheckOutcome
import me.danielstiner.dumble.mumble.voice.latency.CheckSession
import me.danielstiner.dumble.mumble.voice.latency.Heard
import me.danielstiner.dumble.mumble.voice.latency.Measurements
import me.danielstiner.dumble.mumble.voice.latency.ProbeCodec
import me.danielstiner.dumble.mumble.voice.latency.ProbeExchange
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The on-device latency check, through a server: connects a session of its own, takes the speaker,
 * opens capture, and runs a [ProbeExchange] on it, ending the check if the session, the route or
 * capture changes under it.
 */
class LatencyCheck(
    private val connection: Connection,
    private val host: CheckHost,
    private val probe: ProbeCodec,
    /** The voice-call stream's volume now; null when it cannot be read. */
    private val callVolume: () -> CallVolume?,
) {
    /** Runs on the caller's context, which must not be the main thread ([ProbeExchange.run]);
     *  [progress] names each step for the page. The server must present the certificate with
     *  [fingerprint]: the lowercase hex SHA-256 of its leaf in DER, compared exactly, so not as
     *  `openssl x509 -fingerprint -sha256` prints it, uppercase with colons. */
    suspend fun run(endpoint: MumbleEndpoint, fingerprint: String, progress: (String) -> Unit = {}): CheckOutcome {
        val exchange = ProbeExchange(probe)
        // Read as t0 is marked: an abort clears the route and the effects before a partial is built.
        var pathAtOnset = VoicePath.State()
        var routeAtOnset: AudioRoute? = null
        var effectsAtOnset: CheckEffects? = null
        var volume: CallVolume? = null
        // The session's pin store for this one check, holding the one certificate it was given.
        // Trust is then decided as for any connect, and the user's pins never learn this server.
        val pins = InMemoryPinStore().apply { put(endpoint.address, fingerprint) }
        val check = host.connectForCheck(endpoint, USERNAME, pins, exchange::onCaptured, exchange::onArrived)
            ?: return CheckOutcome.Refused("Dumble is connected to a server; disconnect first")

        fun measurements(sent: ComparableTimeMark, heard: Heard?): Measurements {
            val effects = effectsAtOnset
            return Measurements(
                sent = sent, returned = exchange.returned, heard = heard,
                codecDelaySamples = exchange.codecDelaySamples,
                effects = effects?.names.orEmpty(), effectsAllOff = effects?.allOff() ?: false,
                route = routeAtOnset, callVolume = volume, voicePath = pathAtOnset,
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
                is ConnectionStatus.PinMismatch ->
                    throw CheckFailure("the server's certificate ${answer.presented} is not the one given")
                else -> throw CheckFailure("the connection ended before it was up")
            }
            val session = connected.sessionId
            whileConnected(check, connected) {
                val self = within("our user did not appear on the server") {
                    connection.channelTree.mapNotNull { it.users[session] }.first()
                }
                if (self.mute || self.suppress || self.selfMute) {
                    return@whileConnected CheckOutcome.Refused(
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
                connection.requestCapture()
                val unopened = "capture did not open within $CAPTURE_TIMEOUT: is the microphone permission granted, " +
                    "or is another app using the microphone?"
                within(unopened, CAPTURE_TIMEOUT) { check.captureOpen.first { it } }
                abortOn(check.captureOpen, "capture closed during the check") { !it }

                // While the call is up and on the speaker: the stream's volume is per route, and
                // the route goes with the session. Not at the onset, where a system call would
                // sit between marking t0 and the send.
                volume = callVolume()
                val heard = exchange.run(check, session, progress) {
                    pathAtOnset = connection.voicePath.value
                    routeAtOnset = connection.audioRoutes.value.current
                    effectsAtOnset = check.effects
                }
                CheckOutcome.Measured(measurements(checkNotNull(exchange.sent), heard))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is CheckFailure) e.message else "the check failed unexpectedly: $e"
            CheckOutcome.Failed(reason, exchange.sent?.let { measurements(it, heard = null) })
        } finally {
            // Ending the check's own session puts back everything the check changed. Not
            // disconnect(), which would end a session the user opened since.
            check.end()
        }
    }

    /** Runs [steps] beside watchers that end the check once [check]'s session ends, the status
     *  leaves [connected] or a call takes the microphone; [steps] adds its own with [abortOn]. */
    private suspend fun whileConnected(
        check: CheckSession,
        connected: ConnectionStatus.Connected,
        steps: suspend CoroutineScope.() -> CheckOutcome,
    ): CheckOutcome = coroutineScope {
        // The status is the connection's: [connected] may be a session the user opened since, and
        // a link replaced between two reads of an equal status never shows in it.
        val ended = "the connection to the server ended or was replaced during the check"
        if (check.ended.value) throw CheckFailure(ended)
        abortOn(check.ended, ended) { it }
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
