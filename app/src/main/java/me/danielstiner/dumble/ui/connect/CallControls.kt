package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headphones
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

// A wide pill normally, squarer while active. The shape change is what reads as "on" across the
// room, ahead of any colour difference.
private val controlPillShape = RoundedCornerShape(percent = 50)
private val controlActiveShape = RoundedCornerShape(percent = 30)

private const val MICROPHONE_DENIED_REASON =
    "Microphone permission denied — you can still hear others"

/**
 * The call screen's bottom bar. Buttons take equal weight so they are wide pills with large touch
 * targets rather than small circles, matching the stock phone app.
 *
 * Push-to-talk and mute are alternatives for the same slot, not separate controls: a mute button is
 * meaningless when the gate is already closed by default, so only Talk appears here. Deafen and
 * Speaker are disabled placeholders for later PRs — Deafen needs `self_deaf` on the wire (PR 2), and
 * Speaker becomes the audio-route picker (PR 3). Deafen is not redundant with push-to-talk: it is
 * about not hearing others, not about not transmitting.
 */
@Composable
fun CallControls(
    microphoneGranted: Boolean?,
    onTransmitting: (Boolean) -> Unit,
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
            TalkControl(microphoneGranted, onTransmitting, Modifier.weight(1f))
            ControlButton(
                icon = Icons.Filled.Headphones, label = "Deafen",
                enabled = false, onClick = {}, modifier = Modifier.weight(1f),
            )
            ControlButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Speaker",
                enabled = false, onClick = {}, modifier = Modifier.weight(1f),
            )
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
 * The caption cannot hold the full denial reason, so it goes to `contentDescription` where screen
 * readers and the long-press tooltip still carry it.
 *
 * [granted] is null until the OS answers. That is not a denial: the permission dialog is up, and
 * labelling the button "No mic" there announces a refusal the user has not made yet. Disabled and
 * unremarkable is the honest rendering — capture has not started either way.
 */
@Composable
private fun TalkControl(granted: Boolean?, onTransmitting: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val denied = granted == false
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
    ControlColumn(if (denied) "No mic" else "Talk", modifier) {
        FilledIconButton(
            onClick = {},
            enabled = granted == true,
            interactionSource = interactions,
            modifier = Modifier.fillMaxWidth().height(72.dp).semantics {
                contentDescription = if (denied) MICROPHONE_DENIED_REASON else "Push to talk"
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

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color? = null,
    content: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    ControlColumn(label, modifier) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = controlPillShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container ?: cs.surfaceBright,
                contentColor = content ?: cs.onSurface,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(26.dp))
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
