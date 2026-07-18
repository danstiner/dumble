package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the decode → mix path the way AudioVoiceEngine.playbackLoop does: per tick, fillTick
 * every speaker and AudioMixer.accumulate the ones that produced, then finalizeMix. FakeDecoder
 * emits ((i%100)-50) for a real frame and 0 for PLC/hold, both under the mixer THRESHOLD, so the
 * mux is an exact integer sum.
 */
class VoicePipelineMixTest {
    private val codec = FakeOpusCodec()

    /** A 4-byte packet whose header encodes its sample count (matches FakeOpusCodec). */
    private fun frame(samples: Int = FRAME_SAMPLES_20MS): ByteArray {
        val b = ByteArray(4)
        b[0] = (samples ushr 24).toByte(); b[1] = (samples ushr 16).toByte()
        b[2] = (samples ushr 8).toByte();  b[3] = samples.toByte()
        return b
    }

    /** Mirror of AudioVoiceEngine.playbackLoop's per-tick mix. Returns the mixed 20 ms frame. */
    private fun mixTick(streams: List<SpeakerStream>): ShortArray {
        val acc = IntArray(FRAME_SAMPLES_20MS)
        val spk = ShortArray(FRAME_SAMPLES_20MS)
        for (s in streams) if (s.fillTick(spk)) AudioMixer.accumulate(acc, spk, FRAME_SAMPLES_20MS)
        val mix = ShortArray(FRAME_SAMPLES_20MS)
        AudioMixer.finalizeMix(acc, mix, FRAME_SAMPLES_20MS)
        return mix
    }

    private fun assertPattern(mix: ShortArray, gain: Int) {
        for (i in 0 until FRAME_SAMPLES_20MS) assertEquals(gain * ((i % 100) - 50), mix[i].toInt())
    }

    @Test fun singleSpeakerContiguousFramesMuxCorrectly() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        s.offer(0, frame(), FRAME_SAMPLES_20MS, false, 0L)
        s.offer(960, frame(), FRAME_SAMPLES_20MS, false, 960L)
        assertPattern(mixTick(listOf(s)), gain = 1)
        assertPattern(mixTick(listOf(s)), gain = 1)
    }

    @Test fun gapMuxesToSilenceThenResumes() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        s.offer(0, frame(), FRAME_SAMPLES_20MS, false, 0L)
        assertEquals(-50, mixTick(listOf(s))[0].toInt())     // real audio
        assertEquals(0, mixTick(listOf(s))[0].toInt())       // underrun → hold → silence
        s.offer(960, frame(), FRAME_SAMPLES_20MS, false, 960L)     // resume at continued timestamp
        assertEquals(-50, mixTick(listOf(s))[0].toInt())     // resumed audio present, not lost
    }

    @Test fun terminatorEndsTalkspurtInMux() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        s.offer(0, frame(), FRAME_SAMPLES_20MS, true, 0L)        // single terminated frame
        assertEquals(-50, mixTick(listOf(s))[0].toInt())     // plays
        assertEquals(0, mixTick(listOf(s))[0].toInt())       // past terminator → reset → silence
    }

    @Test fun twoSpeakersMuxAsSum() {
        val a = SpeakerStream(codec, targetSamples = { 0 })
        val b = SpeakerStream(codec, targetSamples = { 0 })
        a.offer(0, frame(), FRAME_SAMPLES_20MS, false, 0L)
        b.offer(0, frame(), FRAME_SAMPLES_20MS, false, 0L)
        assertPattern(mixTick(listOf(a, b)), gain = 2)       // exact integer sum (both under THRESHOLD)
    }

    @Test fun twoSpeakersOneInGapMuxesActiveOnly() {
        val a = SpeakerStream(codec, targetSamples = { 0 })
        val b = SpeakerStream(codec, targetSamples = { 0 })
        a.offer(0, frame(), FRAME_SAMPLES_20MS, false, 0L)
        a.offer(960, frame(), FRAME_SAMPLES_20MS, false, 960L)
        b.offer(0, frame(), FRAME_SAMPLES_20MS, false, 0L)       // b has only one frame
        assertPattern(mixTick(listOf(a, b)), gain = 2)       // tick 1: both active → sum
        assertPattern(mixTick(listOf(a, b)), gain = 1)       // tick 2: b holds (silence) → only a
    }
}
