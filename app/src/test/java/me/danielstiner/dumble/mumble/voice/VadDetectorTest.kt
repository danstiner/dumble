package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadDetectorTest {
    private fun frame(amp: Int, n: Int = FRAME_SAMPLES_10MS): ShortArray =
        ShortArray(n) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }  // ±amp square wave

    @Test fun burstAboveAdaptedFloorReadsHigh() {
        val d = EnergyVadDetector()
        val quiet = frame(100)
        repeat(200) { d.level(quiet, 0, FRAME_SAMPLES_10MS) }        // let floor adapt to ~ -50 dB
        assertEquals(0f, d.level(quiet, 0, FRAME_SAMPLES_10MS), 0.05f) // background ~0
        assertTrue("loud burst reads high", d.level(frame(8000), 0, FRAME_SAMPLES_10MS) > 0.9f)
    }

    @Test fun floorDoesNotInflateDuringSustainedSpeech() {
        val d = EnergyVadDetector()
        val loud = frame(8000)
        repeat(500) { assertTrue(d.level(loud, 0, FRAME_SAMPLES_10MS) > 0.9f) } // stays high the whole time
    }

    @Test fun silenceReadsZero() {
        val d = EnergyVadDetector()
        val silent = ShortArray(FRAME_SAMPLES_10MS)
        repeat(50) { d.level(silent, 0, FRAME_SAMPLES_10MS) }
        assertEquals(0f, d.level(silent, 0, FRAME_SAMPLES_10MS), 0.001f)
    }

    @Test fun faintSoundBelowAbsoluteGateStaysClosed() {
        val d = EnergyVadDetector()
        val silent = ShortArray(FRAME_SAMPLES_10MS)
        repeat(300) { d.level(silent, 0, FRAME_SAMPLES_10MS) }   // floor drops toward minDb
        // a faint sound (~ -70 dB, RMS ~10) sits well above the adapted floor but below the
        // absolute open gate → must NOT read as speech (this is the over-sensitivity fix).
        assertTrue("faint sound below the absolute gate stays closed",
            d.level(frame(10), 0, FRAME_SAMPLES_10MS) < 0.6f)
        // normal speech (~ -27 dB) still opens.
        assertTrue("normal speech opens", d.level(frame(1500), 0, FRAME_SAMPLES_10MS) > 0.9f)
    }

    @Test fun noneSuppressorIsIdentity() {
        val pcm = ShortArray(FRAME_SAMPLES_10MS) { (it % 7 - 3).toShort() }
        val copy = pcm.copyOf()
        NoiseSuppressor.None.process(pcm, 0, FRAME_SAMPLES_10MS)
        assertTrue(pcm.contentEquals(copy))
    }
}
