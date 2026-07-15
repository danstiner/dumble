package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
