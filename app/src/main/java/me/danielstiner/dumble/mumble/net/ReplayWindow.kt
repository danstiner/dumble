package me.danielstiner.dumble.mumble.net

/**
 * Writes `counter + offset` into [out], which may be [counter] itself. A counter is a little-endian
 * 16-byte block, the form the cipher takes its nonce in, so nothing converts on the way there.
 */
internal fun addToCounter(counter: ByteArray, offset: Int, out: ByteArray) {
    var carry = offset
    for (i in counter.indices) {
        carry += counter[i].toInt() and 0xFF
        out[i] = carry.toByte()
        // An arithmetic shift, so a borrow rides up as -1 the way a carry does as 1.
        carry = carry shr 8
    }
}

/**
 * Which of the peer's counters we have already accepted, and where the stream has reached.
 *
 * The highest counter accepted is the top; [bitmap] records the [MAX_LATE] below it, bit *k*
 * meaning "top − k accepted". Bit 0 is the top itself, so it is set whenever anything has been.
 * This is the sliding-window form IPsec uses (RFC 4303 §3.4.3) and SRTP requires at least 64 of
 * (RFC 3711 §3.3.2), at the size one Long holds -- the hint itself would allow any split of its
 * 256 values.
 *
 * The top and the bitmap are one object rather than two because they move together: advancing the
 * top shifts the bitmap by the same amount.
 */
internal class ReplayWindow {
    private val top = ByteArray(CryptState.NONCE_LEN)
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
        peerNonce.copyInto(top)
        bitmap = -1L
    }

    /**
     * Writes the counter a packet carrying [hint] must have been sealed under into [out], as the
     * nonce to try. False, with no cipher work spent, when that counter has been accepted already.
     *
     * [hint] is the counter's low byte, all that travels. Its 256 values are split [MAX_LATE]
     * behind the top and the rest ahead, so candidates run `top-63` to `top+192` -- one guess
     * covering both loss and reordering, never a second decrypt attempt for an attacker to force.
     * Anything further out rebuilds to the wrong counter and fails the tag.
     */
    fun nonceFor(hint: Int, out: ByteArray): Boolean {
        val offset = offsetFor(hint)
        if (offset <= 0 && (bitmap ushr -offset) and 1L != 0L) return false
        addToCounter(top, offset, out)
        return true
    }

    /**
     * Records the counter [hint] names as accepted, once its tag has verified, and returns how far
     * it sat from the top: positive when it moved the top, otherwise how late it was. A forward
     * step shifts the bitmap by the same amount; a jump of a whole window leaves nothing live to
     * keep, since `newTop − 63` is then past the old top.
     */
    fun accept(hint: Int): Int {
        val offset = offsetFor(hint)
        if (offset > 0) {
            // Kotlin masks a Long shift to six bits, so a jump of exactly the window size would
            // otherwise leave the bitmap unshifted and mark all 63 slots below the new top spent.
            bitmap = if (offset >= WINDOW) 1L else (bitmap shl offset) or 1L
            addToCounter(top, offset, top)
        } else {
            bitmap = bitmap or (1L shl -offset)
        }
        return offset
    }

    private fun offsetFor(hint: Int): Int {
        val step = (hint - top[0]) and 0xFF
        return if (step > 0xFF - MAX_LATE) step - 0x100 else step
    }

    companion object {
        /** Bits in the bitmap: the top plus the [MAX_LATE] counters below it. */
        private const val WINDOW = 64

        /** How far behind the top a packet may arrive and still be accepted. */
        private const val MAX_LATE = WINDOW - 1
    }
}
