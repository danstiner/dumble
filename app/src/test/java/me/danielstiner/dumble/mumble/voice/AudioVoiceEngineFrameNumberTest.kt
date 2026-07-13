package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineFrameNumberTest {
    private fun engine(muted: Boolean = false): AudioVoiceEngine {
        val e = AudioVoiceEngine(
            codec = FakeOpusCodec(),
            recorderFactory = { FakeAudioIn() },     // returns 960 samples per read
            trackFactory = { FakeAudioOut() },
        )
        e.start(); e.setMuted(muted)
        return e
    }

    @Test fun frameNumberAdvancesByTwoPer20ms() {
        val e = engine()
        val f1 = e.nextOutgoingFrame(0)!!
        val f2 = e.nextOutgoingFrame(0)!!
        assertEquals(0L, f1.frameNumber)
        assertEquals(2L, f2.frameNumber)
        e.stop()
    }

    @Test fun mutedReturnsNullButStillReads() {
        val fakeIn = FakeAudioIn()
        val e = AudioVoiceEngine(FakeOpusCodec(), { fakeIn }, { FakeAudioOut() })
        e.start(); e.setMuted(true)
        assertNull(e.nextOutgoingFrame(0)?.takeIf { !it.isTerminator })  // first is the terminator edge
        e.nextOutgoingFrame(0)                                           // subsequent muted → null
        assertTrue("mic still drained while muted", fakeIn.reads >= 1)
        e.stop()
    }
}

class FakeAudioIn : AudioIn {
    var reads = 0
    override fun read(out: ShortArray, n: Int): Int { reads++; return n }
    override fun close() {}
}
class FakeAudioOut : AudioOut {
    override fun write(pcm: ShortArray, n: Int) {}
    override fun close() {}
}
