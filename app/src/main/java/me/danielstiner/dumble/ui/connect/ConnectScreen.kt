package me.danielstiner.dumble.ui.connect

import android.content.pm.PackageManager
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
    onTrust: () -> Unit,
    onCancelTrust: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = state.status is ConnectionStatus.Idle || state.status is ConnectionStatus.Error
    val canConnect = idle && state.draft.host.isNotBlank() && state.draft.username.isNotBlank() && state.portError == null

    // Asked at Connect, not at launch: it is meaningless out of context, and the answer only
    // matters for a server on the local network. Connecting proceeds either way — a denial is not
    // ours to override, and a public server does not need it.
    //
    // No SDK guard. The gate is on targetSdkVersion, which is fixed for every build we ship, so a
    // device check would only be asking "does this permission exist here" — and below 37 it does
    // not, so the request resolves to denied and we connect regardless. Verified on an API 36
    // device: unguarded, it reaches the server normally.
    val requestLocalNetwork = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onConnect() }
    val context = LocalContext.current
    val connect: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) onConnect() else requestLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
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
