package me.danielstiner.dumble.mumble.voice

/**
 * One speaker's playout state.
 *
 * Threading: [offer] runs on the transport's reader coroutine, [fillTick] on the playback
 * thread. Only the encoded queue crosses that boundary, so it alone is locked; the decoder,
 * the PCM FIFO, and the idle counter are playback-thread-only. Decoding happens outside the
 * lock so a slow decode never stalls the reader.
 *
 * Arrival order is correct order: this slice receives audio only through the TCP tunnel, which
 * delivers in order and without loss, so this is a plain FIFO rather than a timestamp-keyed
 * reorder buffer. `frame_number` is deliberately unused here — it becomes load-bearing when UDP
 * lands and reordering becomes possible again.
 */
class SpeakerQueue(private val codec: OpusCodec) {

    private class Packet(val opusData: ByteArray, val spanSamples: Int)

    private val lock = Any()
    private val encoded = ArrayDeque<Packet>()      // guarded by lock
    private var queuedSamples = 0                   // guarded by lock
    private var prebuffered = false                 // guarded by lock
    // Set by a terminator packet: release the gate and play out whatever is queued even if it
    // never reached PREBUFFER_SAMPLES (a short "yep"/"no" spurt otherwise never plays at all).
    // Never re-arms the gate — only the idle path in fillTick does that — so a terminator can't
    // reintroduce the earlier bug where it cleared `prebuffered` mid-spurt and stranded the tail.
    private var flushShort = false                  // guarded by lock

    private val fifo = ShortArrayFifo(MAX_FRAME_SAMPLES * 2)
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    private var decoder: OpusDecoder? = null
    private var idleTicks = 0

    // Set under lock so it doubles as the gate on offer, and volatile so the retirement the
    // playback thread just decided is visible to the reader that must stop enqueueing.
    @Volatile
    var retired = false
        private set

    /** Test-only view of the locked depth counter. */
    internal val queuedSamplesForTest: Int get() = synchronized(lock) { queuedSamples }

    /**
     * Reader-coroutine context; must not block. An empty [opusData] is a tag-only frame and is not
     * enqueued, but [isTerminator] on it is still honored — a terminator can arrive with no
     * trailing audio of its own.
     *
     * Returns false once this queue has retired, meaning the packet was not accepted and the
     * caller must take a fresh queue. Retirement and the removal from the speaker map are not one
     * step, so without this the caller could enqueue into a queue that is already on its way out
     * and lose the packet silently.
     */
    fun offer(opusData: ByteArray, isTerminator: Boolean): Boolean {
        synchronized(lock) {
            if (retired) return false
            if (opusData.isNotEmpty()) {
                val span = codec.packetSamples(opusData, 0, opusData.size)
                if (span > 0) {
                    encoded.addLast(Packet(opusData, span))
                    queuedSamples += span
                    while (queuedSamples > HIGH_WATER_SAMPLES && encoded.size > 1) {
                        queuedSamples -= encoded.removeFirst().spanSamples
                    }
                }
            }
            // is_terminator means no more audio is coming for this spurt: release the gate so
            // fillTick plays out what's queued, however short. Latches rather than plays
            // immediately here because offer() runs on the reader thread and must not touch fifo
            // or the decoder, which are playback-thread-only.
            if (isTerminator) flushShort = true
            return true
        }
    }

    /**
     * Playback thread. Fills [out] with exactly [QUANTUM_SAMPLES], zero-padded when short.
     * Returns true if any real audio was produced.
     */
    fun fillTick(out: ShortArray): Boolean {
        while (fifo.size < QUANTUM_SAMPLES) {
            val next = synchronized(lock) {
                if (!prebuffered) {
                    // A terminator means no more audio is coming for this spurt, so a short
                    // spurt ("yep", "no") must play rather than wait for a margin it will never
                    // reach. flushShort only ever opens the gate here, never re-arms it, so it
                    // can't reintroduce the earlier bug where the terminator itself stranded a
                    // spurt's tail by clearing `prebuffered` mid-playback.
                    if (queuedSamples < PREBUFFER_SAMPLES && !flushShort) return@synchronized null
                    prebuffered = true
                }
                encoded.removeFirstOrNull()?.also { queuedSamples -= it.spanSamples }
            } ?: break
            val n = decoder().decode(next.opusData, 0, next.opusData.size, decodeOut, QUANTUM_SAMPLES)
            if (n > 0) fifo.push(decodeOut, n)
        }
        val produced = fifo.size > 0
        fifo.drainInto(out, QUANTUM_SAMPLES)
        if (!produced && fifo.size == 0) {
            // Fully drained: re-arm so the next talk spurt rebuilds its playout margin. Doing
            // this on idle rather than on the terminator frame means the tail of a spurt plays
            // out first, and a spurt whose terminator never arrives still re-arms. flushShort
            // resets alongside prebuffered — same drain condition — so it can't leak into the
            // next spurt and bypass its playout margin.
            synchronized(lock) { if (encoded.isEmpty()) { prebuffered = false; flushShort = false } }
        }
        idleTicks = if (produced) 0 else idleTicks + 1
        // Under the lock so it cannot land between offer's gate check and its enqueue: the reader
        // either gets in before retirement or is told to go elsewhere, never neither.
        if (!retired && idleTicks >= RETIRE_IDLE_TICKS) synchronized(lock) { retired = true }
        return produced
    }

    private fun decoder(): OpusDecoder = decoder ?: codec.newDecoder().also { decoder = it }

    fun close() {
        decoder?.close()
        decoder = null
        fifo.clear()
    }
}
