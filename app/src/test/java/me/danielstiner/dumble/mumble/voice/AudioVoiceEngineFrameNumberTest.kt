package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineFrameNumberTest {

    /** Fills each read with a scripted per-capture amplitude (±amp square wave). */
    private class ScriptedAudioIn(private val amps: List<Int>) : AudioIn {
        var reads = 0
        override fun read(out: ShortArray, n: Int): Int {
            val amp = amps.getOrElse(reads) { amps.last() }; reads++
            for (i in 0 until n) out[i] = if (i % 2 == 0) amp.toShort() else (-amp).toShort()
            return n
        }
        override fun close() {}
    }

    private fun engine(input: AudioIn): AudioVoiceEngine =
        AudioVoiceEngine(FakeOpusCodec(), { input }, { FakeAudioOut() }).also { it.start() }

    @Test fun frameNumberAdvancesByTwoWhileSpeaking() {
        val e = engine(ScriptedAudioIn(List(4) { 8000 }))   // loud → gate open
        val f1 = e.nextOutgoingFrame(0)!!
        val f2 = e.nextOutgoingFrame(0)!!
        assertEquals(0L, f1.frameNumber)
        assertEquals(2L, f2.frameNumber)
        assertFalse(f1.isTerminator)
        e.stop()
    }

    @Test fun silentMicProducesNoFrames() {
        val e = engine(ScriptedAudioIn(List(5) { 0 }))      // silence → gate stays closed
        repeat(5) { assertNull("silence must not transmit", e.nextOutgoingFrame(0)) }
        e.stop()
    }

    @Test fun speechThenSilenceEmitsOneTerminatorAndFreezesFrameNumber() {
        // 3 loud captures, then silence long enough to expire the 200 ms (20-tick) hangover.
        val amps = List(3) { 8000 } + List(20) { 0 }
        val e = engine(ScriptedAudioIn(amps))

        var lastSent = -1L
        var terminators = 0
        var terminatorFrameNumber = -1L
        var sawNullAfterTerminator = false
        repeat(amps.size) {
            val f = e.nextOutgoingFrame(0)
            when {
                f == null -> if (terminators > 0) sawNullAfterTerminator = true
                f.isTerminator -> { terminators++; terminatorFrameNumber = f.frameNumber }
                else -> lastSent = f.frameNumber
            }
        }
        assertTrue("at least the 3 speech frames were sent", lastSent >= 4L)
        assertEquals("exactly one terminator", 1, terminators)
        assertEquals("terminator freezes frameNumber at the next value after last sent",
            lastSent + 2L, terminatorFrameNumber)
        assertTrue("idle nulls follow the terminator", sawNullAfterTerminator)
        e.stop()
    }

    @Test fun mutedReturnsNullButStillReads() {
        val fakeIn = ScriptedAudioIn(List(3) { 8000 })
        val e = AudioVoiceEngine(FakeOpusCodec(), { fakeIn }, { FakeAudioOut() })
        e.start(); e.setMuted(true)
        val first = e.nextOutgoingFrame(0)
        assertTrue("mute emits one terminator", first != null && first.isTerminator)
        assertNull("subsequent muted → null", e.nextOutgoingFrame(0))
        assertTrue("mic still drained while muted", fakeIn.reads >= 1)
        e.stop()
    }
}

class FakeAudioOut : AudioOut {
    override fun write(pcm: ShortArray, n: Int) {}
    override fun close() {}
}
