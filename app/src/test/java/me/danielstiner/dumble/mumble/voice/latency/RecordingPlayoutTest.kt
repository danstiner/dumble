package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.voice.FakePlayoutEngine
import me.danielstiner.dumble.mumble.voice.NativePlayout
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RecordingPlayoutTest {
    private val clock = TestTimeSource()
    private val engine = FakePlayoutEngine()
    private val arrived = mutableListOf<ArrivedPacket>()

    @Test
    fun anAcceptedPacketIsReportedWithItsMarkAndWhatWasAheadOfIt() {
        val recording = RecordingPlayout(engine, clock) { arrived += it }
        val start = clock.markNow()
        clock += 30.milliseconds
        engine.aheadSamples = 960
        engine.targetSamples = 1440
        engine.writeAheadSamples = 480

        val offered = recording.offer(5, byteArrayOf(1), 42, terminator = false)

        assertEquals(NativePlayout.OFFER_ACCEPTED, offered.code)
        val packet = arrived.single()
        assertEquals(5, packet.session)
        assertEquals(42L, packet.frameNumber)
        assertEquals(960, packet.aheadSamples)
        assertEquals(1440, packet.targetSamples)
        assertEquals(480, packet.writeAheadSamples)
        assertEquals(30.milliseconds, packet.at - start)
        assertEquals("the engine took the packet", 1, engine.offered.size)
    }

    @Test
    fun aRefusedPacketIsNotReported() {
        val recording = RecordingPlayout(engine, clock) { arrived += it }
        engine.offerResult = NativePlayout.OFFER_SPEAKER_CAP

        assertEquals(NativePlayout.OFFER_SPEAKER_CAP, recording.offer(5, byteArrayOf(1), 42, terminator = false).code)

        assertEquals(emptyList<ArrivedPacket>(), arrived)
    }

    /** It runs on the transport's reader. */
    @Test
    fun aCheckThatThrowsStillGetsThePacketPlayed() {
        val recording = RecordingPlayout(engine, clock) { error("a check's bug") }

        assertEquals(NativePlayout.OFFER_ACCEPTED, recording.offer(5, byteArrayOf(1), 42, terminator = false).code)

        assertEquals(1, engine.offered.size)
    }
}
