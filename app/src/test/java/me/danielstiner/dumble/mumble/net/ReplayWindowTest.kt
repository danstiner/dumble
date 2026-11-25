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
        // 32 behind is the window's spent slots, the rest is further back than it reaches.
        for (hint in 72..200) assertFalse("hint $hint", window.rebuildNonce(hint, nonce))
    }

    @Test
    fun aJumpOfTheWindowDepthKeepsTheOldTopSpent() {
        val window = windowAt(0)
        assertTrue(window.rebuildNonce(1, nonce))
        assertEquals(1, window.accept(nonce))
        assertTrue(window.rebuildNonce(33, nonce))
        assertEquals(32, window.accept(nonce))
        // The old top is now the last slot the window reaches; the one the jump skipped is open.
        assertFalse(window.rebuildNonce(1, nonce))
        assertTrue(window.rebuildNonce(2, nonce))
        assertEquals(-31, window.accept(nonce))
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
