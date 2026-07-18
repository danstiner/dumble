package me.danielstiner.dumble.mumble.voice

private const val DEEPEN_INTERVAL_TICKS = 10   // >= 200 ms between valve deepens (20 ms/tick)
private const val BACKWARD_RESET_GAP_SAMPLES = 12_000   // 250 ms: a ts drop > this on a QUEUED packet = frame_number reset

/**
 * Per-speaker playout. Playback thread calls fillTick(); receive thread calls offer().
 * offer() only touches the (synchronized) JitterBuffer; the decoder is created lazily and
 * decode happens on the playback thread inside fillTick(). One OpusDecoder per speaker.
 */
class SpeakerStream(
    private val codec: OpusCodec,
    private val estimator: DownlinkJitterEstimator = DownlinkJitterEstimator(),
    private val targetSamples: () -> Int = { estimator.targetSamples },  // prod: adaptive; tests override to a fixed depth
    private val reanchorGapSamples: Long = SAMPLE_RATE.toLong(),  // 1 s forward jump → boundary
    private val maxHoldTicks: Int = 10,                           // ~200 ms held underrun → boundary reset
    private val retireIdleTicks: Int = 500,                       // ~10 s un-anchored + empty → retire
) {
    private val buffer = JitterBuffer()
    @Volatile private var cursor = -1L          // -1 = un-anchored; only fillTick writes it
    private var consecutivePlc = 0              // consecutive HELD (live-underrun) ticks
    private var idleTicks = 0                   // consecutive un-anchored + empty ticks
    private var ticksSinceDeepen = DEEPEN_INTERVAL_TICKS   // playback-thread only; allow immediate first deepen
    private var lastOfferTs = Long.MIN_VALUE               // receive-thread only: detect frame_number resets
    private var decoder: OpusDecoder? = null    // playback-thread only
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    private val fifo = ShortArrayFifo(MAX_FRAME_SAMPLES * 4)

    val decoderCreated get() = decoder != null
    var retired = false; private set

    /** Diagnostic read-only accessor: current playout cursor in samples (−1 = un-anchored). Written
     *  only by the playback thread; a slightly stale read from the receive thread is fine here. */
    fun playoutCursor(): Long = cursor

    /** Receive thread. Feeds the estimator (every non-empty packet, incl. late) and enqueues. */
    fun offer(timestampSamples: Long, opus: ByteArray, spanSamples: Int, isTerminator: Boolean, arrivalNanos: Long): JitterBuffer.OfferResult {
        val cur = cursor
        val result = buffer.offer(JitterBuffer.Packet(timestampSamples, opus, spanSamples, isTerminator),
            if (cur < 0) 0 else cur)
        // A large BACKWARD ts jump on a freshly-anchoring (QUEUED) packet = the sender's frame_number reset
        // (Mumble renumbers after ~5 s silence, which has already un-anchored us). Reset the estimator HERE
        // (receive thread — race-free vs onPacket) so its d-metric doesn't mix two baselines and pin the
        // target at MAX. QUEUED (not LATE) gates out a mid-spurt straggler; a forward gap (loss) needs no
        // reset. Do NOT reset from resetToIdle() — that's the PLAYBACK thread.
        if (result == JitterBuffer.OfferResult.QUEUED && lastOfferTs != Long.MIN_VALUE &&
            timestampSamples < lastOfferTs - BACKWARD_RESET_GAP_SAMPLES) {
            estimator.reset()
        }
        lastOfferTs = timestampSamples
        if (result != JitterBuffer.OfferResult.EMPTY) {
            estimator.onPacket(timestampSamples, arrivalNanos, result == JitterBuffer.OfferResult.LATE)
        }
        return result
    }

    /** Playback thread. Fills [out] (960 samples). Returns true if audio was produced. */
    fun fillTick(out: ShortArray): Boolean {
        if (cursor < 0) {                                       // un-anchored
            val first = buffer.peekFirstTimestamp()
            if (first == null) {                               // idle: nothing to play
                if (++idleTicks >= retireIdleTicks) retired = true
                return false                                   // caller ignores `out` when !produced
            }
            idleTicks = 0
            if (buffer.bufferedSamples() < targetSamples() && buffer.terminatorTimestamp == null) return false
            cursor = first
            consecutivePlc = 0
        }
        // Mid-spurt grow valve: sustained lates WITHOUT underrun (queue non-empty), still shallow,
        // rate-limited to 1/200 ms. Deepen by one PLC frame while HOLDING the cursor — WITHOUT touching
        // consecutivePlc (that counter belongs to the underrun/resetToIdle path; bumping it would reset).
        ticksSinceDeepen++
        if (estimator.lateBurst && !buffer.isEmpty() && buffer.bufferedSamples() < targetSamples() &&
            ticksSinceDeepen >= DEEPEN_INTERVAL_TICKS) {
            ticksSinceDeepen = 0
            plcDeepen()
            fifo.drainInto(out, FRAME_SAMPLES_20MS)
            return true
        }
        while (fifo.size < FRAME_SAMPLES_20MS) {                // ensure >= one 20 ms frame
            val next = buffer.peekFirstTimestamp()
            if (next == null) {                                // live underrun
                if (isPastTerminator() || consecutivePlc >= maxHoldTicks) { resetToIdle(); break }
                plcHold(); break                               // conceal, HOLD cursor
            }
            when {
                next > cursor + reanchorGapSamples -> { resetToIdle(); break }  // big jump → boundary
                next > cursor -> { consecutivePlc = 0; plcAdvance() }           // measured hole
                else -> decodeNext()                                           // due
            }
        }
        val produced = fifo.size > 0
        fifo.drainInto(out, FRAME_SAMPLES_20MS)                 // pads with silence if < 960
        return produced
    }

    private fun ensureDecoder(): OpusDecoder = decoder ?: codec.newDecoder().also { decoder = it }

    private fun decodeNext() {
        val p = buffer.pollFirst() ?: return
        val n = ensureDecoder().decode(p.opus, 0, p.opus.size, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
        cursor = p.timestampSamples + n
        consecutivePlc = 0
        // Decoding audio beyond a tagged terminator means the talkspurt continued past it (a new
        // or contiguous talkspurt) — the tag is stale, so clear it; otherwise a later mid-talkspurt
        // underrun would fire a spurious boundary reset against the old tag.
        val term = buffer.terminatorTimestamp
        if (term != null && p.timestampSamples > term) buffer.clearTerminator()
    }

    private fun plcHold() {                 // live underrun — conceal, do NOT advance cursor
        consecutivePlc++
        val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
    }

    private fun plcAdvance() {              // measured hole — conceal AND advance toward the queued packet
        val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
        cursor += FRAME_SAMPLES_20MS
    }

    private fun plcDeepen() {               // mid-spurt grow — conceal + HOLD cursor; does NOT touch consecutivePlc
        val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
    }

    private fun resetToIdle() {             // boundary reset — playback thread only
        cursor = -1
        fifo.clear()
        consecutivePlc = 0
        ticksSinceDeepen = 0
        buffer.clearTerminator()
    }

    private fun isPastTerminator(): Boolean {
        val t = buffer.terminatorTimestamp ?: return false
        return cursor >= t
    }

    fun close() { decoder?.close(); decoder = null }
}

/** Simple growable short FIFO (playback-thread only). */
class ShortArrayFifo(initialCapacity: Int) {
    private var buf = ShortArray(initialCapacity)
    private var head = 0
    var size = 0; private set
    fun push(src: ShortArray, n: Int) {
        ensure(size + n)
        System.arraycopy(src, 0, buf, head + size, n)
        size += n
    }
    /** Copies min(size, count) into dst[0..count); pads remainder with 0. Advances head. */
    fun drainInto(dst: ShortArray, count: Int) {
        val take = minOf(size, count)
        System.arraycopy(buf, head, dst, 0, take)
        for (i in take until count) dst[i] = 0
        head += take; size -= take
        if (size == 0) head = 0
    }
    fun clear() { head = 0; size = 0 }
    private fun ensure(needed: Int) {
        if (head + needed <= buf.size) return
        val compact = ShortArray(maxOf(buf.size * 2, needed))
        System.arraycopy(buf, head, compact, 0, size)
        buf = compact; head = 0
    }
}
