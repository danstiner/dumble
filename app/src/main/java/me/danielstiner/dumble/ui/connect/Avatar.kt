package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal val AvatarSize = 40.dp

/**
 * A filled circle in the user's own color carrying their initial. Shared by the roster row and the
 * detail sheet, so one name can never draw two different colors.
 */
@Composable
internal fun AvatarCircle(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CircleShape).background(avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstCodePoint().uppercase(),
            color = AvatarInitialColor,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The name's first code point — [String.take] splits a surrogate pair and renders half an emoji.
 *
 * Code points, not grapheme clusters: a name opening with a combining mark loses the mark.
 * `internal` only so [ChannelTreeViewInitialTest] can reach it without a composable.
 */
internal fun String.firstCodePoint(): String =
    if (isEmpty()) "" else String(Character.toChars(codePointAt(0)))
