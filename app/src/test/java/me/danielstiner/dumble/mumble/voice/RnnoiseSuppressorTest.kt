package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Empirical verification (real host RNNoise) that disabling denoising leaves the audio raw while the
 * VAD probability stays byte-identical to the enabled path — i.e. the scratch-copy path advances the
 * DenoiseState exactly like in-place processing, so voice-activation is unaffected by the toggle.
 */
class RnnoiseSuppressorTest {
    // A 300 Hz tone spanning frameIndex so consecutive frames are phase-continuous, giving RNNoise
    // real, evolving content (silence would make "enabled changed the audio" trivially true).
    private fun frame(frameIndex: Int) = ShortArray(FRAME_SAMPLES_10MS) { i ->
        val t = (frameIndex.toLong() * FRAME_SAMPLES_10MS + i).toDouble()
        (3000.0 * sin(2.0 * PI * 300.0 * t / 48000.0)).toInt().toShort()
    }

    @Test fun disabledKeepsAudioRawAndVadMatchesEnabled() {
        val enabled = RnnoiseSuppressor()                            // denoise on (default)
        val disabled = RnnoiseSuppressor().apply { setDenoiseEnabled(false) }
        var enabledAlteredAudio = false
        try {
            for (f in 0 until 30) {
                val bufEnabled = frame(f)
                val bufDisabled = frame(f)
                enabled.process(bufEnabled, 0, FRAME_SAMPLES_10MS)
                disabled.process(bufDisabled, 0, FRAME_SAMPLES_10MS)

                // Disabled path must not touch the audio.
                assertArrayEquals("disabled must leave audio raw (frame $f)", frame(f), bufDisabled)
                // Both must produce the exact same VAD probability => identical DenoiseState trajectory.
                assertEquals("VAD prob identical enabled vs disabled (frame $f)",
                    enabled.lastVadProb, disabled.lastVadProb, 0f)

                if (!bufEnabled.contentEquals(frame(f))) enabledAlteredAudio = true
            }
            assertTrue("enabled path should actually denoise (alter) the audio", enabledAlteredAudio)
        } finally {
            enabled.close(); disabled.close()
        }
    }
}
