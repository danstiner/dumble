package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.routeMenuNeeded
import me.danielstiner.dumble.mumble.voice.speakerToggleTarget

// A wide pill normally, squarer while active. The shape change is what reads as "on" across the
// room, ahead of any colour difference.
private val controlPillShape = RoundedCornerShape(percent = 50)
private val controlActiveShape = RoundedCornerShape(percent = 30)

/**
 * The call screen's bottom bar. Buttons take equal weight so they are wide pills with large touch
 * targets rather than small circles, matching the stock phone app.
 *
 * Push-to-talk and mute are alternatives for the same slot, not separate controls: a mute button is
 * meaningless when the gate is already closed by default, so only Talk appears here. Deafen is not
 * redundant with push-to-talk — it is about not hearing others, not about not transmitting. Speaker
 * is now the audio-route control, which reads [AudioRoutes.available] to decide whether it is a
 * speaker toggle or a menu — see [RouteControl].
 *
 * [deafened] and [talkBlock] are the server's answer, not the last tap, so both lag a round trip and
 * that is deliberate — see `ConnectViewModel.onToggleDeafen`.
 */
@Composable
fun CallControls(
    talkBlock: TalkBlock?,
    deafened: Boolean,
    audioRoutes: AudioRoutes,
    onTransmitting: (Boolean) -> Unit,
    onToggleDeafen: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TalkControl(talkBlock, onTransmitting, Modifier.weight(1f))
            ControlButton(
                icon = if (deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headphones,
                label = "Deafen",
                // The caption stays "Deafen" — a label that renamed itself under the user's thumb
                // reads as the button having moved. Shape and colour carry the state, and this
                // spells it out for screen readers.
                description = if (deafened) "Undeafen — you cannot hear anyone" else "Deafen",
                active = deafened, onClick = onToggleDeafen, modifier = Modifier.weight(1f),
            )
            RouteControl(audioRoutes, onSelectRoute, Modifier.weight(1f))
            ControlButton(
                icon = Icons.Filled.CallEnd, label = "Disconnect", onClick = onHangUp,
                // A fixed deep red rather than the theme's error colour, which goes pale on dark and
                // stops reading as the hang-up button.
                container = Color(0xFFD32F2F), content = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Push-to-talk, driven by [PressInteraction] from the button's own interaction source rather than a
 * raw `pointerInput`. A Material button consumes the down event before any gesture detector attached
 * to it would see one, and the interaction source is the supported way to observe press and release
 * while keeping the button's semantics, ripple and disabled handling.
 *
 * The caption cannot hold a full explanation, so the reason goes to `contentDescription` where
 * screen readers and the long-press tooltip still carry it.
 *
 * [block] can change mid-press, unlike the microphone answer it replaced: an admin mute, a channel
 * suppress, or a deafen from a second finger all disable this button under a held thumb. Compose
 * disposes the button's interactions on disable and emits `PressInteraction.Cancel`, which the
 * collector below treats as a release — without that the transmit level stays raised and every
 * rebuilt capture session re-applies it. Pinned by `talkDisabledWhileHeldClosesTheGate`, and
 * measured on device by muting a held talker from another client.
 */
@Composable
private fun TalkControl(
    block: TalkBlock?,
    onTransmitting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactions) {
        // Press/Release/Cancel only — hover and focus interactions share this flow, so an `else`
        // here would close the gate when the button takes focus mid-press.
        interactions.interactions.collect {
            if (it is PressInteraction) {
                pressed = it is PressInteraction.Press
                onTransmitting(pressed)
            }
        }
    }
    // The gesture's own Cancel cannot close the gate when the button is taken away mid-press: the
    // clickable node emits it at detach, but this composable's LaunchedEffect is cancelled in the
    // same frame and never resumes to read it. Two fingers reach it — hold Talk, tap Chat or
    // Settings — and so does rotating the device one-handed, which recreates the Activity under a
    // button still held. That leaves the level set, and the level is what every later capture
    // session re-applies, so the microphone stays live across rebuilds. Measured by deleting this
    // line: the engine kept encoding at 50 packets/s for 22 s after the composition was destroyed,
    // with nothing pressed. A no-op on an ordinary release, which has already cleared `pressed`.
    DisposableEffect(Unit) { onDispose { if (pressed) onTransmitting(false) } }
    val cs = MaterialTheme.colorScheme
    ControlColumn(talkCaption(block), modifier) {
        FilledIconButton(
            onClick = {},
            enabled = block == null,
            interactionSource = interactions,
            modifier = Modifier.fillMaxWidth().height(72.dp).semantics {
                contentDescription = talkDescription(block)
            },
            shape = if (pressed) controlActiveShape else controlPillShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (pressed) cs.inverseSurface else cs.surfaceBright,
                contentColor = if (pressed) cs.inverseOnSurface else cs.onSurface,
            ),
        ) {
            Icon(Icons.Filled.Mic, null, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * The audio-route control, in the stock phone app's two modes — see [routeMenuNeeded] for the rule
 * and where it was verified.
 *
 * With no Bluetooth device around there are at most three routes and one of them is the speaker, so
 * the control is a plain speaker toggle: a menu to choose between "speaker" and "not speaker" is a
 * tap and a decision spent on a binary. Once a Bluetooth device appears the choice stops being
 * binary and the control becomes a menu anchored to itself.
 *
 * The caption names the current route in both modes rather than the stock app's fixed "Speaker" /
 * "Audio" — with a device name available, "OpenRun by Shokz" says everything the icon and the label
 * were separately trying to.
 *
 * Highlighted on speaker in toggle mode (that is what the toggle is *about*), and on anything but
 * the earpiece in menu mode, matching `SpeakerButtonInfo`'s `isChecked` in each branch.
 */
@Composable
private fun RouteControl(
    routes: AudioRoutes,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = routes.current
    val menuMode = routeMenuNeeded(routes.available)
    // Saveable, not remember: a rotation with the picker open otherwise drops it, and the user is
    // back where they started with no idea why. Caught on device by rotating mid-pick.
    //
    // Keyed on menuMode, which resets it on every mode flip. Leaving menu mode drops the menu from
    // composition without any dismiss firing, so an unkeyed `true` outlives the menu it described
    // and reopens it unasked when Bluetooth comes back — the rotation defect's twin.
    var menuOpen by rememberSaveable(menuMode) { mutableStateOf(false) }
    val toggleTarget = if (menuMode) null else speakerToggleTarget(routes.available, current)
    // Whatever is actually known, in order: the route the platform has named, then the destination
    // a tap would reach — the latter only in toggle mode, where the tap goes straight there with no
    // menu in between to read. Both can be absent, and independently: `available` and `current` are
    // separate collectors, so a frame arrives with routes but no current yet, and that used to read
    // as the bare "Audio output" of a dead control while the button was live with somewhere to go.
    val known = listOfNotNull(
        current?.label,
        toggleTarget?.let { "tap for ${it.label}" },
    ).joinToString(", ")
    // The Box is the menu's anchor, so it takes the caller's weight and the button fills it.
    Box(modifier) {
        ControlButton(
            icon = routeIcon(current?.type ?: AudioRoute.Type.UNKNOWN),
            label = current?.label ?: "Audio",
            description = if (known.isEmpty()) "Audio output" else "Audio output — $known",
            // menuMode implies a Bluetooth device is in `available`, so there is always something to
            // open; the toggle has to check, because "speaker and nothing else" has no destination.
            enabled = menuMode || toggleTarget != null,
            active = if (menuMode) {
                current != null && current.type != AudioRoute.Type.EARPIECE
            } else {
                current?.type == AudioRoute.Type.SPEAKER
            },
            onClick = {
                if (menuMode) menuOpen = true else toggleTarget?.let { onSelect(it.id) }
            },
            opensMenu = menuMode,
            modifier = Modifier.fillMaxWidth(),
        )
        // Only composed in menu mode: a dropdown anchored to a button that never opens one is a
        // popup waiting for a state change that cannot happen.
        if (menuMode) {
            AudioRouteMenu(menuOpen, routes, onSelect = onSelect, onDismiss = { menuOpen = false })
        }
    }
}

private fun talkCaption(block: TalkBlock?) = when (block) {
    null -> "Talk"
    TalkBlock.NO_MICROPHONE -> "No mic"
    TalkBlock.DEAFENED -> "Deafened"
    TalkBlock.MUTED -> "Muted"
}

private fun talkDescription(block: TalkBlock?) = when (block) {
    null -> "Push to talk"
    TalkBlock.NO_MICROPHONE -> "Microphone permission denied — you can still hear others"
    TalkBlock.DEAFENED -> "Undeafen to talk"
    // Deliberately not "the server has muted you": the cause can be our own self_mute, and will be
    // routinely once a mute control exists. States the consequence, which is true for all three.
    TalkBlock.MUTED -> "Muted — the server will not carry your audio"
}

/**
 * The description goes in a `semantics {}` block on the button rather than on the [Icon], which is
 * where it used to be: that put the label on a separate, non-clickable node, so `uiautomator` saw
 * two nodes per control and the one carrying the text reported `enabled=true` even for a disabled
 * button. [TalkControl] always did it this way.
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    enabled: Boolean = true,
    active: Boolean = false,
    container: Color? = null,
    content: Color? = null,
    opensMenu: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    ControlColumn(label, modifier) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(72.dp)
                .semantics { contentDescription = description },
            // Mirrors TalkControl's pressed styling, so "on" reads the same way whichever control
            // is holding it.
            shape = if (active) controlActiveShape else controlPillShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container ?: if (active) cs.inverseSurface else cs.surfaceBright,
                contentColor = content ?: if (active) cs.inverseOnSurface else cs.onSurface,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(26.dp))
                // The stock app's "more" indicator (`setShouldShowMoreIndicator(!nonBluetoothMode)`):
                // the only thing distinguishing a button that acts from one that offers a choice.
                // A chevron, not ArrowDropDown's filled triangle — the phone app's is the open "v".
                if (opensMenu) {
                    Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun ControlColumn(label: String, modifier: Modifier = Modifier, button: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        button()
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}
