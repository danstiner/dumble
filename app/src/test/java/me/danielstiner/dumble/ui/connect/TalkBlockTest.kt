package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.channeltree.User
import me.danielstiner.dumble.mumble.protocol.DeafenState
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

    private val unasked = DeafenState()
    private val deafened = DeafenState().withSelfDeaf(true)
    private val muted = DeafenState().withSelfMute(true)

    @Test fun aCleanRowLeavesTalkAvailable() {
        assertNull(talkBlock(user(), unasked, microphoneGranted = true))
    }

    @Test fun aDeniedMicrophoneBlocksTalk() {
        assertEquals(TalkBlock.NO_MICROPHONE, talkBlock(user(), unasked, microphoneGranted = false))
    }

    /** The microphone outranks the wire: undeafening does not give back a permission. */
    @Test fun aDeniedMicrophoneOutranksBeingDeafened() {
        assertEquals(TalkBlock.NO_MICROPHONE, talkBlock(user(), deafened, microphoneGranted = false))
    }

    /**
     * A deafen mutes too, so a deafened user matches the mute disjunction as well. Reporting MUTED
     * there would be true and useless — the fix is to undeafen.
     */
    @Test fun deafenedOutranksTheMuteItImplies() {
        assertEquals(TalkBlock.DEAFENED, talkBlock(user(), deafened, microphoneGranted = true))
    }

    @Test fun anAdminMuteBlocksTalk() {
        assertEquals(TalkBlock.MUTED, talkBlock(user(mute = true), unasked, microphoneGranted = true))
    }

    @Test fun channelSuppressBlocksTalk() {
        assertEquals(TalkBlock.MUTED, talkBlock(user(suppress = true), unasked, microphoneGranted = true))
    }

    @Test fun aSelfMuteWithoutDeafenBlocksTalk() {
        assertEquals(TalkBlock.MUTED, talkBlock(user(), muted, microphoneGranted = true))
    }

    /** The self half is the ask's: the echo on our row lags it, and the capture gate obeys the ask. */
    @Test fun theRowsOwnSelfFieldsDecideNothing() {
        assertNull(talkBlock(user(selfDeaf = true, selfMute = true), unasked, microphoneGranted = true))
    }

    /**
     * A server deafen does not stop the server carrying our audio — only its mute does, and murmur
     * sets that too. The row decides; `deaf` alone is not a Talk block.
     */
    @Test fun aServerDeafenAloneDoesNotBlockTalk() {
        assertNull(talkBlock(user(deaf = true), unasked, microphoneGranted = true))
    }

    /** The window between Connected and the tree catching up. Disabling here flickers every connect. */
    @Test fun anAbsentRowLeavesTalkAvailable() {
        assertNull(talkBlock(null, unasked, microphoneGranted = true))
    }

    /** What was asked for needs no row to block on. */
    @Test fun anAbsentRowStillHonoursTheAsk() {
        assertEquals(TalkBlock.DEAFENED, talkBlock(null, deafened, microphoneGranted = true))
    }
}
