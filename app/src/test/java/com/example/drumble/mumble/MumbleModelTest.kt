package com.example.drumble.mumble

import com.example.drumble.mumble.model.*
import com.example.drumble.mumble.proto.MumbleProtos
import org.junit.Assert.*
import org.junit.Test

class MumbleModelTest {
    private fun channelState(id: Int, name: String? = null, parent: Int? = null): MumbleProtos.ChannelState {
        val b = MumbleProtos.ChannelState.newBuilder().setChannelId(id)
        name?.let { b.setName(it) }; parent?.let { b.setParent(it) }
        return b.build()
    }
    private fun userState(session: Int, name: String? = null, channel: Int? = null): MumbleProtos.UserState {
        val b = MumbleProtos.UserState.newBuilder().setSession(session)
        name?.let { b.setName(it) }; channel?.let { b.setChannelId(it) }
        return b.build()
    }

    @Test fun channelTreeAndPartialUpdate() {
        var m = ServerModel()
        m = ModelReducers.applyChannelState(m, channelState(0, name = "Root"))
        m = ModelReducers.applyChannelState(m, channelState(1, name = "Lobby", parent = 0))
        m = ModelReducers.applyChannelState(m, channelState(1, parent = 0)) // no name → preserved
        assertEquals("Lobby", m.channels[1]!!.name)
        assertEquals(0, m.channels[1]!!.parentId)
        m = ModelReducers.applyChannelRemove(m, MumbleProtos.ChannelRemove.newBuilder().setChannelId(1).build())
        assertNull(m.channels[1])
    }

    @Test fun userLifecycle() {
        var m = ServerModel()
        m = ModelReducers.applyUserState(m, userState(42, name = "dan"))
        assertEquals(0, m.users[42]!!.channelId) // joins root by default
        m = ModelReducers.applyUserState(m, userState(42, channel = 3))
        assertEquals("dan", m.users[42]!!.name)  // preserved
        assertEquals(3, m.users[42]!!.channelId)
        m = ModelReducers.applyUserRemove(m, MumbleProtos.UserRemove.newBuilder().setSession(42).build())
        assertNull(m.users[42])
    }

    @Test fun serverSync() {
        var m = ServerModel()
        val sync = MumbleProtos.ServerSync.newBuilder().setSession(7).setMaxBandwidth(72000).build()
        m = ModelReducers.applyServerSync(m, sync)
        assertEquals(7, m.sessionId)
        assertEquals(72000, m.maxBandwidth)
    }

    @Test fun holderEmitsSnapshots() {
        val holder = MumbleModel()
        holder.apply { onChannelState(channelState(1, name = "A", parent = 0)) }
        assertEquals("A", holder.state.value.channels[1]!!.name)
        holder.reset()
        assertTrue(holder.state.value.channels.isEmpty())
    }
}
