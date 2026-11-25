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
 * This is the sliding-window form IPsec uses (RFC 4303 §3.4.3).
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
     * Rebuilds the counter a packet carrying [hint] must have been sealed under and writes it to
     * [out] as the nonce to try. False, with [out] untouched, when that counter has been accepted
     * already, or sits further behind the top than the bitmap records.
     *
     * [hint] is the counter's low byte, the only part of it sent on the wire. It is read as the
     * counter nearest the top that ends in that byte, so up to 127 ahead -- one guess, never a
     * second decrypt attempt for an attacker to force. Anything further ahead rebuilds to the
     * wrong counter and fails the tag or lands behind and is refused here.
     */
    fun rebuildNonce(hint: Int, out: ByteArray): Boolean {
        val offset = offsetFor(hint)
        // Bit k is the counter k below the top, so a counter at or behind the top is bit -offset.
        if (offset <= 0 && (offset < -MAX_LATE || (bitmap ushr -offset) and 1L != 0L)) return false
        addToCounter(top, offset, out)
        return true
    }

    /**
     * Records [nonce], the counter [rebuildNonce] produced, as accepted now that its tag has
     * verified, and returns its offset from the top: positive when it becomes the new top,
     * negative when it arrived late. Moving the top shifts the bitmap by the same amount; a jump
     * past [MAX_LATE] leaves nothing live to keep, since every slot below the new top is then
     * above the old one.
     */
    fun accept(nonce: ByteArray): Int {
        val offset = offsetFor(nonce[0].toInt())
        if (offset > 0) {
            // Not only a shortcut: Kotlin masks a Long shift to six bits, so a jump of 64 would
            // leave the bitmap unshifted and every slot below the new top spent.
            bitmap = if (offset > MAX_LATE) 1L else (bitmap shl offset) or 1L
            nonce.copyInto(top)
        } else {
            bitmap = bitmap or (1L shl -offset)
        }
        return offset
    }

    /** The difference of the low bytes as a signed byte: -128 to 127, the nearest such counter. */
    private fun offsetFor(hint: Int): Int = (hint - top[0]).toByte().toInt()

    companion object {
        /** How far behind the top a packet may arrive and still be accepted. Upstream allows 29. */
        private const val MAX_LATE = 32
    }
}
