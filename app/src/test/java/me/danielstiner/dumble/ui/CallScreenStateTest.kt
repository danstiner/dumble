package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.model.MumbleChannel
import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.model.ServerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreenStateTest {
    private val model = ServerModel(
        channels = mapOf(
            0 to MumbleChannel(id = 0, parentId = null, name = "Root", position = 0),
            1 to MumbleChannel(id = 1, parentId = 0, name = "Gaming", position = 1),
            2 to MumbleChannel(id = 2, parentId = 0, name = "Empty", position = 2),
        ),
        users = mapOf(
            10 to MumbleUser(session = 10, name = "me", channelId = 1),
            11 to MumbleUser(session = 11, name = "alice", channelId = 1),
            12 to MumbleUser(session = 12, name = "bob", channelId = 0, mute = true),
        ),
        sessionId = 10,
    )

    private fun build(muted: Boolean = false, deaf: Boolean = false,
                      speaking: Set<Int> = emptySet(), selfTx: Boolean = false) =
        buildCallScreenState(model, speaking, selfTx, muted, deaf, configHostFallback = "host")

    @Test fun serverNameFromRootChannel() {
        assertEquals("Root", build().serverName)
        assertEquals("host", buildCallScreenState(ServerModel(), emptySet(), false, false, false, "host").serverName)
    }

    @Test fun emptyChannelsHiddenExceptYours() {
        val names = build().channels.map { it.name }
        assertTrue(names.contains("Gaming")); assertTrue(names.contains("Root"))
        assertFalse("empty non-self channel hidden", names.contains("Empty"))
    }

    @Test fun selfFloatedToTopAndTaggedYou() {
        val gaming = build().channels.first { it.name == "Gaming" }
        assertEquals("me", gaming.users.first().name)
        assertTrue(gaming.users.first().isYou)
        assertTrue("your channel is active", gaming.isActive)
    }

    @Test fun serverMuteVsSelfMuteMapping() {
        val bob = build().channels.first { it.name == "Root" }.users.first { it.name == "bob" }
        assertTrue("mute -> server (red)", bob.serverMute); assertFalse(bob.selfMute)
    }

    @Test fun selfRowPrefersLocalMuteAndTransmit() {
        val meMuted = build(muted = true).channels.first { it.name == "Gaming" }.users.first()
        assertTrue(meMuted.selfMute); assertFalse(meMuted.speaking)
        val meTx = build(selfTx = true).channels.first { it.name == "Gaming" }.users.first()
        assertTrue("local transmit drives own speaking", meTx.speaking)
    }

    @Test fun otherSpeakingFromSet() {
        val alice = build(speaking = setOf(11)).channels.first { it.name == "Gaming" }.users.first { it.name == "alice" }
        assertTrue(alice.speaking)
    }
}
