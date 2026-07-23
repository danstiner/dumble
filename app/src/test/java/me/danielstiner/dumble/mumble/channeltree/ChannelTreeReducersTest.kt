package me.danielstiner.dumble.mumble.channeltree

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTreeReducersTest {

    @Test fun channelStateAddsAChannelAndRootHasNoParent() {
        val tree = ChannelTreeReducers.applyChannelState(
            ChannelTree(),
            MumbleProtos.ChannelState.newBuilder().setChannelId(0).setName("Root").build(),
        )
        assertEquals(Channel(0, null, "Root", 0), tree.channels[0])
        assertNull(tree.channels[0]!!.parentId)
    }

    @Test fun partialChannelStatePreservesExistingFields() {
        var tree = ChannelTreeReducers.applyChannelState(
            ChannelTree(),
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setParent(0).setName("Gaming").setPosition(2).build(),
        )
        tree = ChannelTreeReducers.applyChannelState(
            tree,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setPosition(9).build(),
        )
        assertEquals("Gaming", tree.channels[1]!!.name)
        assertEquals(0, tree.channels[1]!!.parentId)
        assertEquals(9, tree.channels[1]!!.position)
    }

    @Test fun userStateAddsAUserWithFlags() {
        val tree = ChannelTreeReducers.applyUserState(
            ChannelTree(),
            MumbleProtos.UserState.newBuilder().setSession(7).setName("alice").setChannelId(1).setSelfMute(true).build(),
        )
        val u = tree.users[7]!!
        assertEquals("alice", u.name)
        assertEquals(1, u.channelId)
        assertTrue(u.selfMute)
        assertFalse(u.deaf)
    }

    @Test fun partialUserStatePreservesExistingFields() {
        var tree = ChannelTreeReducers.applyUserState(
            ChannelTree(),
            MumbleProtos.UserState.newBuilder().setSession(7).setName("alice").setChannelId(1).build(),
        )
        tree = ChannelTreeReducers.applyUserState(
            tree,
            MumbleProtos.UserState.newBuilder().setSession(7).setChannelId(2).build(),
        )
        assertEquals("alice", tree.users[7]!!.name)
        assertEquals(2, tree.users[7]!!.channelId)
    }

    @Test fun partialUserStatePreservesBooleanFlags() {
        var tree = ChannelTreeReducers.applyUserState(
            ChannelTree(),
            MumbleProtos.UserState.newBuilder().setSession(7).setName("alice").setChannelId(1).setSelfMute(true).build(),
        )
        // A pure channel move omits the flags; they must not revert to false.
        tree = ChannelTreeReducers.applyUserState(
            tree,
            MumbleProtos.UserState.newBuilder().setSession(7).setChannelId(2).build(),
        )
        assertTrue(tree.users[7]!!.selfMute)
    }

    @Test fun removesPruneEntries() {
        var tree = ChannelTree(
            channels = mapOf(1 to Channel(1, 0, "Gaming", 0)),
            users = mapOf(7 to User(7, "alice", 1, false, false, false, false, false)),
        )
        tree = ChannelTreeReducers.applyChannelRemove(tree, MumbleProtos.ChannelRemove.newBuilder().setChannelId(1).build())
        tree = ChannelTreeReducers.applyUserRemove(tree, MumbleProtos.UserRemove.newBuilder().setSession(7).build())
        assertTrue(tree.channels.isEmpty())
        assertTrue(tree.users.isEmpty())
    }
}
