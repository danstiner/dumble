package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerStreamTest {
    private val codec = FakeOpusCodec()
    private fun encoded(samples: Int): ByteArray {
        // FakeOpusCodec header encodes the sample count; build a packet of that span.
        val b = ByteArray(4)
        b[0] = (samples ushr 24).toByte(); b[1] = (samples ushr 16).toByte()
        b[2] = (samples ushr 8).toByte();  b[3] = samples.toByte()
        return b
    }

    @Test fun fortyMsPacketYieldsTwoTicks() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(1920), 1920, false)          // one 40 ms packet
        val out = ShortArray(FRAME_SAMPLES_20MS)
        assertTrue(s.fillTick(out))                     // tick 1: first 960 from the single decode
        assertTrue(s.fillTick(out))                     // tick 2: second 960 from FIFO, no new decode
    }

    @Test fun lazyDecoderNotCreatedOnOffer() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(960), 960, false)
        assertFalse(s.decoderCreated)                   // still null until first fillTick
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))
        assertTrue(s.decoderCreated)
    }

    @Test fun retiresAfterTerminatorDrains() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(960), 960, false)
        s.offer(960, ByteArray(0), 0, true)             // terminator at 960
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))      // plays ts 0..960
        assertFalse(s.fillTick(ShortArray(FRAME_SAMPLES_20MS))) // nothing left, past terminator → idle
        assertTrue(s.retired)
    }
}
