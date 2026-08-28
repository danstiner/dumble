package me.danielstiner.dumble.mumble.voice

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scripted stand-in for the native engine: each entry is one fillQuantum outcome. The playback
 * loop blocks on [fills] the way the real loop blocks on AudioTrack, so a test controls the
 * cadence exactly.
 */
class FakePlayoutEngine : VoiceReceiver.PlayoutEngine {
    /** One fill: which sessions produced, how many speakers are live, whether the fill was
     *  short of a full quantum (which the real engine counts as concealment), and how many packets
     *  the jitter queues threw away. Drops are charged to a fill only because fills are the test's
     *  clock — natively they accrue on the reader thread, on offer(). */
    data class Fill(
        val producing: List<Int> = emptyList(),
        val activeSpeakers: Int = producing.size,
        val concealed: Boolean = false,
        val dropped: Int = 0,
    )

    private val fills = LinkedBlockingQueue<Fill>()

    data class Offer(
        val session: Int,
        val opusData: ByteArray,
        val frameNumber: Long,
        val terminator: Boolean,
    )

    /** Every offer the reader made, in order. */
    val offered = mutableListOf<Offer>()

    /** The last write-ahead the loop handed over; -1 until it has. */
    @Volatile var writeAheadGiven = -1

    override fun setWriteAhead(samples: Int) { writeAheadGiven = samples }

    /** What [offer] returns. Tests that exercise the cap or a broken engine override it. */
    @Volatile var offerResult = NativePlayout.OFFER_ACCEPTED

    /** When true, [fillQuantum] and [readStats] report the caller's arrays as too small, the way
     *  native does when this side allocates them wrong. */
    @Volatile var refuseBuffers = false

    /** Every call to [destroy], so a test can catch a double-free rather than merely "at least
     *  once" — double-free is the historical bug class in this area. */
    val destroyCalls = AtomicInteger()

    /** True once [destroy] has been called at least once. */
    val destroyed: Boolean get() = destroyCalls.get() > 0

    /** Depth reported per session by [readStats]. */
    @Volatile var depthsBySession: Map<Int, Int> = emptyMap()

    /** Target reported per session by [readStats]. */
    @Volatile var targetsBySession: Map<Int, Int> = emptyMap()

    @Volatile private var concealedGaps = 0L
    @Volatile private var droppedPackets = 0L

    /** True (the default) suits VoiceReceiverTest: the test drives the cadence, so an exhausted
     *  script should park the loop rather than manufacture silence. MumbleConnectionTest's
     *  receiver instead runs unscripted for most of a session — a blocking take() there would
     *  wedge the playback thread on the first fillQuantum() with nothing queued, and every
     *  disconnect()/stop() would then wait out its 1 s join. Set false there: an empty queue
     *  reports one idle fill immediately instead of blocking. */
    @Volatile var blockWhenEmpty = true

    fun script(vararg t: Fill) = t.forEach { fills.put(it) }

    /** A fill that never arrives parks the loop, the way silence does in production. */
    fun scriptSilence(count: Int) = repeat(count) { fills.put(Fill()) }

    @Synchronized
    override fun offer(
        session: Int,
        opusData: ByteArray,
        frameNumber: Long,
        terminator: Boolean,
    ): Int {
        offered += Offer(session, opusData, frameNumber, terminator)
        return offerResult
    }

    override fun fillQuantum(pcm: ShortArray, status: IntArray): Int {
        val fill = if (blockWhenEmpty) fills.take() else fills.poll() ?: Fill()
        if (refuseBuffers) return NativePlayout.ERROR_BUFFER_TOO_SMALL
        if (fill.concealed) concealedGaps++
        droppedPackets += fill.dropped
        status[NativePlayout.STATUS_ACTIVE_SPEAKERS] = fill.activeSpeakers
        fill.producing.forEachIndexed { i, session ->
            status[NativePlayout.STATUS_SESSIONS + i] = session
        }
        // Non-silent audio so a test can tell a written quantum from an unwritten one.
        if (fill.producing.isNotEmpty()) pcm.fill(1000)
        return fill.producing.size
    }

    override fun readStats(
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        counters: LongArray,
    ): Int {
        if (refuseBuffers) return NativePlayout.ERROR_BUFFER_TOO_SMALL
        counters[NativePlayout.COUNTER_CONCEALED_GAPS] = concealedGaps
        counters[NativePlayout.COUNTER_DROPPED_PACKETS] = droppedPackets
        counters[NativePlayout.COUNTER_SHRUNK_PACKETS] = 0
        counters[NativePlayout.COUNTER_CATCH_UP_PACKETS] = 0
        counters[NativePlayout.COUNTER_CONTENDED_FILLS] = 0
        counters[NativePlayout.COUNTER_FILL_MICROS_MAX] = 0
        counters[NativePlayout.COUNTER_FILL_MICROS_MEAN] = 0
        depthsBySession.entries.forEachIndexed { i, (session, depth) ->
            sessions[i] = session
            depths[i] = depth
            targets[i] = targetsBySession[session] ?: 0
        }
        return depthsBySession.size
    }

    override fun destroy() { destroyCalls.incrementAndGet() }
}
