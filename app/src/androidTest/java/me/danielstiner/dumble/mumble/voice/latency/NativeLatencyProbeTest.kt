package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.voice.TRANSMIT_BITRATE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The binding on a real ABI: the host tests cover the search; this covers the arrays crossing JNI
 *  and the device's own Opus build. */
class NativeLatencyProbeTest {
    private val lead = 9_600   // 200 ms
    private val tail = 9_600

    @Test
    fun aProbeSpurtIsFoundExactlyWhereItWasPlaced() {
        val packets = NativeLatencyProbe.encodeProbeSpurt(PEAK, lead, tail, TRANSMIT_BITRATE)
        assertEquals("200 ms + 250 ms + 200 ms in 20 ms packets, the last padded", 33, packets.size)
        val codec = NativeLatencyProbe.codecDelaySamples(TRANSMIT_BITRATE)
        assertEquals("Opus's delay at Dumble's settings, as the host harness pins it", 310, codec)
        val out = DoubleArray(3)
        assertTrue(NativeLatencyProbe.locate(frames(packets.size), packets, codec, out))
        assertEquals(lead.toDouble(), out[0], 0.0)
        assertTrue("peak ${out[1]} next ${out[2]}", out[1] >= 0.1 && out[1] >= 5 * out[2])
    }

    @Test
    fun aSilentSpurtIsNotFound() {
        val packets = NativeLatencyProbe.encodeProbeSpurt(0, lead, tail, TRANSMIT_BITRATE)
        assertFalse(NativeLatencyProbe.locate(frames(packets.size), packets, 310, DoubleArray(3)))
    }

    @Test
    fun mismatchedArraysAreRefusedWithoutWriting() {
        val packets = NativeLatencyProbe.encodeProbeSpurt(PEAK, lead, tail, TRANSMIT_BITRATE)
        val out = doubleArrayOf(-7.0, -7.0, -7.0)
        assertFalse(NativeLatencyProbe.locate(frames(packets.size - 1), packets, 310, out))
        assertFalse(NativeLatencyProbe.locate(frames(packets.size), packets, 310, DoubleArray(2)))
        assertArrayEquals(doubleArrayOf(-7.0, -7.0, -7.0), out, 0.0)
    }

    private fun frames(n: Int) = LongArray(n) { 5_000L + 2L * it }

    private companion object {
        const val PEAK = 1600
    }
}
