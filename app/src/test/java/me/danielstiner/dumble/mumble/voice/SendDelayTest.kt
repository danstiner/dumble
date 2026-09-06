package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.VoicePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class SendDelayTest {

    private fun capture(inputLatencyMillis: Double?, encodeMicrosMean: Long = 0) = CaptureStats(
        encodedPackets = 0, encodeErrors = 0, encodeMicrosMean = encodeMicrosMean, encodeMicrosMax = 0,
        ringOverruns = 0, skippedSamples = 0, streamOverruns = 0, framesPerBurst = 0,
        droppedFrames = 0, inputLatencyMillis = inputLatencyMillis,
    )

    /** 12 ms in the device, a 20 ms packet plus 0.4 ms of Opus, half of a 6 ms round trip. */
    @Test fun theStepsAddUp() {
        val d = SendDelay.of(capture(12.0, 400), VoicePath.State(onUdp = true, roundTrip = 6.milliseconds), 40.milliseconds)
        assertEquals(12.milliseconds, d.inputBuffer)
        assertEquals(20.4.milliseconds, d.encode)
        assertEquals(3.milliseconds, d.network)
        assertEquals(35.4.milliseconds, d.total)
    }

    /** The round trip is the leg carrying voice: UDP once promoted, the TCP ping until then. */
    @Test fun theNetworkFollowsThePathCarryingVoice() {
        val tunneled = SendDelay.of(null, VoicePath.State(), 40.milliseconds)
        assertEquals(20.milliseconds, tunneled.network)
        val promoted = SendDelay.of(null, VoicePath.State(onUdp = true, roundTrip = 6.milliseconds), 40.milliseconds)
        assertEquals(3.milliseconds, promoted.network)
    }

    /** No capture session is no device reading, and no round trip yet is no network one. */
    @Test fun nothingReadIsNothingClaimed() {
        val d = SendDelay.of(null, VoicePath.State(), null)
        assertNull(d.inputBuffer)
        assertNull(d.encode)
        assertNull(d.network)
        assertNull(d.total)
    }

    /** A step with no reading is skipped, not zeroed: the total is a floor either way. */
    @Test fun anAbsentStepShrinksTheFloor() {
        val d = SendDelay.of(capture(null, 400), VoicePath.State(), null)
        assertNull(d.inputBuffer)
        assertEquals(20.4.milliseconds, d.total)
    }
}
