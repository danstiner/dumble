package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.voice.FakeOpusCodec.Companion.packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerQueueTest {

    private val out = ShortArray(QUANTUM_SAMPLES)

    /** Six 10 ms packets = 60 ms = exactly the prebuffer threshold. */
    private fun SpeakerQueue.arm(tenMsFrames: Int = 1, count: Int = 6) {
        repeat(count) { offer(packet(tenMsFrames)) }
    }

    @Test
    fun producesNothingUntilPrebufferIsMet() {
        val q = SpeakerQueue(FakeOpusCodec())
        q.offer(packet(1))   // 10 ms — well short of 60
        assertFalse(q.fillTick(out))
        q.arm(count = 5)                            // now 60 ms total
        assertTrue(q.fillTick(out))
    }

    @Test
    fun tenMillisecondSenderDrainsOneQuantumPerTick() {
        val codec = FakeOpusCodec()
        val q = SpeakerQueue(codec)
        q.arm(tenMsFrames = 1, count = 6)
        repeat(6) { assertTrue("tick $it produced nothing", q.fillTick(out)) }
        assertFalse(q.fillTick(out))
        assertEquals(6, codec.decodeCalls)
    }

    @Test
    fun sixtyMillisecondSenderDrainsOneQuantumPerTick() {
        val codec = FakeOpusCodec()
        val q = SpeakerQueue(codec)
        q.offer(packet(6))   // one 60 ms packet meets the prebuffer alone
        repeat(6) { assertTrue("tick $it produced nothing", q.fillTick(out)) }
        assertFalse(q.fillTick(out))
        assertEquals(1, codec.decodeCalls)
    }

    @Test
    fun decodedSamplesCarryThroughInOrder() {
        val q = SpeakerQueue(FakeOpusCodec())
        q.offer(packet(6))
        q.fillTick(out)
        assertTrue(out.all { it.toInt() == 6 })
    }

    @Test
    fun goingIdleReArmsThePrebuffer() {
        val q = SpeakerQueue(FakeOpusCodec())
        q.arm()
        // Drain the whole spurt — every queued sample must play, none stranded.
        repeat(6) { assertTrue(q.fillTick(out)) }
        assertFalse(q.fillTick(out))          // now idle; prebuffer re-arms here

        q.offer(packet(1))                     // 10 ms — nowhere near the 60 ms threshold
        assertFalse("prebuffer was not re-armed when the speaker went idle", q.fillTick(out))
    }

    @Test
    fun theTailOfASpurtIsNotStranded() {
        val codec = FakeOpusCodec()
        val q = SpeakerQueue(codec)
        q.arm()                                // exactly 60 ms queued
        // All six quanta must play. The earlier design stranded five of them by re-arming the
        // prebuffer while audio was still queued.
        repeat(6) { assertTrue("quantum $it was stranded", q.fillTick(out)) }
        assertEquals(6, codec.decodeCalls)
    }

    @Test
    fun overflowDropsOldestAndIsCappedInSamples() {
        val q = SpeakerQueue(FakeOpusCodec())
        repeat(120) { q.offer(packet(6)) }   // 7.2 s, far past 600 ms
        assertTrue(q.queuedSamplesForTest <= HIGH_WATER_SAMPLES)
    }

    @Test
    fun retiresAfterIdleAndClosesItsDecoder() {
        val codec = FakeOpusCodec()
        val q = SpeakerQueue(codec)
        q.arm()
        repeat(6) { q.fillTick(out) }
        assertFalse(q.retired)
        repeat(RETIRE_IDLE_TICKS) { q.fillTick(out) }
        assertTrue(q.retired)
        q.close()
        assertEquals(1, codec.decodersClosed)
    }
}
