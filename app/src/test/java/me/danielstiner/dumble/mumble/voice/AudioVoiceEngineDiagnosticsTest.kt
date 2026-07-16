package me.danielstiner.dumble.mumble.voice

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
}
