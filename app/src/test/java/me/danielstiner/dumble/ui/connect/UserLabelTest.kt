package me.danielstiner.dumble.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the one hop where a user's state becomes something the user can see. Everything either
 * side of it — [channelTreeRows] tagging and the ViewModel plumbing — was already tested, so
 * deleting the speaking glyph, or passing an empty speaking set down from MainActivity, left the
 * feature entirely dead on screen with a green suite.
 */
class UserLabelTest {

    private fun row(
        name: String = "alice",
        mute: Boolean = false,
        deaf: Boolean = false,
        selfMute: Boolean = false,
        selfDeaf: Boolean = false,
        suppress: Boolean = false,
        isSpeaking: Boolean = false,
    ) = ChannelTreeRow.UserRow(
        depth = 1, session = 1, name = name,
        mute = mute, deaf = deaf, selfMute = selfMute, selfDeaf = selfDeaf, suppress = suppress,
        isMe = false, isSpeaking = isSpeaking,
    )

    @Test fun aSilentUserIsJustTheirName() {
        assertEquals("alice", userLabel(row()))
    }

    @Test fun aSpeakingUserIsMarked() {
        assertEquals("🔊 alice", userLabel(row(isSpeaking = true)))
    }

    @Test fun speakingCombinesWithTheOtherGlyphs() {
        // Server-mute and self-mute deliberately collapse to one mark, so a user who is both
        // reads the same as a user who is either.
        assertEquals("🔊 alice 🔇", userLabel(row(isSpeaking = true, mute = true, selfMute = true)))
        assertEquals("alice 🔇 🔈 🚫", userLabel(row(mute = true, deaf = true, suppress = true)))
    }
}
