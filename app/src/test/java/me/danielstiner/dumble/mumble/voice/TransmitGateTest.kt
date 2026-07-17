package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmitGateTest {
    private val speech = floatArrayOf(1f, 1f)
    private val silence = floatArrayOf(0f, 0f)

    @Test fun opensOnSpeechAndSends() {
        val g = TransmitGate()
        val d = g.update(speech)
        assertTrue(d.send); assertFalse(d.terminator)
    }

    @Test fun onsetInSecondHalfSendsWholeCapture() {
        val g = TransmitGate()
        val d = g.update(floatArrayOf(0f, 1f))   // silent first 10 ms, speech second 10 ms
        assertTrue("capture with one voiced sub-frame is sent", d.send)
    }

    @Test fun holdsThroughShortSilenceThenTerminatesOnce() {
        val g = TransmitGate(maxHoldTicks = 20)
        g.update(speech)                                  // open
        // 9 silent captures = 18 ticks < 20 → still transmitting (hangover)
        repeat(9) { assertTrue(g.update(silence).send) }
        // 10th silent capture: ticks reach 20 → closes → send the real terminator frame
        val closing = g.update(silence)
        assertTrue(closing.send); assertTrue(closing.terminator)
        // subsequent silence → idle, no repeat terminator
        val idle = g.update(silence)
        assertFalse(idle.send); assertFalse(idle.terminator)
    }

    @Test fun singleQuietSubframeDoesNotFlutterClosed() {
        val g = TransmitGate(maxHoldTicks = 20)
        g.update(speech)
        assertTrue("one quiet sub-frame among speech keeps sending",
            g.update(floatArrayOf(0f, 1f)).send)
    }

    @Test fun reopensAfterTerminator() {
        val g = TransmitGate(maxHoldTicks = 2)
        g.update(speech)
        g.update(silence)                                 // ticks=2 → closes → terminator
        assertTrue("re-opens on next speech", g.update(speech).send)
    }

    @Test fun resetReturnsToClosed() {
        val g = TransmitGate()
        g.update(speech)
        g.reset()
        // after reset, a silent capture must not emit a terminator (was not transmitting)
        val d = g.update(silence)
        assertFalse(d.send); assertFalse(d.terminator)
    }

    @Test fun closeThresholdTracksOpenLevelWithGap() {
        val g = TransmitGate(openLevel = 0.5f)   // closeLevel = 0.5 - 0.15 = 0.35
        // idle: a level between close (0.35) and open (0.5) must NOT open the gate
        assertFalse("0.4 < open 0.5 → stays closed", g.update(floatArrayOf(0.4f, 0.4f)).send)
        g.update(floatArrayOf(0.9f, 0.9f))       // open with a clear level
        // now transmitting: the same 0.4 (> close 0.35) keeps it fully open
        assertTrue("0.4 > close 0.35 → stays open", g.update(floatArrayOf(0.4f, 0.4f)).send)
    }

    @Test fun closeLevelFlooredSoLowOpenLevelStillCloses() {
        // openLevel 0.1 would compute close = -0.05; the CLOSE_FLOOR (0.05) keeps it positive so the
        // gate can still close (a non-floored 0 close would hold open on any positive level).
        val g = TransmitGate(openLevel = 0.1f, maxHoldTicks = 1)
        g.update(floatArrayOf(0.9f, 0.9f))       // open
        val d = g.update(floatArrayOf(0.02f, 0.02f))   // below the 0.05 floor → hangover → closes
        assertTrue("must close (send terminator) — floor keeps close > 0", d.send && d.terminator)
    }
}
