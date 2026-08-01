package me.danielstiner.dumble.ui.connect

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.protocol.ServerVersion

private const val MICROPHONE_DENIED_REASON = "Microphone permission denied — you can still hear others"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    server: String,
    sessionId: Int,
    serverVersion: ServerVersion?,
    rttMs: Double?,
    channelTree: ChannelTree,
    speaking: Set<Int>,
    unread: Int,
    microphoneGranted: Boolean?,
    onOpenChat: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onMicrophonePermissionResult: (Boolean) -> Unit,
    onTransmitting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onMicrophonePermissionResult,
    )
    // This composable only exists while the connection is Synchronized, so its appearing IS
    // reaching that state. Guarded on `microphoneGranted == null` so returning here from Chat
    // doesn't re-prompt after the first answer.
    LaunchedEffect(Unit) {
        if (microphoneGranted == null) {
            val already = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (already) onMicrophonePermissionResult(true) else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Dumble") },
            actions = {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Connected to $server")
            Text("Session #$sessionId")
            Text("Server version: ${serverVersion?.toString() ?: "—"}")
            Text("Ping: ${rttMs?.let { "%.1f ms".format(it) } ?: "—"}")
            ChannelTreeView(channelTree, mySession = sessionId, speaking = speaking, modifier = Modifier.weight(1f).fillMaxWidth())
            PttButton(
                enabled = microphoneGranted == true,
                disabledReason = if (microphoneGranted == false) MICROPHONE_DENIED_REASON else null,
                onTransmitting = onTransmitting,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenChat) { Text(if (unread > 0) "Chat ($unread)" else "Chat") }
                Button(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}
