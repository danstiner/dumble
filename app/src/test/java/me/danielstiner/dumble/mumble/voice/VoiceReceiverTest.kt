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

    /** For tests that never call start(), so an unexpected playback thread fails loudly. */
    private val unusedOut: () -> AudioOut = { error("playback thread must not start") }

    private fun audioPayload(session: Int, tenMsFrames: Int): ByteArray {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(session)
            .setOpusData(ByteString.copyFrom(packet(tenMsFrames)))
            .build()
        return byteArrayOf(0) + audio.toByteArray()
    }

    /**
     * The ordering MumbleConnection can produce and cannot easily be driven from there: an attempt
     * killed by an instant auth reject is retired — which calls stop() — before connect()'s
     * coroutine gets as far as start(). Without the one-way latch that is a playback thread nothing
     * will ever stop, holding an open AudioOut until the process dies, one per failed connect.
     *
     * Tested here rather than through MumbleConnection because the invariant is this class's: there
     * the same window is a race between two coroutines that has to be lost on purpose.
     */
    @Test
    fun startAfterStopNeverRunsAPlaybackThread() {
        val opened = CountDownLatch(1)
        val rx = VoiceReceiver(FakeOpusCodec()) {
            opened.countDown()
            LatchingOut(CountDownLatch(1))
        }

        rx.stop()
        rx.start()

        // loop() builds its output before anything else, so a thread that did start would trip
        // this well inside the wait. The assertion is on the negative, so a slow machine can only
        // make it pass — never fail.
        assertFalse(
            "start() after stop() ran a playback thread",
            opened.await(500, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun routesToPerSenderDecoders() {
        val codec = FakeOpusCodec()
        val latch = CountDownLatch(4)
        val out = LatchingOut(latch)
        val rx = VoiceReceiver(codec) { out }

        // Offered before start(), so no playback thread exists to tick while this runs. LatchingOut
        // never blocks, so once started the loop is unpaced: a stall between these offers was
        // enough for session 1 to drain, reach RETIRE_IDLE_TICKS, and be removed — after which the
        // third packet allocates a third decoder and both assertions below fail.
        // 60 ms each meets the prebuffer immediately.
        rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
        rx.onTunneledAudio(audioPayload(session = 2, tenMsFrames = 6))
        // A second packet for a session already in the map. Without it the assertion below holds
        // for a per-packet decoder just as well as a per-sender one, so it would not test what it
        // names.
        rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
        assertEquals("one decoder per speaker, not per packet", 2, codec.decodersCreated)

        rx.start()
        try {
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
        } finally {
            rx.stop()
        }
        assertTrue("output not closed by stop()", out.closed)
        assertEquals("stop() must close every decoder it created", 2, codec.decodersClosed)
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
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 3, tenMsFrames = 6))
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
        val codec = FakeOpusCodec()
        val latch = CountDownLatch(1)
        val out = LatchingOut(latch)
        val rx = VoiceReceiver(codec) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
        } finally {
            rx.stop()
        }
        val afterStop = codec.decodersCreated

        // The reader is a separate thread from stop(), so a packet can still be in flight when the
        // sweep finishes. offer() answering false makes the retry take a *fresh* playout, so an
        // ungated late packet does not merely get dropped — it allocates a native decoder into a
        // map nothing will ever sweep again, one per session, for the life of the process.
        rx.onTunneledAudio(audioPayload(session = 2, tenMsFrames = 6))

        assertEquals("a packet arriving after stop() must not allocate", afterStop, codec.decodersCreated)
        assertEquals("every decoder must still be closed", codec.decodersCreated, codec.decodersClosed)
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
        val rx = VoiceReceiver(FakeOpusCodec()) { built.incrementAndGet(); LatchingOut(latch) }
        rx.start()
        rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
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
                // Sampled here rather than polled from the test thread. This fake never blocks, so
                // unlike a real AudioTrack it imposes no pacing: the whole 60 ms spurt drains in
                // microseconds and "speaking" is over before a poller can see it. loop() publishes
                // the set before it writes, so any write is a valid observation point.
                speakingAtWrite.compareAndSet(null, rx.speakingSessions.value)
                latch.countDown()
                return true
            }
            override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
            override fun close() = Unit
        }
        rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 9, tenMsFrames = 6))
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
            assertEquals(setOf(9), speakingAtWrite.get())
            // Drained is a terminal state, so waiting for it cannot lose a race the way waiting
            // for "speaking" can.
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.speakingSessions.value.isNotEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("speaking must clear once the playout drains", emptySet<Int>(), rx.speakingSessions.value)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun capsConcurrentSpeakers() {
        val codec = FakeOpusCodec()
        // Deliberately not started: with no playback thread nothing drains and nothing retires,
        // so the map only grows — which is the case the cap exists for.
        val rx = VoiceReceiver(codec, unusedOut)
        repeat(MAX_SPEAKERS + 8) { rx.onTunneledAudio(audioPayload(session = it, tenMsFrames = 6)) }
        // Decoders are the thing the cap exists to bound: a playout allocates one eagerly in its
        // constructor. packetSamples only counts offers, which happen after that construction, so
        // it alone would not catch an implementation that allocated past the cap and discarded.
        assertEquals("sessions past the cap must not allocate", MAX_SPEAKERS, codec.decodersCreated)
        assertEquals(MAX_SPEAKERS, codec.packetSamplesCalls)

        // A speaker already in the map keeps working — the cap turns away new sessions, it does
        // not mute the channel once it trips.
        rx.onTunneledAudio(audioPayload(session = 0, tenMsFrames = 6))
        assertEquals(MAX_SPEAKERS, codec.decodersCreated)
        assertEquals(MAX_SPEAKERS + 1, codec.packetSamplesCalls)
    }

    @Test
    fun ignoresUnknownTypeByte() {
        val codec = FakeOpusCodec()
        // Not started: admission is synchronous on this thread, so decodersCreated is a
        // deterministic assertion where sampling speakingSessions would be vacuous.
        val rx = VoiceReceiver(codec, unusedOut)

        // The body must be a *valid* Audio message, differing from an accepted packet only in the
        // type byte. With a garbage body the malformed-protobuf path would reject it anyway, and
        // the test would pass just as well with the type check deleted.
        val wrongType = audioPayload(session = 1, tenMsFrames = 6).also { it[0] = 99 }
        rx.onTunneledAudio(wrongType)
        rx.onTunneledAudio(ByteArray(0))

        assertEquals("a non-audio type byte must not reach a playout", 0, codec.decodersCreated)
        assertEquals(0, codec.packetSamplesCalls)
    }

    @Test
    fun ignoresMalformedProtobufBody() {
        val codec = FakeOpusCodec()
        val rx = VoiceReceiver(codec, unusedOut)

        // Valid type byte, garbage body. The property under test is that this does not propagate:
        // MumbleTcpTransport's reader catches Throwable and tears the whole session down, so an
        // escaping parse failure would take chat and channels with it.
        rx.onTunneledAudio(byteArrayOf(0, -1, -1, -1, -1, -1))

        assertEquals(0, codec.decodersCreated)
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
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            // Drained is terminal, so waiting on it cannot lose a race the way waiting on
            // "speaking" can.
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            val stats = rx.playoutStats.value
            assertNotNull("a spurt must publish stats when it ends", stats)
            // Every underrun the platform counted was already there when the spurt began, so the
            // spurt itself is clean. Without the baseline this would report 7.
            assertEquals(0, stats!!.underruns)
            // Six full quanta and nothing else: every packet spans whole 10 ms frames, so no tick
            // can come up short. A concealment condition of "produced anything" would report 6.
            assertEquals("a clean spurt has no gaps", 0, stats.concealedTicks)
        } finally {
            rx.stop()
        }
    }

    /**
     * The counter is loop-owned rather than per-speaker precisely so this cannot go negative when
     * a speaker retires mid-spurt and takes its count out of the sum.
     */
    @Test
    fun aPartialTickCountsAsConcealment() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        val codec = FakeOpusCodec()
        val rx = VoiceReceiver(codec) { out }
        rx.start()
        try {
            // The packet nominally spans 60 ms, but nextDecodeSamples forces its one decode to
            // yield only 240 samples — half a quantum — and nothing else is queued behind it, so
            // the entire spurt is that single partial tick. Every packet otherwise spans whole
            // 10 ms frames, so a full quantum is the only thing a tick can normally produce; a
            // short decode is what breaks that alignment.
            codec.nextDecodeSamples = 240
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertTrue(
                "a zero-padded tick is an audible gap and must be counted",
                (rx.playoutStats.value?.concealedTicks ?: 0) >= 1,
            )
        } finally {
            rx.stop()
        }
    }

    /**
     * [aPartialTickCountsAsConcealment] alone cannot tell "resets to 0 before the spurt" apart
     * from "never touched again": with a single spurt, both look identical. This drives a glitchy
     * spurt followed by a clean one on the same session — a deleted reset would leak spurt 1's
     * count into spurt 2's published stats, contradicting the "current talk spurt, not
     * cumulative" contract on [PlayoutStats.concealedTicks].
     *
     * Spurt 1 is engineered to always publish a nonzero count, so seeing 0 later can only be
     * spurt 2's own publish — never a stale read of spurt 1's value, and the separate write-count
     * check rules out "spurt 2 never started" rather than leaving that to a bare timeout.
     */
    @Test
    fun concealedTicksDoesNotCarryAcrossSpurts() {
        val writes = AtomicInteger()
        val out = object : AudioOut {
            override fun write(pcm: ShortArray, n: Int): Boolean { writes.incrementAndGet(); return true }
            override fun outputStats() = OutputStats(latencyMs = null, underrunsTotal = 0)
            override fun close() = Unit
        }
        val codec = FakeOpusCodec()
        val rx = VoiceReceiver(codec) { out }
        rx.start()
        try {
            // Spurt 1: force the first decode short, same technique as
            // aPartialTickCountsAsConcealment, so this spurt is guaranteed to publish >=1.
            codec.nextDecodeSamples = 240
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val firstDeadline = System.currentTimeMillis() + 5_000
            while ((rx.playoutStats.value?.concealedTicks ?: 0) == 0 && System.currentTimeMillis() < firstDeadline) {
                Thread.sleep(5)
            }
            assertTrue(
                "first spurt must accrue a concealed tick",
                (rx.playoutStats.value?.concealedTicks ?: 0) >= 1,
            )
            val writesAfterFirstSpurt = writes.get()

            // Spurt 2: clean, same session, no short decode.
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val startDeadline = System.currentTimeMillis() + 5_000
            while (writes.get() == writesAfterFirstSpurt && System.currentTimeMillis() < startDeadline) {
                Thread.sleep(5)
            }
            assertTrue("second spurt never produced audio", writes.get() > writesAfterFirstSpurt)

            val secondDeadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value?.concealedTicks != 0 && System.currentTimeMillis() < secondDeadline) {
                Thread.sleep(5)
            }
            assertEquals(
                "a clean spurt must not inherit the previous spurt's concealment count",
                0,
                rx.playoutStats.value?.concealedTicks,
            )
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
     * The counter is stepped from inside outputStats() rather than from the test thread: the
     * baseline is read on the spurt's first producing tick and this spurt is 60 ms long, so a test
     * thread racing to move the counter in between would usually lose. outputStats() is only ever
     * called at a spurt's baseline read and at its publishes, so "first call" is unambiguously the
     * baseline.
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
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
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
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 4, tenMsFrames = 6))
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            // The map is keyed by session so a per-speaker view needs no extra plumbing later.
            assertTrue("session 4 missing from depths", rx.playoutStats.value!!.bufferedSamples.containsKey(4))
        } finally {
            rx.stop()
        }
    }

    /**
     * Every other test drives one ~60 ms spurt, so writesThisSpurt never gets near
     * WRITES_PER_SAMPLE (100) and the periodic mid-spurt sample never fires — proven by the fact
     * that raising WRITES_PER_SAMPLE to Int.MAX_VALUE leaves every other test green.
     *
     * A dedicated feeder thread keeps offering packets for this session in a tight loop, with no
     * pacing of its own, for as long as the test needs — so supply is never the bottleneck and the
     * spurt cannot end on its own mid-poll. That makes speakingSessions a reliable discriminator:
     * the loop clears it to empty before every end-of-spurt publish and leaves it non-empty before
     * every periodic one, and with supply guaranteed the first publish this test observes can only
     * be reached by staying non-empty through write 100 — i.e. the periodic path.
     *
     * The depth assertion is FINDING F4: `statsCarryEachSpeakersBufferedDepth` only checks
     * containsKey(4), so replacing the depth map's body with a constant 0 leaves it green. Depth is
     * ~0 at every end-of-spurt sample (nothing left queued), so this is only checkable mid-spurt —
     * which is exactly the sample this test captures.
     */
    @Test
    fun aLongSpurtPublishesAPeriodicSampleWhileStillRunning() {
        val out = FakeAudioOut(writeSleepMillis = 1)
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        val feederRunning = AtomicBoolean(true)
        val feeder = Thread({
            while (feederRunning.get()) {
                rx.onTunneledAudio(audioPayload(session = 4, tenMsFrames = 6))
            }
        }, "test-feeder").apply { isDaemon = true }
        rx.start()
        feeder.start()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(1)
            }
            val stats = rx.playoutStats.value
            val speaking = rx.speakingSessions.value
            assertNotNull("no publish arrived before the deadline", stats)
            assertTrue(
                "a publish with an empty speaking set is the end-of-spurt sample, not the periodic one",
                speaking.contains(4),
            )
            assertTrue(
                "a mid-spurt sample must show a real jitter-buffer depth, not the ~0 an end-of-spurt one has",
                (stats!!.bufferedSamples[4] ?: 0) > 0,
            )
        } finally {
            feederRunning.set(false)
            feeder.join(5_000)
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
        rx = VoiceReceiver(FakeOpusCodec()) { out }
        val feederRunning = AtomicBoolean(true)
        val feeder = Thread({
            while (feederRunning.get()) {
                rx.onTunneledAudio(audioPayload(session = 4, tenMsFrames = 6))
            }
        }, "test-feeder").apply { isDaemon = true }
        rx.start()
        feeder.start()
        try {
            val deadline = System.currentTimeMillis() + 20_000
            while (writes.get() < 300 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            val wrote = writes.get()
            val sampled = samples.get()
            assertTrue("the spurt never reached 300 writes; got $wrote", wrote >= 300)
            assertTrue("a long spurt must keep sampling, not sample once and stop", sampled >= 2)
            assertTrue(
                "sampled $sampled times in $wrote writes; the periodic sample is not rate-limited",
                sampled < wrote / 10,
            )
        } finally {
            feederRunning.set(false)
            feeder.join(5_000)
            rx.stop()
        }
    }

    /**
     * Instrumentation must never reach the loop's fatal catch (Throwable). Voice is additive: a
     * stats bug silencing a call would be worse than the bug.
     *
     * A latch on the first few writes is not enough: publishStats only runs at a spurt boundary
     * or every 100 writes, and this spurt is six writes, so a latch(3) is satisfied well before
     * the throw is even attempted — it would pass just as well against a `publishStats` with no
     * guard at all. The throw has to actually be given a chance to kill the loop, and then the
     * test has to look for a survivor: a second spurt fed in after the first one has fully
     * drained (and so has already been through the end-of-spurt publish). If the guard is gone,
     * `stopped` is true by the time the second spurt arrives, so onTunneledAudio drops it at its
     * first line and the write count never moves again.
     *
     * The first-write latch below is load-bearing, not decorative: without it, polling
     * speakingSessions for "empty" races the loop's own starting state — both are emptySet(), so
     * a poll that lands before the spurt has even begun observes "drained" immediately and the
     * rest of the test proceeds against a spurt that never actually finished (caught by hand:
     * an earlier version of this test passed against the very mutation it exists to catch,
     * because writesAfterFirstSpurt came back 0).
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
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            // Synchronizes on the spurt having actually started, so speakingSessions is known
            // non-empty before the drain-wait below can mistake "not yet begun" for "finished".
            assertTrue("no audio written", firstWrite.await(5, TimeUnit.SECONDS))

            // Drained is terminal, so waiting on it cannot lose a race the way waiting on
            // "speaking" can. It is also exactly the drain that fires the end-of-spurt publish.
            val firstDeadline = System.currentTimeMillis() + 5_000
            while (rx.speakingSessions.value.isNotEmpty() && System.currentTimeMillis() < firstDeadline) {
                Thread.sleep(5)
            }
            assertEquals("first spurt must fully drain", emptySet<Int>(), rx.speakingSessions.value)
            val writesAfterFirstSpurt = writes.get()

            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val secondDeadline = System.currentTimeMillis() + 5_000
            while (writes.get() == writesAfterFirstSpurt && System.currentTimeMillis() < secondDeadline) {
                Thread.sleep(5)
            }
            assertTrue(
                "a stats failure at the first spurt's end stopped playback for the next spurt",
                writes.get() > writesAfterFirstSpurt,
            )
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
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            val stats = rx.playoutStats.value
            assertNotNull("the spurt must still publish latency and depths", stats)
            assertNull("a failed baseline must not yield a count", stats!!.underruns)
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
     *
     * The throwing call is armed only after the test has confirmed the first spurt fully
     * published, rather than by hardcoding a call index: outputStats() is only ever called at a
     * spurt's baseline read and at its publish, so the first call after a confirmed spurt-1
     * publish is unambiguously spurt 2's baseline, however many calls spurt 1 itself needed.
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
                // Fires exactly once, on the first call after the test arms it — see the class
                // doc above for why that call is guaranteed to be spurt 2's baseline read.
                if (armThrow.get() && thrown.compareAndSet(false, true)) {
                    error("second spurt's baseline read failed")
                }
                return OutputStats(latencyMs = null, underrunsTotal = 100 + call)
            }
            override fun close() = Unit
        }
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val firstDeadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == null && System.currentTimeMillis() < firstDeadline) {
                Thread.sleep(5)
            }
            val firstSpurt = rx.playoutStats.value
            assertNotNull("first spurt must publish", firstSpurt)
            assertNotNull("first spurt's baseline read must succeed", firstSpurt!!.underruns)
            // Drained is terminal, so waiting on it (rather than on the publish alone) cannot
            // race a spurt that has published but not yet fully cleared speakingSessions.
            val idleDeadline = System.currentTimeMillis() + 5_000
            while (rx.speakingSessions.value.isNotEmpty() && System.currentTimeMillis() < idleDeadline) {
                Thread.sleep(5)
            }
            assertEquals("first spurt must fully drain before the second begins", emptySet<Int>(), rx.speakingSessions.value)

            armThrow.set(true)
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            val secondDeadline = System.currentTimeMillis() + 5_000
            while (rx.playoutStats.value == firstSpurt && System.currentTimeMillis() < secondDeadline) {
                Thread.sleep(5)
            }
            val secondSpurt = rx.playoutStats.value
            assertNotNull("second spurt must still publish latency/depth despite the baseline failure", secondSpurt)
            assertTrue("the armed baseline call was never reached", thrown.get())
            assertNull("a failed baseline must not fall back to the previous spurt's count", secondSpurt!!.underruns)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun statsAreClearedWhenTheLoopExits() {
        val out = FakeAudioOut(writeSleepMillis = 0)
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
        val deadline = System.currentTimeMillis() + 5_000
        while (rx.playoutStats.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertNotNull(rx.playoutStats.value)
        rx.stop()
        // The loop owns the flow while alive and has to hand it back empty, or a stats page shows
        // the previous call's numbers after a disconnect.
        assertNull("stats outlived the playback loop", rx.playoutStats.value)
    }
}
