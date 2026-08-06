package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.channeltree.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkBlockTest {

    private fun user(
        mute: Boolean = false,
        deaf: Boolean = false,
        selfMute: Boolean = false,
        selfDeaf: Boolean = false,
        suppress: Boolean = false,
    ) = User(
        session = 7, name = "me", channelId = 0,
        mute = mute, deaf = deaf, selfMute = selfMute, selfDeaf = selfDeaf, suppress = suppress,
    )

    @Test fun aCleanRowLeavesTalkAvailable() {
        assertNull(talkBlock(user(), microphoneGranted = true))
    }

    @Test fun aDeniedMicrophoneBlocksTalk() {
        assertEquals(TalkBlock.NO_MICROPHONE, talkBlock(user(), microphoneGranted = false))
    }

    /** The microphone outranks the wire: undeafening does not give back a permission. */
    @Test fun aDeniedMicrophoneOutranksBeingDeafened() {
        assertEquals(
            TalkBlock.NO_MICROPHONE,
            talkBlock(user(selfDeaf = true, selfMute = true), microphoneGranted = false),
        )
    }

    /**
     * murmur sets self_mute alongside self_deaf, so a deafened user matches the mute disjunction
     * too. Reporting MUTED there would be true and useless — the fix is to undeafen.
     */
    @Test fun deafenedOutranksTheMuteItImplies() {
        assertEquals(
            TalkBlock.DEAFENED,
            talkBlock(user(selfDeaf = true, selfMute = true), microphoneGranted = true),
        )
    }

    @Test fun anAdminMuteBlocksTalk() {
        assertEquals(TalkBlock.MUTED, talkBlock(user(mute = true), microphoneGranted = true))
    }

    @Test fun channelSuppressBlocksTalk() {
        assertEquals(TalkBlock.MUTED, talkBlock(user(suppress = true), microphoneGranted = true))
    }

    @Test fun aSelfMuteWithoutDeafenBlocksTalk() {
        assertEquals(TalkBlock.MUTED, talkBlock(user(selfMute = true), microphoneGranted = true))
    }

    /**
     * A server deafen does not stop the server carrying our audio — only its mute does, and murmur
     * sets that too. The row decides; `deaf` alone is not a Talk block.
     */
    @Test fun aServerDeafenAloneDoesNotBlockTalk() {
        assertNull(talkBlock(user(deaf = true), microphoneGranted = true))
    }

    /** The window between Connected and the tree catching up. Disabling here flickers every connect. */
    @Test fun anAbsentRowLeavesTalkAvailable() {
        assertNull(talkBlock(null, microphoneGranted = true))
    }
}
