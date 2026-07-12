package com.example.drumble.mumble

import com.example.drumble.mumble.net.CryptState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Deterministic fuzz coverage for [CryptState] (OCB2-AES128 -- the only
 * crypto in the app). Every property below drives a [java.util.Random] with
 * a FIXED seed, so a failure is reproducible from the iteration index alone:
 * re-run the same test and the same iteration regenerates the exact same
 * bytes. Failure messages additionally embed the iteration index and a hex
 * preview of the relevant bytes/lengths.
 *
 * These are SECURITY INVARIANTS, not just round-trip checks: tampered,
 * duplicated, or malformed input must never be accepted, and malformed
 * input must never crash the decoder.
 */
class CryptStateFuzzTest {

    companion object {
        // Fixed base seed; each property offsets it so the four suites don't
        // share a PRNG stream but are each independently deterministic.
        private const val SEED = 20260711L
    }

    /** Hex dump capped at [max] bytes so failure messages stay readable. */
    private fun hexPreview(bytes: ByteArray, len: Int = bytes.size, max: Int = 64): String {
        val n = minOf(len, max)
        val prefix = (0 until n).joinToString("") { "%02x".format(bytes[it]) }
        return if (len > max) "$prefix...(${len} bytes total)" else prefix
    }

    private fun randomBytes(rnd: Random, n: Int) = ByteArray(n) { rnd.nextInt(256).toByte() }

    // ------------------------------------------------------------------
    // 1. Round trip: random keys, random nonces, random lengths (incl. the
    //    boundary sizes 0/15/16/17 and up to 1500) -- encrypt then decrypt
    //    must recover the exact plaintext.
    // ------------------------------------------------------------------
    @Test fun roundTripFuzz() {
        val rnd = Random(SEED)
        val iterations = 2000
        for (i in 0 until iterations) {
            val key = randomBytes(rnd, 16)
            val nonceA = randomBytes(rnd, 16)
            val nonceB = randomBytes(rnd, 16)
            val a = CryptState().apply { setKeys(key, nonceA, nonceB) }
            val b = CryptState().apply { setKeys(key, nonceB, nonceA) }

            // Guarantee coverage of the interesting boundary sizes, then fuzz.
            val len = when (i) {
                0 -> 0
                1 -> 15
                2 -> 16
                3 -> 17
                4 -> 1500
                else -> rnd.nextInt(1501)
            }
            val plain = randomBytes(rnd, len)
            val wire = ByteArray(len + CryptState.OVERHEAD)
            val out = ByteArray(len)

            val encLen = a.encrypt(plain, len, wire)
            assertEquals(
                "iteration=$i len=$len key=${hexPreview(key)}: encrypt returned wrong length",
                len + CryptState.OVERHEAD, encLen,
            )

            val decLen = b.decrypt(wire, wire.size, out)
            assertEquals(
                "iteration=$i len=$len key=${hexPreview(key)} nonceA=${hexPreview(nonceA)} " +
                    "nonceB=${hexPreview(nonceB)} wire=${hexPreview(wire)}: decrypt returned wrong length",
                len, decLen,
            )
            assertArrayEquals(
                "iteration=$i len=$len: plaintext mismatch plain=${hexPreview(plain)} out=${hexPreview(out)}",
                plain, out,
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. Tamper: flipping exactly one bit anywhere in the wire packet
    //    (header or ciphertext) must always be rejected. Fresh mirrored
    //    pair per iteration so IV window state can't confound the result.
    // ------------------------------------------------------------------
    @Test fun tamperAlwaysRejectedFuzz() {
        val rnd = Random(SEED + 1)
        val iterations = 2000
        for (i in 0 until iterations) {
            val key = randomBytes(rnd, 16)
            val nonceA = randomBytes(rnd, 16)
            val nonceB = randomBytes(rnd, 16)
            val a = CryptState().apply { setKeys(key, nonceA, nonceB) }
            val b = CryptState().apply { setKeys(key, nonceB, nonceA) }

            val len = 1 + rnd.nextInt(300)
            val plain = randomBytes(rnd, len)
            val wire = ByteArray(len + CryptState.OVERHEAD)
            a.encrypt(plain, len, wire)

            val bitIndex = rnd.nextInt(wire.size * 8)
            val byteIdx = bitIndex / 8
            val mask = 1 shl (bitIndex % 8)
            wire[byteIdx] = (wire[byteIdx].toInt() xor mask).toByte()

            val out = ByteArray(len)
            val result = b.decrypt(wire, wire.size, out)
            assertEquals(
                "iteration=$i len=$len bitIndex=$bitIndex byteIdx=$byteIdx wire=${hexPreview(wire)}: " +
                    "TAMPERED packet was ACCEPTED -- must always be rejected",
                -1, result,
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Malformed input must never crash the decoder, regardless of
    //    length/content, whether keyed or not.
    // ------------------------------------------------------------------
    @Test fun malformedNeverCrashesFuzz() {
        val rnd = Random(SEED + 2)
        val iterations = 2000

        val keyedKey = randomBytes(rnd, 16)
        val keyedNonceA = randomBytes(rnd, 16)
        val keyedNonceB = randomBytes(rnd, 16)
        // Decrypt-side state: mirrors what a real client would hold.
        val keyed = CryptState().apply { setKeys(keyedKey, keyedNonceB, keyedNonceA) }

        for (i in 0 until iterations) {
            val bufLen = rnd.nextInt(2049)
            val buf = ByteArray(bufLen)
            rnd.nextBytes(buf)
            val len = if (rnd.nextBoolean()) bufLen else rnd.nextInt(bufLen + 1)
            val outSize = maxOf(0, len - CryptState.OVERHEAD) + rnd.nextInt(4)
            val out = ByteArray(outSize)

            // Every 5th iteration exercises a fresh, un-keyed instance.
            val useFresh = i % 5 == 0
            val target = if (useFresh) CryptState() else keyed

            var result: Int? = null
            try {
                result = target.decrypt(buf, len, out)
            } catch (t: Throwable) {
                fail(
                    "iteration=$i len=$len bufLen=$bufLen outSize=$outSize fresh=$useFresh " +
                        "buf=${hexPreview(buf, bufLen)}: decrypt THREW ${t::class.simpleName}: ${t.message}"
                )
            }
            val r = result!!
            assertTrue(
                "iteration=$i len=$len bufLen=$bufLen fresh=$useFresh buf=${hexPreview(buf, bufLen)}: " +
                    "return value $r out of range (expected -1 or 0..$len)",
                r == -1 || r in 0..len,
            )
            if (useFresh) {
                assertEquals(
                    "iteration=$i len=$len: un-keyed CryptState must always return -1, got $r",
                    -1, r,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Reorder within the tolerated window: packets delivered out of
    //    order (but only shuffled within small sliding groups, so
    //    displacement stays well under the ~30-packet late window) must
    //    each be accepted at most once, and a re-delivery of any accepted
    //    packet must always be rejected as a replay. Accepted packets must
    //    decrypt to the exact original plaintext.
    // ------------------------------------------------------------------
    @Test fun reorderWithinWindowFuzz() {
        val rnd = Random(SEED + 3)
        // Heavier per-iteration cost than the other properties (N packets
        // created + delivered + replayed each time); a smaller iteration
        // count keeps the whole suite in the "few seconds" budget while
        // still exercising many distinct shuffles.
        val iterations = 500
        val n = 40
        val groupSize = 8

        for (iter in 0 until iterations) {
            val key = randomBytes(rnd, 16)
            val nonceA = randomBytes(rnd, 16)
            val nonceB = randomBytes(rnd, 16)
            val a = CryptState().apply { setKeys(key, nonceA, nonceB) }
            val b = CryptState().apply { setKeys(key, nonceB, nonceA) }

            val plains = Array(n) { randomBytes(rnd, 8 + rnd.nextInt(24)) }
            val wires = Array(n) { idx ->
                val w = ByteArray(plains[idx].size + CryptState.OVERHEAD)
                a.encrypt(plains[idx], plains[idx].size, w)
                w
            }

            // Shuffle only within sliding groups of `groupSize` so IV
            // displacement relative to the decryptor's current position
            // stays well under 30 (the late-packet window).
            val order = ArrayList<Int>(n)
            var start = 0
            while (start < n) {
                val end = minOf(start + groupSize, n)
                val group = (start until end).toMutableList()
                for (k in group.indices.reversed()) {
                    val j = rnd.nextInt(k + 1)
                    val t = group[k]; group[k] = group[j]; group[j] = t
                }
                order.addAll(group)
                start = end
            }

            val accepted = BooleanArray(n)
            for (idx in order) {
                val out = ByteArray(plains[idx].size)
                val ret = b.decrypt(wires[idx], wires[idx].size, out)
                if (ret != -1) {
                    assertFalse(
                        "iteration=$iter idx=$idx order=$order: duplicate packet was ACCEPTED (replay!) " +
                            "wire=${hexPreview(wires[idx])}",
                        accepted[idx],
                    )
                    accepted[idx] = true
                    assertEquals(
                        "iteration=$iter idx=$idx: accepted packet decrypted to wrong length",
                        plains[idx].size, ret,
                    )
                    assertArrayEquals(
                        "iteration=$iter idx=$idx: accepted packet decrypted to WRONG PLAINTEXT " +
                            "expected=${hexPreview(plains[idx])} got=${hexPreview(out)}",
                        plains[idx], out,
                    )
                }
            }

            // Replay: re-deliver every already-accepted packet; must now always be rejected.
            for (idx in 0 until n) {
                if (!accepted[idx]) continue
                val out = ByteArray(plains[idx].size)
                val ret = b.decrypt(wires[idx], wires[idx].size, out)
                assertEquals(
                    "iteration=$iter idx=$idx: REPLAY of already-accepted packet was ACCEPTED " +
                        "wire=${hexPreview(wires[idx])}",
                    -1, ret,
                )
            }
        }
    }
}
