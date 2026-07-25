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

class VoiceReceiverTest {

    /** Counts writes and releases a latch, so tests wait on real progress rather than sleeping. */
    private class CountingOut(private val latch: CountDownLatch) : AudioOut {
        val writes = AtomicInteger()
        @Volatile var closed = false
        override fun write(pcm: ShortArray, n: Int) { writes.incrementAndGet(); latch.countDown() }
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
            rx.onTunneledAudio(audioPayload(session = 1, tenMsFrames = 6), 0L)
            rx.onTunneledAudio(audioPayload(session = 2, tenMsFrames = 6), 0L)
            assertTrue("no audio written", latch.await(5, TimeUnit.SECONDS))
            assertEquals("one decoder per speaker", 2, codec.decodersCreated)
        } finally {
            rx.stop()
        }
        assertTrue("output not closed by stop()", out.closed)
    }

    @Test
    fun reportsSpeakingSessions() {
        val latch = CountDownLatch(2)
        val rx = VoiceReceiver(FakeOpusCodec()) { CountingOut(latch) }
        rx.start()
        try {
            rx.onTunneledAudio(audioPayload(session = 9, tenMsFrames = 6), 0L)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 2_000
            while (rx.speakingSessions.value != setOf(9) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(setOf(9), rx.speakingSessions.value)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun ignoresUnknownTypeByte() {
        val rx = VoiceReceiver(FakeOpusCodec()) { CountingOut(CountDownLatch(1)) }
        rx.start()
        try {
            rx.onTunneledAudio(byteArrayOf(99, 1, 2, 3), 0L)   // not UDP_TYPE_AUDIO
            rx.onTunneledAudio(ByteArray(0), 0L)               // empty
            assertEquals(emptySet<Int>(), rx.speakingSessions.value)
        } finally {
            rx.stop()
        }
    }

    @Test
    fun ignoresMalformedProtobufBody() {
        val rx = VoiceReceiver(FakeOpusCodec()) { CountingOut(CountDownLatch(1)) }
        rx.start()
        try {
            // Valid type byte, garbage body — must not propagate out of the reader coroutine.
            rx.onTunneledAudio(byteArrayOf(0, -1, -1, -1, -1, -1), 0L)
            assertEquals(emptySet<Int>(), rx.speakingSessions.value)
        } finally {
            rx.stop()
        }
    }
}
