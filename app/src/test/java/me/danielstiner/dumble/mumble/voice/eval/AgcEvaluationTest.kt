package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.GainControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGC scoreboard: the makeup gain must pull the real-speech corpus toward the target loudness and
 * tighten cross-clip consistency, without ever clipping. Relative assertions (no pinned dB bars) so
 * this stays robust to convergence-lag and future constant tuning.
 */
class AgcEvaluationTest {
    private val target = -18f

    @Test fun agcConvergesTightensSpreadAndNeverClips() {
        val clips = CorpusBuilder.build()

        val noAgc = clips.map { VadEvaluator.evaluate(it).speechLoudnessDbFs }
        val withAgc = clips.map {
            VadEvaluator.evaluate(it, GainControl(targetDbFs = target, enabled = true))
        }

        // Never clips.
        for ((c, m) in clips.zip(withAgc))
            assertEquals("${c.name} must not clip under AGC", 0, m.clipping)

        // Each clip moves toward target vs its no-AGC loudness.
        for (i in clips.indices) {
            val before = kotlin.math.abs(noAgc[i] - target)
            val after = kotlin.math.abs(withAgc[i].speechLoudnessDbFs - target)
            assertTrue("${clips[i].name} loudness moves toward target " +
                "($before → $after dB from target)", after <= before + 1e-6)
        }

        // Cross-clip spread shrinks.
        val spreadBefore = noAgc.max() - noAgc.min()
        val spreadAfter = withAgc.maxOf { it.speechLoudnessDbFs } - withAgc.minOf { it.speechLoudnessDbFs }
        assertTrue("AGC tightens cross-clip spread ($spreadBefore → $spreadAfter dB)",
            spreadAfter < spreadBefore)
    }
}
