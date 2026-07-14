package me.danielstiner.dumble.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLaunchEchoTest: () -> Unit,
    onLaunchVadDebug: () -> Unit,
    vadThreshold: Float,
    onVadThresholdChange: (Float) -> Unit,
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
                Text("Voice activity")
                Text("Sensitivity threshold: %.2f".format(vadThreshold))
                Slider(
                    value = vadThreshold,
                    onValueChange = onVadThresholdChange,
                    valueRange = 0.3f..0.95f,
                )
                Text("Higher = transmits only on clearer speech. Applies live to the active call.")
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
        }
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    DumbleTheme {
        SettingsScreen(onBack = {}, onLaunchEchoTest = {}, onLaunchVadDebug = {},
            vadThreshold = 0.5f, onVadThresholdChange = {})
    }
}
