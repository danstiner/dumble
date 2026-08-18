package me.danielstiner.dumble.mumble.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

/**
 * The receive path end to end on a device: real Opus, the real native engine, and a real
 * AudioTrack, driven at wall-clock cadence.
 *
 * Everything else that covers this path stops at a seam. The JVM tests script fill outcomes
 * against a fake engine and a non-blocking output, so the loop runs as fast as the test drives it;
 * [NativePlayoutTest] proves the engine decodes but calls fillQuantum in a tight loop. Neither can
 * see what only real time produces: the loop is paced by a blocking AudioTrack.write, the
 * prebuffer gate is an adaptive target — ~30 ms on an idle link, 80 ms cold — measured in *wall
 * clock*, and the retire and stall windows are counted in polls, which only arrive at ~100 Hz when
 * audio is actually playing.
 *
 * The transport is deliberately absent. It is unchanged by the native playout work, and a
 * loopback server delivers packets with no jitter at all — so the arrival pattern is scripted
 * here instead, which is both harsher and reproducible.
 */
class PlayoutLoopDeviceTest {

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

    private fun newReceiver() = VoiceReceiver({ openNativePlayout() }, { AndroidAudioOut(context) })

    private fun awaitTrue(message: String, timeoutMillis: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(message, cond())
    }

    /**
     * A speaker talks for one second at the cadence a 20 ms sender actually uses. Audio has to
     * reach the track, the UI has to see the speaker, and the spurt has to close on its
     * terminator rather than time out.
     */
    @Test
    fun aRealSpurtPlaysThroughTheAudioTrack() {
        val packets = toneStream()
        val rx = newReceiver()
        rx.start()
        try {
            // 50 packets is one second, which is also the stats sampling period — so this is the
            // shortest spurt that publishes a periodic sample rather than only a closing one.
            // Both reads happen mid-spurt: a paced sender is drained as fast as it feeds, so by
            // the last packet the speaker has often already gone quiet.
            var lit = false
            var mid: PlayoutStats? = null
            for (i in 0 until 50) {
                rx.onTunneledAudio(payload(SESSION, packets[i]))
                Thread.sleep(20)
                if (rx.speakingSessions.value == setOf(SESSION)) lit = true
                rx.playoutStats.value?.let { mid = it }
            }
            assertTrue("the speaker never lit up", lit)
            assertNotNull("no stats published during a one-second spurt", mid)
            // The platform's own measurement of the track we are feeding. Null here means
            // AudioTrack never answered a timestamp, which on a real device means it never played.
            assertNotNull("AudioTrack reported no latency, so nothing played", mid!!.latencyMs)
            assertEquals("a paced spurt must not overflow the jitter queue", 0, mid.droppedPackets)
            // One frame of slack: the opening fill can land mid-packet, which is a real splice
            // and is counted as one. Anything beyond that is the loop failing to keep up.
            assertTrue("concealment during a clean spurt: ${mid.concealedGaps}",
                       mid.concealedGaps <= 1)

            rx.onTunneledAudio(payload(SESSION, packets[50], terminator = true))
            awaitTrue("the spurt never closed") { rx.speakingSessions.value.isEmpty() }
            val end = rx.playoutStats.value
            assertNotNull("no closing sample", end)
            assertEquals(0, end!!.droppedPackets)
        } finally {
            rx.stop()
        }
    }

    /**
     * The gap case, which is the whole reason the concealment counter exists. A sender goes quiet
     * mid-sentence for 300 ms and then resumes.
     *
     * Every sample is collected rather than only the last, because the loop treats the hole as the
     * end of a talk spurt — it is longer than kRetireIdlePolls, so the speaker retires and the
     * resume is a fresh spurt with fresh baselines. That is the right accounting (the gap is
     * charged to the spurt it interrupted) but it means the closing sample of the *second* spurt
     * correctly reports nothing, and a test reading only that one would call this a silent
     * failure.
     */
    @Test
    fun aMidSpurtStallIsChargedToTheSpurtItInterrupted() {
        val packets = toneStream()
        val rx = newReceiver()
        val samples = mutableListOf<PlayoutStats>()
        fun sample() = rx.playoutStats.value?.let { if (samples.lastOrNull() != it) samples += it }
        rx.start()
        try {
            for (i in 0 until 25) {
                rx.onTunneledAudio(payload(SESSION, packets[i]))
                Thread.sleep(20)
                sample()
            }
            assertTrue("the speaker never lit up", samples.isNotEmpty())

            // Past the cold-start target and past kRetireIdlePolls: the queue drains, the gate
            // re-arms, and the slot retires. The resume below has to prebuffer again.
            repeat(30) { Thread.sleep(10); sample() }

            for (i in 25 until 50) {
                rx.onTunneledAudio(payload(SESSION, packets[i]))
                Thread.sleep(20)
                sample()
            }
            rx.onTunneledAudio(payload(SESSION, packets[50], terminator = true))
            awaitTrue("the spurt never resumed and closed") {
                sample(); rx.speakingSessions.value.isEmpty()
            }

            assertTrue("a 300 ms hole went unreported across ${samples.size} samples",
                       samples.any { it.concealedGaps >= 1 })
            // Charged once for the hole rather than once per poll — 30 polls pass during the
            // stall, and a per-poll charge would show up here as tens.
            assertTrue("a single gap was charged per fill: ${samples.map { it.concealedGaps }}",
                       samples.all { it.concealedGaps <= 4 })
            assertTrue("a stall is not a queue overflow: ${samples.map { it.droppedPackets }}",
                       samples.all { it.droppedPackets == 0 })
        } finally {
            rx.stop()
        }
    }

    /**
     * A burst is the shape TCP head-of-line blocking actually delivers: nothing for a while, then
     * everything at once. It must play rather than overflow, since the whole second is well under
     * the queue's 600 ms of depth only if the loop drains it while it arrives.
     */
    @Test
    fun aBurstIsAbsorbedRatherThanDropped() {
        val packets = toneStream()
        val rx = newReceiver()
        rx.start()
        try {
            // 15 packets is 300 ms of audio arriving in one go — past the prebuffer, inside
            // kMaxQueuedPackets and inside kHighWaterSamples, so none of it may be thrown away.
            for (i in 0 until 15) rx.onTunneledAudio(payload(SESSION, packets[i]))
            awaitTrue("a burst produced no audio") { rx.speakingSessions.value == setOf(SESSION) }
            rx.onTunneledAudio(payload(SESSION, packets[15], terminator = true))
            awaitTrue("the burst never drained") { rx.speakingSessions.value.isEmpty() }

            val end = rx.playoutStats.value
            assertNotNull("no closing sample", end)
            assertEquals("a 300 ms burst must fit the queue", 0, end!!.droppedPackets)
        } finally {
            rx.stop()
        }
    }

    private companion object {
        const val SESSION = 42
    }
}
