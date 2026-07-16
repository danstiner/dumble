package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end transmit-gate regression guard over the real-speech corpus.
 *
 * Green by design. Bars are pinned just below the gate's measured baseline (see
 * [CorpusBuilder.build]) — this is a REGRESSION GUARD, not a coverage=1.0 requirement. Dumble
 * matches Mumble's transmit model (no onset pre-roll; soft onsets are clipped 20-80 ms, smoothing
 * is tail-only via hangover), so coverage tops out ~0.96-0.99 and that is accepted. A genuine gate
 * degradation drops a clip below its bar and trips this test; recovering the clipped onset with a
 * pre-roll buffer is a deferred optional enhancement (task #35, revisit when tuning Silero).
 * Report ([metrics.md]) is written before the asserts, so it is produced on every run.
 */
class VadEvaluationTest {
    @Test fun corpusMeetsThresholds() {
        val clips = CorpusBuilder.build()
        val results = clips.map { it to VadEvaluator.evaluate(it) }
        EvalReport.write(File("build/reports/vad-eval"), results)

        for ((c, m) in results) {
            val t = c.thresholds
            assertTrue("${c.name} coverage ${"%.3f".format(m.coverage)} < ${t.minCoverage}",
                m.coverage >= t.minCoverage - 1e-9)
            assertTrue("${c.name} midDropout ${m.midDropoutMs}ms > ${t.maxMidUtteranceDropoutMs}",
                m.midDropoutMs <= t.maxMidUtteranceDropoutMs)
            assertTrue("${c.name} onset ${m.onsetMs}ms > ${t.maxOnsetMs}",
                m.onsetMs <= t.maxOnsetMs)
            assertTrue("${c.name} falseOpenings ${"%.2f".format(m.falseOpenings)} > ${t.maxFalseOpeningsPer10s}",
                m.falseOpenings <= t.maxFalseOpeningsPer10s + 1e-9)
        }
        assertTrue(File("build/reports/vad-eval/metrics.md").exists())
    }
}
