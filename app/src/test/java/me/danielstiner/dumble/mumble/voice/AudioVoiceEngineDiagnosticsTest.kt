package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineDiagnosticsTest {
    private class SteadyAudioIn(private val amp: Int) : AudioIn {
        override fun read(out: ShortArray, n: Int): Int {
            for (i in 0 until n) out[i] = (if (i % 2 == 0) amp else -amp).toShort(); return n
        }
        override fun close() {}
    }

    @Test fun diagnosticsPopulateWithRawAndPostGainLevels() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(6000) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = EnergyVadDetector(),
        ).also { it.start() }
        repeat(30) { engine.nextOutgoingFrame(0) }   // cross a DIAG_INTERVAL (25) boundary
        val d = engine.diagnostics.value
        engine.stop()
        assertTrue("connected", d.connected)
        assertTrue("raw level measured (~ -14.7 dBFS for amp 6000)", d.rawDbFs in -20f..-10f)
        assertTrue("post-gain level measured", d.postGainDbFs.isFinite())
    }

    private class AlwaysSpeechVad : VadDetector {
        override fun level(pcm: ShortArray, off: Int, n: Int): Float = 1.0f
    }

    @Test fun agcGainDbIsZeroWhenAgcDisabledDespiteFrozenGain() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(500) }, { FakeAudioOut() },   // quiet -> gain adapts UP
            suppressor = NoiseSuppressor.None, vad = AlwaysSpeechVad(),
        ).also { it.start() }
        repeat(200) { engine.nextOutgoingFrame(0) }                        // let gain adapt above unity
        assertTrue("precondition: gain adapted (agcGainDb > 3 dB)", engine.diagnostics.value.agcGainDb > 3f)
        engine.setAgcEnabled(false)                                        // disable -> gain frozen, not applied
        repeat(30) { engine.nextOutgoingFrame(0) }                        // cross a diag interval
        val agcGainDb = engine.diagnostics.value.agcGainDb
        engine.stop()
        assertEquals("disabled AGC -> effective gain 0 dB (no phantom)", 0.0, agcGainDb.toDouble(), 0.01)
    }
}
