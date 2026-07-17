package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineSpeakingTest {
    private class SteadyAudioIn(private val amp: Int) : AudioIn {
        override fun read(out: ShortArray, n: Int): Int {
            for (i in 0 until n) out[i] = (if (i % 2 == 0) amp else -amp).toShort(); return n
        }
        override fun close() {}
    }
    private class AlwaysSpeechVad : VadDetector {
        override fun level(pcm: ShortArray, off: Int, n: Int): Float = 1.0f
    }

    @Test fun selfTransmittingTracksRealSendsWithReleaseHold() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(6000) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = AlwaysSpeechVad(), gateOpenLevel = 0.5f,
        ).also { it.start() }
        repeat(5) { engine.nextOutgoingFrame(0) }
        assertTrue("transmitting while sending", engine.selfTransmitting.value)
        engine.setMuted(true)                                          // -> one terminator, then silence
        repeat(TransmitHold.TRANSMIT_HOLD_TICKS + 3) { engine.nextOutgoingFrame(0) }
        assertFalse("released after hold once sending stops", engine.selfTransmitting.value)
        engine.stop()
    }

    @Test fun setDeafenedTogglesEngineFlag() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(500) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = EnergyVadDetector(),
        ).also { it.start() }
        assertFalse(engine.isDeafened)
        engine.setDeafened(true)
        assertTrue(engine.isDeafened)
        engine.stop()
        assertFalse("reset on stop", engine.isDeafened)
    }
}
