package me.danielstiner.dumble.mumble.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which check refuses a packet. Through [CryptState] a window refusal and a tag failure both come
 * back as -1, so only here can "refused before any decryption" be told from "decrypted and failed".
 */
class ReplayWindowTest {
    private val nonce = ByteArray(16)

    private fun windowAt(top: Int) =
        ReplayWindow().apply { restartAt(ByteArray(16).also { it[0] = top.toByte() }) }

    @Test
    fun anAcceptedCounterIsRefusedWithoutTouchingTheNonce() {
        val window = windowAt(0)
        assertTrue(window.rebuildNonce(1, nonce))
        assertEquals(1, window.accept(nonce))
        val untouched = ByteArray(16) { 0x55 }
        assertFalse(window.rebuildNonce(1, untouched))
        assertArrayEquals(ByteArray(16) { 0x55 }, untouched)
    }

    @Test
    fun aRestartSpendsItsSeedAndEverythingBehindIt() {
        val window = windowAt(200)
        // Hints 137..200 are the seed and the 63 slots below it, which the restart marked spent.
        // Hints 72..136 are 64 to 128 behind, further back than the window reaches.
        for (hint in 72..200) assertFalse("hint $hint", window.rebuildNonce(hint, nonce))
    }

    @Test
    fun aJumpOfTheWindowDepthKeepsTheOldTopSpent() {
        val window = windowAt(0)
        assertTrue(window.rebuildNonce(1, nonce))
        assertEquals(1, window.accept(nonce))
        assertTrue(window.rebuildNonce(64, nonce))
        assertEquals(63, window.accept(nonce))
        // The old top is now the last slot the window reaches; the one the jump skipped is open.
        assertFalse(window.rebuildNonce(1, nonce))
        assertTrue(window.rebuildNonce(2, nonce))
        assertEquals(-62, window.accept(nonce))
    }

    // A Long shift masks its count to six bits, so without the distance check 65 behind would
    // read the bit for 1 behind.
    @Test
    fun pastTheWindowIsRefusedThoughNeverSeen() {
        val window = windowAt(0)
        assertTrue(window.rebuildNonce(66, nonce))
        assertEquals("a jump past the window, which leaves every slot below it open", 66, window.accept(nonce))
        assertTrue("63 behind", window.rebuildNonce(3, nonce))
        assertFalse("65 behind", window.rebuildNonce(1, nonce))
    }

    @Test
    fun oneTwentySevenAheadRebuildsAndOneMoreReadsAsBehind() {
        val window = windowAt(200)
        assertTrue(window.rebuildNonce(71, nonce))
        // 200 + 127 carries into the second byte.
        assertArrayEquals(ByteArray(16).also { it[0] = 71; it[1] = 1 }, nonce)
        assertFalse(window.rebuildNonce(72, nonce))
    }
}
