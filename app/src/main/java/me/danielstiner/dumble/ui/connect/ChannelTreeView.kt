package me.danielstiner.dumble.ui.connect

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.channeltree.ChannelTree

@Composable
fun ChannelTreeView(
    tree: ChannelTree,
    mySession: Int,
    speaking: Set<Int>,
    onUserClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                is ChannelTreeRow.UserRow -> UserRow(row, onUserClick)
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
private fun UserRow(u: ChannelTreeRow.UserRow, onClick: (Int) -> Unit) {
    ListItem(
        // ListItem's own 16dp start padding equals ChannelHeader's indent at depth 0; adding
        // depth * 12dp mirrors the header's per-level step so a user sits under its own channel.
        // clickable inside the padding so the ripple stays under the row, not the indent.
        //
        // stateDescription, not contentDescription: ListItem merges descendants, and a merged
        // contentDescription replaces the text — the row read as "speaking" with the name gone.
        modifier = Modifier.padding(start = (u.depth * 12).dp)
            .clickable { onClick(u.session) }
            .then(
                if (u.isSpeaking) Modifier.semantics { stateDescription = "speaking" }
                else Modifier
            ),
        leadingContent = { Avatar(u) },
    ) {
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
    }
}

private val HaloBoxSize = 48.dp

// Extent is taste. Alpha is not: the halo is the only visual speaking cue, and 0.60 measured
// 2.68:1 on baseline light, under WCAG 1.4.11's 3:1. 0.70 is 3.26:1 — do not lower it unmeasured.
private const val GlowAlpha = 0.70f
private const val GlowExtent = 1.30f

/**
 * A 40 dp circle in the user's own color, inside a 48 dp box carrying the speaking halo.
 *
 * The halo is `primary`, not the user's color — one consistent color reads as "someone is talking"
 * more easily than sixteen. Only alpha animates, so the roster never reflows.
 */
@Composable
private fun Avatar(u: ChannelTreeRow.UserRow) {
    val cs = MaterialTheme.colorScheme
    val halo = cs.primary
    val haloAlpha by animateFloatAsState(
        targetValue = if (u.isSpeaking) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "halo",
    )
    Box(
        Modifier
            .size(HaloBoxSize)
            .drawBehind {
                if (haloAlpha <= 0f) return@drawBehind
                // Full alpha out to the avatar's edge, then fall off. Starting the falloff any
                // earlier only buys a dead band, which reads as a gap between circle and halo.
                val radius = size.minDimension / 2f * GlowExtent
                val edge = AvatarSize.toPx() / 2f / radius
                val color = halo.copy(alpha = GlowAlpha * haloAlpha)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(0f to color, edge to color, 1f to Color.Transparent),
                        radius = radius,
                    ),
                    radius = radius,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(AvatarSize), contentAlignment = Alignment.Center) {
            AvatarCircle(u.name, Modifier.matchParentSize())
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
}
