package me.danielstiner.dumble.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
    rnnoiseEnabled: Boolean,
    onRnnoiseEnabledChange: (Boolean) -> Unit,
    vadEngine: String,
    onVadEngineChange: (String) -> Unit,
    lookaheadMs: Int,
    onLookaheadChange: (Int) -> Unit,
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
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TransmitMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = transmitMode == mode,
                            onClick = { onTransmitModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, TransmitMode.entries.size),
                        ) {
                            Text(
                                when (mode) {
                                    TransmitMode.VOICE_ACTIVATED -> "Voice activity"
                                    TransmitMode.PUSH_TO_TALK -> "Push to talk"
                                },
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

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Noise suppression (RNNoise)", modifier = Modifier.weight(1f))
                    Switch(checked = rnnoiseEnabled, onCheckedChange = onRnnoiseEnabledChange)
                }
                Text("Off sends your raw mic (relies on the phone's own noise removal). Voice activation is unaffected.")

                Text(
                    "Voice detection engine",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                val engines = listOf("energy" to "Energy", "rnnoise" to "RNNoise", "silero" to "Silero")
                Column(Modifier.selectableGroup()) {
                    engines.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = vadEngine == key,
                                    onClick = { onVadEngineChange(key) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vadEngine == key, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }

                Text(
                    "Lookahead delay: $lookaheadMs ms" + if (lookaheadMs == 0) "  (0 = lowest latency)" else "",
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(
                    value = lookaheadMs.toFloat(),
                    onValueChange = { onLookaheadChange((it / 20f).toInt() * 20) },
                    valueRange = 0f..100f,
                    steps = 4,
                )
                Text("Delays transmit start to capture speech onset before the gate opens. Higher = less clipped words, more latency.")
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
            rnnoiseEnabled = true, onRnnoiseEnabledChange = {},
            vadEngine = "rnnoise", onVadEngineChange = {},
            lookaheadMs = 0, onLookaheadChange = {},
            onOpenDiagnostics = {})
    }
}
