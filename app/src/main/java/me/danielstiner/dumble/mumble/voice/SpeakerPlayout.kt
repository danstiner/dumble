package me.danielstiner.dumble.mumble.voice

/**
 * One speaker's playout path: encoded packets in, quantum-sized PCM out.
 *
 * [offer] appends still-encoded packets to a locked deque; each [fillTick] pops and decodes
 * just enough of them into a small PCM fifo to emit exactly one quantum. Audio therefore waits
 * compressed for as long as the network requires (capped at [HIGH_WATER_SAMPLES]) and exists
 * as PCM for at most one frame's worth of ticks. A new talk spurt is held until
 * [PREBUFFER_SAMPLES] are queued, so the first network stall doesn't glitch the first syllable.
 *
 * Threading: [offer] runs on the transport's reader coroutine, [fillTick] and [close] on the
 * playback thread. Only the encoded deque crosses that boundary, so it alone is locked; the
 * decoder, the PCM fifo, and the idle counter are playback-thread-only. Decoding happens
 * outside the lock so a slow decode never stalls the reader.
 *
 * Arrival order is correct order: this slice receives audio only through the TCP tunnel, which
 * delivers in order and without loss, so this is a plain FIFO rather than a timestamp-keyed
 * reorder buffer. `frame_number` is deliberately unused here — it becomes load-bearing when UDP
 * lands and reordering becomes possible again.
 */
class SpeakerPlayout(private val codec: OpusCodec) {

    private class Packet(val opusData: ByteArray, val spanSamples: Int)

    private val lock = Any()
    private val encoded = ArrayDeque<Packet>()      // guarded by lock
    private var queuedSamples = 0                   // guarded by lock
    // Opened either by reaching PREBUFFER_SAMPLES or by a terminator, which means no more audio
    // is coming for this spurt so a short "yep"/"no" must play rather than wait for a margin it
    // will never reach. Only the drained path in fillTick ever re-arms it, so a terminator cannot
    // reintroduce the earlier bug where it cleared the gate mid-spurt and stranded the tail.
    private var prebuffered = false                 // guarded by lock

    // fillTick decodes only while below one quantum and one decode adds at most one frame,
    // so this is the fifo's exact occupancy bound.
    private val fifo = ShortArrayFifo(QUANTUM_SAMPLES + MAX_FRAME_SAMPLES)
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    // Eagerly create the decoder so opus_decoder_create and its malloc run on the transport
    // reader thread rather than the playback thread that needs predictable timing.
    private val decoder = codec.newDecoder()
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
     * Takes ownership of [opusData]: it is read later on the playback thread with no
     * synchronization, so the caller must hand over an array it will not touch again.
     *
     * Returns false once this queue has retired, meaning the packet was not accepted and the
     * caller must take a fresh queue. Retirement and the removal from the speaker map are not one
     * step, so without this the caller could enqueue into a queue that is already on its way out
     * and lose the packet silently.
     */
    fun offer(opusData: ByteArray, isTerminator: Boolean): Boolean {
        // Parsed before taking the lock: it is a JNI call reaching NativeOpus, whose class init
        // loads libopus, and the playback thread would otherwise wait out that dlopen.
        val span = if (opusData.isEmpty()) 0 else codec.packetSamples(opusData, 0, opusData.size)
        synchronized(lock) {
            if (retired) return false
            if (span > 0) {
                encoded.addLast(Packet(opusData, span))
                queuedSamples += span
                while (queuedSamples > HIGH_WATER_SAMPLES && encoded.size > 1) {
                    queuedSamples -= encoded.removeFirst().spanSamples
                }
            }
            // Latches rather than playing immediately: offer() runs on the reader thread and must
            // not touch the fifo or the decoder, which are playback-thread-only.
            if (isTerminator) prebuffered = true
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
                    if (queuedSamples < PREBUFFER_SAMPLES) return@synchronized null
                    prebuffered = true
                }
                encoded.removeFirstOrNull()?.also { queuedSamples -= it.spanSamples }
            } ?: break
            val n = decoder.decode(next.opusData, 0, next.opusData.size, decodeOut, QUANTUM_SAMPLES)
            if (n > 0) fifo.push(decodeOut, n)
        }
        val produced = fifo.size > 0
        fifo.drainInto(out, QUANTUM_SAMPLES)
        idleTicks = if (produced) 0 else idleTicks + 1
        // One lock acquisition for both decisions, so they cannot disagree about whether the queue
        // is drained across a reader's offer landing between them.
        synchronized(lock) {
            val drained = encoded.isEmpty()
            // Fully drained: re-arm so the next talk spurt rebuilds its playout margin. Doing this
            // on idle rather than on the terminator frame means the tail of a spurt plays out
            // first, and a spurt whose terminator never arrives still re-arms.
            if (!produced && drained) prebuffered = false
            // Two windows, because "produced nothing this tick" means two different things. Once
            // the queue is drained it means the speaker stopped talking, and that is the case the
            // short window is for. While packets are still queued it means the prebuffer gate has
            // not opened yet — a spurt is silent for its first PREBUFFER_SAMPLES, and the loop
            // ticks it faster than 100 Hz while doing so because each arriving packet wakes the
            // loop, so charging those ticks as idle retires a speaker before it plays anything.
            // Set under the lock so retirement cannot land between offer's gate check and its
            // enqueue: the reader either gets in first or is told to go elsewhere, never neither.
            if (idleTicks >= if (drained) RETIRE_IDLE_TICKS else STALL_IDLE_TICKS) retired = true
        }
        return produced
    }

    /**
     * Playback thread. Terminal: retires the queue so a reader that still holds a reference is
     * told to take a fresh one rather than filling a queue nothing will ever play.
     */
    fun close() {
        synchronized(lock) {
            retired = true
            encoded.clear()
            queuedSamples = 0
        }
        decoder.close()
        fifo.clear()
    }
}
