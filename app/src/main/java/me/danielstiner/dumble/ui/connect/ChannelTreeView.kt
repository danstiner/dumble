package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.channeltree.ChannelTree

@Composable
fun ChannelTreeView(tree: ChannelTree, mySession: Int, speaking: Set<Int> = emptySet(), modifier: Modifier = Modifier) {
    // Flatten once per (tree, mySession, speaking); the list feeds a flat LazyColumn — no recursive composables.
    val rows = remember(tree, mySession, speaking) { channelTreeRows(tree, mySession, speaking) }
    LazyColumn(modifier) {
        items(
            rows,
            key = { row ->
                when (row) {
                    is ChannelTreeRow.ChannelRow -> "c${row.id}"
                    is ChannelTreeRow.UserRow -> "u${row.session}"
                }
            },
        ) { row ->
            val indent = Modifier.padding(start = (row.depth * 16).dp)
            when (row) {
                is ChannelTreeRow.ChannelRow -> Text(
                    "${row.name} (${row.userCount})",
                    fontWeight = if (row.isMine) FontWeight.Bold else FontWeight.Medium,
                    modifier = indent.padding(top = 6.dp),
                )
                is ChannelTreeRow.UserRow -> Text(
                    userLabel(row),
                    fontWeight = if (row.isMe) FontWeight.Bold else FontWeight.Normal,
                    modifier = indent,
                )
            }
        }
    }
}

/** Glyphs stand in for proper icons; server-mute and self-mute collapse to one speaker-off mark. */
private fun userLabel(u: ChannelTreeRow.UserRow): String = buildString {
    if (u.isSpeaking) append("🔊 ")
    append(u.name)
    if (u.mute || u.selfMute) append(" 🔇")
    if (u.deaf || u.selfDeaf) append(" 🔈")
    if (u.suppress) append(" 🚫")
}
