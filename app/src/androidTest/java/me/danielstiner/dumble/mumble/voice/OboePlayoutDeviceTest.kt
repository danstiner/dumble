package me.danielstiner.dumble.mumble.voice

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

/**
 * The receive path end to end on a device: real Opus, the real native engine, and a real Oboe
 * output stream pulling from it, driven at wall-clock cadence.
 *
 * Everything else that covers this path stops at a seam. The JVM tests drive the poll against a
 * fake engine; [NativePlayoutTest] proves the seam's layouts but never starts the stream. Only
 * real time shows what this asserts: the stream starts within a poll of the first packet, the
 * callback keeps up with the burst, the stream pauses and comes back, and the stream's own
 * latency and underrun counters read.
 *
 * The transport is deliberately absent: a loopback server delivers packets with no jitter at all,
 * so the arrival pattern is scripted here instead.
 */
class OboePlayoutDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    /** The 20 ms packets of `tone-20ms.opus`, in order — one continuous 440 Hz spurt. */
    private fun toneStream(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        DataInputStream(context.assets.open("tone-20ms.opus").buffered()).use { input ->
            while (true) {
                val lo = input.read()
                if (lo < 0) break
                val len = lo or (input.read() shl 8)
                packets += ByteArray(len).also { input.readFully(it) }
            }
        }
        return packets
    }

    private fun payload(session: Int, opus: ByteArray, terminator: Boolean = false): ByteArray {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(session)
            .setOpusData(ByteString.copyFrom(opus))
            .setIsTerminator(terminator)
            .build()
        return byteArrayOf(0) + audio.toByteArray()
    }

    private fun newReceiver() = VoiceReceiver({ openNativePlayout() })

    private fun awaitTrue(message: String, timeoutMillis: Long = 10_000, cond: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!cond() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue(message, cond())
    }

    /** Offers [packets] one per 20 ms on an absolute schedule — a sender paced by sleeping after
     *  each send runs slow and drains any jitter buffer — sampling [onEach] after every offer. */
    private fun playPaced(rx: VoiceReceiver, packets: List<ByteArray>, onEach: (Int) -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        packets.forEachIndexed { i, p ->
            rx.onTunneledAudio(payload(SESSION, p))
            onEach(i)
            val next = t0 + (i + 1) * 20L
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) SystemClock.sleep(wait)
        }
    }

    /**
     * A speaker talks for a second and a half at the cadence a 20 ms sender actually uses. The
     * stream has to start within a poll of the first packet, the UI has to see the speaker, the
     * stream has to report a latency, and the spurt has to close on its terminator rather than
     * time out. Long enough for the periodic sample, which lands one second after the spurt
     * opens — the prebuffer after the first packet, not the packet itself.
     */
    @Test
    fun aRealSpurtPlaysThroughTheStream() {
        val packets = toneStream()
        val rx = newReceiver()
        rx.start()
        try {
            var litAfterMillis = -1L
            var mid: PlayoutStats? = null
            val t0 = SystemClock.elapsedRealtime()
            playPaced(rx, packets.subList(0, 75)) {
                if (litAfterMillis < 0 && rx.speakingSessions.value == setOf(SESSION)) {
                    litAfterMillis = SystemClock.elapsedRealtime() - t0
                }
                rx.playoutStats.value?.let { mid = it }
            }
            assertTrue("the speaker never lit up", litAfterMillis >= 0)
            // The cold-start target is 80 ms, the poll 50 ms, the stream open tens of ms.
            assertTrue("lit after $litAfterMillis ms", litAfterMillis <= 300)
            assertNotNull("no stats published during a one-second spurt", mid)
            assertNotNull("the stream reported no latency, so it never ran", mid!!.latencyMs)
            assertEquals("a paced spurt must not overflow the jitter queue", 0, mid.droppedPackets)
            assertEquals("the callback missed a burst", 0, mid.underruns)

            rx.onTunneledAudio(payload(SESSION, packets[75], terminator = true))
            awaitTrue("the spurt never closed", 1_000) { rx.speakingSessions.value.isEmpty() }
            val end = rx.playoutStats.value
            assertNotNull("no closing sample", end)
            assertEquals(0, end!!.droppedPackets)
            // One gap of slack: the opening fill can land mid-packet, which is a real splice and
            // is counted as one. Anything beyond that is the callback failing to keep up.
            assertTrue("concealment during a clean spurt: ${end.concealedGaps}", end.concealedGaps <= 1)
        } finally {
            rx.stop()
        }
    }

    /**
     * A second spurt after a silence long enough for the slot to retire must light up as fast as
     * the first, with nothing published in between and the stream's counters clean: the stream
     * stays started through the silence, so there is no restart to pay for.
     */
    @Test
    fun aSecondSpurtAfterASilenceLightsAsFastAsTheFirst() {
        val packets = toneStream()
        val rx = newReceiver()
        rx.start()
        try {
            playPaced(rx, packets.subList(0, 25)) {}
            rx.onTunneledAudio(payload(SESSION, packets[25], terminator = true))
            awaitTrue("the first spurt never closed", 1_000) { rx.speakingSessions.value.isEmpty() }
            val closing = rx.playoutStats.value
            assertNotNull("no closing sample", closing)

            // Well past the slot's retire window.
            SystemClock.sleep(2_500)
            assertTrue("stats must not publish while idle", rx.playoutStats.value === closing)

            var litAfterMillis = -1L
            val t0 = SystemClock.elapsedRealtime()
            playPaced(rx, packets.subList(26, 51)) {
                if (litAfterMillis < 0 && rx.speakingSessions.value == setOf(SESSION)) {
                    litAfterMillis = SystemClock.elapsedRealtime() - t0
                }
            }
            assertTrue("the second spurt never lit up", litAfterMillis >= 0)
            assertTrue("lit after $litAfterMillis ms", litAfterMillis <= 300)
            rx.onTunneledAudio(payload(SESSION, packets[51], terminator = true))
            awaitTrue("the second spurt never closed", 1_000) { rx.speakingSessions.value.isEmpty() }
            val end = rx.playoutStats.value!!
            assertNotNull("the stream reported no latency", end.latencyMs)
            assertEquals("the second spurt must not underrun", 0, end.underruns)
        } finally {
            rx.stop()
        }
    }

    /**
     * The callback's budget is one burst — 2.7 ms on a Pixel 7a — and the engine's own timing is
     * the only instrument that says how much of it a fill used. Every sample over a paced spurt,
     * since the first fill of a spurt is the one that decodes a whole prebuffer.
     */
    @Test
    fun theFillNeverExceedsABurst() {
        val packets = toneStream()
        val rx = newReceiver()
        val samples = mutableListOf<PlayoutStats>()
        rx.start()
        try {
            playPaced(rx, packets.subList(0, 75)) {
                rx.playoutStats.value?.let { if (samples.lastOrNull() !== it) samples += it }
            }
            rx.onTunneledAudio(payload(SESSION, packets[75], terminator = true))
            awaitTrue("the spurt never closed", 1_000) { rx.speakingSessions.value.isEmpty() }
            rx.playoutStats.value?.let { if (samples.lastOrNull() !== it) samples += it }
            assertTrue("no samples", samples.isNotEmpty())
            val worst = samples.maxOf { it.fillMicrosMax }
            // Under the smallest burst any device asks for, with room: the host benchmark reads
            // tens of microseconds for one speaker.
            assertTrue("a fill took $worst us: ${samples.map { it.fillMicrosMax }}", worst < 2_000)
        } finally {
            rx.stop()
        }
    }

    private companion object {
        const val SESSION = 42
    }
}
