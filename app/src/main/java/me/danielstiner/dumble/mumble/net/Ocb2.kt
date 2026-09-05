package me.danielstiner.dumble.mumble.net

import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OCB2-AES128 (Rogaway, ASIACRYPT 2004) with empty associated data, plus the two countermeasures
 * against the XEX* forgery of eprint 2019/311 that peers expect on the wire.
 *
 * Working buffers are instance fields, so a steady-state [seal]/[open] allocates nothing. An
 * instance is therefore not thread-safe and cannot serve two calls at once.
 *
 * The caller's plaintext is never written to, so the countermeasure in [seal] flips its bit in a
 * scratch block instead. The flip is not hidden: [open] recovers the flipped byte.
 */
class Ocb2(key: ByteArray) {
    init {
        require(key.size == BLOCK) { "OCB2-AES128 needs a 16-byte key, got ${key.size}" }
    }

    private val encryptor = cipher(Cipher.ENCRYPT_MODE, key)
    private val decryptor = cipher(Cipher.DECRYPT_MODE, key)

    private val delta = ByteArray(BLOCK)
    private val checksum = ByteArray(BLOCK)
    private val scratch = ByteArray(BLOCK)
    private val pad = ByteArray(BLOCK)

    /**
     * Encrypts [len] bytes at [input]/[inOff] into [output]/[outOff] and writes the 16-byte tag to
     * [tagOut]. In-place use (same array, same offset) is safe.
     *
     * Returns false only when the last full block is one the XEX* forgery can exploit and
     * [modifyPlainOnXEXStarAttack] is false. **A false return does not mean nothing was written:**
     * [output] and [tagOut] are filled either way, and in that case they hold exactly the
     * ciphertext the forgery of eprint 2019/311 wants. A caller passing the flag as false must
     * discard both rather than treat false as "no output".
     *
     * With the flag set, which is production, the exploitable block has one bit of its first byte
     * flipped and the result is true. The flip lands only in this class's scratch, so [input] is
     * untouched and the sender is never told its frame will decrypt one bit different at the peer.
     * That is deliberate -- nothing here re-reads a sent frame -- but it is why the flag's name
     * describes upstream's behaviour rather than this one's.
     */
    fun seal(
        nonce: ByteArray,
        input: ByteArray,
        inOff: Int,
        len: Int,
        output: ByteArray,
        outOff: Int,
        tagOut: ByteArray,
        modifyPlainOnXEXStarAttack: Boolean = true,
    ): Boolean {
        begin(nonce, input, inOff, len, output, outOff, tagOut)
        var safe = true

        val fullBlocks = fullBlockCount(len)
        var from = inOff
        var to = outOff
        for (i in 0 until fullBlocks) {
            double(delta)
            System.arraycopy(input, from, scratch, 0, BLOCK)
            if (i == fullBlocks - 1 && isExploitable(scratch)) {
                if (modifyPlainOnXEXStarAttack) {
                    scratch[0] = (scratch[0].toInt() xor 1).toByte()
                } else {
                    safe = false
                }
            }
            xorInto(checksum, scratch)
            xorInto(scratch, delta)
            encryptor.doFinal(scratch, 0, BLOCK, pad, 0)
            for (j in 0 until BLOCK) output[to + j] = (pad[j].toInt() xor delta[j].toInt()).toByte()
            from += BLOCK
            to += BLOCK
        }

        val tail = len - fullBlocks * BLOCK
        finalPad(tail)
        for (j in 0 until BLOCK) {
            val cipherByte =
                if (j < tail) (input[from + j].toInt() xor pad[j].toInt()).toByte() else 0
            if (j < tail) output[to + j] = cipherByte
            checksum[j] = (checksum[j].toInt() xor cipherByte.toInt() xor pad[j].toInt()).toByte()
        }

        tag(tagOut)
        return safe
    }

    /**
     * Decrypts [len] bytes and writes the recomputed 16-byte tag to [tagOut] for the caller to
     * compare. In-place use (same array, same offset) is safe.
     *
     * **A true return is not authentication.** Nothing here verifies the tag: [output] is filled
     * with unverified plaintext before this returns, and forged input yields forged plaintext.
     * The caller MUST compare [tagOut] against the tag on the wire and discard [output] unless it
     * matches. False adds one thing that comparison cannot catch on its own -- the final block
     * decrypting to the shape the XEX* forgery produces -- and is to be treated exactly like a
     * tag mismatch, not as the only check.
     */
    fun open(
        nonce: ByteArray,
        input: ByteArray,
        inOff: Int,
        len: Int,
        output: ByteArray,
        outOff: Int,
        tagOut: ByteArray,
    ): Boolean {
        begin(nonce, input, inOff, len, output, outOff, tagOut)

        val fullBlocks = fullBlockCount(len)
        var from = inOff
        var to = outOff
        repeat(fullBlocks) {
            double(delta)
            for (j in 0 until BLOCK) {
                scratch[j] = (input[from + j].toInt() xor delta[j].toInt()).toByte()
            }
            decryptor.doFinal(scratch, 0, BLOCK, pad, 0)
            for (j in 0 until BLOCK) {
                val plain = (pad[j].toInt() xor delta[j].toInt()).toByte()
                output[to + j] = plain
                checksum[j] = (checksum[j].toInt() xor plain.toInt()).toByte()
            }
            from += BLOCK
            to += BLOCK
        }

        val tail = len - fullBlocks * BLOCK
        finalPad(tail)
        // Compare only the first 15 bytes: the last one moves with the length encoding.
        var forged = true
        for (j in 0 until BLOCK) {
            val cipherByte = if (j < tail) input[from + j] else 0
            val accumulated = (cipherByte.toInt() xor pad[j].toInt()).toByte()
            if (j < tail) output[to + j] = accumulated
            checksum[j] = (checksum[j].toInt() xor accumulated.toInt()).toByte()
            if (j < BLOCK - 1 && accumulated != delta[j]) forged = false
        }

        tag(tagOut)
        return !forged
    }

    /**
     * Every precondition, checked before the first cipher call so a caller bug cannot leave a
     * half-enciphered output behind. These are argument errors, not data conditions: a peer's bytes
     * never reach here as anything but [input] contents.
     */
    private fun begin(
        nonce: ByteArray,
        input: ByteArray,
        inOff: Int,
        len: Int,
        output: ByteArray,
        outOff: Int,
        tagOut: ByteArray,
    ) {
        // Exactly 16, not "at least": a longer nonce would be silently truncated to its first
        // 16 bytes, so two nonces sharing a prefix would reuse an offset without any complaint.
        require(nonce.size == BLOCK) { "nonce must be exactly 16 bytes, got ${nonce.size}" }
        require(tagOut.size >= BLOCK) { "tag buffer must hold 16 bytes, got ${tagOut.size}" }
        require(len >= 0) { "negative length $len" }
        require(inOff >= 0) { "negative input offset $inOff" }
        require(outOff >= 0) { "negative output offset $outOff" }
        // Subtract rather than add: inOff + len can overflow, input.size - inOff cannot.
        require(len <= input.size - inOff) {
            "input range [$inOff, ${inOff.toLong() + len}) exceeds ${input.size} bytes"
        }
        require(len <= output.size - outOff) {
            "output range [$outOff, ${outOff.toLong() + len}) exceeds ${output.size} bytes"
        }
        // Sharing one array is safe only while writes trail reads. Both loops take a whole block
        // out of input before storing it, and the tail reads input[j] before writing output[j], so
        // an output at or below the input offset never lands on a byte still to be read. Above it
        // does, one block ahead, and corrupts silently.
        require(input !== output || outOff <= inOff) {
            "in-place output must not start after the input, got in=$inOff out=$outOff"
        }
        // The tag is written last and would otherwise land on top of finished ciphertext.
        // Also not the nonce: it is consumed into delta up front and the tag is written last, so
        // aliasing them corrupts the caller's counter silently -- correct output, lost nonce.
        require(tagOut !== output && tagOut !== input && tagOut !== nonce) {
            "tag buffer must be its own array"
        }

        encryptor.doFinal(nonce, 0, BLOCK, delta, 0)
        checksum.fill(0)
    }

    /** Delta = 2*Delta; pad = E(bit length of the final block, xor Delta). */
    private fun finalPad(tail: Int) {
        double(delta)
        scratch.fill(0)
        val bits = tail * 8
        scratch[BLOCK - 2] = (bits ushr 8).toByte()
        scratch[BLOCK - 1] = bits.toByte()
        xorInto(scratch, delta)
        encryptor.doFinal(scratch, 0, BLOCK, pad, 0)
    }

    /** tag = E(checksum xor 3*Delta). */
    private fun tag(tagOut: ByteArray) {
        System.arraycopy(delta, 0, scratch, 0, BLOCK)
        double(scratch)
        xorInto(scratch, delta)
        xorInto(scratch, checksum)
        encryptor.doFinal(scratch, 0, BLOCK, tagOut, 0)
    }

    private companion object {
        const val BLOCK = 16

        // OCB2 is defined over the raw block cipher, so ECB is the mode the construction calls for.
        @SuppressLint("GetInstance")
        fun cipher(mode: Int, key: ByteArray): Cipher =
            Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"))
            }

        /** A zero-length message is a single empty final block, so it has no full blocks. */
        fun fullBlockCount(len: Int) = if (len == 0) 0 else (len - 1) / BLOCK

        fun isExploitable(block: ByteArray): Boolean {
            for (j in 0 until BLOCK - 1) if (block[j].toInt() != 0) return false
            return true
        }

        fun xorInto(dst: ByteArray, src: ByteArray) {
            for (j in 0 until BLOCK) dst[j] = (dst[j].toInt() xor src[j].toInt()).toByte()
        }

        /** Shift left one bit in GF(2^128), reducing by x^128 = x^7 + x^2 + x + 1. */
        fun double(x: ByteArray) {
            val carry = (x[0].toInt() ushr 7) and 1
            for (j in 0 until BLOCK - 1) {
                x[j] = ((x[j].toInt() shl 1) or ((x[j + 1].toInt() ushr 7) and 1)).toByte()
            }
            x[BLOCK - 1] = ((x[BLOCK - 1].toInt() shl 1) xor if (carry == 1) 0x87 else 0).toByte()
        }
    }
}
