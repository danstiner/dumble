package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShortArrayFifoTest {

    private fun ramp(n: Int, from: Int = 0) = ShortArray(n) { (from + it).toShort() }

    @Test
    fun drainsInOrderAcrossPushes() {
        val f = ShortArrayFifo(8)
        f.push(ramp(3), 3)
        f.push(ramp(3, 3), 3)
        val out = ShortArray(6)
        f.drainInto(out, 6)
        assertArrayEquals(ramp(6), out)
        assertEquals(0, f.size)
    }

    @Test
    fun zeroPadsWhenShort() {
        val f = ShortArrayFifo(8)
        f.push(shortArrayOf(7, 7), 2)
        val out = ShortArray(4) { -1 }
        f.drainInto(out, 4)
        assertArrayEquals(shortArrayOf(7, 7, 0, 0), out)
    }

    /** Both the write and the read must split correctly across the physical end of the ring. */
    @Test
    fun wrapPreservesContents() {
        val f = ShortArrayFifo(8)
        f.push(ramp(6), 6)
        val out = ShortArray(5)
        f.drainInto(out, 5)          // head = 5, size = 1, holding value 5
        f.push(ramp(4, 100), 4)      // writes indices 6..7, wraps to 0..1
        val rest = ShortArray(5)
        f.drainInto(rest, 5)         // read wraps past the end
        assertArrayEquals(shortArrayOf(5, 100, 101, 102, 103), rest)
    }

    /**
     * The residue case that used to make the growable version reallocate: spans that are not a
     * multiple of the drain count skip size past zero, so head walks the ring indefinitely.
     * Mirrors SpeakerQueue's loop — fill past a quantum, drain exactly one quantum — with the
     * stream's ordering checked across thousands of wraps.
     */
    @Test
    fun headCreepStreamsCorrectlyAcrossWraps() {
        val f = ShortArrayFifo(QUANTUM_SAMPLES + MAX_FRAME_SAMPLES)
        val out = ShortArray(QUANTUM_SAMPLES)
        // One 2.5 ms frame per twenty 60 ms frames — the mix that grew worst in simulation.
        val spans = listOf(120) + List(20) { 2880 }
        var pushed = 0L
        var drained = 0L
        var i = 0
        repeat(3000) {
            while (f.size < QUANTUM_SAMPLES) {
                val n = spans[i++ % spans.size]
                val base = pushed
                f.push(ShortArray(n) { ((base + it) % 31).toShort() }, n)
                pushed += n
            }
            f.drainInto(out, QUANTUM_SAMPLES)
            for (j in 0 until QUANTUM_SAMPLES) {
                assertEquals(((drained + j) % 31).toShort(), out[j])
            }
            drained += QUANTUM_SAMPLES
        }
    }

    /** Overflow is a broken caller, not backpressure — it must fail loudly, not drop or grow. */
    @Test
    fun pushingPastCapacityThrows() {
        val f = ShortArrayFifo(8)
        f.push(ramp(8), 8)
        assertThrows(IllegalArgumentException::class.java) { f.push(ramp(1), 1) }
    }
}
