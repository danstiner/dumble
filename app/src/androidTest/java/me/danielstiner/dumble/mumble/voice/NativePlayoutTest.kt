package me.danielstiner.dumble.mumble.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The seam itself, on a real ABI: array validation, the flat `status` and `counters` layouts, and
 * the byte-for-byte trip a payload takes into libopus and back out as PCM. [FakePlayoutEngine]
 * stands in for all of this in the JVM tests, so nothing there can catch a layout that drifted
 * from `playout_jni.cpp` — which is the one bug this file exists for.
 */
class NativePlayoutTest {

    private var handle = 0L

    @Before fun open() {
        handle = NativePlayout.create(SAMPLE_RATE, QUANTUM_SAMPLES)
        assertNotEquals("no engine", 0L, handle)
    }

    @After fun close() {
        if (handle != 0L) NativePlayout.destroy(handle)
    }

    private fun status() = IntArray(NativePlayout.STATUS_LENGTH)
    private fun pcm() = ShortArray(QUANTUM_SAMPLES)

    private class Stats {
        val sessions = IntArray(MAX_SPEAKERS)
        val depths = IntArray(MAX_SPEAKERS)
        val counters = LongArray(NativePlayout.COUNTER_COUNT)
        var speakers = 0
        val concealedTicks get() = counters[NativePlayout.COUNTER_CONCEALED_TICKS]
        val droppedPackets get() = counters[NativePlayout.COUNTER_DROPPED_PACKETS]
        fun depthOf(session: Int): Int {
            for (i in 0 until speakers) if (sessions[i] == session) return depths[i]
            return -1
        }
    }

    private fun stats(): Stats {
        val s = Stats()
        s.speakers = NativePlayout.readStats(handle, s.sessions, s.depths, s.counters)
        assertTrue("readStats refused its arrays", s.speakers >= 0)
        return s
    }

    @Test
    fun anEngineRefusesAQuantumItCannotHold() {
        // The null handle openNativePlayout turns into "voice unavailable". Reachable only through
        // a caller that sizes its quantum wrong, but it is the only failure create() reports and
        // nothing else proves the 0 travels back as a Kotlin Long.
        assertEquals(0L, NativePlayout.create(SAMPLE_RATE, 0))
        assertEquals(0L, NativePlayout.create(SAMPLE_RATE, MAX_PACKET_SAMPLES + 1))
    }

    @Test
    fun aSpurtOfferedAsBytesComesBackAsMixedPcm() {
        // The whole seam in one assertion: a Kotlin ByteArray copied in, decoded by the real
        // libopus for this ABI, mixed, and copied back into a Kotlin ShortArray. The terminator
        // is what opens the prebuffer gate on a single packet — without it 10 ms sits below
        // kPrebufferSamples and plays nothing, which is the engine working, not a failure.
        assertEquals(
            NativePlayout.OFFER_ACCEPTED,
            NativePlayout.offer(handle, SESSION, TONE_10MS, true),
        )
        val pcm = pcm()
        val status = status()
        assertEquals("one speaker producing", 1, NativePlayout.fillQuantum(handle, pcm, status))
        assertEquals(1, status[NativePlayout.STATUS_ACTIVE_SPEAKERS])
        assertEquals(SESSION, status[NativePlayout.STATUS_SESSIONS])
        assertTrue("the quantum came back silent", pcm.any { it.toInt() != 0 })
    }

    @Test
    fun everyProducingSpeakerComesBackInTheStatusArray() {
        // One speaker only ever fills status[STATUS_SESSIONS], so the rest of that array — the
        // whole reason it is sized to kMaxSpeakers — is never written by the single-speaker case
        // above. A copy length taken from the wrong count shows up here and nowhere else.
        val speakers = listOf(11, 22, 33)
        for (session in speakers) {
            assertEquals(
                NativePlayout.OFFER_ACCEPTED,
                NativePlayout.offer(handle, session, TONE_10MS, true),
            )
        }
        val pcm = pcm()
        val status = status()
        assertEquals(speakers.size, NativePlayout.fillQuantum(handle, pcm, status))
        assertEquals(speakers.size, status[NativePlayout.STATUS_ACTIVE_SPEAKERS])
        val reported = (0 until speakers.size).map { status[NativePlayout.STATUS_SESSIONS + it] }
        assertEquals(speakers.toSet(), reported.toSet())
        assertTrue("three mixed speakers came back silent", pcm.any { it.toInt() != 0 })
    }

    @Test
    fun anIdleEngineFillsSilenceAndReportsNobody() {
        val pcm = ShortArray(QUANTUM_SAMPLES) { 999 }
        val status = status()
        assertEquals(0, NativePlayout.fillQuantum(handle, pcm, status))
        assertEquals(0, status[NativePlayout.STATUS_ACTIVE_SPEAKERS])
        assertTrue("an idle quantum must be written, not left alone", pcm.all { it.toInt() == 0 })
    }

    @Test
    fun undersizedBuffersAreRefusedRatherThanOverrun() {
        // Every one of these is an allocation bug on this side, and every one would otherwise be
        // a native write past the end of a Java array.
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.fillQuantum(handle, pcm(), IntArray(NativePlayout.STATUS_LENGTH - 1)),
        )
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.fillQuantum(handle, ShortArray(0), status()),
        )
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.fillQuantum(handle, ShortArray(MAX_PACKET_SAMPLES + 1), status()),
        )
        val sessions = IntArray(MAX_SPEAKERS)
        val depths = IntArray(MAX_SPEAKERS)
        val counters = LongArray(NativePlayout.COUNTER_COUNT)
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.readStats(handle, IntArray(MAX_SPEAKERS - 1), depths, counters),
        )
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.readStats(handle, sessions, IntArray(MAX_SPEAKERS - 1), counters),
        )
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.readStats(handle, sessions, depths, LongArray(1)),
        )
    }

    @Test
    fun aRefusedQuantumLeavesTheCallersBufferAlone() {
        // The caller must not play a refused tick, so nothing is copied into its buffer — it holds
        // what this side put there rather than a frame of native stack.
        val refusedBySeam = ShortArray(QUANTUM_SAMPLES) { 999 }
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.fillQuantum(handle, refusedBySeam, IntArray(1)),
        )
        assertTrue("a refused quantum was published", refusedBySeam.all { it.toInt() == 999 })

        // The other origin, and the only one that reaches fillQuantum at all: a quantum inside the
        // seam's scratch bound but wider than the engine was created for. The engine leaves its
        // output untouched and answers the same code, which has to travel back out without the
        // scratch behind it being copied anywhere.
        val refusedByEngine = ShortArray(2 * QUANTUM_SAMPLES) { 999 }
        assertEquals(
            NativePlayout.ERROR_BUFFER_TOO_SMALL,
            NativePlayout.fillQuantum(handle, refusedByEngine, status()),
        )
        assertTrue("a refused quantum was published", refusedByEngine.all { it.toInt() == 999 })
    }

    @Test
    fun anOversizedPayloadIsRefusedButItsTerminatorIsNot() {
        // Oversize is refused by the seam rather than the engine, because the stack scratch is
        // what cannot hold it — so this is the one refusal where the terminator has to be carried
        // across by hand. It is a protobuf field beside the payload and stays true when the
        // payload is garbage, so the spurt still ends.
        val huge = ByteArray(MAX_PACKET_BYTES + 1)
        assertEquals(
            NativePlayout.OFFER_PACKET_TOO_LARGE,
            NativePlayout.offer(handle, SESSION, huge, true),
        )
        assertEquals("the terminator never reached the engine", 1, stats().speakers)
        assertEquals("an oversized payload must queue no samples", 0, stats().depthOf(SESSION))
    }

    @Test
    fun anUnparseablePayloadIsRefusedOnItsOwnCode() {
        // A code 3 packet claiming zero frames: it parses, and measures at zero samples. Its own
        // code rather than the oversize one, so a peer sending garbage is distinguishable from a
        // peer violating the size bound.
        assertEquals(
            NativePlayout.OFFER_MALFORMED_PACKET,
            NativePlayout.offer(handle, SESSION, byteArrayOf(3, 0), false),
        )
    }

    @Test
    fun anEmptyPayloadCarriesATerminatorAndNothingElse() {
        // What a real end-of-spurt frame looks like on the wire: is_terminator set, opus_data
        // empty. `data` is null in native for this one, so it also pins that the length-0 branch
        // never dereferences it.
        assertEquals(
            NativePlayout.OFFER_ACCEPTED,
            NativePlayout.offer(handle, SESSION, ByteArray(0), true),
        )
    }

    @Test
    fun depthsAndSessionsComeBackAsParallelArrays() {
        NativePlayout.offer(handle, 4, TONE_10MS, false)
        NativePlayout.offer(handle, 4, TONE_10MS, false)
        NativePlayout.offer(handle, 8, TONE_10MS, false)
        val stats = stats()
        assertEquals(2, stats.speakers)
        // 10 ms apiece at the sample rate the engine was created with. Wrong-index bugs in the
        // seam show up here as the depths landing on the wrong sessions.
        assertEquals(2 * QUANTUM_SAMPLES, stats.depthOf(4))
        assertEquals(QUANTUM_SAMPLES, stats.depthOf(8))
    }

    /**
     * The counters cross as a flat array, so their two indices are agreed by hand between
     * `playout_jni.cpp` and [NativePlayout]. Nothing in the JVM tests can catch those being
     * swapped — [FakePlayoutEngine] fills the same array from the same constants, so it agrees
     * with itself whatever they are. This is the test that would fail.
     */
    @Test
    fun theSpeakerCapRefusesAndIsCountedAsADrop() {
        for (session in 1..MAX_SPEAKERS) {
            assertEquals(
                "session $session was refused below the cap",
                NativePlayout.OFFER_ACCEPTED,
                NativePlayout.offer(handle, session, TONE_10MS, false),
            )
        }
        assertEquals(
            NativePlayout.OFFER_SPEAKER_CAP,
            NativePlayout.offer(handle, MAX_SPEAKERS + 1, TONE_10MS, false),
        )
        val stats = stats()
        assertEquals(MAX_SPEAKERS, stats.speakers)
        assertEquals("a capped packet is lost audio and must be counted", 1, stats.droppedPackets)
        // The other half of the same check: nothing has been mixed yet, so a concealment count
        // above zero means the two indices are crossed.
        assertEquals(0, stats.concealedTicks)
    }

    private companion object {
        const val SESSION = 7

        /** Mirrors `PlayoutConstants.h`'s kMaxPacketBytes, which is what the seam refuses above. */
        const val MAX_PACKET_BYTES = 1276

        /** Its sibling kMaxPacketSamples, the seam's other refusal bound. Mirrored here rather
         *  than shared from AudioConstants: nothing the app itself does is sized from it, only
         *  the refusals this file pokes at. */
        const val MAX_PACKET_SAMPLES = 5760

        /**
         * One 10 ms packet of the 440 Hz tone the host C++ tests use, from the same encoder at the
         * same 40 kbps — pasted rather than encoded here because this side has no encoder binding,
         * only a decoder. Regenerate by encoding `tone(480)` with `AudioEncoder` at 48 kHz mono.
         */
        val TONE_10MS = byteArrayOf(
            112, -126, 1, -62, 81, 115, -5, 73, -1, -65, -71, -72,
            4, 109, 73, -110, 126, -78, 26, -36, -18, 108, 88, -54,
            61, -48, 92, -10, -100, 103, -124, -86, -102, 65, -28, 91,
            -12, 21, 46, 78, 123, -2, -45, 120, 123, 28, -68, 30,
            -70, 98,
        )
    }
}
