package me.danielstiner.dumble.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLaunchEchoTest: () -> Unit,
    onLaunchVadDebug: () -> Unit,
    transmitMode: TransmitMode,
    onTransmitModeChange: (TransmitMode) -> Unit,
    vadThreshold: Float,
    onVadThresholdChange: (Float) -> Unit,
    agcEnabled: Boolean,
    onAgcEnabledChange: (Boolean) -> Unit,
    agcTargetDbFs: Float,
    onAgcTargetChange: (Float) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Transmit mode")
                Column(modifier = Modifier.selectableGroup()) {
                    TransmitMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = transmitMode == mode,
                                    onClick = { onTransmitModeChange(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = transmitMode == mode, onClick = null)
                            Text(
                                when (mode) {
                                    TransmitMode.VOICE_ACTIVATED -> "Voice activity"
                                    TransmitMode.PUSH_TO_TALK -> "Push to talk"
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                val vaMode = transmitMode == TransmitMode.VOICE_ACTIVATED
                Text("Sensitivity threshold: %.2f".format(vadThreshold))
                Slider(
                    value = vadThreshold,
                    onValueChange = onVadThresholdChange,
                    valueRange = 0.3f..0.95f,
                    enabled = vaMode,
                )
                Text(
                    if (vaMode) "Higher = transmits only on clearer speech. Applies live to the active call."
                    else "Applies in Voice activity mode.",
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Automatic gain control", modifier = Modifier.weight(1f))
                    Switch(checked = agcEnabled, onCheckedChange = onAgcEnabledChange)
                }
                Text("Transmit loudness: %.0f dBFS".format(agcTargetDbFs))
                Slider(
                    value = agcTargetDbFs,
                    onValueChange = onAgcTargetChange,
                    valueRange = -30f..-9f,
                    enabled = agcEnabled,
                )
                Text("Higher = louder transmit. Normalizes your level so peers hear you consistently.")
            }
            ListItem(
                headlineContent = { Text("Echo Test") },
                supportingContent = { Text("Local audio loopback debug tool") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLaunchEchoTest),
            )
            ListItem(
                headlineContent = { Text("VAD Gate Tuner") },
                supportingContent = { Text("Tune the voice-activity gate live (no server)") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLaunchVadDebug),
            )
            ListItem(
                headlineContent = { Text("Audio diagnostics") },
                supportingContent = { Text("Platform effects + live stage levels (read-only)") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDiagnostics),
            )
        }
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    DumbleTheme {
        SettingsScreen(onBack = {}, onLaunchEchoTest = {}, onLaunchVadDebug = {},
            transmitMode = TransmitMode.VOICE_ACTIVATED, onTransmitModeChange = {},
            vadThreshold = 0.5f, onVadThresholdChange = {},
            agcEnabled = true, onAgcEnabledChange = {},
            agcTargetDbFs = -18f, onAgcTargetChange = {},
            onOpenDiagnostics = {})
    }
}
