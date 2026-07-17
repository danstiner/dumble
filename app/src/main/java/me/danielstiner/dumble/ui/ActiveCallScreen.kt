package me.danielstiner.dumble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.telecom.AudioRoute
import me.danielstiner.dumble.ui.theme.DumbleTheme

// Deterministic avatar palette — indexed by session; reads acceptably on light and dark.
private val avatarPalette = listOf(
    Color(0xFF5A6BF0), Color(0xFF4CAF50), Color(0xFFC64AA6), Color(0xFFC6971F),
    Color(0xFF17A79A), Color(0xFF7E8AA0), Color(0xFF7E57C2), Color(0xFFC0603C),
)
private fun avatarColor(session: Int): Color = avatarPalette[Math.floorMod(session, avatarPalette.size)]

/** One selectable audio route for the route picker (shown when a headset is connected). */
data class RouteOption(val type: Int, val icon: AudioRoute.RouteIcon, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    state: CallScreenState,
    connectedText: String,                 // "Connected · 12:34" or "Connecting…"
    connecting: Boolean,                   // true until the call is synchronized — show a big connecting state
    muted: Boolean,
    deafened: Boolean,
    speaker: Boolean,
    routeIcon: AudioRoute.RouteIcon,
    routeLabel: String,                    // "Speaker" / "Bluetooth" / device name / …
    transmitMode: TransmitMode,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleSpeaker: () -> Unit,               // no-headset speaker toggle
    routeOptions: List<RouteOption>,           // available routes for the picker (when a headset is present)
    activeRouteType: Int?,
    onSelectRoute: (Int) -> Unit,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onHangUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Box(Modifier.padding(start = 12.dp).size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.HeadsetMic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp))
                    }
                },
                title = {
                    Column {
                        Text(state.serverName, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(connectedText, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Tune, "Settings") } },
            )
        },
        bottomBar = {
            ControlBar(muted, deafened, speaker, routeIcon, routeLabel, transmitMode,
                onToggleMute, onToggleDeafen, onToggleSpeaker, routeOptions, activeRouteType, onSelectRoute,
                onPttPress, onPttRelease, onHangUp)
        },
    ) { padding ->
        if (connecting) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
                    Text("Connecting…", style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
                state.channels.forEach { ch ->
                    item(key = "ch-${ch.id}") { ChannelHeader(ch) }
                    items(ch.users, key = { "u-${it.session}" }) { u -> UserRow(u) }
                }
            }
        }
    }
}

@Composable
private fun ChannelHeader(ch: ChannelVm) {
    val color = if (ch.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = (16 + ch.depth * 12).dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp), tint = color)
        Text(ch.name.uppercase(), style = MaterialTheme.typography.labelLarge, color = color,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun UserRow(u: UserVm) {
    ListItem(
        leadingContent = { Avatar(u) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (u.isYou) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary) { Text("YOU") }
                }
            }
        },
        trailingContent = if (u.recording || u.speaking) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (u.recording) Icon(Icons.Filled.FiberManualRecord, "recording",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                    if (u.speaking) {
                        if (u.recording) Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.GraphicEq, "speaking", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else null,
    )
}

@Composable
private fun Avatar(u: UserVm) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        val ring = if (u.speaking) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
        Box(Modifier.size(40.dp).then(ring).clip(CircleShape).background(avatarColor(u.session)),
            contentAlignment = Alignment.Center) {
            Text(u.initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        // Badge priority: deaf (can't hear you) over mute. Server-imposed = error/red, self = neutral.
        val showDeaf = u.serverDeaf || u.selfDeaf
        val showMute = !showDeaf && (u.serverMute || u.selfMute)
        if (showDeaf || showMute) {
            val server = if (showDeaf) u.serverDeaf else u.serverMute
            Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape)
                .background(if (server) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                Icon(if (showDeaf) Icons.Filled.HeadsetOff else Icons.Filled.MicOff,
                    if (showDeaf) "deafened" else "muted", modifier = Modifier.size(11.dp),
                    tint = if (server) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ControlBar(
    muted: Boolean, deafened: Boolean, speaker: Boolean,
    routeIcon: AudioRoute.RouteIcon, routeLabel: String, transmitMode: TransmitMode,
    onToggleMute: () -> Unit, onToggleDeafen: () -> Unit, onToggleSpeaker: () -> Unit,
    routeOptions: List<RouteOption>, activeRouteType: Int?, onSelectRoute: (Int) -> Unit,
    onPttPress: () -> Unit, onPttRelease: () -> Unit, onHangUp: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            if (transmitMode == TransmitMode.PUSH_TO_TALK) {
                // Deafen implies self-mute; disable Talk while deafened (same hot-mic guard as Mute).
                HoldToTalkControl(enabled = !deafened, onPress = onPttPress, onRelease = onPttRelease)
            } else {
                // Mute stays tappable while deafened; tapping Unmute then undeafens too (matches desktop/iOS
                // + the murmur-enforced self_mute=false => self_deaf=false invariant, handled in setMuted).
                ControlToggle(checked = muted, onCheckedChange = { onToggleMute() },
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (muted) "Unmute" else "Mute", danger = true)
            }
            ControlToggle(checked = deafened, onCheckedChange = { onToggleDeafen() },
                icon = if (deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headphones,
                label = if (deafened) "Undeafen" else "Deafen", danger = true)
            // Headset present → route picker (like the stock phone app); else a plain Speaker toggle.
            if (routeOptions.any { it.icon == AudioRoute.RouteIcon.BLUETOOTH || it.icon == AudioRoute.RouteIcon.WIRED }) {
                RouteControl(icon = routeIcon, label = routeLabel, options = routeOptions,
                    activeType = activeRouteType, onSelect = onSelectRoute)
            } else {
                ControlToggle(checked = speaker, onCheckedChange = { onToggleSpeaker() },
                    icon = Icons.Filled.VolumeUp, label = "Speaker", danger = false)
            }
            LeaveControl(onHangUp)
        }
    }
}

/** Standard M3 filled icon toggle button + caption. `danger` = its "on" state means muted/deafened (error tint). */
@Composable
private fun ControlToggle(
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector, label: String, danger: Boolean, enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    ControlColumn(label) {
        FilledIconToggleButton(
            checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            modifier = Modifier.size(60.dp),
            shape = if (checked) RoundedCornerShape(percent = 35) else CircleShape,   // morph to squircle when active
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = cs.surfaceContainerHighest,          // inactive blends into the bar
                contentColor = cs.onSurface,
                checkedContainerColor = if (danger) cs.errorContainer else cs.inverseSurface,   // active highlight
                checkedContentColor = if (danger) cs.onErrorContainer else cs.inverseOnSurface,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(26.dp))
        }
    }
}

/** Audio-route picker (shown when a headset is connected) — current device + chevron, tap for a menu. */
@Composable
private fun RouteControl(
    icon: AudioRoute.RouteIcon, label: String,
    options: List<RouteOption>, activeType: Int?, onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        ControlColumn(label) {
            // White-ish highlight when on a non-default route (not earpiece); dark/blended otherwise.
            val highlighted = icon != AudioRoute.RouteIcon.EARPIECE
            FilledIconButton(
                onClick = { expanded = true }, modifier = Modifier.size(60.dp),
                shape = if (highlighted) RoundedCornerShape(percent = 35) else CircleShape,   // squircle when non-default
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (highlighted) cs.inverseSurface else cs.surfaceContainerHighest,
                    contentColor = if (highlighted) cs.inverseOnSurface else cs.onSurface,
                ),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(routeIconVector(icon), "Audio route", modifier = Modifier.size(22.dp))
                    Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(16.dp))   // chevron affordance
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    onClick = { onSelect(opt.type); expanded = false },
                    leadingIcon = { Icon(routeIconVector(opt.icon), null) },
                    trailingIcon = {
                        if (opt.type == activeType) Icon(Icons.Filled.Check, "selected",
                            tint = MaterialTheme.colorScheme.primary)
                    },
                )
            }
        }
    }
}

@Composable
private fun LeaveControl(onHangUp: () -> Unit) {
    ControlColumn("Disconnect") {
        // Fixed deep red like the stock phone app's hang-up (M3 `error` goes light on dark themes).
        FilledIconButton(
            onClick = onHangUp, modifier = Modifier.size(width = 76.dp, height = 56.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
        ) {
            Icon(Icons.Filled.CallEnd, "Disconnect", modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun HoldToTalkControl(enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    DisposableEffect(Unit) { onDispose { currentRelease() } }   // release if it leaves composition while held
    val cs = MaterialTheme.colorScheme
    val container = when { !enabled -> cs.onSurface.copy(alpha = 0.12f); pressed -> cs.inverseSurface; else -> cs.surfaceContainerHighest }
    val content = when { !enabled -> cs.onSurface.copy(alpha = 0.38f); pressed -> cs.inverseOnSurface; else -> cs.onSurface }
    // Raw pointerInput drives press/hold, so the button semantics (role + label, disabled state) must
    // be set explicitly — a Surface with no onClick isn't exposed as an actionable button to TalkBack.
    val gesture = if (enabled) Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            pressed = true; currentPress(); tryAwaitRelease(); pressed = false; currentRelease()
        })
    } else Modifier
    ControlColumn(if (pressed) "Release" else "Talk") {
        Surface(shape = if (pressed) RoundedCornerShape(percent = 35) else CircleShape, color = container, contentColor = content,
            modifier = Modifier.size(60.dp).then(gesture).semantics {
                role = Role.Button; contentDescription = "Push to talk"; if (!enabled) disabled()
            }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, null, modifier = Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun ControlColumn(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 76.dp))
    }
}

private fun routeIconVector(icon: AudioRoute.RouteIcon): ImageVector = when (icon) {
    AudioRoute.RouteIcon.BLUETOOTH -> Icons.Filled.BluetoothAudio
    AudioRoute.RouteIcon.WIRED -> Icons.Filled.Headset
    AudioRoute.RouteIcon.EARPIECE -> Icons.Filled.PhoneInTalk
    AudioRoute.RouteIcon.SPEAKER, AudioRoute.RouteIcon.UNKNOWN -> Icons.Filled.VolumeUp
}

@Preview
@Composable
private fun ActiveCallScreenPreview() {
    DumbleTheme {
        val state = CallScreenState(
            serverName = "Acoustic HQ",
            channels = listOf(
                ChannelVm(1, "General", isActive = true, depth = 0, users = listOf(
                    UserVm(10, "C", "citelao", isYou = true, speaking = true, selfMute = false, serverMute = false,
                        selfDeaf = false, serverDeaf = false, recording = false),
                    UserVm(11, "A", "AdamTReineke", isYou = false, speaking = false, selfMute = false, serverMute = false,
                        selfDeaf = false, serverDeaf = false, recording = false),
                )),
                ChannelVm(2, "Gaming", isActive = false, depth = 1, users = listOf(
                    UserVm(12, "H", "hayden", isYou = false, speaking = true, selfMute = false, serverMute = false,
                        selfDeaf = false, serverDeaf = false, recording = true),
                    UserVm(13, "G", "gun", isYou = false, speaking = false, selfMute = false, serverMute = false,
                        selfDeaf = true, serverDeaf = false, recording = false),
                )),
            ),
        )
        ActiveCallScreen(state, "Connected · 24:32", connecting = false, muted = false, deafened = false, speaker = false,
            routeIcon = AudioRoute.RouteIcon.BLUETOOTH, routeLabel = "OpenRun by Shokz",
            transmitMode = TransmitMode.VOICE_ACTIVATED,
            onToggleMute = {}, onToggleDeafen = {}, onToggleSpeaker = {},
            routeOptions = listOf(
                RouteOption(2, AudioRoute.RouteIcon.BLUETOOTH, "OpenRun by Shokz"),
                RouteOption(4, AudioRoute.RouteIcon.SPEAKER, "Speaker"),
                RouteOption(1, AudioRoute.RouteIcon.EARPIECE, "Earpiece"),
            ),
            activeRouteType = 2, onSelectRoute = {},
            onPttPress = {}, onPttRelease = {}, onHangUp = {}, onOpenSettings = {})
    }
}
