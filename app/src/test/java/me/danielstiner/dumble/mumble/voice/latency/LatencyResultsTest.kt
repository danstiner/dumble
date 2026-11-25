package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class LatencyResultsTest {
    private val time = TestTimeSource()
    private val t0 = time.markNow()
    private val device = DeviceFacts("Phone", "16")

    private fun capture(inputLatency: Double? = 12.5) = CaptureStats(
        encodedPackets = 0, encodeErrors = 0, encodeMicrosMean = 500, encodeMicrosMax = 0, ringOverruns = 0,
        skippedSamples = 0, streamOverruns = 0, framesPerBurst = 192, droppedFrames = 0, inputLatencyMillis = inputLatency,
    )

    private fun playout(latency: Double? = 25.0) = PlayoutStats(
        latencyMs = latency, underruns = 0, concealedGaps = 0, droppedPackets = 0, shrunkPackets = 1,
        catchUpPackets = 2, contendedFills = 0, fillMicrosMax = 0, fillMicrosMean = 0, lostSamples = 0,
        outOfOrderPackets = 0, bufferedSamples = emptyMap(), targetSamples = emptyMap(),
    )

    private fun measurements(
        heard: Heard? = Heard(t0 + 202500.microseconds, 0.9, 0.2),
        effectsAllOff: Boolean = true,
        captureStats: CaptureStats? = capture(),
        playoutStats: PlayoutStats? = playout(),
        voicePath: VoicePath.State = VoicePath.State(onUdp = true, roundTrip = 30.milliseconds),
    ) = Measurements(
        sent = t0,
        returned = ArrivedPacket(1, 50, t0 + 90.milliseconds, aheadSamples = 1440),
        heard = heard, codecDelaySamples = 312,
        effects = listOf("echo canceller", "noise suppressor"), effectsAllOff = effectsAllOff,
        route = AudioRoute("1", AudioRoute.Type.SPEAKER), callVolume = CallVolume(5, 7), voicePath = voicePath,
        roundTripTime = 40.milliseconds, captureStats = captureStats, playoutStats = playoutStats,
    )

    private fun text(outcome: CheckOutcome) = LatencyResults.text(outcome, device)

    private fun lines(text: String) = text.lines().filter { it.isNotEmpty() }.associate { line ->
        line.substringBefore(" = ") to line.substringAfter(" = ")
    }

    @Test fun aMeasuredRunGivesEveryRow() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals(
                """
                result = ok
                device.model = Phone
                device.android = 16
                route = speaker
                voice_path = udp
                voice_call_volume = 5/7
                capture.frames_per_burst = 192
                effects = echo canceller, noise suppressor
                effects_off = true
                network.round_trip = 90.00
                network.ping = 30.00
                loop.phone = 112.50
                receive.jitter_buffer = 30.00
                receive.shrunk_packets = 1
                receive.catch_up_packets = 2
                receive.codec_delay = 6.50
                receive.audio_output = 25.00
                send.input_buffer = 12.50
                send.encode = 20.50
                unaccounted = 18.00
                detection.peak = 0.90
                detection.next_peak = 0.20
                """.trimIndent() + "\n",
                text(CheckOutcome.Measured(measurements())),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun noCapturedPacketsNamesThatAsTheCause() {
        val rows = lines(text(CheckOutcome.Measured(measurements(heard = null))))
        assertEquals("not_detected", rows["result"])
        assertEquals("capture produced no packets", rows["likely_cause"])
    }

    @Test fun notDetectedPrintsNoLoopTime() {
        val weak = Heard(null, 0.3, 0.29)
        val off = lines(text(CheckOutcome.Measured(measurements(heard = weak))))
        assertEquals("not_detected", off["result"])
        assertEquals("volume, background noise, or an echo canceller in the audio hardware", off["likely_cause"])
        assertFalse("loop.phone" in off)
        assertFalse("unaccounted" in off)
        assertEquals("0.30", off["detection.peak"])
        assertEquals("90.00", off["network.round_trip"])

        val on = lines(text(CheckOutcome.Measured(measurements(heard = weak, effectsAllOff = false))))
        assertEquals(
            "volume, background noise, or an echo canceller in the audio hardware, or effects still on",
            on["likely_cause"],
        )
    }

    @Test fun onTcpThePingIsTheControlChannels() {
        // A reply while tunneled still sets the UDP round trip.
        val tunneled = measurements(voicePath = VoicePath.State(onUdp = false, roundTrip = 30.milliseconds))
        val rows = lines(text(CheckOutcome.Measured(tunneled)))
        assertEquals("tcp", rows["voice_path"])
        assertEquals("40.00", rows["network.ping"])
    }

    @Test fun aMissingStreamLatencyLeavesUnaccountedOut() {
        for (m in listOf(measurements(playoutStats = playout(null)), measurements(captureStats = capture(null)))) {
            val rows = lines(text(CheckOutcome.Measured(m)))
            assertEquals("112.50", rows["loop.phone"])
            assertFalse("unaccounted" in rows)
        }
    }

    @Test fun refusedAndFailedPrintTheirReason() {
        val refused = lines(text(CheckOutcome.Refused("Dumble is connected to a server; disconnect first")))
        assertEquals("refused", refused["result"])
        assertEquals("Dumble is connected to a server; disconnect first", refused["reason"])

        val failed = lines(text(CheckOutcome.Failed("the probe did not come back from the server", measurements(heard = null))))
        assertEquals("failed", failed["result"])
        assertEquals("the probe did not come back from the server", failed["reason"])
        assertFalse("loop.phone" in failed)

        assertEquals("failed", lines(text(CheckOutcome.Failed("x", null)))["result"])
    }
}
