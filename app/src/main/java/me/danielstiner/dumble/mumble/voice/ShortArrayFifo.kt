package me.danielstiner.dumble.mumble.voice

/** Growable short FIFO. Playback-thread only — no synchronization. */
class ShortArrayFifo(initialCapacity: Int) {
    private var buf = ShortArray(initialCapacity)
    private var head = 0
    var size = 0
        private set

    fun push(src: ShortArray, n: Int) {
        ensure(size + n)
        System.arraycopy(src, 0, buf, head + size, n)
        size += n
    }

    /** Copies min(size, count) into dst[0..count), zero-padding the remainder. Advances head. */
    fun drainInto(dst: ShortArray, count: Int) {
        val take = minOf(size, count)
        System.arraycopy(buf, head, dst, 0, take)
        java.util.Arrays.fill(dst, take, count, 0)
        head += take
        size -= take
        if (size == 0) head = 0
    }

    fun clear() { head = 0; size = 0 }

    private fun ensure(needed: Int) {
        if (head + needed <= buf.size) return
        val compact = ShortArray(maxOf(buf.size * 2, needed))
        System.arraycopy(buf, head, compact, 0, size)
        buf = compact
        head = 0
    }
}
