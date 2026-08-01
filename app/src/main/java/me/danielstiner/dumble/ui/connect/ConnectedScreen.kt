package me.danielstiner.dumble.ui.connect

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.unit.dp
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
    onMicrophoneReady: () -> Unit,
    onTransmitting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Notifications are best-effort — the foreground service runs either way, it just runs
        // without a visible notification — so only the microphone answer gates voice.
        onMicrophonePermissionResult(result[Manifest.permission.RECORD_AUDIO] == true)
    }
    // This composable only exists while the connection is Synchronized, so its appearing IS
    // reaching that state — and it is a visible activity, which is the only place a microphone
    // foreground service may be started from.
    //
    // Keyed on the answer rather than on Unit, and driving capture from the *state* rather than
    // from the permission callback. The answer outlives the connection it was given for, since it
    // sits in the ViewModel: keying this on Unit and starting capture from the callback meant the
    // second and later connections in a process never started capture at all, because nothing
    // asked again once the answer was known.
    LaunchedEffect(microphoneGranted) {
        when (microphoneGranted) {
            // No self-check first: the contract resolves already-granted permissions without
            // showing anything, and asking for both together covers holding one but not the other.
            null -> requestPermissions.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            )
            // Idempotent: a Chat/Connected remount re-runs this, and startCapture ignores a
            // session that already has a sender.
            true -> onMicrophoneReady()
            false -> {}
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
