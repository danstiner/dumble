package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Test

class GainControlTest {
    /** A constant-RMS square sub-frame of amplitude [amp] (RMS == amp). */
    private fun square(amp: Int, n: Int = FRAME_SAMPLES_10MS) =
        ShortArray(n) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }

    private fun rmsDbFs(pcm: ShortArray): Double {
        var s = 0.0; for (v in pcm) s += v.toDouble() * v
        return 20.0 * log10(sqrt(s / pcm.size) / 32768.0)
    }

    private fun peak(pcm: ShortArray) = pcm.maxOf { abs(it.toInt()) }

    @Test fun convergesTowardTargetOnSteadyTone() {
        val agc = GainControl(targetDbFs = -18f)
        lateinit var frame: ShortArray
        repeat(400) { frame = square(2000); agc.process(frame, 0, frame.size, 1.0f) }
        assertEquals("output RMS converges to target", -18.0, rmsDbFs(frame), 1.0)
    }

    @Test fun freezesDuringNonSpeech() {
        val agc = GainControl(targetDbFs = -18f)
        repeat(400) { val f = square(500); agc.process(f, 0, f.size, 0.0f) }
        assertEquals("gain frozen at unity when not speech", 1.0f, agc.gain, 1e-4f)
    }

    @Test fun limiterHoldsPeaksBelowFullScale() {
        val agc = GainControl(targetDbFs = -18f)
        // Drive gain high on a very quiet tone, then hit it with a loud sub-frame.
        repeat(400) { val f = square(40); agc.process(f, 0, f.size, 1.0f) }
        val loud = square(20000); agc.process(loud, 0, loud.size, 1.0f)
        assertTrue("no sample wraps / clips at full scale", peak(loud) < 32767)
    }

    @Test fun staysWithinGainBounds() {
        val agc = GainControl(targetDbFs = -18f, maxGainDb = 30f)
        repeat(2000) { val f = square(10); agc.process(f, 0, f.size, 1.0f) } // wants > +30 dB
        assertTrue("gain capped at maxGain", agc.gain <= 31.7f) // 10^(30/20)=31.62
    }

    @Test fun disabledIsBitExactPassthrough() {
        val agc = GainControl(enabled = false)
        val f = square(3000); val copy = f.copyOf()
        agc.process(f, 0, f.size, 1.0f)
        assertTrue("disabled passes audio through unchanged", f.contentEquals(copy))
    }

    @Test fun deterministic() {
        fun run(): ShortArray {
            val agc = GainControl(); var last = ShortArray(0)
            repeat(50) { val f = square(1500); agc.process(f, 0, f.size, 1.0f); last = f }
            return last
        }
        assertTrue("same input → same output", run().contentEquals(run()))
    }
}
