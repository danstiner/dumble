package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmitHoldTest {
    @Test fun trueWhileSendingThenReleasesAfterHold() {
        val h = TransmitHold(holdTicks = 3)
        assertTrue(h.update(true))
        assertTrue(h.update(false))   // hold 1
        assertTrue(h.update(false))   // hold 2
        assertFalse("released after hold ticks", h.update(false)) // hold 3 -> 0
    }

    @Test fun sendingRefreshesTheHold() {
        val h = TransmitHold(holdTicks = 2)
        h.update(true); h.update(false)
        assertTrue(h.update(true))
        assertTrue(h.update(false))
    }
}
