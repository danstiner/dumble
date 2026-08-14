package me.danielstiner.dumble.mumble.voice

import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.voice.FakeOpusCodec.Companion.packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoiceReceiverTest {

    /** Releases a latch on write, so tests wait on real progress rather than sleeping. */
    private class LatchingOut(private val latch: CountDownLatch) : AudioOut {
        @Volatile var closed = false
        override fun write(pcm: ShortArray, n: Int): Boolean { latch.countDown(); return true }
        override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
        override fun close() { closed = true }
    }

    /** outFactory must never run: for tests where the playback loop never gets built at all —
     *  either start() is never reached, or newEngine() itself refuses — so an unexpected call
     *  fails loudly rather than silently opening an output nothing should have opened. */
    private val unusedOut: () -> AudioOut = { error("playback thread must not start") }

    private fun audioPayload(session: Int, tenMsFrames: Int, terminator: Boolean = false): ByteArray {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(session)
            .setOpusData(ByteString.copyFrom(packet(tenMsFrames)))
            .setIsTerminator(terminator)
            .build()
        return byteArrayOf(0) + audio.toByteArray()
    }

    /** Polls [cond] until it is true or [timeoutMillis] elapses, then asserts it — the loop
     *  parking/pacing this class drives means most assertions are about eventual state, not an
     *  instant one. */
    private fun awaitTrue(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(message, cond())
    }

    @Test
    fun routesEachPacketToTheEngine() {
        val fake = FakePlayoutEngine()
        // Nothing here drives the tick cadence by hand: every offer wakes the loop, and a
        // blocking take() on an exhausted script would wedge it there, so stop() would wait out
        // its full 1 s join and leave a daemon thread parked for the rest of the test JVM.
        fake.blockWhenEmpty = false
        val rx = VoiceReceiver({ fake }) { FakeAudioOut() }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            rx.onTunneledAudio(audioPayload(session = 2, tenMsFrames = 6, terminator = true))

            assertEquals("every packet must reach the engine", 2, fake.offered.size)
            assertEquals(1, fake.offered[0].first)
            assertFalse("only the second packet set the terminator flag", fake.offered[0].third)
            assertEquals(2, fake.offered[1].first)
            assertTrue("the terminator flag must reach the engine", fake.offered[1].third)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun aFailedWriteStopsTheLoop() {
        val closed = CountDownLatch(1)
        val writes = AtomicInteger()
        val out = object : AudioOut {
            // Returns false without blocking, like a real AudioTrack whose track has died. The
            // loop's only pacing is a successful write, so a loop that ignored this would spin a
            // core at THREAD_PRIORITY_URGENT_AUDIO rather than stop.
            override fun write(pcm: ShortArray, n: Int): Boolean { writes.incrementAndGet(); return false }
            override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
            override fun close() = closed.countDown()
        }
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(3)))
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            assertTrue("loop did not exit after a failed write", closed.await(5, TimeUnit.SECONDS))
            assertEquals("a failed write must stop the loop, not be retried", 1, writes.get())
            // The write that failed happened on a producing tick, so the set had just been
            // published non-empty. close() runs after the clear, so awaiting it above orders this
            // read. Without the clear an audioserver restart lights a speaker for the session.
            assertEquals("a self-death must not strand speaking state", emptySet<Int>(), rx.speakingSessions.value)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun audioArrivingAfterStopAllocatesNothing() {
        val fake = FakePlayoutEngine()
        // Racily rather than always: the loop can drain the whole script and park before the
        // packet below is posted, and that packet's notifyAll then wakes it into a tick the script
        // cannot serve. Blocking there costs stop() its full 1 s join, and the destroyed assertion
        // fails outright.
        fake.blockWhenEmpty = false
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        fake.scriptSilence(1)
        val latch = CountDownLatch(1)
        val out = LatchingOut(latch)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
        } finally {
            rx.stop()
        }
        val offeredBeforeStop = fake.offered.size

        // The reader is a separate thread from stop(), so a packet can still be in flight when
        // stop() latches. Retirement is native now, so the only thing left to prove here is that
        // the reader itself is gated — a late packet must never reach an engine that is about to
        // be destroyed.
        rx.onTunneledAudio(audioPayload(session = 2, tenMsFrames = 6))

        assertEquals("a packet arriving after stop() must not reach the engine", offeredBeforeStop, fake.offered.size)
        assertTrue("the engine must still be destroyed", fake.destroyed)
    }

    /**
     * Single-shot: MumbleConnection builds a fresh receiver per connection attempt and never
     * restarts one, so `stopped` is one-way and start() refuses after it. Without that guard a
     * restart runs a second playback thread the already-completed stop() will never join, holding
     * an open AudioOut for the life of the process.
     *
     * The second stop() is what makes this deterministic rather than a sleep: it joins whatever the
     * second start() may have created, so the count below is settled by the time it returns.
     */
    @Test
    fun startAfterStopIsRefused() {
        val built = AtomicInteger()
        val latch = CountDownLatch(1)
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { built.incrementAndGet(); LatchingOut(latch) }
        rx.start()
        assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
        rx.stop()
        assertEquals(1, built.get())

        rx.start()
        rx.stop()
        assertEquals("start() after stop() must not run a second playback thread", 1, built.get())
    }

    @Test
    fun reportsSpeakingSessions() {
        val latch = CountDownLatch(1)
        val speakingAtWrite = AtomicReference<Set<Int>?>(null)
        lateinit var rx: VoiceReceiver
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean {
                // Sampled here rather than polled from the test thread. loop() publishes the set
                // before it writes, so any write is a valid observation point.
                speakingAtWrite.compareAndSet(null, rx.speakingSessions.value)
                latch.countDown()
                return true
            }
            override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(9)))
        fake.scriptSilence(1)
        rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
            assertEquals(setOf(9), speakingAtWrite.get())
            // Drained is a terminal state, so waiting for it cannot lose a race the way waiting
            // for "speaking" can.
            awaitTrue("speaking must clear once the playout drains") { rx.speakingSessions.value.isEmpty() }
        } finally {
            rx.stop()
        }
    }

    /**
     * A peer sending nothing but unparseable payloads must not take the reader down with it, and
     * must not poison the session for the good packets that follow. This is the shape a truncated
     * or hostile stream takes, so it arrives at the packet rate.
     */
    @Test
    fun keepsReadingThroughMalformedPayloads() {
        val fake = FakePlayoutEngine()
        fake.offerResult = NativePlayout.OFFER_MALFORMED_PACKET
        fake.blockWhenEmpty = false
        val rx = VoiceReceiver({ fake }) { FakeAudioOut() }
        rx.start()
        try {
            repeat(8) { rx.onTunneledAudio(audioPayload(session = 3, tenMsFrames = 6)) }
            assertEquals("a malformed payload must not stop the reader", 8, fake.offered.size)

            fake.offerResult = NativePlayout.OFFER_ACCEPTED
            rx.onTunneledAudio(audioPayload(session = 3, tenMsFrames = 6))
            assertEquals("the session must still work after garbage", 9, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    /**
     * The cap is enforced natively now; the only thing left on this side is that a capped
     * response does not stop the reader from routing packets, and a session already admitted
     * keeps working once native clears room for it.
     */
    @Test
    fun logsTheSpeakerCapOnce() {
        val fake = FakePlayoutEngine()
        fake.offerResult = NativePlayout.OFFER_SPEAKER_CAP
        fake.blockWhenEmpty = false
        val rx = VoiceReceiver({ fake }) { FakeAudioOut() }
        rx.start()
        try {
            repeat(8) { rx.onTunneledAudio(audioPayload(session = it, tenMsFrames = 6)) }
            assertEquals("every packet must still reach the engine even while capped", 8, fake.offered.size)

            fake.offerResult = NativePlayout.OFFER_ACCEPTED
            rx.onTunneledAudio(audioPayload(session = 0, tenMsFrames = 6))
            assertEquals("the reader must keep working after the cap trips", 9, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    /**
     * Unlike the cap and oversize-payload codes above, OFFER_ENGINE_UNUSABLE is session-terminal —
     * a wrong branch here silently kills receive for the rest of the session, with a green suite
     * everywhere else, since nothing else exercises this code.
     */
    @Test
    fun engineUnusableDisablesReceiveForTheSession() {
        val fake = FakePlayoutEngine()
        fake.offerResult = NativePlayout.OFFER_ENGINE_UNUSABLE
        fake.blockWhenEmpty = false
        val rx = VoiceReceiver({ fake }) { FakeAudioOut() }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            assertEquals("engine-unusable must latch and stop further offers", 1, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun ignoresUnknownTypeByte() {
        val fake = FakePlayoutEngine()
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { FakeAudioOut() }
        rx.start()
        try {
            // The body must be a *valid* Audio message, differing from an accepted packet only in
            // the type byte. With a garbage body the malformed-protobuf path would reject it
            // anyway, and the test would pass just as well with the type check deleted.
            val wrongType = audioPayload(session = 1, tenMsFrames = 6).also { it[0] = 99 }
            rx.onTunneledAudio(wrongType)
            rx.onTunneledAudio(ByteArray(0))

            assertEquals("a non-audio type byte must not reach the engine", 0, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun ignoresMalformedProtobufBody() {
        val fake = FakePlayoutEngine()
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { FakeAudioOut() }
        rx.start()
        try {
            // Valid type byte, garbage body. The property under test is that this does not
            // propagate: MumbleTcpTransport's reader catches Throwable and tears the whole session
            // down, so an escaping parse failure would take chat and channels with it.
            rx.onTunneledAudio(byteArrayOf(0, -1, -1, -1, -1, -1))

            assertEquals(0, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    /**
     * A spurt shorter than the periodic interval still has to publish, because a stall segments
     * glitchy speech into exactly such spurts — without the end-of-spurt sample the counters would
     * go blind precisely when the call is worst.
     */
    @Test
    fun aSpurtPublishesStatsWhenItEnds() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        out.nextStats = OutputStats(latencyMs = 42.0, underrunsTotal = 7)
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            // Drained is terminal, so waiting on it cannot lose a race the way waiting on
            // "speaking" can.
            awaitTrue("a spurt must publish stats when it ends") { rx.playoutStats.value != null }
            val stats = rx.playoutStats.value!!
            // Every underrun the platform counted was already there when the spurt began, so the
            // spurt itself is clean. Without the baseline this would report 7.
            assertEquals(0, stats.underruns)
            // A clean tick from the engine, and nothing else, so no gap can have been counted
            // and nothing can have been thrown away.
            assertEquals("a clean spurt has no gaps", 0, stats.concealedTicks)
            assertEquals("a clean spurt drops nothing", 0, stats.droppedPackets)
        } finally {
            rx.stop()
        }
    }

    /**
     * The counter is native and monotonic, and the loop subtracts a spurt-start baseline from it
     * exactly the way it does for the platform's underrun count.
     */
    @Test
    fun aPartialTickCountsAsConcealment() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1), concealed = true))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            awaitTrue("a zero-padded tick is an audible gap and must be counted") {
                (rx.playoutStats.value?.concealedTicks ?: 0) >= 1
            }
        } finally {
            rx.stop()
        }
    }

    /**
     * [aPartialTickCountsAsConcealment] alone cannot tell "the baseline was rearmed at this
     * spurt's close" apart from "never touched again": with a single spurt, both look identical.
     * This drives a glitchy spurt followed by a clean one on the same session — a baseline that
     * failed to rearm would leak spurt 1's count into spurt 2's published stats, contradicting the
     * "current talk spurt, not cumulative" contract on [PlayoutStats.concealedTicks].
     *
     * The closing tick between the two spurts reports a nonzero active-speaker count, but not for
     * the reason a bounded idle wait might suggest — nothing in [FakePlayoutEngine.script] ever
     * notifies idleLock, so an unbounded wait here would park the loop forever with no way to
     * retry fillQuantum(). The nonzero count keeps that wait bounded (10 ms) purely so the loop
     * gets back around to calling fillQuantum() again at all; what actually picks spurt 2's tick
     * back up once this test scripts it is [FakePlayoutEngine.fillQuantum]'s own `ticks.take()`,
     * which blocks until `put()` regardless of any wait.
     */
    @Test
    fun concealedTicksDoesNotCarryAcrossSpurts() {
        val writes = AtomicInteger()
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean { writes.incrementAndGet(); return true }
            override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        val rx = VoiceReceiver({ fake }) { out }
        fake.script(
            FakePlayoutEngine.Tick(producing = listOf(1), concealed = true),
            FakePlayoutEngine.Tick(activeSpeakers = 1),
        )
        rx.start()
        try {
            awaitTrue("first spurt must accrue a concealed tick") {
                (rx.playoutStats.value?.concealedTicks ?: 0) >= 1
            }
            val writesAfterFirstSpurt = writes.get()

            // Spurt 2: clean, same session, no concealment.
            fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
            fake.scriptSilence(1)
            awaitTrue("second spurt never produced audio") { writes.get() > writesAfterFirstSpurt }
            awaitTrue("a clean spurt must not inherit the previous spurt's concealment count") {
                rx.playoutStats.value?.concealedTicks == 0
            }
        } finally {
            rx.stop()
        }
    }

    /**
     * The one instrument that would show the 32-slot packet pool capping a 10 ms sender's backlog
     * at 320 ms, which is the receive path's one documented behaviour delta. It has to reach the
     * flow, and it has to be scoped to the spurt the way concealment is — a cumulative count would
     * report every earlier burst of the session against whatever spurt happened to be running.
     *
     * Two spurts on the same session, for the reason [concealedTicksDoesNotCarryAcrossSpurts]
     * needs two: with one, "rearmed at the spurt's close" and "never touched again" look the same.
     */
    @Test
    fun droppedPacketsAreCountedFromTheSpurtBaseline() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        val fake = FakePlayoutEngine()
        val rx = VoiceReceiver({ fake }) { out }
        fake.script(
            FakePlayoutEngine.Tick(producing = listOf(1), dropped = 5),
            // activeSpeakers keeps the closing park bounded so the loop comes back around for
            // spurt 2 — see concealedTicksDoesNotCarryAcrossSpurts for why that matters.
            FakePlayoutEngine.Tick(activeSpeakers = 1),
        )
        rx.start()
        try {
            awaitTrue("the spurt must report the drops the engine counted") {
                rx.playoutStats.value?.droppedPackets == 5
            }
            // Scripted only now: the baseline is rearmed in the same loop iteration that published
            // the reading above, so appending spurt 2 afterwards cannot race it.
            fake.script(FakePlayoutEngine.Tick(producing = listOf(1), dropped = 3))
            fake.scriptSilence(1)
            awaitTrue("a spurt must report its own drops, not the session's running total") {
                rx.playoutStats.value?.droppedPackets == 3
            }
        } finally {
            rx.stop()
        }
    }

    /**
     * The platform reading and the engine's own counters fail independently, so a broken AudioOut
     * costs the two fields derived from it and nothing else. It must not cost the sample, and —
     * because the baselines are rearmed only when a reading was good — it must not cost the next
     * spurt either: suppressing the whole publication would leave spurt 2 measuring against
     * spurt 1's baseline and reporting 8 drops instead of 3.
     */
    @Test
    fun aThrowingOutputStatsStillPublishesTheEnginesOwnCounters() {
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean = true
            override fun outputStats(): OutputStats = error("stats are broken")
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        val rx = VoiceReceiver({ fake }) { out }
        fake.script(
            FakePlayoutEngine.Tick(producing = listOf(1), dropped = 5),
            FakePlayoutEngine.Tick(activeSpeakers = 1),
        )
        rx.start()
        try {
            awaitTrue("the engine's own numbers must survive a broken AudioOut") {
                rx.playoutStats.value?.droppedPackets == 5
            }
            assertNull("a broken AudioOut cannot report latency", rx.playoutStats.value?.latencyMs)
            assertNull("nor underruns", rx.playoutStats.value?.underruns)
            fake.script(FakePlayoutEngine.Tick(producing = listOf(1), dropped = 3))
            fake.scriptSilence(1)
            awaitTrue("the baseline must rearm even though the platform reading failed") {
                rx.playoutStats.value?.droppedPackets == 3
            }
        } finally {
            rx.stop()
        }
    }

    /**
     * Every other underrun assertion is satisfied by a constant: `aSpurtPublishesStatsWhenItEnds`
     * pins 0 against a platform counter that never moves, the two baseline tests only ask whether
     * the count is null, and none of them can tell `stats.underrunsTotal - it` apart from a plain
     * `0`. This is the only test that moves the platform counter between the baseline read and the
     * publish, so it is the only one that pins the subtraction itself.
     *
     * Three underruns precede the spurt and two more land inside it: a count that forgot to
     * subtract the baseline reports 5, and one that published the baseline reports 3.
     */
    @Test
    fun underrunsAreCountedFromTheSpurtBaseline() {
        val calls = AtomicInteger()
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean = true
            override fun outputStats() = OutputStats(
                latencyMs = null,
                underrunsTotal = if (calls.incrementAndGet() == 1) 3 else 5,
            )
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            awaitTrue("a spurt must publish stats when it ends") { rx.playoutStats.value != null }
            assertEquals(
                "only the underruns inside this spurt count; the 3 that preceded it are the baseline",
                2,
                rx.playoutStats.value?.underruns,
            )
        } finally {
            rx.stop()
        }
    }

    @Test
    fun statsCarryEachSpeakersBufferedDepth() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        val fake = FakePlayoutEngine()
        fake.depthsBySession = mapOf(4 to 960)
        fake.script(FakePlayoutEngine.Tick(producing = listOf(4)))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            awaitTrue("a spurt must publish stats when it ends") { rx.playoutStats.value != null }
            // The map is keyed by session so a per-speaker view needs no extra plumbing later.
            assertTrue("session 4 missing from depths", rx.playoutStats.value!!.bufferedSamples.containsKey(4))
        } finally {
            rx.stop()
        }
    }

    /**
     * Every other test drives one short spurt, so writesThisSpurt never gets near
     * WRITES_PER_SAMPLE (100) and the periodic mid-spurt sample never fires — proven by the fact
     * that raising WRITES_PER_SAMPLE to Int.MAX_VALUE leaves every other test green.
     *
     * A dedicated feeder thread keeps pushing producing ticks onto the fake, with no pacing of its
     * own, for as long as the test needs — so supply is never the bottleneck and the spurt cannot
     * end on its own mid-poll. That makes speakingSessions a reliable discriminator: the loop
     * clears it to empty before every end-of-spurt publish and leaves it non-empty before every
     * periodic one, and with supply guaranteed the first publish this test observes can only be
     * reached by staying non-empty through write 100 — i.e. the periodic path.
     *
     * The depth is a fixed nonzero value for the whole test rather than modeling real drainage:
     * `statsCarryEachSpeakersBufferedDepth` only checks containsKey(4), so a depth map that always
     * reports 0 would leave that test green. This test is what pins a mid-spurt sample to a real
     * depth rather than the ~0 an end-of-spurt one would have.
     */
    @Test
    fun aLongSpurtPublishesAPeriodicSampleWhileStillRunning() {
        val out = FakeAudioOut(writeSleepMillis = 1)
        val fake = FakePlayoutEngine()
        fake.depthsBySession = mapOf(4 to 480)
        val rx = VoiceReceiver({ fake }) { out }
        val feederRunning = AtomicBoolean(true)
        val feeder = Thread({
            // Paced roughly to the 1 ms write, not left fully unthrottled: unlike the old
            // per-speaker jitter buffer, FakePlayoutEngine's tick queue is unbounded, so a feeder
            // that outruns consumption by orders of magnitude grows it without limit for as long
            // as the test runs.
            while (feederRunning.get()) {
                fake.script(FakePlayoutEngine.Tick(producing = listOf(4)))
                Thread.sleep(0, 200_000)
            }
        }, "test-feeder").apply { isDaemon = true }
        rx.start()
        feeder.start()
        try {
            awaitTrue("no publish arrived before the deadline") { rx.playoutStats.value != null }
            val stats = rx.playoutStats.value!!
            val speaking = rx.speakingSessions.value
            assertTrue(
                "a publish with an empty speaking set is the end-of-spurt sample, not the periodic one",
                speaking.contains(4),
            )
            assertTrue(
                "a mid-spurt sample must show a real jitter-buffer depth, not the ~0 an end-of-spurt one has",
                (stats.bufferedSamples[4] ?: 0) > 0,
            )
        } finally {
            feederRunning.set(false)
            feeder.join(5_000)
            fake.scriptSilence(1)
            rx.stop()
        }
    }

    /**
     * [aLongSpurtPublishesAPeriodicSampleWhileStillRunning] waits for the *first* publish and stops,
     * so it says nothing about the rate of the ones after it. Dropping `writesThisSpurt = 0` from
     * the periodic branch leaves the counter above the threshold forever, which samples on every
     * single write rather than once a second — a hundredfold rise in map allocation and (in debug)
     * string formatting on a THREAD_PRIORITY_URGENT_AUDIO thread, for no visible change in
     * behaviour. Caught by hand: that mutation passes every other test in this class.
     *
     * The bound is deliberately loose. One sample per 100 writes is the contract; asserting
     * "well under one per 10" separates it from the mutant's one-per-one by a factor of ten while
     * leaving room for the spurt boundaries a feeder-driven test cannot fully rule out.
     *
     * Only calls that see a non-empty speaking set are counted, so the spurt's baseline read (taken
     * before the set is published) and any end-of-spurt publish (taken after it is cleared) stay out
     * of the rate being measured.
     */
    @Test
    fun thePeriodicSampleIsRateLimited() {
        val writes = AtomicInteger()
        val samples = AtomicInteger()
        lateinit var rx: VoiceReceiver
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean {
                writes.incrementAndGet()
                Thread.sleep(1)
                return true
            }
            override fun outputStats(): OutputStats {
                if (rx.speakingSessions.value.isNotEmpty()) samples.incrementAndGet()
                return OutputStats(latencyMs = null, underrunsTotal = 0)
            }
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        rx = VoiceReceiver({ fake }) { out }
        val feederRunning = AtomicBoolean(true)
        val feeder = Thread({
            // See aLongSpurtPublishesAPeriodicSampleWhileStillRunning for why this is paced
            // rather than a bare spin: FakePlayoutEngine's tick queue is unbounded.
            while (feederRunning.get()) {
                fake.script(FakePlayoutEngine.Tick(producing = listOf(4)))
                Thread.sleep(0, 200_000)
            }
        }, "test-feeder").apply { isDaemon = true }
        rx.start()
        feeder.start()
        try {
            awaitTrue("the spurt never reached 300 writes", timeoutMillis = 20_000) { writes.get() >= 300 }
            val wrote = writes.get()
            val sampled = samples.get()
            assertTrue("a long spurt must keep sampling, not sample once and stop", sampled >= 2)
            assertTrue(
                "sampled $sampled times in $wrote writes; the periodic sample is not rate-limited",
                sampled < wrote / 10,
            )
        } finally {
            feederRunning.set(false)
            feeder.join(5_000)
            fake.scriptSilence(1)
            rx.stop()
        }
    }

    /**
     * Instrumentation must never reach the loop's fatal catch (Throwable). Voice is additive: a
     * stats bug silencing a call would be worse than the bug.
     *
     * The first-write latch is load-bearing, not decorative: without it, polling speakingSessions
     * for "empty" races the loop's own starting state — both are emptySet(), so a poll that lands
     * before the spurt has even begun observes "drained" immediately and the rest of the test
     * proceeds against a spurt that never actually finished.
     */
    @Test
    fun aThrowingOutputStatsDoesNotStopPlayback() {
        val writes = AtomicInteger()
        val firstWrite = CountDownLatch(1)
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean {
                writes.incrementAndGet()
                firstWrite.countDown()
                return true
            }
            override fun outputStats(): OutputStats = error("stats are broken")
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        fake.script(
            FakePlayoutEngine.Tick(producing = listOf(1)),
            FakePlayoutEngine.Tick(activeSpeakers = 1),
        )
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            assertTrue("no audio written", firstWrite.await(5, TimeUnit.SECONDS))
            awaitTrue("first spurt must fully drain") { rx.speakingSessions.value.isEmpty() }
            val writesAfterFirstSpurt = writes.get()

            fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
            fake.scriptSilence(1)
            awaitTrue(
                "a stats failure at the first spurt's end stopped playback for the next spurt",
            ) { writes.get() > writesAfterFirstSpurt }
        } finally {
            rx.stop()
        }
    }

    /**
     * A baseline that could not be read must not fall back to the previous spurt's, which would
     * make every count in this spurt wrong — possibly negative. Reporting nothing is better.
     */
    @Test
    fun aFailedBaselineNullsTheUnderrunCount() {
        val calls = AtomicInteger()
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean = true
            override fun outputStats(): OutputStats {
                // The first call is the spurt-start baseline read; later calls are the publishes.
                if (calls.incrementAndGet() == 1) error("baseline read failed")
                return OutputStats(latencyMs = 12.0, underrunsTotal = 9)
            }
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            awaitTrue("the spurt must still publish latency and depths") { rx.playoutStats.value != null }
            val stats = rx.playoutStats.value!!
            assertNull("a failed baseline must not yield a count", stats.underruns)
            assertEquals(12.0, stats.latencyMs!!, 1e-9)
        } finally {
            rx.stop()
        }
    }

    /**
     * [aFailedBaselineNullsTheUnderrunCount] drives only one spurt, so there is no previous
     * baseline to leak from — it cannot tell `.getOrNull()` apart from
     * `.getOrNull() ?: underrunBaseline`. This drives two spurts: the first's baseline read
     * succeeds, the second's throws, and a stale-fallback mutant would report the second spurt's
     * counts relative to the first spurt's baseline instead of null.
     */
    @Test
    fun aStaleBaselineDoesNotLeakIntoTheNextSpurt() {
        val calls = AtomicInteger()
        val armThrow = AtomicBoolean(false)
        val thrown = AtomicBoolean(false)
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean = true
            override fun outputStats(): OutputStats {
                val call = calls.incrementAndGet()
                // Fires exactly once, on the first call after the test arms it — the class doc
                // above is why that call is guaranteed to be spurt 2's baseline read.
                if (armThrow.get() && thrown.compareAndSet(false, true)) {
                    error("second spurt's baseline read failed")
                }
                return OutputStats(latencyMs = null, underrunsTotal = 100 + call)
            }
            override fun close() = Unit
        }
        val fake = FakePlayoutEngine()
        fake.script(
            FakePlayoutEngine.Tick(producing = listOf(1)),
            FakePlayoutEngine.Tick(activeSpeakers = 1),
        )
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        try {
            awaitTrue("first spurt must publish") { rx.playoutStats.value != null }
            val firstSpurt = rx.playoutStats.value
            assertNotNull("first spurt's baseline read must succeed", firstSpurt!!.underruns)
            // Drained is terminal, so waiting on it (rather than on the publish alone) cannot
            // race a spurt that has published but not yet fully cleared speakingSessions.
            awaitTrue("first spurt must fully drain before the second begins") {
                rx.speakingSessions.value.isEmpty()
            }

            armThrow.set(true)
            fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
            fake.scriptSilence(1)
            awaitTrue("second spurt must still publish despite the baseline failure") {
                rx.playoutStats.value != null && rx.playoutStats.value != firstSpurt
            }
            val secondSpurt = rx.playoutStats.value!!
            assertTrue("the armed baseline call was never reached", thrown.get())
            assertNull("a failed baseline must not fall back to the previous spurt's count", secondSpurt.underruns)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun statsAreClearedWhenTheLoopExits() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        val fake = FakePlayoutEngine()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        fake.scriptSilence(1)
        val rx = VoiceReceiver({ fake }) { out }
        rx.start()
        awaitTrue("a spurt must publish stats when it ends") { rx.playoutStats.value != null }
        rx.stop()
        // The loop owns the flow while alive and has to hand it back empty, or a stats page shows
        // the previous call's numbers after a disconnect.
        assertNull("stats outlived the playback loop", rx.playoutStats.value)
    }

    @Test
    fun refusedBuffersStopThePlaybackLoop() {
        val fake = FakePlayoutEngine()
        fake.refuseBuffers = true
        val writes = AtomicInteger()
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean { writes.incrementAndGet(); return true }
            override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
            override fun close() = Unit
        }
        val receiver = VoiceReceiver({ fake }) { out }
        receiver.start()
        fake.script(FakePlayoutEngine.Tick(producing = listOf(1)))
        // The loop must not treat the refusal as "nobody is speaking" and park: it has to stop,
        // because a negative return does not block and spinning on it burns a core at
        // THREAD_PRIORITY_URGENT_AUDIO.
        awaitTrue("loop did not stop after a refused buffer") { fake.destroyed }
        assertEquals("nothing should have been written", 0, writes.get())
    }

    @Test
    fun theEngineIsDestroyedExactlyOnceWhenTheLoopExits() {
        val fake = FakePlayoutEngine()
        val receiver = VoiceReceiver({ fake }) { FakeAudioOut() }
        receiver.start()
        fake.scriptSilence(1)
        receiver.stop()
        // Double-free is the historical bug class here (see VoiceReceiver.stop()'s join
        // discipline), so this pins the exact count rather than merely "at least once".
        assertEquals("engine must be destroyed exactly once", 1, fake.destroyCalls.get())
    }

    /**
     * newEngine() returning null — libopus unreachable, the same way outFactory can fail to build
     * an AudioOut — must degrade receive to silence for the session: latched, no playback thread,
     * and in particular no AudioOut ever built. unusedOut fails loudly if that guarantee slips.
     */
    @Test
    fun anUnavailableEngineDisablesReceiveWithoutTouchingAudioOut() {
        val rx = VoiceReceiver({ null }, unusedOut)
        rx.start()
        rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
        assertEquals(emptySet<Int>(), rx.speakingSessions.value)
        // Refused exactly like a start() after a real stop() — see startAfterStopIsRefused.
        rx.start()
        rx.stop()
    }

    /**
     * Mirrors MumbleConnection.teardown(): an attempt superseded before publish, superseded
     * mid-handshake, or one whose transport.connect() throws all call stop() on a receiver whose
     * start() was never reached. newEngine must never run on that path — the bug this guards
     * against built the engine eagerly at construction, leaking one per such attempt regardless of
     * whether it ever played anything. builds staying 0 proves both that nothing was allocated and,
     * trivially, that nothing was leaked: there is nothing to free.
     */
    @Test
    fun stopWithoutEverStartingBuildsNoEngine() {
        val builds = AtomicInteger()
        val rx = VoiceReceiver({ builds.incrementAndGet(); FakePlayoutEngine() }, unusedOut)
        rx.stop()
        assertEquals("a receiver that never started must never build an engine", 0, builds.get())
        // start() after stop() stays refused, so a late guard success can't allocate one either.
        rx.start()
        assertEquals(0, builds.get())
    }
}
