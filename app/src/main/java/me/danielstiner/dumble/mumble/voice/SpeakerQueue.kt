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

    private class Packet(val opus: ByteArray, val spanSamples: Int)

    private val lock = Any()
    private val encoded = ArrayDeque<Packet>()      // guarded by lock
    private var queuedSamples = 0                   // guarded by lock
    private var prebuffered = false                 // guarded by lock

    private val fifo = ShortArrayFifo(MAX_FRAME_SAMPLES * 2)
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    private var decoder: OpusDecoder? = null
    private var idleTicks = 0

    var retired = false
        private set

    /** Test-only view of the locked depth counter. */
    internal val queuedSamplesForTest: Int get() = synchronized(lock) { queuedSamples }

    /** Reader-coroutine context; must not block. An empty [opus] is a tag-only frame and is not enqueued. */
    fun offer(opus: ByteArray) {
        synchronized(lock) {
            if (opus.isEmpty()) return
            val span = codec.packetSamples(opus, 0, opus.size)
            if (span <= 0) return
            encoded.addLast(Packet(opus, span))
            queuedSamples += span
            while (queuedSamples > HIGH_WATER_SAMPLES && encoded.size > 1) {
                queuedSamples -= encoded.removeFirst().spanSamples
            }
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
                    if (queuedSamples < PREBUFFER_SAMPLES) return@synchronized null
                    prebuffered = true
                }
                encoded.removeFirstOrNull()?.also { queuedSamples -= it.spanSamples }
            } ?: break
            val n = decoder().decode(next.opus, 0, next.opus.size, decodeOut, QUANTUM_SAMPLES)
            if (n > 0) fifo.push(decodeOut, n)
        }
        val produced = fifo.size > 0
        fifo.drainInto(out, QUANTUM_SAMPLES)
        if (!produced && fifo.size == 0) {
            // Fully drained: re-arm so the next talk spurt rebuilds its playout margin. Doing
            // this on idle rather than on the terminator frame means the tail of a spurt plays
            // out first, and a spurt whose terminator never arrives still re-arms.
            synchronized(lock) { if (encoded.isEmpty()) prebuffered = false }
        }
        idleTicks = if (produced) 0 else idleTicks + 1
        if (idleTicks >= RETIRE_IDLE_TICKS) retired = true
        return produced
    }

    private fun decoder(): OpusDecoder = decoder ?: codec.newDecoder().also { decoder = it }

    fun close() {
        decoder?.close()
        decoder = null
        fifo.clear()
    }
}
