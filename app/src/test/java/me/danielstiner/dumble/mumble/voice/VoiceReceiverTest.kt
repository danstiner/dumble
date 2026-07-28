package me.danielstiner.dumble.mumble.voice

import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.voice.FakeOpusCodec.Companion.packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoiceReceiverTest {

    /** Counts writes and releases a latch, so tests wait on real progress rather than sleeping. */
    private class CountingOut(private val latch: CountDownLatch) : AudioOut {
        val writes = AtomicInteger()
        @Volatile var closed = false
        override fun write(pcm: ShortArray, n: Int): Boolean { writes.incrementAndGet(); latch.countDown(); return true }
        override fun close() { closed = true }
    }

    private fun audioPayload(session: Int, tenMsFrames: Int): ByteArray {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(session)
            .setOpusData(ByteString.copyFrom(packet(tenMsFrames)))
            .build()
        return byteArrayOf(0) + audio.toByteArray()
    }

    @Test
    fun routesToPerSenderDecoders() {
        val codec = FakeOpusCodec()
        val latch = CountDownLatch(4)
        val out = CountingOut(latch)
        val rx = VoiceReceiver(codec) { out }
        rx.start()
        try {
            // 60 ms each meets the prebuffer immediately.
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            rx.onTunneledAudio(audioPayload(session = 2, tenMsFrames = 6))
            // A second packet for a session already in the map. Without it the assertion below
            // holds for a per-packet decoder just as well as a per-sender one, so it would not
            // test what it names.
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6))
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
            assertEquals("one decoder per speaker, not per packet", 2, codec.decodersCreated)
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
            override fun close() = closed.countDown()
        }
        val rx = VoiceReceiver(FakeOpusCodec()) { out }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 3, tenMsFrames = 6))
            assertTrue("loop did not exit after a failed write", closed.await(5, TimeUnit.SECONDS))
            assertEquals("a failed write must stop the loop, not be retried", 1, writes.get())
        } finally {
            rx.stop()
        }
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
        val rx = VoiceReceiver(codec) { CountingOut(CountDownLatch(1)) }
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
        val rx = VoiceReceiver(codec) { CountingOut(CountDownLatch(1)) }

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
        val rx = VoiceReceiver(codec) { CountingOut(CountDownLatch(1)) }

        // Valid type byte, garbage body. The property under test is that this does not propagate:
        // MumbleTcpTransport's reader catches Throwable and tears the whole session down, so an
        // escaping parse failure would take chat and channels with it.
        rx.onTunneledAudio(byteArrayOf(0, -1, -1, -1, -1, -1))

        assertEquals(0, codec.decodersCreated)
    }
}
