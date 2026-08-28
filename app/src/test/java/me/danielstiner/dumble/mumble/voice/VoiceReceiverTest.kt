package me.danielstiner.dumble.mumble.voice

import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VoiceReceiverTest {

    private fun audioPayload(session: Int, terminator: Boolean = false): ByteArray {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(session)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .setIsTerminator(terminator)
            .build()
        return byteArrayOf(0) + audio.toByteArray()
    }

    /** Polls [cond] until it is true or [timeoutMillis] elapses, then asserts it — the poll this
     *  class drives means most assertions are about eventual state, not an instant one. */
    private fun awaitTrue(message: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(message, cond())
    }

    private fun receiver(fake: FakePlayoutEngine) = VoiceReceiver({ fake })

    @Test
    fun theStreamStartsWithTheReceiverNotWithTheFirstPacket() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            // Before any packet: a start costs up to 100 ms on some devices, and packets landing
            // during it pile up ahead of the gate as standing delay.
            awaitTrue("the stream must start with the receiver") { fake.started }
            assertTrue("nothing was offered yet", fake.offered.isEmpty())
            fake.liveSessions = emptySet()
            Thread.sleep(200)
            assertEquals("silence must not pause the stream", listOf("start"), fake.calls)
        } finally {
            rx.stop()
        }
    }

    /**
     * The adapter's own reopen backs off and gives up after five attempts; a Bluetooth codec
     * renegotiation is enough. The poll's every-interval start() is what brings the stream back
     * afterwards, and the one thing that must never happen is the poll deciding it is started
     * and never asking again.
     */
    @Test
    fun aStreamThatCannotOpenIsRetriedUntilItCan() {
        val fake = FakePlayoutEngine()
        fake.startResult = false
        val rx = receiver(fake)
        rx.start()
        try {
            awaitTrue("start must be attempted repeatedly") { fake.startAttempts.get() >= 3 }
            assertTrue("nothing started while it could not open", fake.calls.isEmpty())
            fake.startResult = true
            awaitTrue("the stream must come up once it can") { fake.started }
        } finally {
            rx.stop()
        }
    }

    @Test
    fun speakingFollowsTheAudibleSet() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            // Two slots held, one producing: live is what starts the stream, audible is what the
            // UI shows, and they are not the same set.
            fake.liveSessions = setOf(3, 4)
            fake.audibleSessions = setOf(3)
            awaitTrue("speaking must be the audible set") { rx.speakingSessions.value == setOf(3) }
            fake.audibleSessions = emptySet()
            awaitTrue("speaking must clear once nobody produces") { rx.speakingSessions.value.isEmpty() }
        } finally {
            rx.stop()
        }
    }

    @Test
    fun aHoldPausesTheStreamAndAResumeStartsIt() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            awaitTrue("start") { fake.started }
            rx.setHeld(true)
            // Within a poll, not after some idle window: the platform has the device.
            awaitTrue("a hold must pause the stream", 500) { fake.calls.contains("pause") }
            fake.liveSessions = setOf(3)
            rx.onTunneledAudio(audioPayload(session = 3))
            Thread.sleep(200)
            assertEquals("held: nothing may start", 1, fake.calls.count { it == "start" })
            rx.setHeld(false)
            awaitTrue("a resume must start the stream again", 500) {
                fake.calls.count { it == "start" } == 2
            }
        } finally {
            rx.stop()
        }
    }

    @Test
    fun stopJoinsThePollDestroysOnceAndDropsALateOffer() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        awaitTrue("start") { fake.started }
        rx.onTunneledAudio(audioPayload(session = 3))
        rx.stop()
        val offeredBeforeStop = fake.offered.size
        assertEquals("destroy must be the last call", "destroy", fake.calls.last())
        assertEquals("engine must be destroyed exactly once", 1, fake.destroyCalls)
        rx.onTunneledAudio(audioPayload(session = 3))
        assertEquals("a packet after stop() must not reach the engine", offeredBeforeStop, fake.offered.size)
        rx.stop()
        assertEquals("a second stop() must not destroy again", 1, fake.destroyCalls)
        assertEquals(emptySet<Int>(), rx.speakingSessions.value)
        assertNull(rx.playoutStats.value)
    }

    /**
     * The ordering MumbleConnection can produce: an attempt killed by an instant auth reject is
     * retired — which calls stop() — before connect()'s coroutine gets as far as start(). The
     * refused start() must build nothing, since nothing would ever destroy it.
     */
    @Test
    fun startAfterStopBuildsNoEngine() {
        val engines = AtomicInteger()
        val rx = VoiceReceiver({ engines.incrementAndGet(); FakePlayoutEngine() })
        rx.stop()
        rx.start()
        assertEquals("start() after stop() built a playout engine", 0, engines.get())
    }

    @Test
    fun startIsSingleShot() {
        val engines = AtomicInteger()
        val rx = VoiceReceiver({ engines.incrementAndGet(); FakePlayoutEngine() })
        rx.start()
        rx.start()
        assertEquals(1, engines.get())
        rx.stop()
        rx.start()
        assertEquals("start() after stop() must not build a second engine", 1, engines.get())
    }

    @Test
    fun routesEachPacketToTheEngine() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 1))
            rx.onTunneledAudio(audioPayload(session = 2, terminator = true))
            assertEquals("every packet must reach the engine", 2, fake.offered.size)
            assertEquals(1, fake.offered[0].session)
            assertFalse("only the second packet set the terminator flag", fake.offered[0].terminator)
            assertEquals(2, fake.offered[1].session)
            assertTrue("the terminator flag must reach the engine", fake.offered[1].terminator)
        } finally {
            rx.stop()
        }
    }

    /**
     * A peer sending nothing but unparseable payloads must not take the reader down with it, and
     * must not poison the session for the good packets that follow.
     */
    @Test
    fun keepsReadingThroughMalformedPayloads() {
        val fake = FakePlayoutEngine()
        fake.offerResult = NativePlayout.OFFER_MALFORMED_PACKET
        val rx = receiver(fake)
        rx.start()
        try {
            repeat(8) { rx.onTunneledAudio(audioPayload(session = 3)) }
            assertEquals("a malformed payload must not stop the reader", 8, fake.offered.size)
            fake.offerResult = NativePlayout.OFFER_ACCEPTED
            rx.onTunneledAudio(audioPayload(session = 3))
            assertEquals("the session must still work after garbage", 9, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun keepsReadingWhileTheSpeakerCapRefuses() {
        val fake = FakePlayoutEngine()
        fake.offerResult = NativePlayout.OFFER_SPEAKER_CAP
        val rx = receiver(fake)
        rx.start()
        try {
            repeat(8) { rx.onTunneledAudio(audioPayload(session = it)) }
            assertEquals("every packet must still reach the engine even while capped", 8, fake.offered.size)
            fake.offerResult = NativePlayout.OFFER_ACCEPTED
            rx.onTunneledAudio(audioPayload(session = 0))
            assertEquals("the reader must keep working after the cap trips", 9, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun ignoresUnknownTypeByte() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            // The body must be a *valid* Audio message, differing from an accepted packet only in
            // the type byte; with a garbage body the malformed-protobuf path would reject it anyway.
            val wrongType = audioPayload(session = 1).also { it[0] = 99 }
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
        val rx = receiver(fake)
        rx.start()
        try {
            // Valid type byte, garbage body. MumbleTcpTransport's reader catches Throwable and
            // tears the whole session down, so an escaping parse failure would take chat with it.
            rx.onTunneledAudio(byteArrayOf(0, -1, -1, -1, -1, -1))
            assertEquals(0, fake.offered.size)
        } finally {
            rx.stop()
        }
    }

    /** Opens a spurt on session 1 and returns once the receiver has seen it. */
    private fun openSpurt(fake: FakePlayoutEngine, rx: VoiceReceiver) {
        fake.liveSessions = setOf(1)
        fake.audibleSessions = setOf(1)
        awaitTrue("the spurt never opened") { rx.speakingSessions.value == setOf(1) }
    }

    /** Closes the open spurt and returns once its closing sample is published. */
    private fun closeSpurt(fake: FakePlayoutEngine, rx: VoiceReceiver, before: PlayoutStats?) {
        fake.audibleSessions = emptySet()
        awaitTrue("a spurt must publish stats when it ends") {
            rx.playoutStats.value != null && rx.playoutStats.value !== before
        }
    }

    /**
     * A spurt shorter than the periodic interval still has to publish, because a stall segments
     * glitchy speech into exactly such spurts — without the end-of-spurt sample the counters would
     * go blind precisely when the call is worst.
     */
    @Test
    fun aSpurtPublishesStatsWhenItEnds() {
        val fake = FakePlayoutEngine()
        // Underruns the stream counted before the spurt are its baseline, not its glitches.
        fake.counter(NativePlayout.COUNTER_X_RUNS, 7)
        fake.counter(NativePlayout.COUNTER_LATENCY_MICROS, 42_000)
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            closeSpurt(fake, rx, null)
            val stats = rx.playoutStats.value!!
            assertEquals(0, stats.underruns)
            assertEquals(42.0, stats.latencyMs!!, 1e-9)
            assertEquals("a clean spurt has no gaps", 0, stats.concealedGaps)
            assertEquals("a clean spurt drops nothing", 0, stats.droppedPackets)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun aLatencyOfMinusOneFromTheSeamReadsAsNull() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            closeSpurt(fake, rx, null)
            assertNull("-1 from the seam is no reading, not a reading of -1 ms", rx.playoutStats.value!!.latencyMs)
        } finally {
            rx.stop()
        }
    }

    /**
     * The engine's counters are monotonic and the poll subtracts a baseline re-armed at each
     * spurt's close. With a single spurt "re-armed" and "never touched again" look the same, so
     * this drives a glitchy spurt followed by a clean one: a baseline that failed to re-arm would
     * leak spurt 1's count into spurt 2's published stats.
     */
    @Test
    fun concealedGapsDoesNotCarryAcrossSpurts() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_CONCEALED_GAPS, 1)
            closeSpurt(fake, rx, null)
            val first = rx.playoutStats.value!!
            assertEquals(1, first.concealedGaps)

            openSpurt(fake, rx)
            closeSpurt(fake, rx, first)
            assertEquals("a clean spurt must not inherit the previous spurt's count", 0, rx.playoutStats.value!!.concealedGaps)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun droppedPacketsAreCountedFromTheSpurtBaseline() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_DROPPED_PACKETS, 5)
            closeSpurt(fake, rx, null)
            val first = rx.playoutStats.value!!
            assertEquals(5, first.droppedPackets)

            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_DROPPED_PACKETS, 8)
            closeSpurt(fake, rx, first)
            assertEquals("a spurt must report its own drops, not the session's running total", 3, rx.playoutStats.value!!.droppedPackets)
        } finally {
            rx.stop()
        }
    }

    /**
     * Three underruns precede the spurt and two more land inside it: a count that forgot to
     * subtract the baseline reports 5, and one that published the baseline reports 3.
     */
    @Test
    fun underrunsAreCountedFromTheSpurtBaseline() {
        val fake = FakePlayoutEngine()
        fake.counter(NativePlayout.COUNTER_X_RUNS, 3)
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_X_RUNS, 5)
            closeSpurt(fake, rx, null)
            assertEquals(2, rx.playoutStats.value?.underruns)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun statsCarryEachSpeakersBufferedDepthAndTarget() {
        val fake = FakePlayoutEngine()
        fake.depthsBySession = mapOf(1 to 960)
        fake.targetsBySession = mapOf(1 to 1440)
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            closeSpurt(fake, rx, null)
            // Keyed by session so a per-speaker view needs no extra plumbing.
            assertEquals(960, rx.playoutStats.value!!.bufferedSamples[1])
            assertEquals(1440, rx.playoutStats.value!!.targetSamples[1])
        } finally {
            rx.stop()
        }
    }

    /**
     * Every other test drives a short spurt, so the periodic mid-spurt sample never fires. This
     * one holds a spurt open past the period and expects a sample while the speaker is still
     * audible — the closing sample is the only other way a publish can happen, and it cannot
     * happen while the set is non-empty.
     */
    @Test
    fun aLongSpurtPublishesAPeriodicSampleWhileStillRunning() {
        val fake = FakePlayoutEngine()
        fake.depthsBySession = mapOf(1 to 480)
        val rx = receiver(fake)
        rx.start()
        try {
            openSpurt(fake, rx)
            awaitTrue("no periodic sample inside a long spurt", 3_000) { rx.playoutStats.value != null }
            assertEquals("the speaker must still be audible at the periodic sample", setOf(1), rx.speakingSessions.value)
            assertEquals(480, rx.playoutStats.value!!.bufferedSamples[1])
        } finally {
            rx.stop()
        }
    }

    /**
     * One sample per second inside a spurt is the contract; a poll that published on every read
     * would sample twenty times a second. Counted over three seconds of a held-open spurt.
     */
    @Test
    fun thePeriodicSampleIsRateLimited() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        val samples = AtomicInteger()
        rx.start()
        try {
            openSpurt(fake, rx)
            var last: PlayoutStats? = null
            val deadline = System.currentTimeMillis() + 3_200
            while (System.currentTimeMillis() < deadline) {
                // Every reading distinct, or the flow conflates equal samples and hides them.
                fake.counter(NativePlayout.COUNTER_FILL_MICROS_MAX, System.nanoTime())
                val s = rx.playoutStats.value
                if (s != null && s != last) { samples.incrementAndGet(); last = s }
                Thread.sleep(5)
            }
            assertTrue("sampled ${samples.get()} times in 3.2 s", samples.get() in 2..4)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun statsAreClearedWhenTheReceiverStops() {
        val fake = FakePlayoutEngine()
        val rx = receiver(fake)
        rx.start()
        openSpurt(fake, rx)
        closeSpurt(fake, rx, null)
        rx.stop()
        // The poll owns the flow while alive and has to hand it back empty, or a stats page shows
        // the previous call's numbers after a disconnect.
        assertNull("stats outlived the receiver", rx.playoutStats.value)
    }

    /**
     * Instrumentation must never cost playback. A refused stats read — this side's bug — is
     * logged and polled past, so the stream still runs and stats resume with the reads that do.
     */
    @Test
    fun refusedStatsBuffersDoNotStopThePoll() {
        val fake = FakePlayoutEngine()
        fake.refuseBuffers = true
        val rx = receiver(fake)
        rx.start()
        try {
            awaitTrue("the stream must start regardless") { fake.started }
            fake.liveSessions = setOf(3)
            fake.audibleSessions = setOf(3)
            Thread.sleep(150)
            assertTrue("a refused read must not publish off stale scratch", rx.speakingSessions.value.isEmpty())
            fake.refuseBuffers = false
            awaitTrue("the poll must recover once the read succeeds") { rx.speakingSessions.value == setOf(3) }
        } finally {
            rx.stop()
        }
    }

    /**
     * newEngine() returning null — libopus unreachable — must degrade receive to silence for the
     * session: latched, no poll, and a later start() refused the way one after stop() is.
     */
    @Test
    fun anUnavailableEngineDisablesReceive() {
        val builds = AtomicInteger()
        val rx = VoiceReceiver({ builds.incrementAndGet(); null })
        rx.start()
        rx.onTunneledAudio(audioPayload(session = 1))
        assertEquals(emptySet<Int>(), rx.speakingSessions.value)
        rx.start()
        assertEquals("a refused start() must not be retried", 1, builds.get())
        rx.stop()
    }

    /**
     * Mirrors MumbleConnection.teardown(): an attempt superseded before publish or failing in
     * connect() calls stop() on a receiver whose start() was never reached. newEngine must never
     * run on that path — the bug this guards against built the engine eagerly at construction,
     * leaking one per such attempt.
     */
    @Test
    fun stopWithoutEverStartingBuildsNoEngine() {
        val builds = AtomicInteger()
        val rx = VoiceReceiver({ builds.incrementAndGet(); FakePlayoutEngine() })
        rx.stop()
        assertEquals("a receiver that never started must never build an engine", 0, builds.get())
        rx.start()
        assertEquals(0, builds.get())
    }
}
