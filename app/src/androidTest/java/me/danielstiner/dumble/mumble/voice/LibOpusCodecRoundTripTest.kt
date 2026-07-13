package me.danielstiner.dumble.mumble.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LibOpusCodecRoundTripTest {
    @Test fun encodeThenDecode_returns960SamplesWithEnergy() {
        val codec = LibOpusCodec()
        val encoder = codec.newEncoder()
        val decoder = codec.newDecoder()
        val pcm = ShortArray(FRAME_SAMPLES_20MS) { i ->
            (sin(2 * PI * 440 * i / SAMPLE_RATE) * 8000).toInt().toShort()
        }
        val packet = encoder.encode(pcm, FRAME_SAMPLES_20MS)
        assertTrue("non-empty packet", packet.isNotEmpty())
        assertEquals(FRAME_SAMPLES_20MS, codec.packetSamples(packet, 0, packet.size))
        val out = ShortArray(MAX_FRAME_SAMPLES)
        val n = decoder.decode(packet, 0, packet.size, out, FRAME_SAMPLES_20MS)
        assertEquals(FRAME_SAMPLES_20MS, n)
        val energy = (0 until n).sumOf { Math.abs(out[it].toInt()).toLong() }
        assertTrue("decoded energy > 0", energy > 0)
        val plc = decoder.decode(null, 0, 0, out, FRAME_SAMPLES_20MS)
        assertEquals(FRAME_SAMPLES_20MS, plc)
        encoder.close(); decoder.close()
    }
}
