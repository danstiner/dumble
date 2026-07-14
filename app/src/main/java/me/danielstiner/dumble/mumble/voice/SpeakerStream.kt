package me.danielstiner.dumble.mumble.voice

/**
 * Per-speaker playout. Playback thread calls fillTick(); receive thread calls offer().
 * offer() only touches the (synchronized) JitterBuffer; the decoder is created lazily and
 * decode happens on the playback thread inside fillTick(). One OpusDecoder per speaker.
 */
class SpeakerStream(
    private val codec: OpusCodec,
    private val prebufferSamples: Int = FRAME_SAMPLES_20MS * 5,   // ~100 ms
    private val reanchorGapSamples: Long = SAMPLE_RATE.toLong(),  // 1 s forward jump → boundary
    private val maxHoldTicks: Int = 10,                           // ~200 ms held underrun → boundary reset
    private val retireIdleTicks: Int = 500,                       // ~10 s un-anchored + empty → retire
) {
    private val buffer = JitterBuffer()
    @Volatile private var cursor = -1L          // -1 = un-anchored; only fillTick writes it
    private var consecutivePlc = 0              // consecutive HELD (live-underrun) ticks
    private var idleTicks = 0                   // consecutive un-anchored + empty ticks
    private var decoder: OpusDecoder? = null    // playback-thread only
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    private val fifo = ShortArrayFifo(MAX_FRAME_SAMPLES * 4)

    val decoderCreated get() = decoder != null
    var retired = false; private set

    /** Receive thread. Only touches the synchronized JitterBuffer; a slightly stale cursor is safe. */
    fun offer(timestampSamples: Long, opus: ByteArray, spanSamples: Int, isTerminator: Boolean): Boolean {
        val cur = cursor
        return buffer.offer(JitterBuffer.Packet(timestampSamples, opus, spanSamples, isTerminator),
            if (cur < 0) 0 else cur)
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
            if (buffer.bufferedSamples() < prebufferSamples && buffer.terminatorTimestamp == null) return false
            cursor = first
            consecutivePlc = 0
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

    private fun resetToIdle() {             // boundary reset — playback thread only
        cursor = -1
        fifo.clear()
        consecutivePlc = 0
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
