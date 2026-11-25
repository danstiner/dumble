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
 * Which of the peer's counters we have accepted: the sliding window IPsec uses (RFC 4303 §3.4.3).
 *
 * The top is the highest counter accepted. Bit *k* of [bitmap] is set once `top − k` has been
 * accepted, so bit 0 is the top itself.
 */
internal class ReplayWindow {
    private val top = ByteArray(CryptState.NONCE_LEN)
    private var bitmap = 0L

    /**
     * Restarts the stream at [peerNonce], whether that is ahead of the old top or behind it
     * (docs/mumble-protocol.md, Resync).
     *
     * Everything at or below it is marked accepted, not cleared, so nothing older can be
     * replayed. The seed itself is never a packet: a sender increments before it seals.
     */
    fun restartAt(peerNonce: ByteArray) {
        peerNonce.copyInto(top)
        bitmap = -1L
    }

    /**
     * Writes to [out] the nonce to try for a packet: the counter nearest the top whose low byte
     * is [hint], the only byte sent. That is 128 behind to 127 ahead.
     *
     * False, with [out] untouched, when that counter is refused. There is only one guess, so an
     * attacker cannot force a second decrypt.
     */
    fun rebuildNonce(hint: Int, out: ByteArray): Boolean {
        val offset = offsetFor(hint)
        if (offset <= 0) {
            val behind = -offset
            // Refused: further back than the bitmap records, or its bit says already accepted.
            if (behind > MAX_LATE || (bitmap ushr behind) and 1L != 0L) return false
        }
        addToCounter(top, offset, out)
        return true
    }

    /**
     * Marks [nonce] accepted, once [rebuildNonce] has produced it and its tag has verified.
     * Returns its offset from the old top: positive for a new top, negative for a late packet.
     */
    fun accept(nonce: ByteArray): Int {
        val offset = offsetFor(nonce[0].toInt())
        if (offset > 0) {
            // A jump past MAX_LATE leaves nothing accepted in range, so start empty. The shift
            // cannot do that: Kotlin masks its count to six bits, so `shl 64` shifts by nothing.
            bitmap = if (offset > MAX_LATE) 1L else (bitmap shl offset) or 1L
            nonce.copyInto(top)
        } else {
            bitmap = bitmap or (1L shl -offset)
        }
        return offset
    }

    /** Offset from the top of the nearest counter whose low byte is [hint]: -128 to 127. */
    private fun offsetFor(hint: Int): Int = (hint - top[0]).toByte().toInt()

    companion object {
        /** How far behind the top a packet is still accepted: a Long holds the top and 63 more. */
        private const val MAX_LATE = 63
    }
}
