package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookaheadDelayTest {
    private fun cap(v: Int) = ShortArray(CAPTURE_SAMPLES) { v.toShort() }

    @Test fun kZeroIsIdentity() {
        val d = LookaheadDelay(0)
        val out = d.offer(cap(7), open = true, frameNumber = 100)
        assertEquals(7, out!!.pcm[0].toInt())
        assertTrue(out.send)
        assertEquals(100L, out.frameNumber)
    }

    @Test fun kTwoBuffersAndRecoversOnset() {
        val d = LookaheadDelay(2)
        assertNull(d.offer(cap(0), open = false, frameNumber = 0))
        assertNull(d.offer(cap(1), open = false, frameNumber = 1))
        val e2 = d.offer(cap(2), open = true, frameNumber = 2)!!
        assertEquals(0, e2.pcm[0].toInt())      // 2-capture delay
        assertTrue("pre-onset capture must transmit", e2.send)
    }

    @Test fun flushDrainsInOrder() {
        val d = LookaheadDelay(2)
        d.offer(cap(0), false, 0); d.offer(cap(1), false, 1)
        val drained = d.flush()
        assertEquals(listOf(0, 1), drained.map { it.pcm[0].toInt() })
    }
}
