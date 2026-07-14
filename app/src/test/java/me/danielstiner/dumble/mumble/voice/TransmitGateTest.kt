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
        // 10th silent capture: ticks reach 20 → closes this capture → terminator
        val closing = g.update(silence)
        assertFalse(closing.send); assertTrue(closing.terminator)
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
}
