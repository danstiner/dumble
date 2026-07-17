package me.danielstiner.dumble.mumble.voice

import java.util.TreeMap

/**
 * Per-speaker reorder buffer in the SAMPLE/TIME domain. Codec-free: each packet's span
 * (samples) is precomputed by the caller (OpusCodec.packetSamples). Enqueue on the receive
 * thread, drain on the playback thread — all methods synchronized (short critical sections,
 * byte copies only, no decode/alloc under the lock).
 */
class JitterBuffer(
    private val highWaterSamples: Int = 28800, // ~600 ms
) {
    class Packet(
        val timestampSamples: Long,
        val opus: ByteArray,
        val spanSamples: Int,
        val isTerminator: Boolean,
    )

    /**
     * Why [offer] did not enqueue a packet, so callers can tell a genuine late drop (lost audio)
     * from a harmless [DUPLICATE] (reordered/retransmitted same timestamp) or an [EMPTY] tag-only
     * terminator. Only [LATE] should feed the lateDrops diagnostic.
     */
    enum class OfferResult { QUEUED, LATE, DUPLICATE, EMPTY }

    private val queue = TreeMap<Long, Packet>()
    private var bufferedSpans = 0

    @Volatile var terminatorTimestamp: Long? = null
        private set

    @Synchronized fun offer(p: Packet, playoutCursor: Long): OfferResult {
        if (p.isTerminator && p.timestampSamples >= playoutCursor) {
            val t = terminatorTimestamp
            if (t == null || p.timestampSamples >= t) terminatorTimestamp = p.timestampSamples
        }
        if (p.opus.isEmpty()) return OfferResult.EMPTY               // terminator / empty → tag only
        if (p.timestampSamples < playoutCursor) return OfferResult.LATE
        if (queue.containsKey(p.timestampSamples)) return OfferResult.DUPLICATE
        queue[p.timestampSamples] = p
        bufferedSpans += p.spanSamples
        while (bufferedSpans > highWaterSamples && queue.size > 1) {
            val dropped = queue.pollFirstEntry().value
            bufferedSpans -= dropped.spanSamples
        }
        return OfferResult.QUEUED
    }

    @Synchronized fun peekFirstTimestamp(): Long? = if (queue.isEmpty()) null else queue.firstKey()

    @Synchronized fun pollFirst(): Packet? {
        val e = queue.pollFirstEntry() ?: return null
        bufferedSpans -= e.value.spanSamples
        return e.value
    }

    @Synchronized fun bufferedSamples(): Int = bufferedSpans

    @Synchronized fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized fun clearTerminator() { terminatorTimestamp = null }
}
