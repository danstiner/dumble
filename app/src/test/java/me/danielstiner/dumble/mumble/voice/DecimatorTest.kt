package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DecimatorTest {
    private fun tone(freqHz: Double, n: Int, ampl: Double = 0.5): ShortArray =
        ShortArray(n) { (sin(2 * PI * freqHz * it / SAMPLE_RATE) * ampl * 32767).toInt().toShort() }

    private fun rms(x: FloatArray): Double {
        var s = 0.0; for (v in x) s += v.toDouble() * v; return sqrt(s / x.size)
    }

    @Test fun producesThirdSampleCount() {
        val d = Decimator()
        val out = d.decimate(ShortArray(480), 0, 480)
        assertEquals(160, out.size)
    }

    @Test fun passesSpeechBandTone() {
        val d = Decimator()
        val inp = tone(1000.0, 480 * 8)
        var last = FloatArray(0)
        for (b in 0 until 8) last = d.decimate(inp, b * 480, 480)
        assertTrue("1kHz RMS ${rms(last)}", rms(last) in 0.30..0.40)
    }

    @Test fun attenuatesAboveNyquist() {
        val d = Decimator()
        val inp = tone(11000.0, 480 * 8)
        var last = FloatArray(0)
        for (b in 0 until 8) last = d.decimate(inp, b * 480, 480)
        assertTrue("11kHz RMS ${rms(last)} not attenuated", rms(last) < 0.0035)
    }

    @Test fun resetMakesDeterministic() {
        val d = Decimator()
        val inp = tone(1000.0, 480 * 4)
        var a = FloatArray(0); for (b in 0 until 4) a = d.decimate(inp, b * 480, 480)
        d.reset()
        var c = FloatArray(0); for (b in 0 until 4) c = d.decimate(inp, b * 480, 480)
        assertTrue(a.toList() == c.toList())
    }
}
