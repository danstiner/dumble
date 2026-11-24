package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LatencyResultsTest {
    private val time = TestTimeSource()
    private val t0 = time.markNow()
    private val device = DeviceFacts("Pixel 7a", "16", voiceCallVolume = 5, voiceCallVolumeMax = 7)

    private fun capture(inputLatency: Double? = 12.5) = CaptureStats(
        encodedPackets = 0, encodeErrors = 0, encodeMicrosMean = 500, encodeMicrosMax = 0, ringOverruns = 0,
        skippedSamples = 0, streamOverruns = 0, framesPerBurst = 192, droppedFrames = 0, inputLatencyMillis = inputLatency,
    )

    private fun playout(latency: Double? = 25.0) = PlayoutStats(
        latencyMs = latency, underruns = 0, concealedGaps = 0, droppedPackets = 0, shrunkPackets = 0,
        catchUpPackets = 0, contendedFills = 0, fillMicrosMax = 0, fillMicrosMean = 0, lostSamples = 0,
        outOfOrderPackets = 0, bufferedSamples = emptyMap(), targetSamples = emptyMap(),
    )

    private fun packet(frame: Long, at: Duration) = CapturedPacket(frame, ByteArray(0), t0 + at)

    private fun measurements(
        located: Located? = Located(3000, 0.9, 0.2, found = true),
        captured: List<CapturedPacket> = listOf(
            packet(100, 100.milliseconds), packet(102, 120.milliseconds),
            packet(104, 140.milliseconds), packet(106, 200.milliseconds),
        ),
        effectsAllOff: Boolean = true,
        captureStats: CaptureStats? = capture(),
        playoutStats: PlayoutStats? = playout(),
    ) = Measurements(
        sent = t0,
        returned = ArrivedPacket(
            1, 50, t0 + 90.milliseconds, aheadSamples = 1200, targetSamples = 480, writeAheadSamples = 240,
        ),
        captured = captured, located = located, codecDelaySamples = 312,
        effects = listOf("echo canceller", "noise suppressor"), effectsAllOff = effectsAllOff,
        route = AudioRoute("1", AudioRoute.Type.SPEAKER), voicePath = VoicePath.State(onUdp = true, roundTrip = 30.milliseconds),
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
                device.model = Pixel 7a
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
                receive.jitter_buffer = 25.00
                receive.jitter_buffer.target = 10.00
                receive.jitter_buffer.write_ahead = 5.00
                receive.codec_delay = 6.50
                receive.audio_output = 25.00
                send.input_buffer = 12.50
                send.encode = 20.50
                unaccounted = 23.00
                estimate.network_ms = 15.00
                detection.peak = 0.90
                detection.next_peak = 0.20
                """.trimIndent() + "\n",
                text(CheckOutcome.Measured(measurements())),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun theOnsetIsCorrectedByItsPlaceInItsPacket() {
        // The onset's real time is fixed; moving the packet grid 300 samples earlier puts it 300
        // samples into its packet, and the packet leaves 300 samples earlier.
        fun loopPhone(phase: Int): String {
            val packets = (0..4).map { j ->
                CapturedPacket(100L + 2 * j, ByteArray(0), t0 + 100.milliseconds + (960L * (j + 1) - phase).seconds / 48000)
            }
            val located = Located(960L * 3 + phase, 0.9, 0.2, found = true)
            return lines(text(CheckOutcome.Measured(measurements(located = located, captured = packets)))).getValue("loop.phone")
        }
        assertEquals(loopPhone(0), loopPhone(300))
    }

    @Test fun theCarrierIsChosenByFrameNumberAcrossAGap() {
        // The engine skipped to the newest frames: packet 3 holds frame 106, not 106 - 6 = frame 104's slot.
        val gapped = listOf(
            packet(100, 100.milliseconds), packet(102, 120.milliseconds),
            packet(106, 200.milliseconds), packet(108, 300.milliseconds),
        )
        val rows = lines(text(CheckOutcome.Measured(measurements(captured = gapped))))
        assertEquals("112.50", rows["loop.phone"])
    }

    @Test fun noCapturedPacketsNamesThatAsTheCause() {
        val rows = lines(text(CheckOutcome.Measured(measurements(located = null, captured = emptyList()))))
        assertEquals("not_detected", rows["result"])
        assertEquals("capture produced no packets", rows["likely_cause"])
    }

    @Test fun notDetectedPrintsNoLoopTime() {
        val weak = Located(0, 0.3, 0.29, found = false)
        val off = lines(text(CheckOutcome.Measured(measurements(located = weak))))
        assertEquals("not_detected", off["result"])
        assertEquals("volume, background noise, or an echo canceller in the audio hardware", off["likely_cause"])
        assertFalse("loop.phone" in off)
        assertFalse("unaccounted" in off)
        assertEquals("0.30", off["detection.peak"])
        assertEquals("90.00", off["network.round_trip"])

        val on = lines(text(CheckOutcome.Measured(measurements(located = weak, effectsAllOff = false))))
        assertEquals(
            "volume, background noise, or an echo canceller in the audio hardware, or effects still on",
            on["likely_cause"],
        )
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

        val failed = lines(text(CheckOutcome.Failed("the probe did not come back from the server", measurements(located = null))))
        assertEquals("failed", failed["result"])
        assertEquals("the probe did not come back from the server", failed["reason"])
        assertFalse("loop.phone" in failed)

        assertEquals("failed", lines(text(CheckOutcome.Failed("x", null)))["result"])
    }
}
