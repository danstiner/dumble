package me.danielstiner.dumble.mumble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each sequence ends on the wire where desktop Mumble's mute and deafen actions leave it. */
class DeafenStateTest {

    private fun wire(state: DeafenState) = state.selfDeaf to state.selfMute

    @Test fun deafeningMutesOnTheWire() {
        assertEquals(true to true, wire(DeafenState().withSelfDeaf(true)))
    }

    @Test fun undeafeningLiftsTheMuteTheDeafenForced() {
        assertEquals(false to false, wire(DeafenState().withSelfDeaf(true).withSelfDeaf(false)))
    }

    /** The hot-mic case: the microphone must not reopen without the user asking. */
    @Test fun aMuteTheUserSetSurvivesADeafenAndItsUndeafen() {
        val state = DeafenState().withSelfMute(true).withSelfDeaf(true).withSelfDeaf(false)
        assertEquals(false to true, wire(state))
    }

    @Test fun aMuteAskedForWhileDeafenedSurvivesTheUndeafen() {
        val state = DeafenState().withSelfDeaf(true).withSelfMute(true).withSelfDeaf(false)
        assertEquals(false to true, wire(state))
    }

    @Test fun unmutingWhileDeafenedUndeafensToo() {
        assertEquals(DeafenState(), DeafenState().withSelfDeaf(true).withSelfMute(false))
        assertEquals(DeafenState(), DeafenState().withSelfMute(true).withSelfDeaf(true).withSelfMute(false))
    }

    /** A second tap inside one round trip asks for the same thing again. */
    @Test fun aRepeatAskChangesNothing() {
        val deafened = DeafenState().withSelfDeaf(true)
        assertEquals(deafened, deafened.withSelfDeaf(true))
        assertEquals(DeafenState(), deafened.withSelfDeaf(false).withSelfDeaf(false))
        val muted = DeafenState().withSelfMute(true)
        assertEquals(muted, muted.withSelfMute(true))
    }
}
