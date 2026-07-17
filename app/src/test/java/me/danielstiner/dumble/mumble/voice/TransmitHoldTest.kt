package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmitHoldTest {
    @Test fun trueWhileSendingThenReleasesAfterHold() {
        val h = TransmitHold(holdTicks = 2)
        assertFalse("not held before any send", h.update(false))
        assertTrue(h.update(true))    // sending
        assertTrue(h.update(false))   // hold 1
        assertTrue(h.update(false))   // hold 2
        assertFalse("released after holdTicks holds", h.update(false))
    }

    @Test fun sendingRefreshesTheHold() {
        val h = TransmitHold(holdTicks = 2)
        h.update(true); h.update(false)  // sending, hold 1
        assertTrue(h.update(true))       // sending again -> refresh
        assertTrue(h.update(false))      // hold 1 after refresh
        assertTrue(h.update(false))      // hold 2 after refresh
        assertFalse(h.update(false))     // released
    }

    @Test fun clearResetsToNotHeld() {
        val h = TransmitHold(holdTicks = 5)
        h.update(true)
        h.clear()
        assertFalse(h.update(false))
    }
}
