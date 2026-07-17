package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety net (K=0 golden path) + basic K>0 onset-recovery proof for the lookahead-delay wiring
 * in [AudioVoiceEngine]. The K=0 test pins down the exact emitted sequence for a scripted talkspurt
 * so any accidental behavior change in the identity path fails loudly.
 */
class AudioVoiceEngineLookaheadTest {

    /** Fills each read with a scripted per-capture amplitude (±amp square wave). Same shape used by
     *  AudioVoiceEngineFrameNumberTest/TransmitModeTest to reliably open/close the VA gate. */
    private class ScriptedAudioIn(private val amps: List<Int>) : AudioIn {
        var reads = 0
        override fun read(out: ShortArray, n: Int): Int {
            val amp = amps.getOrElse(reads) { amps.last() }; reads++
            for (i in 0 until n) out[i] = if (i % 2 == 0) amp.toShort() else (-amp).toShort()
            return n
        }
        override fun close() {}
    }

    /** (frameNumber, isTerminator) per capture, or null when no frame was emitted. */
    private fun drive(engine: AudioVoiceEngine, ticks: Int): List<Pair<Long, Boolean>?> =
        (0 until ticks).map { engine.nextOutgoingFrame(0)?.let { it.frameNumber to it.isTerminator } }

    // 2 silent captures, 3 loud (speech) captures, 20 silent captures (enough to expire the
    // 200 ms / 20-tick hangover and land exactly one real terminator).
    private val script = List(2) { 0 } + List(3) { 8000 } + List(20) { 0 }

    @Test fun kZeroGoldenSequenceIsUnchanged() {
        val e = AudioVoiceEngine(FakeOpusCodec(), { ScriptedAudioIn(script) }, { FakeAudioOut() })
            .also { it.start() }
        val got = drive(e, script.size)
        e.stop()

        // Captured against the pre-lookahead engine for this exact script: 2 silent nulls, then
        // speech frames at fn=4,6,8 (one per loud capture), hangover frames fn=10..26 (silent
        // content still transmitted per TransmitGate's 200 ms hold), one real terminator at
        // fn=28, then 10 trailing nulls once the gate is closed.
        val expected: List<Pair<Long, Boolean>?> = listOf(
            null, null,
            4L to false, 6L to false, 8L to false,
            10L to false, 12L to false, 14L to false, 16L to false, 18L to false,
            20L to false, 22L to false, 24L to false, 26L to false,
            28L to true,
            null, null, null, null, null, null, null, null, null, null,
        )
        assertEquals("K=0 must be byte-identical to pre-lookahead engine behavior", expected, got)
    }

    @Test fun kTwoRecoversPreOnsetAudioEarlierThanKZero() {
        val onsetScript = List(3) { 0 } + List(3) { 8000 } + List(20) { 0 }

        val e0 = AudioVoiceEngine(FakeOpusCodec(), { ScriptedAudioIn(onsetScript) }, { FakeAudioOut() })
            .also { it.start() }
        val got0 = drive(e0, onsetScript.size)
        e0.stop()
        val firstFrameK0 = got0.first { it != null }!!.first

        val e2 = AudioVoiceEngine(
            FakeOpusCodec(), { ScriptedAudioIn(onsetScript) }, { FakeAudioOut() },
            initialLookaheadCaptures = 2,
        ).also { it.start() }
        val got2 = drive(e2, onsetScript.size)
        e2.stop()
        val firstFrameK2 = got2.first { it != null }!!.first

        assertTrue(
            "K=2 must transmit an earlier (pre-onset) frameNumber than K=0's own onset ($firstFrameK0)",
            firstFrameK2 < firstFrameK0,
        )

        // Frame numbers of the emitted (non-null) frames stay contiguous (constant 2-capture shift,
        // no gaps, no crashes) once flowing.
        val fns = got2.filterNotNull().map { it.first }
        for (i in 1 until fns.size) {
            assertEquals("K>0 frame numbers must stay contiguous", FRAMES_PER_PACKET.toLong(), fns[i] - fns[i - 1])
        }
    }
}
