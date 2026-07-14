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

    private val queue = TreeMap<Long, Packet>()
    private var bufferedSpans = 0

    @Volatile var terminatorTimestamp: Long? = null
        private set

    @Synchronized fun offer(p: Packet, playoutCursor: Long): Boolean {
        if (p.isTerminator) terminatorTimestamp = p.timestampSamples
        if (p.opus.isEmpty()) return false                       // terminator / empty → tag only
        if (p.timestampSamples < playoutCursor) return false      // late
        if (queue.containsKey(p.timestampSamples)) return false   // duplicate
        queue[p.timestampSamples] = p
        bufferedSpans += p.spanSamples
        while (bufferedSpans > highWaterSamples && queue.size > 1) {
            val dropped = queue.pollFirstEntry().value
            bufferedSpans -= dropped.spanSamples
        }
        return true
    }

    @Synchronized fun peekFirstTimestamp(): Long? = if (queue.isEmpty()) null else queue.firstKey()

    @Synchronized fun pollFirst(): Packet? {
        val e = queue.pollFirstEntry() ?: return null
        bufferedSpans -= e.value.spanSamples
        return e.value
    }

    @Synchronized fun bufferedSamples(): Int = bufferedSpans

    @Synchronized fun isEmpty(): Boolean = queue.isEmpty()
}
