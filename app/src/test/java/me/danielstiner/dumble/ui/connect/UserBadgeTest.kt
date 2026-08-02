package me.danielstiner.dumble.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserBadgeTest {

    private fun row(
        mute: Boolean = false, deaf: Boolean = false,
        selfMute: Boolean = false, selfDeaf: Boolean = false, suppress: Boolean = false,
    ) = ChannelTreeRow.UserRow(
        depth = 1, session = 1, name = "alice",
        mute = mute, deaf = deaf, selfMute = selfMute, selfDeaf = selfDeaf, suppress = suppress,
        isMe = false, isSpeaking = false,
    )

    @Test fun noStateHasNoBadge() = assertNull(userBadge(row()))

    @Test fun serverDeafOutranksEverything() =
        assertEquals(
            UserBadge(UserBadge.Kind.DEAF, server = true),
            userBadge(row(deaf = true, selfDeaf = true, mute = true, selfMute = true)),
        )

    /** Deaf beats mute: not being able to hear you is the more useful fact about a user. */
    @Test fun selfDeafOutranksMute() =
        assertEquals(
            UserBadge(UserBadge.Kind.DEAF, server = false),
            userBadge(row(selfDeaf = true, mute = true, selfMute = true)),
        )

    @Test fun serverMuteOutranksSelfMute() =
        assertEquals(
            UserBadge(UserBadge.Kind.MUTE, server = true),
            userBadge(row(mute = true, selfMute = true)),
        )

    @Test fun selfMuteIsNeutral() =
        assertEquals(UserBadge(UserBadge.Kind.MUTE, server = false), userBadge(row(selfMute = true)))

    /** Suppress is the server refusing to carry your audio, so it reads as a server mute. */
    @Test fun suppressReadsAsServerMute() =
        assertEquals(UserBadge(UserBadge.Kind.MUTE, server = true), userBadge(row(suppress = true)))
}
