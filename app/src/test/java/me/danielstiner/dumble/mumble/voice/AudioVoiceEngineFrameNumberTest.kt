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

    @Test fun speechThenSilenceSendsExactlyOneRealTerminator() {
        // 3 loud captures, then silence long enough to expire the 200 ms (20-tick) hangover.
        val amps = List(3) { 8000 } + List(20) { 0 }
        val e = engine(ScriptedAudioIn(amps))

        var terminators = 0
        var terminatorLen = -1
        var sawNullAfterTerminator = false
        repeat(amps.size) {
            val f = e.nextOutgoingFrame(0)
            when {
                f == null -> if (terminators > 0) sawNullAfterTerminator = true
                f.isTerminator -> { terminators++; terminatorLen = f.length }
                else -> {}
            }
        }
        assertEquals("exactly one terminator", 1, terminators)
        assertTrue("terminator is a real (non-empty) frame, not an empty packet", terminatorLen > 0)
        assertTrue("idle nulls follow the terminator", sawNullAfterTerminator)
        e.stop()
    }

    @Test fun frameNumberTracksWallClockAcrossSilence() {
        // speech, long silence, speech again — the 2nd talkspurt's frameNumber reflects the
        // wall-clock gap (it does NOT resume right after the 1st), so a Mumble receiver that
        // schedules by absolute frame_number places it correctly instead of dropping it as late.
        val amps = List(2) { 8000 } + List(30) { 0 } + List(2) { 8000 }
        val e = engine(ScriptedAudioIn(amps))

        var lastFirst = -1L
        var firstSecond = -1L
        var seenNull = false
        repeat(amps.size) {
            val f = e.nextOutgoingFrame(0)
            when {
                f == null -> seenNull = true
                f.isTerminator -> {}
                seenNull -> { if (firstSecond < 0) firstSecond = f.frameNumber }
                else -> lastFirst = f.frameNumber
            }
        }
        assertTrue("both talkspurts produced frames", lastFirst >= 0 && firstSecond >= 0)
        assertTrue("2nd talkspurt frameNumber reflects the wall-clock silence gap, not a +2 resume",
            firstSecond - lastFirst > 20L)
        e.stop()
    }

    @Test fun mutedReturnsNullButStillReads() {
        val fakeIn = ScriptedAudioIn(List(3) { 8000 })
        val e = AudioVoiceEngine(FakeOpusCodec(), { fakeIn }, { FakeAudioOut() })
        e.start(); e.setMuted(true)
        val first = e.nextOutgoingFrame(0)
        assertTrue("mute emits one terminator", first != null && first.isTerminator)
        assertTrue("mute terminator is a real (non-empty) frame", first!!.length > 0)
        assertNull("subsequent muted → null", e.nextOutgoingFrame(0))
        assertTrue("mic still drained while muted", fakeIn.reads >= 1)
        e.stop()
    }

    private class RecordingSuppressor : NoiseSuppressor {
        val calls = mutableListOf<Int>()   // offsets seen
        override fun process(pcm: ShortArray, off: Int, n: Int) { calls.add(off) }
        override fun close() {}
    }

    @Test fun engineDenoisesEachHalfInPlacePerCapture() {
        val sup = RecordingSuppressor()
        val e = AudioVoiceEngine(
            FakeOpusCodec(), { ScriptedAudioIn(List(2) { 8000 }) }, { FakeAudioOut() }, sup,
        )
        e.start()
        e.nextOutgoingFrame(0)                       // one capture
        assertEquals("two 10 ms sub-frames per capture", listOf(0, FRAME_SAMPLES_10MS), sup.calls)
        e.stop()
    }
}

class FakeAudioOut : AudioOut {
    override fun write(pcm: ShortArray, n: Int) {}
    override fun close() {}
}
