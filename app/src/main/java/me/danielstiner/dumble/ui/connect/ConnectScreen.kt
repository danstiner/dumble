package me.danielstiner.dumble.ui.connect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onLocalNetworkAsked: () -> Unit,
    onMicrophonePermissionResult: (Boolean) -> Unit,
    onTrust: () -> Unit,
    onCancelTrust: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = state.status is ConnectionStatus.Idle || state.status is ConnectionStatus.Error
    val canConnect = idle && state.draft.host.isNotBlank() && state.draft.username.isNotBlank() && state.portError == null

    val context = LocalContext.current
    fun denied(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED

    // The permissions in connectPermissions are asked at Connect, not at launch: they are
    // meaningless out of context. Connecting proceeds whatever the answers are — none of them is
    // ours to override, and a session with no microphone still receives.
    //
    // The microphone is asked here rather than on the connected screen because the call's
    // foreground service starts a main-looper hop after connect() returns, while the Connect tap
    // still has the app in front, and picks its type from this permission. Asked any later, the
    // first service of every install is mediaPlayback-typed and that session can never transmit
    // from the background.
    val reportMicrophone = { onMicrophonePermissionResult(!denied(Manifest.permission.RECORD_AUDIO)) }
    // Local network access is asked for once Android has blocked a connect, because only Android
    // knows whether an address is on the local network. Skipped when already granted: asking would
    // answer at once and retry, so a connect still blocked after a grant would loop.
    val requestLocalNetwork = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onConnect() }
    LaunchedEffect(state.askLocalNetwork) {
        if (state.askLocalNetwork) {
            onLocalNetworkAsked()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && denied(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
                requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
    }
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { reportMicrophone(); onConnect() }
    val connect: () -> Unit = {
        val missing = connectPermissions().filter(::denied)
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
                is ConnectionStatus.Error -> {
                    Text("${s.kind}: ${s.detail ?: ""}")
                    if (s.kind == ErrorKind.LOCAL_NETWORK_BLOCKED) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text("Allow in system settings") }
                    }
                }
                else -> {}
            }
        }
    }

    if (state.status is ConnectionStatus.AwaitingTrust || state.status is ConnectionStatus.PinMismatch) {
        TrustDialog(state.status, onTrust = onTrust, onCancel = onCancelTrust)
    }
}

private fun connectPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
