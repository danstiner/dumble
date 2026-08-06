package me.danielstiner.dumble.ui.connect

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes

/**
 * Where call audio goes: a menu anchored to the control that opened it.
 *
 * A dropdown, as the prototype had it (`ActiveCallScreen.kt:302` on `vibed-prototype`), not a modal
 * sheet. The list is two to four rows, and a sheet spends a scrim over the roster, a drag handle and
 * a section header on that — then covers the whole control bar, including the button just pressed.
 *
 * Rows come pre-sorted by [AudioRoute]'s own ordering, so this renders the list as given rather than
 * deciding anything.
 *
 * Selecting sends the id and closes; nothing here marks the new route as current. The check moves
 * only when the platform confirms through `currentCallEndpoint`, the same discipline deafen uses in
 * reading itself back from the channel tree.
 */
@Composable
fun AudioRouteMenu(
    expanded: Boolean,
    routes: AudioRoutes,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        routes.available.forEach { route ->
            val selected = route.id == routes.current?.id
            DropdownMenuItem(
                text = { Text(route.label) },
                onClick = { onSelect(route.id); onDismiss() },
                leadingIcon = { Icon(routeIcon(route.type), null) },
                trailingIcon = {
                    // Tinted rather than the default content colour: this is the one glyph in the
                    // menu answering "where is my audio right now", and it sits at the far edge.
                    if (selected) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                // Description on the clickable node, never on the Icon — the accessibility fix
                // documented in ControlButton's KDoc. It carries the check too, which is otherwise
                // colour and glyph only.
                modifier = Modifier.semantics {
                    contentDescription =
                        if (selected) "${route.label}, current route" else route.label
                },
            )
        }
    }
}

internal fun routeIcon(type: AudioRoute.Type): ImageVector = when (type) {
    AudioRoute.Type.BLUETOOTH -> Icons.Filled.BluetoothAudio
    AudioRoute.Type.WIRED_HEADSET -> Icons.Filled.Headset
    AudioRoute.Type.EARPIECE -> Icons.Filled.PhoneInTalk
    // Speaker's own glyph doubles as the fallback: an unknown or streaming route is still output.
    AudioRoute.Type.SPEAKER, AudioRoute.Type.STREAMING, AudioRoute.Type.UNKNOWN ->
        Icons.AutoMirrored.Filled.VolumeUp
}
