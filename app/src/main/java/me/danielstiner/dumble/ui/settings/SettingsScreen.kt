package me.danielstiner.dumble.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.TransmitMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAbout: () -> Unit,
    transmitMode: TransmitMode,
    onSelectTransmitMode: (TransmitMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A segmented row, not radio rows: one control showing both modes reads faster.
            Text(
                "Microphone",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                TransmitMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == transmitMode,
                        onClick = { onSelectTransmitMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, TransmitMode.entries.size),
                    ) { Text(transmitModeLabel(mode)) }
                }
            }
            // Only the selected mode's line: the row already names the alternatives.
            Text(
                transmitModeDescription(transmitMode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAbout),
                supportingContent = { Text("Version and open source licenses") },
            ) { Text("About") }
        }
    }
}

private fun transmitModeLabel(mode: TransmitMode) = when (mode) {
    TransmitMode.PushToTalk -> "Push to talk"
    TransmitMode.VoiceActivity -> "Voice activity"
}

private fun transmitModeDescription(mode: TransmitMode) = when (mode) {
    TransmitMode.PushToTalk -> "Hold the Talk button to transmit"
    TransmitMode.VoiceActivity -> "Transmits whenever you speak"
}
