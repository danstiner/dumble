package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TransmitProcessorRawInputTest {
    /** Suppressor that overwrites every sample with 1 (denoise "in place"). */
    private class OverwriteSuppressor : NoiseSuppressor {
        override fun process(pcm: ShortArray, off: Int, n: Int) { for (i in off until off + n) pcm[i] = 1 }
        override fun close() {}
    }
    /** VAD that records the first sample it was handed. */
    private class RecordingVad : VadDetector {
        var firstSample: Short = 0
        override fun level(pcm: ShortArray, off: Int, n: Int): Float { firstSample = pcm[off]; return 0f }
    }

    @Test fun vadSeesRawNotDenoised() {
        val vad = RecordingVad()
        val proc = TransmitProcessor(OverwriteSuppressor(), vad, TransmitGate())
        val cap = ShortArray(CAPTURE_SAMPLES) { 500 }   // raw sample value 500
        proc.process(cap)
        assertEquals("VAD must see raw (500), not denoised (1)", 500.toShort(), vad.firstSample)
        assertEquals("output stays denoised", 1.toShort(), cap[0])
    }
}
