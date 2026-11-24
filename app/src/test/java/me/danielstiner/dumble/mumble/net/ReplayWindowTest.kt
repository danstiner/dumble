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
    fun aRestartSpendsItsSeedAndTheWholeWindowBelowIt() {
        val window = windowAt(100)
        for (hint in 37..100) assertFalse("hint $hint", window.rebuildNonce(hint, nonce))
        // 64 behind is past the bitmap, so it is read as 192 ahead and left for the tag to fail.
        assertTrue(window.rebuildNonce(36, nonce))
        assertArrayEquals(ByteArray(16).also { it[0] = 36; it[1] = 1 }, nonce)
    }

    @Test
    fun oneNinetyTwoAheadRebuildsAndOneNinetyThreeAliasesToASpentSlot() {
        val window = windowAt(0)
        assertTrue(window.rebuildNonce(192, nonce))
        assertArrayEquals(ByteArray(16).also { it[0] = 192.toByte() }, nonce)
        assertFalse(window.rebuildNonce(193, nonce))
    }
}
