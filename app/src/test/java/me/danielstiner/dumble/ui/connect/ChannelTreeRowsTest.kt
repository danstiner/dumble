package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.channeltree.Channel
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTreeRowsTest {

    private fun user(session: Int, name: String, channelId: Int) =
        User(session, name, channelId, false, false, false, false, false)

    @Test fun nestsChildrenByDepthDepthFirst() {
        val tree = ChannelTree(
            channels = mapOf(
                0 to Channel(0, null, "Root", 0),
                1 to Channel(1, 0, "Gaming", 0),
            ),
            users = mapOf(7 to user(7, "alice", 1)),
        )
        val rows = channelTreeRows(tree, mySession = null)
        assertEquals(
            listOf(
                ChannelTreeRow.ChannelRow(0, 0, "Root", 0, false),
                ChannelTreeRow.ChannelRow(1, 1, "Gaming", 1, false),
                ChannelTreeRow.UserRow(2, 7, "alice", false, false, false, false, false, false),
            ),
            rows,
        )
    }

    @Test fun siblingsOrderByPositionThenName() {
        val tree = ChannelTree(
            channels = mapOf(
                0 to Channel(0, null, "Root", 0),
                1 to Channel(1, 0, "Bravo", 5),
                2 to Channel(2, 0, "Alpha", 1),
            ),
        )
        val names = channelTreeRows(tree, null).filterIsInstance<ChannelTreeRow.ChannelRow>().map { it.name }
        assertEquals(listOf("Root", "Alpha", "Bravo"), names)
    }

    @Test fun usersUnderAChannelSortByName() {
        val tree = ChannelTree(
            channels = mapOf(0 to Channel(0, null, "Root", 0)),
            users = mapOf(1 to user(1, "carol", 0), 2 to user(2, "bob", 0)),
        )
        val names = channelTreeRows(tree, null).filterIsInstance<ChannelTreeRow.UserRow>().map { it.name }
        assertEquals(listOf("bob", "carol"), names)
    }

    @Test fun tagsMyChannelAndMyRow() {
        val tree = ChannelTree(
            channels = mapOf(0 to Channel(0, null, "Root", 0), 1 to Channel(1, 0, "Gaming", 0)),
            users = mapOf(7 to user(7, "me", 1)),
        )
        val rows = channelTreeRows(tree, mySession = 7)
        assertTrue(rows.filterIsInstance<ChannelTreeRow.ChannelRow>().single { it.id == 1 }.isMine)
        assertTrue(rows.filterIsInstance<ChannelTreeRow.UserRow>().single().isMe)
    }

    @Test fun omitsUserWhoseChannelIsUnknown() {
        val tree = ChannelTree(
            channels = mapOf(0 to Channel(0, null, "Root", 0)),
            users = mapOf(9 to user(9, "ghost", 42)),   // channel 42 not present
        )
        assertTrue(channelTreeRows(tree, null).filterIsInstance<ChannelTreeRow.UserRow>().isEmpty())
    }

    @Test fun fallsBackToParentlessChannelsWhenNoRootZero() {
        val tree = ChannelTree(
            channels = mapOf(5 to Channel(5, null, "Orphaned root", 0)),
        )
        assertEquals(
            listOf(ChannelTreeRow.ChannelRow(0, 5, "Orphaned root", 0, false)),
            channelTreeRows(tree, null),
        )
    }
}
