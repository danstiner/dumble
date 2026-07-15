package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadEvaluatorTest {
    private val clip = Clip(
        "t", ShortArray(48 * 1000),
        listOf(Segment(0, 400, Kind.SILENCE), Segment(400, 1000, Kind.SPEECH)),
        scoreFromMs = 0, thresholds = Thresholds(),
    )

    @Test fun fullCoverageNoDropouts() {
        val sends = BooleanArray(50) { it >= 20 }
        val m = VadEvaluator.scoreDecisions(clip, sends)
        assertEquals(1.0, m.coverage, 1e-9)
        assertEquals(0, m.midDropoutMs)
        assertEquals(0, m.onsetMs)
        assertEquals(0.0, m.falseOpenings, 1e-9)
    }

    @Test fun detectsMidUtteranceDropout() {
        val sends = BooleanArray(50) { it >= 20 }
        sends[30] = false; sends[31] = false
        val m = VadEvaluator.scoreDecisions(clip, sends)
        assertTrue("dropout detected", m.midDropoutMs >= 40)
    }
}
