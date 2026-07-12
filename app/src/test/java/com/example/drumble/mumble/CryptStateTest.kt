package com.example.drumble.mumble

import com.example.drumble.mumble.net.CryptState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Parses a hex string (no separators) into a ByteArray, e.g. "00ff10" -> [0x00, 0xFF, 0x10]. */
private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class CryptStateTest {
    private lateinit var a: CryptState // "client"
    private lateinit var b: CryptState // "server"

    private val key = ByteArray(16) { it.toByte() }
    private val nonceA = ByteArray(16) { (0x40 + it).toByte() }
    private val nonceB = ByteArray(16) { (0x80 + it).toByte() }

    @Before fun setUp() {
        a = CryptState().apply { setKeys(key, nonceA, nonceB) } // encrypts with nonceA
        b = CryptState().apply { setKeys(key, nonceB, nonceA) } // decrypts A's output
    }

    private fun roundTrip(size: Int) {
        val plain = ByteArray(size) { (it * 7).toByte() }
        val wire = ByteArray(size + CryptState.OVERHEAD)
        val out = ByteArray(size)
        assertEquals(size + CryptState.OVERHEAD, a.encrypt(plain, size, wire))
        assertEquals(size, b.decrypt(wire, wire.size, out))
        assertArrayEquals(plain, out)
    }

    @Test fun roundTripSizes() { intArrayOf(1, 15, 16, 17, 100).forEach { roundTrip(it) } }

    @Test fun tamperRejected() {
        val plain = ByteArray(32) { it.toByte() }
        val wire = ByteArray(36); val out = ByteArray(32)
        a.encrypt(plain, 32, wire)
        wire[10] = (wire[10].toInt() xor 0x01).toByte()
        assertEquals(-1, b.decrypt(wire, 36, out))
        // state restored: a fresh good packet still decrypts
        val wire2 = ByteArray(36)
        a.encrypt(plain, 32, wire2)
        assertEquals(32, b.decrypt(wire2, 36, out))
    }

    @Test fun replayRejectedLateAccepted() {
        val out = ByteArray(8)
        val w1 = ByteArray(12); a.encrypt(ByteArray(8) { 1 }, 8, w1)
        val w2 = ByteArray(12); a.encrypt(ByteArray(8) { 2 }, 8, w2)
        val w3 = ByteArray(12); a.encrypt(ByteArray(8) { 3 }, 8, w3)
        assertEquals(8, b.decrypt(w1, 12, out))
        assertEquals(8, b.decrypt(w3, 12, out))     // w2 skipped -> lost detected
        assertEquals(8, b.decrypt(w2, 12, out))     // late but in window -> accepted
        assertEquals(1, b.stats().late)
        assertEquals(-1, b.decrypt(w2, 12, out))    // replay -> rejected
    }

    @Test fun ivWraparound() {
        val out = ByteArray(4)
        repeat(300) { i ->
            val w = ByteArray(8)
            a.encrypt(byteArrayOf(1, 2, 3, i.toByte()), 4, w)
            assertEquals("packet $i", 4, b.decrypt(w, 8, out))
        }
        assertEquals(300, b.stats().good)
    }

    @Test fun invalidBeforeKeys() {
        val c = CryptState()
        assertFalse(c.isValid())
        assertEquals(-1, c.decrypt(ByteArray(8), 8, ByteArray(4)))
    }

    // ------------------------------------------------------------------
    // Known-answer vectors ported from desktop Mumble's
    // src/tests/TestCrypt/TestCrypt.cpp (BSD-licensed, mumble-voip/mumble).
    // ------------------------------------------------------------------

    /** Ported from TestCrypt::testvectors (draft-krovetz-ocb-00.txt vectors). */
    @Test fun testVectorsFromDraftKrovetzOcb00() {
        val rawkey = hex("000102030405060708090a0b0c0d0e0f")
        val cs = CryptState().apply { setKeys(rawkey, rawkey, rawkey) }

        val blankTag = hex("BF31081307 73AD5EC70EC69E7875A7B0".replace(" ", ""))
        val enc0 = ByteArray(0)
        val tag0 = ByteArray(16)
        assertTrue(cs.ocbEncryptRaw(ByteArray(0), 0, rawkey, enc0, tag0))
        assertArrayEquals(blankTag, tag0)

        val source = ByteArray(40) { it.toByte() }
        val crypt = ByteArray(40)
        val tag = ByteArray(16)
        assertTrue(cs.ocbEncryptRaw(source, 40, rawkey, crypt, tag))

        val longTag = hex("9DB0CDF880F73E3E10D4EB321776 6688".replace(" ", ""))
        val crypted = hex(
            "F75D6BC8B4DC8D66B836A2B08B32A6369F1CD3C5228D79FD6C267F5F6AA7B231C7DFB9D59951AE9C"
        )
        assertArrayEquals(longTag, tag)
        assertArrayEquals(crypted, crypt)
    }

    /** Ported from TestCrypt::authcrypt: round-trips lengths 0..127 and checks enc/dec tags match. */
    @Test fun authCryptRoundTripsAllShortLengths() {
        val rawkey = hex("000102030405060708090a0b0c0d0e0f")
        val nonce = hex("ffeeddccbbaa99887766554433221100")
        val cs = CryptState().apply { setKeys(rawkey, nonce, nonce) }

        for (len in 0 until 128) {
            val src = ByteArray(len) { (it + 1).toByte() }
            val encrypted = ByteArray(len)
            val decrypted = ByteArray(len)
            val encTag = ByteArray(16)
            val decTag = ByteArray(16)

            assertTrue("encrypt len=$len", cs.ocbEncryptRaw(src, len, nonce, encrypted, encTag))
            assertTrue("decrypt len=$len", cs.ocbDecryptRaw(encrypted, len, nonce, decrypted, decTag))

            assertArrayEquals("tag mismatch len=$len", encTag, decTag)
            assertArrayEquals("plaintext mismatch len=$len", src, decrypted)
        }
    }

    /**
     * Ported from TestCrypt::xexstarAttack: prevention of the forgery attack described
     * in section 4.1 of https://eprint.iacr.org/2019/311 ("XEX*" attack against OCB2).
     *
     * The attack requires a 2-block message whose first block is all-zero except for
     * a final byte equal to 128 (the bit-length of a full block) -- i.e. a block that
     * looks exactly like a legitimate OCB2 length-tag block. When such a block appears,
     * an attacker who observes ciphertext block 2 can forge a valid 1-block message
     * (XOR-ing the length-tag bit and recomputing the tag from block2's ciphertext)
     * without knowing the key.
     */
    @Test fun xexStarAttackMitigation() {
        val rawkey = hex("000102030405060708090a0b0c0d0e0f")
        val nonce = hex("ffeeddccbbaa99887766554433221100")
        val cs = CryptState().apply { setKeys(rawkey, nonce, nonce) }

        val src = ByteArray(32)
        src[15] = 128.toByte() // AES_BLOCK_SIZE * 8 bits, i.e. looks like a full-block length tag
        for (i in 16 until 32) src[i] = 42

        val encrypted = ByteArray(32)
        val decrypted = ByteArray(32)
        val encTag = ByteArray(16)
        val decTag = ByteArray(16)

        // With the mitigation disabled (test-only escape hatch), encryption of the
        // crafted plaintext must itself be flagged as dangerous.
        val failedEncrypt = !cs.ocbEncryptRaw(src, 32, nonce, encrypted, encTag, modifyPlainOnXEXStarAttack = false)

        // Perform the forgery: flip the length-tag bit in ciphertext block 1, and
        // derive a forged tag for a 1-block message from the untouched block 2.
        encrypted[15] = (encrypted[15].toInt() xor 128).toByte()
        for (i in 0 until 16) encTag[i] = (src[16 + i].toInt() xor encrypted[16 + i].toInt()).toByte()

        // Attempt to decrypt the forged 1-block message.
        val failedDecrypt = !cs.ocbDecryptRaw(encrypted, 16, nonce, decrypted, decTag)

        // The forged tag would match if the attack weren't caught -- proving this is a
        // real forgery attempt and not just a garbage-in/garbage-out failure.
        assertArrayEquals(encTag, decTag)

        assertTrue("encrypt-side detection must trip when mitigation is disabled", failedEncrypt)
        assertTrue("decrypt-side detection must always catch the forged block", failedDecrypt)

        // With the mitigation enabled (the production default), the round trip must
        // succeed -- the encoder silently perturbs one bit instead of refusing to send.
        assertTrue(cs.ocbEncryptRaw(src, 32, nonce, encrypted, encTag))
        assertTrue(cs.ocbDecryptRaw(encrypted, 32, nonce, decrypted, decTag))
        assertArrayEquals(encTag, decTag)

        // The transmitted content changed by exactly the mitigation bit-flip.
        assertEquals(0, src[0].toInt())
        assertEquals(1, decrypted[0].toInt())
    }

    /** Ported from TestCrypt::tamper: every single-bit flip of a real packet must be rejected. */
    @Test fun tamperEveryBitRejected() {
        val cs = CryptState().apply {
            val rawkey = hex("000102030405060708090a0b0c0d0e0f")
            val nonce = hex("ffeeddccbbaa99887766554433221100")
            setKeys(rawkey, nonce, nonce)
        }

        val msg = "It was a funky funky town!".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val len = msg.size

        val encrypted = ByteArray(len + CryptState.OVERHEAD)
        val decrypted = ByteArray(len)
        cs.encrypt(msg, len, encrypted)

        for (bit in 0 until len * 8) {
            val byteIdx = bit / 8
            val mask = (1 shl (bit % 8))
            encrypted[byteIdx] = (encrypted[byteIdx].toInt() xor mask).toByte()
            assertEquals("bit $bit should be rejected", -1, cs.decrypt(encrypted, encrypted.size, decrypted))
            encrypted[byteIdx] = (encrypted[byteIdx].toInt() xor mask).toByte()
        }
        assertEquals(len, cs.decrypt(encrypted, encrypted.size, decrypted))
        assertArrayEquals(msg, decrypted)
    }
}
