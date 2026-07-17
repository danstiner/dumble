package me.danielstiner.dumble.mumble.voice

/**
 * Fixed lookahead-delay ring for onset recovery. The gate `open` boolean is computed on LIVE captures;
 * the TX path is delayed by [k] captures, so when the gate opens the pre-onset captures still in the
 * ring are transmitted (jitter-safe: in-order, contiguous frame numbers, constant k-capture shift).
 * k=0 is the identity (no buffering). Single-thread (send thread).
 */
class LookaheadDelay(val k: Int) {
    class Emit(val pcm: ShortArray, val send: Boolean, val frameNumber: Long)
    private class Slot(val pcm: ShortArray, val open: Boolean, val frameNumber: Long)

    private val ring = ArrayDeque<Slot>()

    /** Offer the live capture; returns the capture to emit now (or null while still filling at k>0). */
    fun offer(pcm: ShortArray, open: Boolean, frameNumber: Long): Emit? {
        if (k == 0) return Emit(pcm, open, frameNumber)
        ring.addLast(Slot(pcm.copyOf(), open, frameNumber))
        if (ring.size <= k) return null
        val head = ring.removeFirst()
        val send = head.open || ring.any { it.open }
        return Emit(head.pcm, send, head.frameNumber)
    }

    /** Drain everything still buffered, oldest-first (mute / mode-change / stop). */
    fun flush(): List<Emit> {
        val out = ring.map { Emit(it.pcm, it.open || ring.any { s -> s.open }, it.frameNumber) }
        ring.clear()
        return out
    }
}
