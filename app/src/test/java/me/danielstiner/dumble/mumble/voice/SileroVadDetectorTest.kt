package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class SileroVadDetectorTest {
    private fun bytes() = File("src/main/assets/silero_vad_16k_op15.onnx").readBytes()
    private fun det() = SileroVadDetector(SileroOnnxSession(bytes()))
    private fun silence() = ShortArray(480)

    /**
     * Deterministic speech-ish stand-in: a 120 Hz pitch + three decaying formants (F1/F2/F3),
     * a 4 Hz syllable-rate amplitude envelope, and broadband pseudo-noise (five incommensurate
     * high-frequency sines standing in for breathiness) so the spectrum isn't a bare pure tone.
     * MEASURED against the real model: a flat two-tone sine actually scored *below* silence
     * (Silero read fixed tones as more clearly non-speech than the noise floor); this richer,
     * amplitude-modulated signal is what separates. Must stay a pure function of (seed, i) — no
     * stateful RNG — so resetRestoresBaseline's two independently-built detectors replay identically.
     */
    private fun speech(seed: Int) = ShortArray(480) { i ->
        val t = (i + seed * 480).toDouble() / 48000.0
        val f0 = 120.0
        var v = 1.0 * sin(2 * PI * f0 * t) +
                0.6 * sin(2 * PI * 700.0 * t) +
                0.4 * sin(2 * PI * 1200.0 * t) +
                0.25 * sin(2 * PI * 2500.0 * t)
        val env = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)
        v *= env
        v += 0.04 * (sin(2 * PI * 3371.0 * t) + sin(2 * PI * 5417.0 * t) +
                sin(2 * PI * 7213.0 * t) + sin(2 * PI * 6317.0 * t) + sin(2 * PI * 9161.0 * t))
        (v * 0.25 * 32767).toInt().coerceIn(-32768, 32767).toShort()
    }

    @Test fun returnsHeldProbabilityEveryCall() {
        val d = det()
        repeat(3) { assertEquals(0f, d.level(silence(), 0, 480), 0f) } // <512 buffered → no inference → held 0
        d.close()
    }

    @Test fun fiveInferencesPerSixteenCalls() {
        val spy = CountingSession(bytes())
        val d = SileroVadDetector(spy)
        repeat(16) { i -> d.level(speech(i), 0, 480) }
        assertEquals(5, spy.runs)
        d.close()
    }

    @Test fun speechRaisesHeldProbabilityAboveSilence() {
        val ds = det(); var silenceProb = 0f
        repeat(20) { silenceProb = ds.level(silence(), 0, 480) }
        ds.close()
        val dp = det(); var speechProb = 0f
        repeat(20) { i -> speechProb = dp.level(speech(i), 0, 480) }
        dp.close()
        // Synthetic tones cannot reach Silero's real-speech probabilities; assert speech clearly
        // exceeds the silence baseline rather than an absolute bar. If this margin is too tight for
        // the real model, MEASURE and widen the input, do not weaken below a genuine separation.
        assertTrue("speech=$speechProb silence=$silenceProb", speechProb > silenceProb && speechProb > silenceProb + 0.01f)
    }

    @Test fun resetRestoresBaseline() {
        val d = det()
        repeat(20) { i -> d.level(speech(i), 0, 480) }
        d.reset()
        val fresh = det()
        val a = FloatArray(8) { d.level(speech(it), 0, 480) }
        val b = FloatArray(8) { fresh.level(speech(it), 0, 480) }
        assertTrue(a.toList() == b.toList())
        d.close(); fresh.close()
    }
}

/** Test double counting inferences while delegating to the real ORT session. */
private class CountingSession(bytes: ByteArray) : SileroOnnxSession(bytes) {
    var runs = 0
    override fun run(input576: FloatArray, state: FloatArray): Result { runs++; return super.run(input576, state) }
}
