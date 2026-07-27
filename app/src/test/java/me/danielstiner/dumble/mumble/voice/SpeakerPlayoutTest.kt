package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.voice.FakeOpusCodec.Companion.packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerPlayoutTest {

    private val out = ShortArray(QUANTUM_SAMPLES)

    /** Six 10 ms packets = 60 ms = exactly the prebuffer threshold. */
    private fun SpeakerPlayout.arm(tenMsFrames: Int = 1, count: Int = 6) {
        repeat(count) { offer(packet(tenMsFrames), isTerminator = false) }
    }

    @Test
    fun producesNothingUntilPrebufferIsMet() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.offer(packet(1), isTerminator = false)   // 10 ms — well short of 60
        assertFalse(q.fillTick(out))
        q.arm(count = 5)                            // now 60 ms total
        assertTrue(q.fillTick(out))
    }

    @Test
    fun tenMillisecondSenderDrainsOneQuantumPerTick() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.arm(tenMsFrames = 1, count = 6)
        repeat(6) { assertTrue("tick $it produced nothing", q.fillTick(out)) }
        assertFalse(q.fillTick(out))
        assertEquals(6, codec.decodeCalls)
    }

    @Test
    fun sixtyMillisecondSenderDrainsOneQuantumPerTick() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.offer(packet(6), isTerminator = false)   // one 60 ms packet meets the prebuffer alone
        repeat(6) { assertTrue("tick $it produced nothing", q.fillTick(out)) }
        assertFalse(q.fillTick(out))
        assertEquals(1, codec.decodeCalls)
    }

    @Test
    fun decodedSamplesCarryThroughInOrder() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.offer(packet(6), isTerminator = false)
        q.fillTick(out)
        assertTrue(out.all { it.toInt() == 6 })
    }

    @Test
    fun goingIdleReArmsThePrebuffer() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.arm()
        // Drain the whole spurt — every queued sample must play, none stranded.
        repeat(6) { assertTrue(q.fillTick(out)) }
        assertFalse(q.fillTick(out))          // now idle; prebuffer re-arms here

        q.offer(packet(1), isTerminator = false)   // 10 ms — nowhere near the 60 ms threshold
        assertFalse("prebuffer was not re-armed when the speaker went idle", q.fillTick(out))
    }

    @Test
    fun theTailOfASpurtIsNotStranded() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.arm()                                // exactly 60 ms queued
        // All six quanta must play. The earlier design stranded five of them by re-arming the
        // prebuffer while audio was still queued.
        repeat(6) { assertTrue("quantum $it was stranded", q.fillTick(out)) }
        assertEquals(6, codec.decodeCalls)
    }

    @Test
    fun terminatorPlaysOutAShortSpurt() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.offer(packet(1), isTerminator = false)   // 10 ms
        q.offer(packet(1), isTerminator = false)   // 20 ms total — well under the 60 ms threshold
        q.offer(ByteArray(0), isTerminator = true) // tag-only terminator: no more audio coming
        assertTrue("first quantum was not released by the terminator", q.fillTick(out))
        assertTrue("second quantum was not released by the terminator", q.fillTick(out))
        assertEquals(2, codec.decodeCalls)
    }

    @Test
    fun withoutATerminatorAShortSpurtStillWaitsOnThePrebuffer() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.offer(packet(1), isTerminator = false)
        q.offer(packet(1), isTerminator = false)   // 20 ms total, no terminator arrived
        assertFalse("threshold must still govern when no terminator ever arrives", q.fillTick(out))
    }

    @Test
    fun terminatorDoesNotReArmTheGateMidSpurt() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        repeat(5) { q.offer(packet(1), isTerminator = false) }
        q.offer(packet(1), isTerminator = true)   // terminator on the spurt's last packet
        // All six quanta must still play in one pass. A terminator may only ever release the
        // gate, never re-close it — regressing to that would reintroduce the earlier bug where
        // the terminator itself stranded a spurt's tail by clearing `prebuffered` mid-playback.
        repeat(6) { assertTrue("quantum $it was stranded by the terminator", q.fillTick(out)) }
        assertEquals(6, codec.decodeCalls)
    }

    @Test
    fun overflowDropsOldestAndIsCappedInSamples() {
        val q = SpeakerPlayout(FakeOpusCodec())
        repeat(120) { q.offer(packet(6), isTerminator = false) }   // 7.2 s, far past 600 ms
        assertTrue(q.queuedSamplesForTest <= HIGH_WATER_SAMPLES)
    }

    @Test
    fun retiresAfterIdleAndClosesItsDecoder() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.arm()
        repeat(6) { q.fillTick(out) }
        assertFalse(q.retired)
        repeat(RETIRE_IDLE_TICKS) { q.fillTick(out) }
        assertTrue(q.retired)
        q.close()
        assertEquals(1, codec.decodersClosed)
    }

    @Test
    fun prebufferingDoesNotCountAsIdle() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.offer(packet(1), isTerminator = false)   // 10 ms — far short of the 60 ms gate
        // A spurt produces nothing while it fills its prebuffer, and the playback loop ticks it
        // faster than 100 Hz while nobody is producing. Charging that as idle would retire a
        // speaker before it ever played a sample, and the fresh queue that replaced it would do
        // the same thing again — that speaker would be permanently inaudible.
        repeat(RETIRE_IDLE_TICKS * 4) { assertFalse(q.fillTick(out)) }
        assertFalse("retired while still filling its prebuffer", q.retired)

        q.arm(count = 5)                            // 60 ms total; the gate opens
        assertTrue("prebuffered spurt never played", q.fillTick(out))
    }

    @Test
    fun aSpurtStalledBelowThePrebufferEventuallyReleasesItsSlot() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.offer(packet(1), isTerminator = false)   // sender died mid-spurt: no terminator, ever
        repeat(STALL_IDLE_TICKS) { q.fillTick(out) }
        assertTrue("a stalled spurt would hold its speaker slot forever", q.retired)
    }

    @Test
    fun aRetiredQueueRejectsRatherThanSwallowsAPacket() {
        val q = SpeakerPlayout(FakeOpusCodec())
        repeat(RETIRE_IDLE_TICKS) { q.fillTick(out) }
        assertTrue(q.retired)
        // Retirement and the removal from the speaker map are not one step, so the reader can
        // still find this queue afterwards. Accepting here would drop the packet on the floor;
        // rejecting tells the caller to take a fresh queue.
        assertFalse(q.offer(packet(1), isTerminator = false))
        assertEquals(0, q.queuedSamplesForTest)
    }

    /** Eagerly, on the constructing thread — never lazily from fillTick on the audio thread. */
    @Test
    fun createsExactlyOneDecoderAtConstruction() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        assertEquals(1, codec.decodersCreated)
        q.arm()
        repeat(8) { q.fillTick(out) }
        assertEquals("a spurt must not create a second decoder", 1, codec.decodersCreated)
    }

    /**
     * close() has to retire as well as release. Otherwise a reader still holding this queue is
     * told its packet was accepted, and the next fillTick decodes it through a closed decoder.
     */
    @Test
    fun closeRetiresSoALateOfferIsRejected() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.close()
        assertTrue(q.retired)
        assertFalse(q.offer(packet(1), isTerminator = false))
        assertEquals(1, codec.decodersClosed)
    }

    @Test
    fun closeIsIdempotent() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        q.close()
        q.close()
        assertEquals("a second close must not double-free the native handle", 2, codec.decodersClosed)
        assertFalse(q.fillTick(out))
    }

    /** A queue closed mid-spurt must drop what it held rather than report it still queued. */
    @Test
    fun closeDiscardsQueuedAudio() {
        val q = SpeakerPlayout(FakeOpusCodec())
        q.arm()
        assertTrue(q.queuedSamplesForTest > 0)
        q.close()
        assertEquals(0, q.queuedSamplesForTest)
    }

    /** span 0: packetSamples cannot parse it, so it must not occupy the queue. */
    @Test
    fun anUnparseablePacketIsNotQueued() {
        val q = SpeakerPlayout(FakeOpusCodec())
        assertTrue(q.offer(packet(0), isTerminator = false))
        assertEquals(0, q.queuedSamplesForTest)
    }

    /** A tag-only frame carries no audio and no terminator: accepted, but it changes nothing. */
    @Test
    fun aTagOnlyFrameIsAcceptedAndQueuesNothing() {
        val q = SpeakerPlayout(FakeOpusCodec())
        assertTrue(q.offer(ByteArray(0), isTerminator = false))
        assertEquals(0, q.queuedSamplesForTest)
        assertFalse(q.fillTick(out))
    }

    /** Pins the retirement window from below; the existing test only pins it from above. */
    @Test
    fun doesNotRetireBeforeTheIdleWindowElapses() {
        val q = SpeakerPlayout(FakeOpusCodec())
        repeat(RETIRE_IDLE_TICKS - 1) { q.fillTick(out) }
        assertFalse("retired a tick early", q.retired)
        q.fillTick(out)
        assertTrue(q.retired)
    }

    /**
     * The lock discipline is the design's crux and is otherwise unexercised. An unguarded deque
     * surfaces here as ConcurrentModificationException or NoSuchElementException within a few
     * iterations; the accounting assertion catches a queuedSamples update escaping the lock.
     */
    @Test
    fun concurrentOffersAndTicksKeepTheAccountingSane() {
        val codec = FakeOpusCodec()
        val q = SpeakerPlayout(codec)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val readers = (1..4).map {
            Thread {
                start.await()
                repeat(2000) { q.offer(packet(1), isTerminator = false) }
            }.apply { setUncaughtExceptionHandler { _, t -> failure.compareAndSet(null, t) }; start() }
        }
        val playback = Thread {
            start.await()
            val local = ShortArray(QUANTUM_SAMPLES)
            repeat(2000) {
                q.fillTick(local)
                val queued = q.queuedSamplesForTest
                check(queued in 0..HIGH_WATER_SAMPLES) { "queuedSamples escaped its bounds: $queued" }
            }
        }.apply { setUncaughtExceptionHandler { _, t -> failure.compareAndSet(null, t) }; start() }

        start.countDown()
        (readers + playback).forEach { it.join(30_000) }
        failure.get()?.let { throw AssertionError("concurrent access failed", it) }
        assertTrue(q.queuedSamplesForTest in 0..HIGH_WATER_SAMPLES)
    }
}
