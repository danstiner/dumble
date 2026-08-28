package me.danielstiner.dumble.mumble.voice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for the native session: what [readStats] reports is whatever the test last set, and
 * every stream call is recorded, so a test drives the receiver's poll by state rather than by
 * cadence.
 */
class FakePlayoutEngine : VoiceReceiver.PlayoutEngine {
    data class Offer(
        val session: Int,
        val opusData: ByteArray,
        val frameNumber: Long,
        val terminator: Boolean,
    )

    /** Every offer the reader made, in order. */
    val offered = CopyOnWriteArrayList<Offer>()

    /** Every stream transition — `start` when a stream comes up, `pause`, `destroy` — in order.
     *  [start] is called every poll like native, so only the transition is recorded. */
    val calls = CopyOnWriteArrayList<String>()

    /** What [start] answers: false stands in for a stream that cannot be opened. */
    @Volatile var startResult = true

    /** Every [start] call, successful or not. */
    val startAttempts = AtomicInteger()
    @Volatile private var running = false

    /** What [offer] returns. Tests that exercise the cap or a broken engine override it. */
    @Volatile var offerResult = NativePlayout.OFFER_ACCEPTED

    /** When true, [readStats] reports the caller's arrays as too small, the way native does when
     *  this side allocates them wrong. */
    @Volatile var refuseBuffers = false

    /** Sessions holding a slot — what [readStats] returns as the live count. */
    @Volatile var liveSessions: Set<Int> = emptySet()

    /** The subset of [liveSessions] that produced in the last fill. */
    @Volatile var audibleSessions: Set<Int> = emptySet()

    @Volatile var depthsBySession: Map<Int, Int> = emptyMap()
    @Volatile var targetsBySession: Map<Int, Int> = emptyMap()

    /** The counters as native would report them: monotonic, with latency -1 until a stream
     *  reports one. */
    @Volatile var counters: LongArray = LongArray(NativePlayout.COUNTER_COUNT).also {
        it[NativePlayout.COUNTER_LATENCY_MICROS] = -1
    }

    val destroyCalls: Int get() = calls.count { it == "destroy" }
    val destroyed: Boolean get() = destroyCalls > 0
    val started: Boolean get() = calls.lastOrNull { it != "destroy" } == "start"

    fun counter(index: Int, value: Long) {
        counters = counters.copyOf().also { it[index] = value }
    }

    override fun offer(
        session: Int,
        opusData: ByteArray,
        frameNumber: Long,
        terminator: Boolean,
    ): Int {
        offered += Offer(session, opusData, frameNumber, terminator)
        return offerResult
    }

    override fun readStats(
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        audible: IntArray,
        counters: LongArray,
    ): Int {
        if (refuseBuffers) return NativePlayout.ERROR_BUFFER_TOO_SMALL
        this.counters.copyInto(counters)
        val live = liveSessions.sorted()
        live.forEachIndexed { i, session ->
            sessions[i] = session
            depths[i] = depthsBySession[session] ?: 0
            targets[i] = targetsBySession[session] ?: 0
            audible[i] = if (session in audibleSessions) 1 else 0
        }
        return live.size
    }

    override fun start(): Boolean {
        startAttempts.incrementAndGet()
        if (!startResult) { running = false; return false }
        if (!running) { running = true; calls += "start" }
        return true
    }
    override fun pause() { running = false; calls += "pause" }
    override fun destroy() { calls += "destroy" }
}
