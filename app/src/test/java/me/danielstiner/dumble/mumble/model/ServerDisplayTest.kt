package me.danielstiner.dumble.mumble.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDisplayTest {
    private fun ch(id: Int, parent: Int?, name: String) = MumbleChannel(id = id, parentId = parent, name = name)
    private fun user(session: Int, channel: Int) = MumbleUser(session = session, name = "u$session", channelId = channel)

    @Test fun serverLabel_usesRootChannelName() {
        val m = ServerModel(channels = mapOf(0 to ch(0, null, "My Server"), 1 to ch(1, 0, "General")))
        assertEquals("My Server", serverLabel(m, "host.example"))
    }

    @Test fun serverLabel_fallsBackWhenRootBlankOrDefaultOrMissing() {
        assertEquals("host.example", serverLabel(ServerModel(channels = mapOf(0 to ch(0, null, "Root"))), "host.example"))
        assertEquals("host.example", serverLabel(ServerModel(channels = mapOf(0 to ch(0, null, ""))), "host.example"))
        assertEquals("host.example", serverLabel(ServerModel(), "host.example"))
    }

    @Test fun currentChannelName_selfChannel() {
        val m = ServerModel(
            channels = mapOf(0 to ch(0, null, "Root"), 5 to ch(5, 0, "General")),
            users = mapOf(7 to user(7, 5)),
            sessionId = 7,
        )
        assertEquals("General", currentChannelName(m))
    }

    @Test fun currentChannelName_nullBeforeSyncOrMissing() {
        assertNull(currentChannelName(ServerModel(channels = mapOf(0 to ch(0, null, "Root")))))  // sessionId null
        assertNull(currentChannelName(ServerModel(sessionId = 7)))                                // user missing
    }
}
