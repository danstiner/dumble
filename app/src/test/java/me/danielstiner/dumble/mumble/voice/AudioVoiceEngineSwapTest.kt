package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioVoiceEngineSwapTest {
    private class TagVad(val tag: Float) : VadDetector {
        override fun level(pcm: ShortArray, off: Int, n: Int) = tag
    }
    @Test fun swapRoutesLevelToNewDetector() {
        val gate = TransmitGate()
        val proc = TransmitProcessor(NoiseSuppressor.None, TagVad(0.1f), gate)
        proc.process(ShortArray(CAPTURE_SAMPLES))
        assertEquals(0.1f, proc.lastVadProb, 0f)
        proc.vad = TagVad(0.9f)
        proc.process(ShortArray(CAPTURE_SAMPLES))
        assertEquals(0.9f, proc.lastVadProb, 0f)
    }
}
