package me.danielstiner.dumble.mumble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The branches of desktop Mumble's `on_qaAudioDeaf_triggered` (`src/mumble/MainWindow.cpp`), named
 * for the log line each produces there.
 */
class DeafenStateTest {

    private fun state(deaf: Boolean = false, mute: Boolean = false, owed: Boolean = false) =
        DeafenState(selfDeaf = deaf, selfMute = mute, unmuteOnUndeaf = owed)

    /** "Muted and deafened." */
    @Test fun deafeningWhileUnmutedMutesAndOwesAnUnmute() {
        assertEquals(state(deaf = true, mute = true, owed = true), state().deafen(true))
    }

    /** "Deafened." — the mute was already there, so undeafening does not own it. */
    @Test fun deafeningWhileMutedOwesNothing() {
        assertEquals(state(deaf = true, mute = true), state(mute = true).deafen(true))
    }

    /** "Unmuted and undeafened." */
    @Test fun undeafeningUnmutesWhenTheDeafenSetTheMute() {
        assertEquals(state(), state(deaf = true, mute = true, owed = true).deafen(false))
    }

    /**
     * "Undeafened." — the hot-mic case. A mute the user set themselves must survive
     * mute -> deafen -> undeafen, or the microphone reopens without them asking.
     */
    @Test fun undeafeningKeepsAMuteItDidNotSet() {
        assertEquals(state(mute = true), state(deaf = true, mute = true).deafen(false))
    }

    @Test fun undeafeningWhileUnmutedStaysUnmuted() {
        assertEquals(state(), state(deaf = true).deafen(false))
    }

    /** A deafen is the only thing that raises the debt, so it cannot survive the undeafen it funds. */
    @Test fun undeafeningAlwaysClearsTheDebt() {
        assertEquals(false, state(deaf = true, mute = true, owed = true).deafen(false).unmuteOnUndeaf)
    }
}
