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

    @Test fun trimsHotToneDownTowardTarget() {
        // Trim path: a hot tone (RMS 8000) needs gain < 1.0 to reach -18 dBFS.
        // desired = 32768 * 10^(-18/20) / 8000 = 4125/8000 ≈ 0.52, above minGain → lands on target.
        val agc = GainControl(targetDbFs = -18f)
        lateinit var frame: ShortArray
        repeat(400) { frame = square(8000); agc.process(frame, 0, frame.size, 1.0f) }
        assertEquals("hot tone trimmed to target", -18.0, rmsDbFs(frame), 1.0)
        assertTrue("gain trimmed below unity", agc.gain < 1.0f)
    }

    @Test fun freezesDuringNonSpeech() {
        val agc = GainControl(targetDbFs = -18f)
        // First adapt gain ABOVE 1.0 on quiet-but-speech frames so freeze is distinguishable
        // from a reset-to-unity mutation.
        repeat(200) { val f = square(500); agc.process(f, 0, f.size, 1.0f) }
        val adapted = agc.gain
        assertTrue("precondition: gain adapted above unity", adapted > 1.0f)
        // Now non-speech: gain must be HELD at `adapted`, not reset and not drifting.
        repeat(200) { val f = square(500); agc.process(f, 0, f.size, 0.0f) }
        assertEquals("gain frozen (held, not reset) during non-speech", adapted, agc.gain, 1e-4f)
    }

    @Test fun limiterHoldsPeaksBelowFullScale() {
        val agc = GainControl(targetDbFs = -18f)
        // Drive gain high on a very quiet tone, then hit it with a loud sub-frame.
        repeat(400) { val f = square(40); agc.process(f, 0, f.size, 1.0f) }
        val loud = square(20000); agc.process(loud, 0, loud.size, 1.0f)
        assertTrue("positive peak within full scale", peak(loud) <= 32767)
        // The negative-wrap check: catches the historical bug where a 32768.0f asymptote wrapped
        // to Short.MIN_VALUE (-32768).
        assertTrue("no sample wraps to the negative rail",
            loud.minOf { it.toInt() } > Short.MIN_VALUE.toInt())
    }

    @Test fun staysWithinGainBounds() {
        val agc = GainControl(targetDbFs = -18f, maxGainDb = 30f)
        repeat(2000) { val f = square(10); agc.process(f, 0, f.size, 1.0f) } // wants > +30 dB
        assertTrue("gain capped at maxGain", agc.gain <= 31.7f) // 10^(30/20)=31.62
    }

    @Test fun respectsLowerGainBound() {
        // desired ≈ 4125/32000 ≈ 0.129 is below minGain (10^(-12/20)=0.251), so gain must
        // settle at minGain and never dip below it. Covers decreaseRateDbPerSec, the rate-limit
        // `else` branch, and the lower clamp.
        val agc = GainControl(targetDbFs = -18f)
        repeat(400) { val f = square(32000); agc.process(f, 0, f.size, 1.0f) }
        assertTrue("gain settles at minGain, not below", agc.gain in 0.25f..0.26f)
    }

    @Test fun appliesOnlyToRequestedOffsetRange() {
        // Offset contract (Task 2 calls with off = i*480): only [off, off+n) is touched.
        val agc = GainControl(targetDbFs = -18f)
        // Fresh frame each call, as in every other test here — matches the real calling contract
        // (TransmitProcessor always passes a freshly captured sub-frame; gain never re-processes
        // its own prior output). Reusing one mutated buffer across calls would instead form an
        // artificial exponential feedback loop that can't occur in production.
        repeat(200) { val f = square(500); agc.process(f, 0, f.size, 1.0f) } // drive gain above 1.0
        assertTrue("precondition: gain above unity", agc.gain > 1.0f)
        val buf = ShortArray(960) { 3000 }; val copy = buf.copyOf()
        agc.process(buf, 480, 480, 1.0f)
        assertTrue("samples before offset untouched",
            buf.copyOfRange(0, 480).contentEquals(copy.copyOfRange(0, 480)))
        assertTrue("samples in offset range gained",
            !buf.copyOfRange(480, 960).contentEquals(copy.copyOfRange(480, 960)))
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

    @Test fun gainStaysStableUnderSyllableVaryingInput() {
        // Alternate amplitude over ~200 ms BLOCKS (realistic syllable length, 20 sub-frames/level)
        // so the loudness EMA window is actually exercised. At per-sub-frame alternation the
        // (unchanged) rate limiter alone bounds the swing to ~0.1 dB, so that pattern can't tell the
        // instantaneous-vs-smoothed algorithms apart — this block pattern can.
        //
        // Property: after settling, the gain holds a tight band around the mean-energy-derived ideal
        // (target / sqrt(meanMS) ≈ 0.707 for alternating 8000/2000). The OLD instantaneous-RMS algo
        // hunts DOWN toward the loud-block peak gain (target / 8000 ≈ 0.516) — its fast-decrease /
        // slow-increase asymmetry parks it low — so its gain dips below 0.60. The smoothed algo stays.
        //
        // Measured (settle 600, window 200 frames): OLD gain span [0.516, 0.680] (min 0.516 < 0.60 →
        // FAILS); NEW gain span [0.641, 0.800] (fully inside [0.60, 0.85] → PASSES). ~1.9 dB of
        // separation on the lower bound. (A raw swing threshold does NOT discriminate: OLD 2.40 dB
        // vs NEW 1.93 dB — hence the band property instead.)
        val agc = GainControl(targetDbFs = -18f)
        val blockFrames = 20 // ~200 ms per level
        repeat(600) { i ->
            val f = square(if ((i / blockFrames) % 2 == 0) 8000 else 2000); agc.process(f, 0, f.size, 1.0f)
        }
        var minG = Float.MAX_VALUE; var maxG = 0f
        repeat(200) { i ->
            val f = square(if ((i / blockFrames) % 2 == 0) 8000 else 2000); agc.process(f, 0, f.size, 1.0f)
            minG = minOf(minG, agc.gain); maxG = maxOf(maxG, agc.gain)
        }
        assertTrue("gain must hold a tight band around the mean-energy ideal (~0.707), not hunt down " +
            "toward the loud-syllable peak gain (settled band=[$minG, $maxG])",
            minG >= 0.60f && maxG <= 0.85f)
    }

    @Test fun convergesToTargetOnSyllableVaryingInput() {
        val agc = GainControl(targetDbFs = -18f)
        var sumSq = 0.0; var count = 0L
        repeat(800) { i ->
            val f = square(if (i % 2 == 0) 8000 else 2000); agc.process(f, 0, f.size, 1.0f)
            if (i >= 700) for (v in f) { sumSq += v.toDouble() * v; count++ }   // settled tail only
        }
        val outDb = 20.0 * kotlin.math.log10(kotlin.math.sqrt(sumSq / count) / 32768.0)
        assertEquals("varying-input output converges to target (no undershoot)", -18.0, outDb, 1.5)
    }
}
