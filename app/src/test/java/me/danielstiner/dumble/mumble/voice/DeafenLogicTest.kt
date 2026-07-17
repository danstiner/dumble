package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeafenLogicTest {
    @Test fun unmutedDeafenThenUndeafenReturnsToUnmuted() {
        val d = DeafenLogic.onSetDeafened(deafen = true, curMuted = false, curDeafenSetMute = false)
        assertTrue(d.muted); assertTrue(d.deafenSetMute)
        val u = DeafenLogic.onSetDeafened(deafen = false, curMuted = d.muted, curDeafenSetMute = d.deafenSetMute)
        assertFalse("auto-unmutes because deafen set the mute", u.muted)
        assertFalse(u.deafenSetMute)
    }

    @Test fun manualMuteSurvivesDeafenUndeafen() {
        val d = DeafenLogic.onSetDeafened(deafen = true, curMuted = true, curDeafenSetMute = false)
        assertTrue(d.muted); assertFalse("deafen did not set the mute", d.deafenSetMute)
        val u = DeafenLogic.onSetDeafened(deafen = false, curMuted = d.muted, curDeafenSetMute = d.deafenSetMute)
        assertTrue("manual mute must survive un-deafen (no hot mic)", u.muted)
    }
}
