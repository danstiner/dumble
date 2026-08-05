package me.danielstiner.dumble.ui.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.connection.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onMicrophonePermissionResult: (Boolean) -> Unit,
    onTrust: () -> Unit,
    onCancelTrust: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = state.status is ConnectionStatus.Idle || state.status is ConnectionStatus.Error
    val canConnect = idle && state.draft.host.isNotBlank() && state.draft.username.isNotBlank() && state.portError == null

    val context = LocalContext.current

    // All of them at Connect, not at launch: they are meaningless out of context. Connecting
    // proceeds whatever the answers are — none of them is ours to override, a public server needs
    // no local-network grant, and a session with no microphone still receives.
    //
    // The microphone is asked here rather than on the connected screen because the call's
    // foreground service starts inside addCall's block during connect, and picks its type from
    // this permission. Asked any later, the first service of every install is mediaPlayback-typed
    // and that session can never transmit from the background.
    //
    // No SDK guard on the local-network permission. Its gate is targetSdkVersion, fixed for every
    // build we ship, so a device check would only ask "does this permission exist here" — and below
    // 37 it does not, so the request resolves to denied and we connect regardless. Verified on an
    // API 36 device: unguarded, it reaches the server normally.
    val reportMicrophone = {
        onMicrophonePermissionResult(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { reportMicrophone(); onConnect() }
    val connect: () -> Unit = {
        // Only the ones still outstanding. Below API 37 the local-network permission does not
        // exist, so checkSelfPermission always reports it denied and every connect trampolines
        // through the system dialog activity — the same round trip this screen already made before
        // the microphone joined the set, so no regression, but not free either.
        val missing = connectPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            reportMicrophone()
            onConnect()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Dumble") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { contentPadding ->
        // The form is taller than a phone screen once the keyboard is up; without this the last
        // field is unreachable rather than merely off-screen.
        Column(
            Modifier.fillMaxSize().padding(contentPadding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                state.draft.host, onHost, label = { Text("Server") }, singleLine = true,
                isError = state.hostError != null,
                supportingText = state.hostError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state.portText, onPort, label = { Text("Port") }, singleLine = true,
                isError = state.portError != null,
                supportingText = state.portError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(state.draft.username, onUsername, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                state.password, onPassword, label = { Text("Password (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = connect, enabled = canConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.status is ConnectionStatus.Connecting || state.status is ConnectionStatus.Handshaking) "Connecting…" else "Connect")
            }
            when (val s = state.status) {
                is ConnectionStatus.Error -> Text("${s.kind}: ${s.detail ?: ""}")
                else -> {}
            }
        }
    }

    if (state.status is ConnectionStatus.AwaitingTrust || state.status is ConnectionStatus.PinMismatch) {
        TrustDialog(state.status, onTrust = onTrust, onCancel = onCancelTrust)
    }
}

/** Inlined rather than referenced: the constant does not exist in older platform jars. */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** Notifications are the only one that is version-gated; the others exist on every SDK we ship. */
private fun connectPermissions(): List<String> = buildList {
    add(LOCAL_NETWORK_PERMISSION)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
