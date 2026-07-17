package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.model.MumbleChannel
import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.model.ServerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreenStateTest {
    // Root "Acoustic HQ" > { Gaming > Sub, Empty }. me/alice in Gaming; bob (server-muted) in root;
    // eve (self-deaf, recording) in Sub.
    private val model = ServerModel(
        channels = mapOf(
            0 to MumbleChannel(id = 0, parentId = null, name = "Acoustic HQ", position = 0),
            1 to MumbleChannel(id = 1, parentId = 0, name = "Gaming", position = 1),
            2 to MumbleChannel(id = 2, parentId = 0, name = "Empty", position = 2),
            3 to MumbleChannel(id = 3, parentId = 1, name = "Sub", position = 0),
        ),
        users = mapOf(
            10 to MumbleUser(session = 10, name = "me", channelId = 1),
            11 to MumbleUser(session = 11, name = "alice", channelId = 1),
            12 to MumbleUser(session = 12, name = "bob", channelId = 0, mute = true),
            13 to MumbleUser(session = 13, name = "eve", channelId = 3, selfDeaf = true, recording = true),
        ),
        sessionId = 10,
    )

    private fun build(muted: Boolean = false, deaf: Boolean = false,
                      speaking: Set<Int> = emptySet(), selfTx: Boolean = false) =
        buildCallScreenState(model, speaking, selfTx, muted, deaf, configHostFallback = "host")

    private fun CallScreenState.channel(name: String) = channels.first { it.name == name }

    @Test fun serverNameFromRootChannel() {
        assertEquals("Acoustic HQ", build().serverName)
    }

    @Test fun rootNamedRootFallsBackToHost() {
        val m = ServerModel(
            channels = mapOf(0 to MumbleChannel(id = 0, parentId = null, name = "Root", position = 0)),
            users = mapOf(10 to MumbleUser(session = 10, name = "me", channelId = 0)),
            sessionId = 10,
        )
        assertEquals("host", buildCallScreenState(m, emptySet(), false, false, false, "host").serverName)
    }

    @Test fun emptyModelFallsBackToHost() {
        assertEquals("host", buildCallScreenState(ServerModel(), emptySet(), false, false, false, "host").serverName)
    }

    @Test fun channelsOrderedDepthFirstWithDepth() {
        val chans = build().channels
        assertEquals(listOf("Acoustic HQ", "Gaming", "Sub"), chans.map { it.name })  // Empty hidden
        assertEquals(listOf(0, 1, 2), chans.map { it.depth })
    }

    @Test fun emptyChannelsHiddenExceptYours() {
        assertFalse("empty non-self channel hidden", build().channels.any { it.name == "Empty" })
    }

    @Test fun selfFloatedToTopAndTaggedYou() {
        val gaming = build().channel("Gaming")
        assertEquals("me", gaming.users.first().name)
        assertTrue(gaming.users.first().isYou)
        assertTrue("your channel is active", gaming.isActive)
    }

    @Test fun serverMuteVsSelfMuteMapping() {
        val bob = build().channel("Acoustic HQ").users.first { it.name == "bob" }
        assertTrue("mute -> server (red)", bob.serverMute); assertFalse(bob.selfMute)
    }

    @Test fun selfRowPrefersLocalMuteAndTransmit() {
        val meMuted = build(muted = true).channel("Gaming").users.first()
        assertTrue(meMuted.selfMute); assertFalse(meMuted.speaking)
        val meTx = build(selfTx = true).channel("Gaming").users.first()
        assertTrue("local transmit drives own speaking", meTx.speaking)
    }

    @Test fun otherSpeakingFromSet() {
        val alice = build(speaking = setOf(11)).channel("Gaming").users.first { it.name == "alice" }
        assertTrue(alice.speaking)
    }

    @Test fun deafAndRecordingMapping() {
        val eve = build().channel("Sub").users.first { it.name == "eve" }
        assertTrue("self-deaf badge", eve.selfDeaf); assertFalse(eve.serverDeaf)
        assertTrue("recording indicator", eve.recording)
    }

    @Test fun localDeafenedReflectedOnSelfRow() {
        val me = build(deaf = true).channel("Gaming").users.first()
        assertTrue("local deafen shows on your row", me.selfDeaf)
    }
}
