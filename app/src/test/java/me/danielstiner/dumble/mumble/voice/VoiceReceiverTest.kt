package me.danielstiner.dumble.mumble.voice

import com.google.protobuf.ByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.time.elapse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReceiverTest {

    private val onePoll = VoiceReceiver.POLL_MILLIS.milliseconds

    private fun audioPayload(session: Int, terminator: Boolean = false): ByteArray {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(session)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .setIsTerminator(terminator)
            .build()
        return byteArrayOf(0) + audio.toByteArray()
    }

    /**
     * The receiver's poll and its stats clock on the test's scheduler: nothing moves between two
     * statements unless the test drives it, and [onePoll] of virtual time is one poll. Never
     * `advanceUntilIdle()`: the poll is an endless delay loop. For the same reason the receiver is
     * stopped before the test returns, or runTest's final drain would run the poll forever.
     */
    private fun receiving(
        newEngine: () -> VoiceReceiver.PlayoutEngine?,
        body: suspend TestScope.(VoiceReceiver) -> Unit,
    ) = runTest {
        val rx = VoiceReceiver(newEngine, clock = testScheduler.timeSource, context = StandardTestDispatcher(testScheduler))
        try {
            body(rx)
        } finally {
            rx.stop()
        }
    }

    /** A started receiver whose first poll has run, so each [onePoll] from here is exactly one more. */
    private fun polling(fake: FakePlayoutEngine, body: suspend TestScope.(VoiceReceiver) -> Unit) =
        receiving({ fake }) { rx ->
            rx.start()
            runCurrent()
            body(rx)
        }

    /** A packet in an array of its own length, which is what the tunnel delivers. */
    private fun VoiceReceiver.offer(payload: ByteArray) = onVoicePacket(payload, payload.size)

    /**
     * The UDP transport hands over one reused buffer, so `len` bounds the packet, not the
     * array. The bytes past `len` here are a second valid `sender_session` field: a parse
     * bounded by the array would take it as part of the packet and read session 999.
     */
    @Test
    fun lenBoundsThePacketNotTheArray() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            val packet = audioPayload(session = 5)
            val past = MumbleUdpProtos.Audio.newBuilder().setSenderSession(999).build().toByteArray()
            rx.onVoicePacket(packet + past, packet.size)

            assertEquals(1, fake.offered.size)
            assertEquals(5, fake.offered[0].session)
        }
    }

    @Test
    fun theStreamStartsWithTheReceiverNotWithTheFirstPacket() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            // Before any packet: a start costs up to 100 ms on some devices, and packets landing
            // during it pile up ahead of the gate as standing delay.
            assertTrue("the stream must start with the receiver", fake.started)
            assertTrue("nothing was offered yet", fake.offered.isEmpty())
            fake.liveSessions = emptySet()
            elapse(onePoll * 4)
            assertEquals("four more polls ran", 5, fake.startAttempts.get())
            assertEquals("silence must not pause the stream", listOf("start"), fake.calls)
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
        polling(fake) { rx ->
            elapse(onePoll * 2)
            assertEquals("start must be attempted every poll", 3, fake.startAttempts.get())
            assertTrue("nothing started while it could not open", fake.calls.isEmpty())
            fake.startResult = true
            elapse(onePoll)
            assertTrue("the stream must come up on the next poll once it can", fake.started)
        }
    }

    @Test
    fun speakingFollowsTheAudibleSet() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            // Two slots held, one producing: live is what starts the stream, audible is what the
            // UI shows, and they are not the same set.
            fake.liveSessions = setOf(3, 4)
            fake.audibleSessions = setOf(3)
            elapse(onePoll)
            assertEquals("speaking must be the audible set", setOf(3), rx.speakingSessions.value)
            fake.audibleSessions = emptySet()
            elapse(onePoll)
            assertTrue("speaking must clear once nobody produces", rx.speakingSessions.value.isEmpty())
        }
    }

    @Test
    fun aHoldPausesTheStreamAndAResumeStartsIt() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            assertTrue("start", fake.started)
            fake.liveSessions = setOf(3)
            fake.audibleSessions = setOf(3)
            elapse(onePoll)
            assertEquals("speaking", setOf(3), rx.speakingSessions.value)
            rx.setHeld(true)
            // One poll, not some idle window: the platform has the device.
            elapse(onePoll)
            assertTrue("a hold must pause the stream on the next poll", fake.calls.contains("pause"))
            // The pause released the engine's speakers: whoever was mid-word is not left lit
            // for the length of the hold.
            assertTrue("a hold must end the spurt on the same poll", rx.speakingSessions.value.isEmpty())
            val offeredBeforeHold = fake.offered.size
            rx.offer(audioPayload(session = 3))
            elapse(onePoll * 4)
            assertEquals("held: nothing may start", 1, fake.calls.count { it == "start" })
            assertEquals("held: a packet is dropped, not queued", offeredBeforeHold, fake.offered.size)
            rx.setHeld(false)
            elapse(onePoll)
            assertEquals("a resume must start the stream on the next poll", 2, fake.calls.count { it == "start" })
        }
    }

    @Test
    fun stopJoinsThePollDestroysOnceAndDropsALateOffer() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            assertTrue("start", fake.started)
            rx.offer(audioPayload(session = 3))
            rx.stop()
            val offeredBeforeStop = fake.offered.size
            assertEquals("destroy must be the last call", "destroy", fake.calls.last())
            assertEquals("engine must be destroyed exactly once", 1, fake.destroyCalls)
            rx.offer(audioPayload(session = 3))
            assertEquals("a packet after stop() must not reach the engine", offeredBeforeStop, fake.offered.size)
            rx.stop()
            assertEquals("a second stop() must not destroy again", 1, fake.destroyCalls)
            assertEquals(emptySet<Int>(), rx.speakingSessions.value)
            assertNull(rx.playoutStats.value)
        }
    }

    /**
     * The ordering MumbleConnection can produce: an attempt killed by an instant auth reject is
     * retired — which calls stop() — before connect()'s coroutine gets as far as start(). The
     * refused start() must build nothing, since nothing would ever destroy it.
     */
    @Test
    fun startAfterStopBuildsNoEngine() {
        val engines = AtomicInteger()
        receiving({ engines.incrementAndGet(); FakePlayoutEngine() }) { rx ->
            rx.stop()
            rx.start()
            assertEquals("start() after stop() built a playout engine", 0, engines.get())
        }
    }

    @Test
    fun startIsSingleShot() {
        val engines = AtomicInteger()
        receiving({ engines.incrementAndGet(); FakePlayoutEngine() }) { rx ->
            rx.start()
            rx.start()
            assertEquals(1, engines.get())
            rx.stop()
            rx.start()
            assertEquals("start() after stop() must not build a second engine", 1, engines.get())
        }
    }

    @Test
    fun routesEachPacketToTheEngine() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            rx.offer(audioPayload(session = 1))
            rx.offer(audioPayload(session = 2, terminator = true))
            assertEquals("every packet must reach the engine", 2, fake.offered.size)
            assertEquals(1, fake.offered[0].session)
            assertFalse("only the second packet set the terminator flag", fake.offered[0].terminator)
            assertEquals(2, fake.offered[1].session)
            assertTrue("the terminator flag must reach the engine", fake.offered[1].terminator)
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
        polling(fake) { rx ->
            repeat(8) { rx.offer(audioPayload(session = 3)) }
            assertEquals("a malformed payload must not stop the reader", 8, fake.offered.size)
            fake.offerResult = NativePlayout.OFFER_ACCEPTED
            rx.offer(audioPayload(session = 3))
            assertEquals("the session must still work after garbage", 9, fake.offered.size)
        }
    }

    @Test
    fun keepsReadingWhileTheSpeakerCapRefuses() {
        val fake = FakePlayoutEngine()
        fake.offerResult = NativePlayout.OFFER_SPEAKER_CAP
        polling(fake) { rx ->
            repeat(8) { rx.offer(audioPayload(session = it)) }
            assertEquals("every packet must still reach the engine even while capped", 8, fake.offered.size)
            fake.offerResult = NativePlayout.OFFER_ACCEPTED
            rx.offer(audioPayload(session = 0))
            assertEquals("the reader must keep working after the cap trips", 9, fake.offered.size)
        }
    }

    @Test
    fun ignoresUnknownTypeByte() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            // The body must be a *valid* Audio message, differing from an accepted packet only in
            // the type byte; with a garbage body the malformed-protobuf path would reject it anyway.
            val wrongType = audioPayload(session = 1).also { it[0] = 99 }
            rx.offer(wrongType)
            rx.offer(ByteArray(0))
            assertEquals("a non-audio type byte must not reach the engine", 0, fake.offered.size)
        }
    }

    @Test
    fun ignoresMalformedProtobufBody() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            // Valid type byte, garbage body. MumbleTcpTransport's reader catches Throwable and
            // tears the whole session down, so an escaping parse failure would take chat with it.
            rx.offer(byteArrayOf(0, -1, -1, -1, -1, -1))
            assertEquals(0, fake.offered.size)
        }
    }

    /** Opens a spurt on session 1: the next poll finds it audible. */
    private fun TestScope.openSpurt(fake: FakePlayoutEngine, rx: VoiceReceiver) {
        fake.liveSessions = setOf(1)
        fake.audibleSessions = setOf(1)
        elapse(onePoll)
        assertEquals("the spurt opens on the next poll", setOf(1), rx.speakingSessions.value)
    }

    /** Closes the open spurt: the next poll publishes its closing sample, then clears the speaking set. */
    private fun TestScope.closeSpurt(fake: FakePlayoutEngine, rx: VoiceReceiver) {
        val before = rx.playoutStats.value
        fake.audibleSessions = emptySet()
        elapse(onePoll)
        assertTrue("a spurt must publish stats when it ends", rx.playoutStats.value.let { it != null && it !== before })
        assertTrue("the spurt's end must clear the speaking set", rx.speakingSessions.value.isEmpty())
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
        fake.counter(NativePlayout.COUNTER_UNDERRUNS, 7)
        fake.counter(NativePlayout.COUNTER_LATENCY_MICROS, 42_000)
        polling(fake) { rx ->
            openSpurt(fake, rx)
            closeSpurt(fake, rx)
            val stats = rx.playoutStats.value!!
            assertEquals(0, stats.underruns)
            assertEquals(42.0, stats.latencyMs!!, 1e-9)
            assertEquals("a clean spurt has no gaps", 0, stats.concealedGaps)
            assertEquals("a clean spurt drops nothing", 0, stats.droppedPackets)
        }
    }

    @Test
    fun aLatencyOfMinusOneFromTheSeamReadsAsNull() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            openSpurt(fake, rx)
            closeSpurt(fake, rx)
            assertNull("-1 from the seam is no reading, not a reading of -1 ms", rx.playoutStats.value!!.latencyMs)
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
        polling(fake) { rx ->
            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_CONCEALED_GAPS, 1)
            closeSpurt(fake, rx)
            val first = rx.playoutStats.value!!
            assertEquals(1, first.concealedGaps)

            openSpurt(fake, rx)
            closeSpurt(fake, rx)
            assertEquals("a clean spurt must not inherit the previous spurt's count", 0, rx.playoutStats.value!!.concealedGaps)
        }
    }

    @Test
    fun droppedPacketsAreCountedFromTheSpurtBaseline() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_DROPPED_PACKETS, 5)
            closeSpurt(fake, rx)
            val first = rx.playoutStats.value!!
            assertEquals(5, first.droppedPackets)

            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_DROPPED_PACKETS, 8)
            closeSpurt(fake, rx)
            assertEquals("a spurt must report its own drops, not the session's running total", 3, rx.playoutStats.value!!.droppedPackets)
        }
    }

    /**
     * Three underruns precede the spurt and two more land inside it: a count that forgot to
     * subtract the baseline reports 5, and one that published the baseline reports 3.
     */
    @Test
    fun underrunsAreCountedFromTheSpurtBaseline() {
        val fake = FakePlayoutEngine()
        fake.counter(NativePlayout.COUNTER_UNDERRUNS, 3)
        polling(fake) { rx ->
            openSpurt(fake, rx)
            fake.counter(NativePlayout.COUNTER_UNDERRUNS, 5)
            closeSpurt(fake, rx)
            assertEquals(2, rx.playoutStats.value?.underruns)
        }
    }

    @Test
    fun statsCarryEachSpeakersBufferedDepthAndTarget() {
        val fake = FakePlayoutEngine()
        fake.depthsBySession = mapOf(1 to 960)
        fake.targetsBySession = mapOf(1 to 1440)
        polling(fake) { rx ->
            openSpurt(fake, rx)
            closeSpurt(fake, rx)
            // Keyed by session so a per-speaker view needs no extra plumbing.
            assertEquals(960, rx.playoutStats.value!!.bufferedSamples[1])
            assertEquals(1440, rx.playoutStats.value!!.targetSamples[1])
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
        polling(fake) { rx ->
            openSpurt(fake, rx)
            elapse(VoiceReceiver.STATS_PERIOD)
            assertNotNull("no periodic sample inside a long spurt", rx.playoutStats.value)
            assertEquals("the speaker must still be audible at the periodic sample", setOf(1), rx.speakingSessions.value)
            assertEquals(480, rx.playoutStats.value!!.bufferedSamples[1])
        }
    }

    /**
     * One sample per period inside a spurt is the contract; a poll that published on every read
     * would sample twenty times a second. The poll and the stats clock share the scheduler, so
     * the period is pinned from both sides: nothing on the poll before it, the sample on it.
     */
    @Test
    fun thePeriodicSampleIsRateLimited() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            openSpurt(fake, rx)
            elapse(VoiceReceiver.STATS_PERIOD - onePoll)
            assertNull("no sample before the period", rx.playoutStats.value)

            elapse(onePoll)
            val first = rx.playoutStats.value
            assertNotNull("the first sample, a period in", first)
            // A distinct reading for the next sample, or the flow conflates it with the first.
            fake.counter(NativePlayout.COUNTER_FILL_MICROS_MAX, 7)
            elapse(VoiceReceiver.STATS_PERIOD - onePoll)
            assertEquals("one sample per period, however many polls", first, rx.playoutStats.value)

            elapse(onePoll)
            assertNotEquals("the second sample, another period in", first, rx.playoutStats.value)
        }
    }

    @Test
    fun statsAreClearedWhenTheReceiverStops() {
        val fake = FakePlayoutEngine()
        polling(fake) { rx ->
            openSpurt(fake, rx)
            closeSpurt(fake, rx)
            rx.stop()
            // The poll owns the flow while alive and has to hand it back empty, or a stats page shows
            // the previous call's numbers after a disconnect.
            assertNull("stats outlived the receiver", rx.playoutStats.value)
        }
    }

    /**
     * Instrumentation must never cost playback. A refused stats read — this side's bug — is
     * logged and polled past, so the stream still runs and stats resume with the reads that do.
     */
    @Test
    fun refusedStatsBuffersDoNotStopThePoll() {
        val fake = FakePlayoutEngine()
        fake.refuseBuffers = true
        polling(fake) { rx ->
            assertTrue("the stream must start regardless", fake.started)
            fake.liveSessions = setOf(3)
            fake.audibleSessions = setOf(3)
            elapse(onePoll * 3)
            assertEquals("the poll ran on through the refusals", 4, fake.startAttempts.get())
            assertTrue("a refused read must not publish off stale scratch", rx.speakingSessions.value.isEmpty())
            fake.refuseBuffers = false
            elapse(onePoll)
            assertEquals("the poll must recover on the first read that succeeds", setOf(3), rx.speakingSessions.value)
        }
    }

    /**
     * newEngine() returning null — libopus unreachable — must degrade receive to silence for the
     * session: latched, no poll, and a later start() refused the way one after stop() is.
     */
    @Test
    fun anUnavailableEngineDisablesReceive() {
        val builds = AtomicInteger()
        receiving({ builds.incrementAndGet(); null }) { rx ->
            rx.start()
            rx.offer(audioPayload(session = 1))
            assertEquals(emptySet<Int>(), rx.speakingSessions.value)
            rx.start()
            assertEquals("a refused start() must not be retried", 1, builds.get())
        }
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
        receiving({ builds.incrementAndGet(); FakePlayoutEngine() }) { rx ->
            rx.stop()
            assertEquals("a receiver that never started must never build an engine", 0, builds.get())
            rx.start()
            assertEquals(0, builds.get())
        }
    }
}
