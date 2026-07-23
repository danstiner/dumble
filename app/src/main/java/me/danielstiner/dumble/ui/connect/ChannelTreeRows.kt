package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.channeltree.Channel
import me.danielstiner.dumble.mumble.channeltree.ChannelTree

/** One rendered line. [depth] drives indentation; the composable stays a flat loop. */
sealed interface ChannelTreeRow {
    val depth: Int

    data class ChannelRow(
        override val depth: Int,
        val id: Int,
        val name: String,
        val userCount: Int,
        val isMine: Boolean,
    ) : ChannelTreeRow

    data class UserRow(
        override val depth: Int,
        val session: Int,
        val name: String,
        val mute: Boolean,
        val deaf: Boolean,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val suppress: Boolean,
        val isMe: Boolean,
    ) : ChannelTreeRow
}

/**
 * Flatten the tree to an ordered, depth-tagged list: each channel, then its users (by name), then
 * its child channels (by position, then name), depth-first. Assumes an acyclic tree, which the
 * server guarantees. A user whose channel has not arrived yet is skipped and reappears on the next
 * emission.
 */
fun channelTreeRows(tree: ChannelTree, mySession: Int?): List<ChannelTreeRow> {
    val myChannelId = mySession?.let { tree.users[it]?.channelId }
    val childrenByParent = tree.channels.values.groupBy { it.parentId }
    val usersByChannel = tree.users.values.groupBy { it.channelId }
    val rows = mutableListOf<ChannelTreeRow>()

    fun visit(channel: Channel, depth: Int) {
        val users = usersByChannel[channel.id].orEmpty().sortedBy { it.name.lowercase() }
        rows += ChannelTreeRow.ChannelRow(depth, channel.id, channel.name, users.size, channel.id == myChannelId)
        for (u in users) {
            rows += ChannelTreeRow.UserRow(
                depth + 1, u.session, u.name,
                u.mute, u.deaf, u.selfMute, u.selfDeaf, u.suppress,
                isMe = u.session == mySession,
            )
        }
        childrenByParent[channel.id].orEmpty()
            .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
            .forEach { visit(it, depth + 1) }
    }

    tree.channels.values
        .filter { it.parentId == null || it.parentId !in tree.channels }
        .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
        .forEach { visit(it, 0) }
    return rows
}
