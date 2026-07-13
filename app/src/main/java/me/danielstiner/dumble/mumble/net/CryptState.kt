package me.danielstiner.dumble.mumble.net

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OCB2-AES128 for Mumble's UDP channel, ported from the BSD-licensed desktop
 * Mumble `CryptStateOCB2` (src/crypto/CryptStateOCB2.cpp @ mumble-voip/mumble).
 * Wire format: [ivLSB][tag0][tag1][tag2][ciphertext].
 * OCB2 has known weaknesses (Inoue et al. 2018, https://eprint.iacr.org/2019/311);
 * required for wire compatibility with the reference server/client.
 * All public methods are synchronized; scratch buffers are fields (zero-alloc
 * in the encrypt/decrypt steady state).
 */
class CryptState {
    companion object {
        const val BLOCK = 16
        const val OVERHEAD = 4
        private const val SHIFTBITS = 7

        /** Reduction constant for GF(2^128) doubling (x^128 = x^7+x^2+x+1). */
        private const val POLY = 0x87
    }

    data class Stats(
        val good: Int, val late: Int, val lost: Int, val resync: Int,
        val remoteGood: Int, val remoteLate: Int, val remoteLost: Int, val remoteResync: Int,
    )

    private var encryptCipher: Cipher? = null
    private var decryptCipher: Cipher? = null
    private val encryptIV = ByteArray(BLOCK)
    private val decryptIV = ByteArray(BLOCK)
    private val decryptHistory = ByteArray(256)

    private var good = 0; private var late = 0; private var lost = 0; private var resync = 0
    private var remoteGood = 0; private var remoteLate = 0; private var remoteLost = 0; private var remoteResync = 0
    @Volatile private var lastGoodNanos = 0L
    @Volatile private var lastRequestNanos = 0L

    // scratch (guarded by synchronized methods)
    private val delta = ByteArray(BLOCK)
    private val tmp = ByteArray(BLOCK)
    private val pad = ByteArray(BLOCK)
    private val checksum = ByteArray(BLOCK)
    private val tag = ByteArray(BLOCK)
    private val saveIV = ByteArray(BLOCK)
    private val blockBuf = ByteArray(BLOCK)

    @Synchronized
    fun isValid(): Boolean = encryptCipher != null

    @Synchronized fun setKeys(key: ByteArray, encryptNonce: ByteArray, decryptNonce: ByteArray) {
        val spec = SecretKeySpec(key.copyOf(BLOCK), "AES")
        encryptCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        decryptCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec) }
        encryptNonce.copyInto(encryptIV, 0, 0, BLOCK)
        decryptNonce.copyInto(decryptIV, 0, 0, BLOCK)
        decryptHistory.fill(0)
    }

    @Synchronized fun setDecryptIV(serverNonce: ByteArray) {
        serverNonce.copyInto(decryptIV, 0, 0, BLOCK); resync++
    }

    @Synchronized fun encryptNonceCopy(): ByteArray = encryptIV.copyOf()

    @Synchronized fun setRemoteStats(g: Int, l: Int, lo: Int, r: Int) {
        remoteGood = g; remoteLate = l; remoteLost = lo; remoteResync = r
    }

    @Synchronized fun stats() =
        Stats(good, late, lost, resync, remoteGood, remoteLate, remoteLost, remoteResync)

    fun lastGoodElapsedNanos() = System.nanoTime() - lastGoodNanos
    fun lastRequestElapsedNanos() = System.nanoTime() - lastRequestNanos
    fun markResyncRequested() { lastRequestNanos = System.nanoTime() }

    /** Encrypts src[0..len) into dst (size >= len+4). Returns len+4. */
    @Synchronized fun encrypt(src: ByteArray, len: Int, dst: ByteArray): Int {
        check(encryptCipher != null) { "setKeys not called" }
        for (i in 0 until BLOCK) { encryptIV[i] = (encryptIV[i] + 1).toByte(); if (encryptIV[i] != 0.toByte()) break }
        // modifyPlainOnXEXStarAttack is always on in production; ocb() only ever
        // returns false here for the (test-only) opposite configuration.
        ocb(encrypt = true, input = src, inOff = 0, len = len, output = dst, outOff = OVERHEAD, nonce = encryptIV)
        dst[0] = encryptIV[0]; dst[1] = tag[0]; dst[2] = tag[1]; dst[3] = tag[2]
        return len + OVERHEAD
    }

    /** Decrypts src[0..len) into dst. Returns plaintext length or -1 on any failure. */
    @Synchronized fun decrypt(src: ByteArray, len: Int, dst: ByteArray): Int {
        // len == OVERHEAD is a legal zero-payload packet (4-byte header, no
        // ciphertext) -- matches desktop Mumble's CryptStateOCB2, which only
        // rejects crypted_length < 4.
        if (decryptCipher == null || len < OVERHEAD) return -1
        val plainLen = len - OVERHEAD
        val ivByte = src[0].toInt() and 0xFF
        var restore = false
        var lateThis = 0; var lostThis = 0
        decryptIV.copyInto(saveIV)
        val iv0 = decryptIV[0].toInt() and 0xFF

        if (((iv0 + 1) and 0xFF) == ivByte) {
            // In order.
            if (ivByte > iv0) decryptIV[0] = ivByte.toByte()
            else if (ivByte < iv0) { // wrapped
                decryptIV[0] = ivByte.toByte()
                for (i in 1 until BLOCK) { decryptIV[i] = (decryptIV[i] + 1).toByte(); if (decryptIV[i] != 0.toByte()) break }
            } else return -1
        } else {
            // Out of order or a repeat.
            var diff = ivByte - iv0
            if (diff > 128) diff -= 256 else if (diff < -128) diff += 256
            when {
                ivByte < iv0 && diff > -30 && diff < 0 -> { // late, no wrap
                    lateThis = 1; lostThis = -1; decryptIV[0] = ivByte.toByte(); restore = true
                }
                ivByte > iv0 && diff > -30 && diff < 0 -> { // late, wrapped (e.g. last was 0x02, this is 0xff)
                    lateThis = 1; lostThis = -1; decryptIV[0] = ivByte.toByte()
                    for (i in 1 until BLOCK) { val o = decryptIV[i]; decryptIV[i] = (o - 1).toByte(); if (o != 0.toByte()) break }
                    restore = true
                }
                ivByte > iv0 && diff > 0 -> { // lost some, no wrap
                    lostThis = ivByte - iv0 - 1; decryptIV[0] = ivByte.toByte()
                }
                ivByte < iv0 && diff > 0 -> { // lost some, wrapped
                    lostThis = 256 - iv0 + ivByte - 1; decryptIV[0] = ivByte.toByte()
                    for (i in 1 until BLOCK) { decryptIV[i] = (decryptIV[i] + 1).toByte(); if (decryptIV[i] != 0.toByte()) break }
                }
                else -> return -1
            }
            if (decryptHistory[decryptIV[0].toInt() and 0xFF] == decryptIV[1]) { // replay
                saveIV.copyInto(decryptIV); return -1
            }
        }

        val ok = ocb(encrypt = false, input = src, inOff = OVERHEAD, len = plainLen, output = dst, outOff = 0, nonce = decryptIV)
        if (!ok || tag[0] != src[1] || tag[1] != src[2] || tag[2] != src[3]) {
            saveIV.copyInto(decryptIV); return -1
        }
        decryptHistory[decryptIV[0].toInt() and 0xFF] = decryptIV[1]
        if (restore) saveIV.copyInto(decryptIV)

        good++
        late += lateThis // lateThis is always 0 or 1
        // lost += lostThis, but guarded against driving the unsigned-in-spirit
        // counter negative when lostThis == -1 (a "late" packet un-counts a loss).
        if (lostThis > 0) lost += lostThis
        else if (lost > -lostThis) lost -= -lostThis
        lastGoodNanos = System.nanoTime()
        return plainLen
    }

    // ---- test-only raw OCB entry points (bypass the IV window/replay logic) ----

    /** Test-only: drives [ocb] directly with an explicit nonce, bypassing IV auto-increment. */
    internal fun ocbEncryptRaw(
        plain: ByteArray, len: Int, nonce: ByteArray,
        cipherOut: ByteArray, tagOut: ByteArray,
        modifyPlainOnXEXStarAttack: Boolean = true,
    ): Boolean = synchronized(this) {
        val ok = ocb(
            encrypt = true, input = plain, inOff = 0, len = len, output = cipherOut, outOff = 0,
            nonce = nonce, modifyPlainOnXEXStarAttack = modifyPlainOnXEXStarAttack,
        )
        tag.copyInto(tagOut)
        ok
    }

    /** Test-only: drives [ocb] directly with an explicit nonce, bypassing IV window/replay logic. */
    internal fun ocbDecryptRaw(
        encrypted: ByteArray, len: Int, nonce: ByteArray,
        plainOut: ByteArray, tagOut: ByteArray,
    ): Boolean = synchronized(this) {
        val ok = ocb(encrypt = false, input = encrypted, inOff = 0, len = len, output = plainOut, outOff = 0, nonce = nonce)
        tag.copyInto(tagOut)
        ok
    }

    /**
     * OCB2 core. Leaves the 16-byte auth tag in [tag]. Returns false when the
     * XEX* forgery-attack mitigation (see below) detects an attempted attack —
     * on the encrypt side this only happens when [modifyPlainOnXEXStarAttack]
     * is disabled (test-only); on the decrypt side it is always active.
     *
     * Ported from CryptStateOCB2::ocb_encrypt / ocb_decrypt.
     */
    private fun ocb(
        encrypt: Boolean, input: ByteArray, inOff: Int, len: Int, output: ByteArray, outOff: Int,
        nonce: ByteArray, modifyPlainOnXEXStarAttack: Boolean = true,
    ): Boolean {
        val enc = encryptCipher!!; val dec = decryptCipher!!
        var success = true
        enc.doFinal(nonce, 0, BLOCK, delta, 0)
        checksum.fill(0)
        var remaining = len; var io = inOff; var oo = outOff
        while (remaining > BLOCK) {
            s2(delta)
            if (encrypt) {
                // Counter-cryptanalysis, https://eprint.iacr.org/2019/311 section 9:
                // for an attack, the second-to-last block (i.e. the last iteration of
                // this loop) must be all zero except possibly the last byte.
                var flipABit = false
                if (remaining - BLOCK <= BLOCK) {
                    var sum = 0
                    for (i in 0 until BLOCK - 1) sum = sum or (input[io + i].toInt() and 0xFF)
                    if (sum == 0) {
                        if (modifyPlainOnXEXStarAttack) flipABit = true else success = false
                    }
                }
                for (i in 0 until BLOCK) { blockBuf[i] = input[io + i]; checksum[i] = (checksum[i].toInt() xor blockBuf[i].toInt()).toByte() }
                if (flipABit) checksum[0] = (checksum[0].toInt() xor 1).toByte()
                for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor blockBuf[i].toInt()).toByte()
                if (flipABit) tmp[0] = (tmp[0].toInt() xor 1).toByte()
                enc.doFinal(tmp, 0, BLOCK, tmp, 0)
                for (i in 0 until BLOCK) output[oo + i] = (delta[i].toInt() xor tmp[i].toInt()).toByte()
            } else {
                for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor input[io + i].toInt()).toByte()
                dec.doFinal(tmp, 0, BLOCK, tmp, 0)
                for (i in 0 until BLOCK) {
                    val p = (delta[i].toInt() xor tmp[i].toInt()).toByte()
                    output[oo + i] = p; checksum[i] = (checksum[i].toInt() xor p.toInt()).toByte()
                }
            }
            remaining -= BLOCK; io += BLOCK; oo += BLOCK
        }
        // Final (possibly full) block.
        s2(delta)
        tmp.fill(0)
        val numBits = remaining * 8
        tmp[BLOCK - 2] = ((numBits shr 8) and 0xFF).toByte()
        tmp[BLOCK - 1] = (numBits and 0xFF).toByte()
        for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor delta[i].toInt()).toByte()
        enc.doFinal(tmp, 0, BLOCK, pad, 0)
        tmp.fill(0)
        if (encrypt) {
            input.copyInto(tmp, 0, io, io + remaining)
            pad.copyInto(tmp, remaining, remaining, BLOCK)
            for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor tmp[i].toInt()).toByte()
            for (i in 0 until BLOCK) tmp[i] = (pad[i].toInt() xor tmp[i].toInt()).toByte()
            tmp.copyInto(output, oo, 0, remaining)
        } else {
            input.copyInto(tmp, 0, io, io + remaining)
            for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor pad[i].toInt()).toByte()
            for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor tmp[i].toInt()).toByte()
            tmp.copyInto(output, oo, 0, remaining)

            // Counter-cryptanalysis (decrypt side, always active): in an attack the
            // decrypted last block would equal `delta ^ len(128)`. We check `tmp`
            // (== reconstructed last block before the length-tag XOR is stripped)
            // against `delta` over all but the last byte, since only the last byte
            // ever varies with `len`.
            var eq = true
            for (i in 0 until BLOCK - 1) if (tmp[i] != delta[i]) { eq = false; break }
            if (eq) success = false
        }

        s3(delta)
        for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor checksum[i].toInt()).toByte()
        enc.doFinal(tmp, 0, BLOCK, tag, 0)

        return success
    }

    private fun s2(block: ByteArray) {
        val carry = (block[0].toInt() shr SHIFTBITS) and 0x1
        for (i in 0 until BLOCK - 1)
            block[i] = (((block[i].toInt() shl 1) or ((block[i + 1].toInt() shr SHIFTBITS) and 0x1)) and 0xFF).toByte()
        block[BLOCK - 1] = (((block[BLOCK - 1].toInt() shl 1) xor (carry * POLY)) and 0xFF).toByte()
    }

    private fun s3(block: ByteArray) {
        val carry = (block[0].toInt() shr SHIFTBITS) and 0x1
        for (i in 0 until BLOCK - 1)
            block[i] = ((block[i].toInt() xor ((block[i].toInt() shl 1) or ((block[i + 1].toInt() shr SHIFTBITS) and 0x1))) and 0xFF).toByte()
        block[BLOCK - 1] = ((block[BLOCK - 1].toInt() xor ((block[BLOCK - 1].toInt() shl 1) xor (carry * POLY))) and 0xFF).toByte()
    }
}
