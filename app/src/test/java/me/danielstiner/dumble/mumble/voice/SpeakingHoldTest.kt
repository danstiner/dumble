package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Semantics: a session is present on the tick it is produced, then held for `holdTicks` further
// silent ticks, then dropped. So holdTicks=2 -> present on the producing tick + 2 held ticks.
class SpeakingHoldTest {
    @Test fun heldForHoldTicksThenDropped() {
        val h = SpeakingHold(holdTicks = 2)
        assertTrue(h.tick(setOf(7)).contains(7))     // produced (appearance)
        assertTrue(h.tick(emptySet()).contains(7))   // hold 1
        assertTrue(h.tick(emptySet()).contains(7))   // hold 2
        assertFalse("dropped after holdTicks holds", h.tick(emptySet()).contains(7))
    }

    @Test fun refreshExtendsTheHold() {
        val h = SpeakingHold(holdTicks = 2)
        h.tick(setOf(1))                              // produced
        h.tick(setOf(1))                              // refreshed -> hold restarts
        assertTrue(h.tick(emptySet()).contains(1))   // hold 1 after refresh
        assertTrue(h.tick(emptySet()).contains(1))   // hold 2 after refresh
        assertFalse(h.tick(emptySet()).contains(1))  // dropped
    }

    @Test fun dropRemovesImmediately() {
        val h = SpeakingHold(holdTicks = 5)
        h.tick(setOf(4))
        h.drop(4)
        assertFalse(h.tick(emptySet()).contains(4))
    }

    @Test fun refreshedSessionOutlivesAnUnrefreshedOne() {
        val h = SpeakingHold(holdTicks = 2)
        h.tick(setOf(1, 2))                          // both produced
        h.tick(setOf(1))                             // 1 refreshed; 2 keeps aging
        h.tick(emptySet())                           // both still held
        val c4 = h.tick(emptySet())
        assertTrue("refreshed 1 still held", c4.contains(1))
        assertFalse("unrefreshed 2 already dropped", c4.contains(2))
    }

    @Test fun clearEmptiesTheSet() {
        val h = SpeakingHold(holdTicks = 5)
        h.tick(setOf(1, 2))
        h.clear()
        assertTrue(h.tick(emptySet()).isEmpty())
    }
}
