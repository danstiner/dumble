package me.danielstiner.dumble.mumble.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Published vectors are draft-krovetz-ocb-00, OCB2-AES128 with empty associated data. */
class Ocb2Test {
    private val key = ByteArray(16) { it.toByte() }
    private val nonce = ByteArray(16) { it.toByte() }
    private val ocb2 = Ocb2(key)

    @Test
    fun emptyMessageMatchesPublishedVector() {
        val tag = ByteArray(16)
        assertTrue(ocb2.seal(nonce, ByteArray(0), 0, 0, ByteArray(0), 0, tag))
        assertEquals("BF3108130773AD5EC70EC69E7875A7B0", hex(tag))
    }

    @Test
    fun fortyByteMessageMatchesPublishedVector() {
        val plain = ByteArray(40) { it.toByte() }
        val cipher = ByteArray(40)
        val tag = ByteArray(16)
        assertTrue(ocb2.seal(nonce, plain, 0, plain.size, cipher, 0, tag))
        assertEquals(
            "F75D6BC8B4DC8D66B836A2B08B32A6369F1CD3C5228D79FD6C267F5F6AA7B231C7DFB9D59951AE9C",
            hex(cipher),
        )
        assertEquals("9DB0CDF880F73E3E10D4EB3217766688", hex(tag))
    }

    @Test
    fun roundTripsEveryLengthUnder128() {
        val sealTag = ByteArray(16)
        val openTag = ByteArray(16)
        for (len in 0 until 128) {
            // Never all-zero, so the XEX* countermeasure stays out of the way.
            val plain = ByteArray(len) { (0x80 + it * 7).toByte() }
            val cipher = ByteArray(len)
            val recovered = ByteArray(len)
            assertTrue("len=$len", ocb2.seal(nonce, plain, 0, len, cipher, 0, sealTag))
            assertTrue("len=$len", ocb2.open(nonce, cipher, 0, len, recovered, 0, openTag))
            assertArrayEquals("len=$len", plain, recovered)
            assertArrayEquals("len=$len", sealTag, openTag)
        }
    }

    @Test
    fun decryptsInPlace() {
        val plain = ByteArray(37) { (it * 3 + 1).toByte() }
        val buffer = plain.copyOf()
        val sealTag = ByteArray(16)
        val openTag = ByteArray(16)
        ocb2.seal(nonce, buffer, 0, buffer.size, buffer, 0, sealTag)
        assertTrue(ocb2.open(nonce, buffer, 0, buffer.size, buffer, 0, openTag))
        assertArrayEquals(plain, buffer)
        assertArrayEquals(sealTag, openTag)
    }

    @Test
    fun honoursOffsets() {
        val plain = ByteArray(20) { (it + 5).toByte() }
        val flat = ByteArray(20)
        val offsetIn = ByteArray(20 + 9).also { plain.copyInto(it, 9) }
        val offsetOut = ByteArray(20 + 4)
        val tagA = ByteArray(16)
        val tagB = ByteArray(16)
        ocb2.seal(nonce, plain, 0, 20, flat, 0, tagA)
        ocb2.seal(nonce, offsetIn, 9, 20, offsetOut, 4, tagB)
        assertArrayEquals(flat, offsetOut.copyOfRange(4, 24))
        assertArrayEquals(tagA, tagB)
    }

    /** 15 zero bytes plus one arbitrary byte in the second-to-last block is the forgery's hook. */
    @Test
    fun reportsExploitableBlockWhenNotModifyingPlaintext() {
        val plain = dangerousMessage()
        val cipher = ByteArray(plain.size)
        val tag = ByteArray(16)
        assertFalse(ocb2.seal(nonce, plain, 0, plain.size, cipher, 0, tag, false))
        // The caller's plaintext is untouched either way.
        assertArrayEquals(dangerousMessage(), plain)
    }

    @Test
    fun flipsExploitableBlockWhenModifyingPlaintext() {
        val plain = dangerousMessage()
        val cipher = ByteArray(plain.size)
        val tag = ByteArray(16)
        assertTrue(ocb2.seal(nonce, plain, 0, plain.size, cipher, 0, tag, true))
        assertArrayEquals(dangerousMessage(), plain)

        val recovered = ByteArray(plain.size)
        val openTag = ByteArray(16)
        assertTrue(ocb2.open(nonce, cipher, 0, cipher.size, recovered, 0, openTag))
        assertArrayEquals(tag, openTag)
        val expected = dangerousMessage().also { it[0] = 0x01 }
        assertArrayEquals(expected, recovered)
    }

    @Test
    fun exploitableBlockChangesCiphertextOnlyWhenModifying() {
        val plain = dangerousMessage()
        val unmodified = ByteArray(plain.size)
        val modified = ByteArray(plain.size)
        val tagA = ByteArray(16)
        val tagB = ByteArray(16)
        ocb2.seal(nonce, plain, 0, plain.size, unmodified, 0, tagA, false)
        ocb2.seal(nonce, plain, 0, plain.size, modified, 0, tagB, true)
        assertFalse(unmodified.contentEquals(modified))
        assertFalse(tagA.contentEquals(tagB))
    }

    @Test
    fun rejectsBadArgumentsBeforeWritingAnything() {
        val plain = ByteArray(64) { (it + 1).toByte() }
        val cases = mapOf<String, (ByteArray, ByteArray) -> Unit>(
            "len past end of input" to { out, tag -> ocb2.seal(nonce, plain, 0, 65, out, 0, tag) },
            "len past end of output" to { out, tag -> ocb2.seal(nonce, plain, 0, 64, out, 0, tag) },
            "len past end of input at offset" to
                { out, tag -> ocb2.seal(nonce, plain, 40, 40, out, 0, tag) },
            "negative len" to { out, tag -> ocb2.seal(nonce, plain, 0, -1, out, 0, tag) },
            "negative inOff" to { out, tag -> ocb2.seal(nonce, plain, -1, 16, out, 0, tag) },
            "inOff past end" to { out, tag -> ocb2.seal(nonce, plain, 65, 0, out, 0, tag) },
            "negative outOff" to { out, tag -> ocb2.seal(nonce, plain, 0, 16, out, -1, tag) },
            "outOff past end" to { out, tag -> ocb2.seal(nonce, plain, 0, 16, out, 60, tag) },
            "short tag" to { out, _ -> ocb2.seal(nonce, plain, 0, 16, out, 0, ByteArray(15)) },
            "short nonce" to
                { out, tag -> ocb2.seal(ByteArray(15), plain, 0, 16, out, 0, tag) },
            "open: len past end of input" to
                { out, tag -> ocb2.open(nonce, plain, 0, 65, out, 0, tag) },
            "open: len past end of output" to
                { out, tag -> ocb2.open(nonce, plain, 0, 64, out, 0, tag) },
            "open: negative inOff" to { out, tag -> ocb2.open(nonce, plain, -1, 16, out, 0, tag) },
            "open: negative outOff" to { out, tag -> ocb2.open(nonce, plain, 0, 16, out, -1, tag) },
            "open: short tag" to { out, _ -> ocb2.open(nonce, plain, 0, 16, out, 0, ByteArray(15)) },
            "open: short nonce" to
                { out, tag -> ocb2.open(ByteArray(15), plain, 0, 16, out, 0, tag) },
        )
        for ((name, call) in cases) {
            // 32 bytes: big enough for the "past end of output" cases to be about output, not input.
            val out = ByteArray(32)
            val tag = ByteArray(16)
            val thrown = assertThrows(name, IllegalArgumentException::class.java) { call(out, tag) }
            assertTrue("$name: unhelpful message", (thrown.message?.length ?: 0) > 0)
            assertArrayEquals("$name: output was written", ByteArray(32), out)
            assertArrayEquals("$name: tag was written", ByteArray(16), tag)
        }
        // A rejected call must not disturb the instance either.
        emptyMessageMatchesPublishedVector()
    }

    /** 32 bytes: the last full block is bytes 0..15, i.e. 15 zeros then one arbitrary byte. */
    private fun dangerousMessage() = ByteArray(32).also {
        it[15] = 0x5A
        for (j in 16 until 32) it[j] = (j * 11).toByte()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    /**
     * Aliasing that would corrupt silently. Same array with shifted offsets writes over input the
     * block loop has not read yet; a tag buffer that is the output array overwrites the first 16
     * bytes of finished ciphertext. Both are rejected rather than left to produce wrong bytes.
     */
    @Test
    fun rejectsAliasingThatWouldCorrupt() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(16) { (0x40 + it).toByte() }
        val buf = ByteArray(96)
        val tag = ByteArray(16)

        assertThrows(IllegalArgumentException::class.java) {
            Ocb2(key).seal(nonce, buf, 0, 64, buf, 16, tag)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ocb2(key).open(nonce, buf, 0, 64, buf, 16, tag)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ocb2(key).seal(nonce, buf, 0, 64, buf, 0, buf)
        }

        // The documented in-place case still works and matches the non-aliased result.
        val plain = ByteArray(64) { (it * 7).toByte() }
        val want = ByteArray(64)
        val wantTag = ByteArray(16)
        assertTrue(Ocb2(key).seal(nonce, plain, 0, 64, want, 0, wantTag))
        val inPlace = plain.copyOf()
        val gotTag = ByteArray(16)
        assertTrue(Ocb2(key).seal(nonce, inPlace, 0, 64, inPlace, 0, gotTag))
        assertArrayEquals(want, inPlace)
        assertArrayEquals(wantTag, gotTag)
    }

    /** A nonce that is not exactly 16 bytes is refused rather than silently truncated. */
    @Test
    fun rejectsNonceOfTheWrongLength() {
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(32)
        val out = ByteArray(32)
        val tag = ByteArray(16)
        for (size in intArrayOf(0, 15, 17, 32)) {
            assertThrows(IllegalArgumentException::class.java) {
                Ocb2(key).seal(ByteArray(size), plain, 0, 32, out, 0, tag)
            }
            assertThrows(IllegalArgumentException::class.java) {
                Ocb2(key).open(ByteArray(size), plain, 0, 32, out, 0, tag)
            }
        }
    }

    /**
     * open() fills the output with unverified plaintext and returns true for tampered input: only
     * the caller's tag comparison rejects a forgery. Pinned because a caller trusting the boolean
     * alone would accept forged audio.
     */
    @Test
    fun openReturnsTrueForTamperedInputAndTheTagIsTheOnlyDefence() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(16) { (0x40 + it).toByte() }
        val plain = ByteArray(48) { (it * 3).toByte() }
        val wire = ByteArray(48)
        val sealed = ByteArray(16)
        assertTrue(Ocb2(key).seal(nonce, plain, 0, 48, wire, 0, sealed))

        wire[5] = (wire[5].toInt() xor 0xFF).toByte()
        val out = ByteArray(48)
        val recomputed = ByteArray(16)
        assertTrue("the XEX* check does not fire here", Ocb2(key).open(nonce, wire, 0, 48, out, 0, recomputed))
        assertFalse("the tag is what rejects it", recomputed.contentEquals(sealed))
        assertFalse("and the caller's buffer holds unverified bytes", out.all { it.toInt() == 0 })
    }

    /**
     * Decrypting in place with the output below the input: the shape a receive path wants, where
     * the 4-byte header is stripped as the plaintext is written over the ciphertext. Writes trail
     * reads throughout, so this must produce exactly what distinct buffers produce.
     */
    @Test
    fun inPlaceWithOutputBelowInputMatchesDistinctBuffers() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(16) { (0x40 + it).toByte() }
        for (len in 0..80) {
            val plain = ByteArray(len) { (it * 11 + len).toByte() }
            val wire = ByteArray(len)
            val sealed = ByteArray(16)
            assertTrue(Ocb2(key).seal(nonce, plain, 0, len, wire, 0, sealed))

            val want = ByteArray(len)
            val wantTag = ByteArray(16)
            assertTrue(Ocb2(key).open(nonce, wire, 0, len, want, 0, wantTag))

            val buf = ByteArray(len + 4)
            wire.copyInto(buf, 4)
            val tag = ByteArray(16)
            assertTrue(Ocb2(key).open(nonce, buf, 4, len, buf, 0, tag))
            assertArrayEquals("len=$len", want, buf.copyOf(len))
            assertArrayEquals("len=$len tag", wantTag, tag)
        }
    }
}
