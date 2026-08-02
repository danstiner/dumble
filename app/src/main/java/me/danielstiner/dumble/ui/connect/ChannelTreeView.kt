package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.channeltree.ChannelTree

@Composable
fun ChannelTreeView(tree: ChannelTree, mySession: Int, speaking: Set<Int>, modifier: Modifier = Modifier) {
    // Flatten once per (tree, mySession, speaking); the list feeds a flat LazyColumn — no recursive composables.
    val rows = remember(tree, mySession, speaking) { channelTreeRows(tree, mySession, speaking) }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(
            rows,
            key = { row ->
                when (row) {
                    is ChannelTreeRow.ChannelRow -> "c${row.id}"
                    is ChannelTreeRow.UserRow -> "u${row.session}"
                }
            },
        ) { row ->
            when (row) {
                is ChannelTreeRow.ChannelRow -> ChannelHeader(row)
                is ChannelTreeRow.UserRow -> UserRow(row)
            }
        }
    }
}

@Composable
private fun ChannelHeader(ch: ChannelTreeRow.ChannelRow) {
    val color =
        if (ch.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .padding(start = (16 + ch.depth * 12).dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp), tint = color)
        Text(
            "${ch.name.uppercase()} (${ch.userCount})",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun UserRow(u: ChannelTreeRow.UserRow) {
    ListItem(
        // ListItem's own 16dp start padding equals ChannelHeader's indent at depth 0; adding
        // depth * 12dp mirrors the header's per-level step so a user sits under its own channel.
        modifier = Modifier.padding(start = (u.depth * 12).dp),
        leadingContent = { Avatar(u) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    u.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (u.isMe) {
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Text("YOU") }
                }
            }
        },
        trailingContent = if (u.isSpeaking) {
            { Icon(Icons.Filled.GraphicEq, "speaking", tint = MaterialTheme.colorScheme.primary) }
        } else null,
    )
}

/**
 * Flat `primaryContainer` for now. PR 4 replaces the fill with a per-user colour and adds the
 * speaking halo; the 40 dp box and the badge anchoring are already shaped for it.
 */
@Composable
private fun Avatar(u: ChannelTreeRow.UserRow) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                u.name.take(1).uppercase(),
                color = cs.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        userBadge(u)?.let { badge ->
            Box(
                Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape)
                    .background(if (badge.server) cs.error else cs.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val deaf = badge.kind == UserBadge.Kind.DEAF
                Icon(
                    if (deaf) Icons.Filled.HeadsetOff else Icons.Filled.MicOff,
                    if (deaf) "deafened" else "muted",
                    modifier = Modifier.size(11.dp),
                    tint = if (badge.server) cs.onError else cs.onSurfaceVariant,
                )
            }
        }
    }
}
