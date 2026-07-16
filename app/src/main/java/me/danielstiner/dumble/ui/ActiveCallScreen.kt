package me.danielstiner.dumble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    statusText: String,
    statsText: String,
    muted: Boolean,
    transmitMode: TransmitMode,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    speaker: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dumble") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(statusText, style = MaterialTheme.typography.headlineSmall)
            Text(statsText, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (transmitMode == TransmitMode.VOICE_ACTIVATED) {
                    FilterChip(
                        selected = muted,
                        onClick = onToggleMute,
                        label = { Text(if (muted) "Unmute" else "Mute") },
                        modifier = Modifier.weight(1f),
                    )
                }
                FilterChip(
                    selected = speaker,
                    onClick = onToggleSpeaker,
                    label = { Text("Speaker") },
                    modifier = Modifier.weight(1f),
                )
            }
            if (transmitMode == TransmitMode.PUSH_TO_TALK) {
                PushToTalkButton(onPress = onPttPress, onRelease = onPttRelease)
            }
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Hang Up") }
        }
    }
}

@Composable
private fun PushToTalkButton(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (pressed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; onPress()
                    tryAwaitRelease()          // suspends until release OR gesture cancel
                    pressed = false; onRelease()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (pressed) "Release to stop" else "Hold to talk",
            color = if (pressed) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Preview
@Composable
private fun ActiveCallScreenPreview() {
    DumbleTheme {
        ActiveCallScreen(
            statusText = "In Call",
            statsText = "state=Synchronized mode=UDP\nudpRtt=11.5ms jit=1.4ms",
            muted = false,
            transmitMode = TransmitMode.VOICE_ACTIVATED,
            onPttPress = {},
            onPttRelease = {},
            speaker = false,
            onToggleMute = {},
            onToggleSpeaker = {},
            onHangUp = {},
            onOpenSettings = {},
        )
    }
}
