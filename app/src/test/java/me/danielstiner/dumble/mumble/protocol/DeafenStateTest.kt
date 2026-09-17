package me.danielstiner.dumble.mumble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each sequence ends on the wire where desktop Mumble's mute and deafen actions leave it. */
class DeafenStateTest {

    private fun wire(state: DeafenState) = state.deafened to state.muted

    @Test fun deafeningMutesOnTheWire() {
        assertEquals(true to true, wire(DeafenState().deafen(true)))
    }

    @Test fun undeafeningLiftsTheMuteTheDeafenForced() {
        assertEquals(false to false, wire(DeafenState().deafen(true).deafen(false)))
    }

    /** The hot-mic case: the microphone must not reopen without the user asking. */
    @Test fun aMuteTheUserSetSurvivesADeafenAndItsUndeafen() {
        val state = DeafenState().mute(true).deafen(true).deafen(false)
        assertEquals(false to true, wire(state))
    }

    @Test fun aMuteAskedForWhileDeafenedSurvivesTheUndeafen() {
        val state = DeafenState().deafen(true).mute(true).deafen(false)
        assertEquals(false to true, wire(state))
    }

    @Test fun unmutingWhileDeafenedUndeafensToo() {
        assertEquals(DeafenState(), DeafenState().deafen(true).mute(false))
        assertEquals(DeafenState(), DeafenState().mute(true).deafen(true).mute(false))
    }

    /** A second tap inside one round trip asks for the same thing again. */
    @Test fun aRepeatAskChangesNothing() {
        val deafened = DeafenState().deafen(true)
        assertEquals(deafened, deafened.deafen(true))
        assertEquals(DeafenState(), deafened.deafen(false).deafen(false))
        val muted = DeafenState().mute(true)
        assertEquals(muted, muted.mute(true))
    }

    @Test fun liftingTheUsersOwnMuteLeavesADeafenStanding() {
        assertEquals(DeafenState(), DeafenState().mute(true).liftOwnMute())
        val state = DeafenState().mute(true).deafen(true).liftOwnMute()
        assertEquals(true to true, wire(state))
        assertEquals(false to false, wire(state.deafen(false)))
    }
}
