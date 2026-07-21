package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.model.ServerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffChannelPresenceTest {
    /** users = (session, channelId); [myId] is our session. */
    private fun model(myId: Int?, vararg users: Pair<Int, Int>): ServerModel =
        ServerModel(
            users = users.associate { (s, c) -> s to MumbleUser(session = s, name = "u$s", channelId = c) },
            sessionId = myId,
        )

    @Test fun firstSnapshotBaselinesNoCues() {
        val d = diffChannelPresence(null, model(1, 1 to 0, 2 to 0))
        assertTrue(d.joins.isEmpty() && d.leaves.isEmpty())
        assertEquals(setOf(2), d.next.members)          // self (1) excluded
        assertEquals(0, d.next.channelId)
    }
    @Test fun detectsJoinInMyChannel() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2)), model(1, 1 to 0, 2 to 0, 3 to 0))
        assertEquals(setOf(3), d.joins); assertTrue(d.leaves.isEmpty())
    }
    @Test fun detectsLeave() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2, 3)), model(1, 1 to 0, 2 to 0))
        assertEquals(setOf(3), d.leaves); assertTrue(d.joins.isEmpty())
    }
    @Test fun ignoresOtherChannels() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2)), model(1, 1 to 0, 2 to 0, 4 to 5))
        assertTrue(d.joins.isEmpty() && d.leaves.isEmpty())   // user 4 is in channel 5, not ours
    }
    @Test fun myOwnChannelChangeRebaselinesNoCues() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2)), model(1, 1 to 7, 9 to 7))  // we moved to ch 7
        assertTrue(d.joins.isEmpty() && d.leaves.isEmpty())
        assertEquals(ChannelPresence(7, setOf(9)), d.next)
    }
}
