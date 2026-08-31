package me.danielstiner.dumble.mumble.net

/**
 * A little-endian 128-bit counter held as two unsigned 64-bit halves, so advancing it and offsetting
 * from it stay register operations; only the hand-off to the cipher needs bytes.
 *
 * Mutable and reused in place. These sit on the voice path at ~50 packets/s each way, where a
 * per-packet allocation is jitter rather than a slow benchmark.
 */
internal class U128 {
    var low = 0L
        private set
    var high = 0L
        private set

    /** The byte that travels on the wire. */
    val lowByte: Int get() = low.toInt() and 0xFF

    fun readFrom(bytes: ByteArray) {
        low = readHalf(bytes, 0)
        high = readHalf(bytes, 8)
    }

    fun writeTo(bytes: ByteArray) {
        writeHalf(bytes, 0, low)
        writeHalf(bytes, 8, high)
    }

    fun increment() {
        low++
        if (low == 0L) high++
    }

    /** Writes `this + offset` into [out], carrying or borrowing across the halves. */
    fun writeOffsetTo(offset: Int, out: ByteArray) {
        val lo = low + offset
        writeHalf(out, 0, lo)
        writeHalf(out, 8, carriedHigh(lo, offset))
    }

    /** Advances by a signed [offset] in place. */
    fun advanceBy(offset: Int) {
        val lo = low + offset
        high = carriedHigh(lo, offset)
        low = lo
    }

    /** The high half for a low half that has become [lo] by adding [offset]. Reads the old [low]. */
    private fun carriedHigh(lo: Long, offset: Int): Long = when {
        offset > 0 && lo.toULong() < low.toULong() -> high + 1
        offset < 0 && lo.toULong() > low.toULong() -> high - 1
        else -> high
    }

    private companion object {
        fun readHalf(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        fun writeHalf(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
        }
    }
}

/**
 * Which of the peer's counters we have already accepted, and where the stream has reached.
 *
 * The highest counter accepted is the top; [bitmap] records the [MAX_LATE] below it, bit *k*
 * meaning "top − k accepted". Bit 0 is the top itself, so it is set whenever anything has been.
 * This is the sliding-window form IPsec uses (RFC 6479) and SRTP requires at least 64 of
 * (RFC 3711 §3.3.2), at the size one Long holds -- the hint itself would allow any split of its
 * 256 values.
 *
 * The top and the bitmap are one object rather than two because they move together: advancing the
 * top shifts the bitmap by the same amount.
 */
internal class ReplayWindow {
    private val top = U128()
    private var bitmap = 0L

    /**
     * Restarts the stream at [peerNonce] with everything at or below it consumed, wherever that
     * falls relative to where we had reached. Adoption is unconditional, like upstream: an honest
     * reply is never behind us, and a forged-ahead top is exactly what adopting heals
     * (docs/mumble-protocol.md, Resync).
     *
     * Consumed rather than cleared, so nothing the restart reaches can recur and the seed itself
     * can never be a packet counter: a sender increments before it seals, so its first packet is
     * seed + 1. Genuinely late packets from just before a resync drop with the rest of the
     * window, the cheap half of the trade.
     */
    fun restartAt(peerNonce: ByteArray) {
        top.readFrom(peerNonce)
        bitmap = -1L
    }

    /**
     * Where the sender's counter sits relative to the top, from its low byte alone. The 256 values
     * that byte can take are split [MAX_LATE] behind and the rest ahead, so offsets run `top-63` to
     * `top+192` -- one guess covering both loss and reordering, never a second decrypt attempt for
     * an attacker to force. Anything further out rebuilds to the wrong counter and fails the tag.
     */
    fun offsetFor(hint: Int): Int {
        val step = (hint - top.lowByte) and 0xFF
        return if (step > 0xFF - MAX_LATE) step - 0x100 else step
    }

    /** Whether the counter at [offset] has been accepted already. Costs no cipher work. */
    fun alreadyAccepted(offset: Int): Boolean =
        offset <= 0 && (bitmap ushr -offset) and 1L != 0L

    /** Writes the counter at [offset] into [out] as the nonce to try. */
    fun nonceAt(offset: Int, out: ByteArray) = top.writeOffsetTo(offset, out)

    /**
     * Records the counter at [offset] as accepted. A forward offset carries the top with it and
     * shifts the bitmap by the same amount; a jump of a whole window leaves nothing live to keep,
     * since `newTop − 63` is then past the old top.
     *
     * Independent of [nonceAt]; the two are correct in any order.
     */
    fun accept(offset: Int) {
        if (offset > 0) {
            // Kotlin masks a Long shift to six bits, so a jump of exactly the window size would
            // otherwise leave the bitmap unshifted and mark all 63 slots below the new top spent.
            bitmap = if (offset >= WINDOW) 1L else (bitmap shl offset) or 1L
            top.advanceBy(offset)
        } else {
            bitmap = bitmap or (1L shl -offset)
        }
    }

    companion object {
        /** Bits in the bitmap: the top plus the [MAX_LATE] counters below it. */
        private const val WINDOW = 64

        /** How far behind the top a packet may arrive and still be accepted. */
        private const val MAX_LATE = WINDOW - 1
    }
}
