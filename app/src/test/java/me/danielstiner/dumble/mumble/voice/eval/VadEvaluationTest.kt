package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end transmit-gate acceptance gate over the real-speech corpus.
 *
 * INTENTIONALLY RED (see [CorpusBuilder.build] REQUIRE bars). The requirement is that every
 * human-tagged speech region is fully inside gate-open (coverage=1.0, zero mid-region dropout).
 * The current gate opens 23-78 ms late on region onsets, so coverage is 0.947-0.990 — it fails on
 * purpose. Two ways to green it (see task #35):
 *   1. Gate side: a ~80-100 ms pre-roll buffer flushed on open, so the soft start is transmitted.
 *   2. Annotation side: tighten region starts toward energy-onset, dropping the soft leading edge.
 * The bar is NOT to be loosened to force green — the test greens when the requirement is met.
 * Report ([metrics.md]) is written before the asserts, so it is produced on every (failing) run.
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
