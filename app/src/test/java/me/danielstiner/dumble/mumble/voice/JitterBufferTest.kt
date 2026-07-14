package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    private fun pkt(ts: Long, span: Int = 960, term: Boolean = false) =
        JitterBuffer.Packet(ts, if (term) ByteArray(0) else ByteArray(4), span, term)

    @Test fun ordersByTimestamp() {
        val b = JitterBuffer()
        b.offer(pkt(960), 0); b.offer(pkt(0), 0); b.offer(pkt(480), 0)
        assertEquals(0, b.pollFirst()!!.timestampSamples)
        assertEquals(480, b.pollFirst()!!.timestampSamples)
        assertEquals(960, b.pollFirst()!!.timestampSamples)
    }
    @Test fun dropsDuplicateTimestamp() {
        val b = JitterBuffer()
        b.offer(pkt(0), 0); b.offer(pkt(0), 0)
        b.pollFirst(); assertNull(b.pollFirst())
    }
    @Test fun dropsLateFrame() {
        val b = JitterBuffer()
        b.offer(pkt(0), playoutCursor = 480)
        assertNull(b.pollFirst())
    }
    @Test fun terminatorTagsWithoutQueueing() {
        val b = JitterBuffer()
        b.offer(pkt(1920, span = 0, term = true), 0)
        assertEquals(1920L, b.terminatorTimestamp)
        assertNull(b.pollFirst())
    }
    @Test fun bufferedSamplesSumsSpans() {
        val b = JitterBuffer()
        b.offer(pkt(0, span = 960), 0); b.offer(pkt(960, span = 1920), 0)
        assertEquals(2880, b.bufferedSamples())
    }
    @Test fun highWaterDropsOldest() {
        val b = JitterBuffer(highWaterSamples = 1000)
        b.offer(pkt(0, span = 960), 0); b.offer(pkt(960, span = 960), 0)
        // 1920 > 1000 → oldest (ts 0) dropped
        assertEquals(960, b.pollFirst()!!.timestampSamples)
    }

    @Test fun terminatorTagIsMonotonic() {
        val b = JitterBuffer()
        b.offer(pkt(1920, span = 0, term = true), 0)      // tag = 1920
        b.offer(pkt(960, span = 0, term = true), 0)        // older terminator must NOT lower the tag
        assertEquals(1920L, b.terminatorTimestamp)
        b.offer(pkt(2880, span = 0, term = true), 0)       // newer → updates
        assertEquals(2880L, b.terminatorTimestamp)
    }

    @Test fun clearTerminatorResetsTag() {
        val b = JitterBuffer()
        b.offer(pkt(1920, span = 0, term = true), 0)
        assertEquals(1920L, b.terminatorTimestamp)
        b.clearTerminator()
        assertNull(b.terminatorTimestamp)
    }
}
