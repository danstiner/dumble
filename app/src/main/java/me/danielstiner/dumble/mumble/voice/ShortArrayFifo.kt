package me.danielstiner.dumble.mumble.voice

/**
 * Fixed-capacity short ring. Playback-thread only — no synchronization.
 *
 * A ring rather than a growable buffer so the audio thread's cost per tick is constant by
 * construction — no compaction or reallocation to reason about on a deadline. Capacity rounds
 * up to a power of two so the wrap is a mask. Pushing past capacity throws: the caller's
 * decode loop bounds live data, so overflow is a broken caller, not backpressure to absorb.
 */
class ShortArrayFifo(minCapacity: Int) {
    private val buf: ShortArray
    private val mask: Int
    private var head = 0
    var size = 0
        private set

    init {
        var cap = 1
        while (cap < minCapacity) cap = cap shl 1
        buf = ShortArray(cap)
        mask = cap - 1
    }

    fun push(src: ShortArray, n: Int) {
        require(size + n <= buf.size) { "fifo overflow: $size + $n exceeds ${buf.size}" }
        val tail = (head + size) and mask
        val first = minOf(n, buf.size - tail)
        System.arraycopy(src, 0, buf, tail, first)
        System.arraycopy(src, first, buf, 0, n - first)
        size += n
    }

    /** Copies min(size, count) into dst[0..count), zero-padding the remainder. Advances head. */
    fun drainInto(dst: ShortArray, count: Int) {
        val take = minOf(size, count)
        val first = minOf(take, buf.size - head)
        System.arraycopy(buf, head, dst, 0, first)
        System.arraycopy(buf, 0, dst, first, take - first)
        java.util.Arrays.fill(dst, take, count, 0)
        head = (head + take) and mask
        size -= take
    }

    fun clear() { head = 0; size = 0 }
}
