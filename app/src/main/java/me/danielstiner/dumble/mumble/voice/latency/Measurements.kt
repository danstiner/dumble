package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration

sealed interface CheckOutcome {
    data class Refused(val reason: String) : CheckOutcome
    data class Failed(val reason: String, val partial: Measurements?) : CheckOutcome
    data class Measured(val measurements: Measurements) : CheckOutcome
}

/** The voice-call stream's volume, as an index out of [max]. */
data class CallVolume(val index: Int, val max: Int)

/** What a check measured, raw; times on the check's clock. */
data class Measurements(
    /** t0: the onset packet handed to inject. */
    val sent: ComparableTimeMark,
    /** t1's packet: our own session's first return of the onset frame, with the depth read then. */
    val returned: ArrivedPacket?,
    /** t2 and the match; null when capture produced nothing. */
    val heard: Heard?,
    val codecDelaySamples: Int,
    val effects: List<String>, val effectsAllOff: Boolean,
    val route: AudioRoute?,
    /** Read on the speaker, before the probe. */
    val callVolume: CallVolume?,
    /** At t0: UDP can be promoted mid-check, after the probe went through the tunnel. */
    val voicePath: VoicePath.State,
    val roundTripTime: Duration?, val captureStats: CaptureStats?, val playoutStats: PlayoutStats?,
)
