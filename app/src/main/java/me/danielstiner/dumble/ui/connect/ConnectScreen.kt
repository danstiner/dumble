package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.connection.ConnectionStatus

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
    modifier: Modifier = Modifier,
) {
    val idle = state.status is ConnectionStatus.Idle || state.status is ConnectionStatus.Error
    val canConnect = idle && state.draft.host.isNotBlank() && state.draft.username.isNotBlank() && state.portError == null

    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Button(onClick = onConnect, enabled = canConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.status is ConnectionStatus.Connecting || state.status is ConnectionStatus.Handshaking) "Connecting…" else "Connect")
        }
        when (val s = state.status) {
            is ConnectionStatus.Error -> Text("${s.kind}: ${s.detail ?: ""}")
            else -> {}
        }
    }

    if (state.status is ConnectionStatus.AwaitingTrust || state.status is ConnectionStatus.PinMismatch) {
        TrustDialog(state.status, onTrust = onTrust, onCancel = onCancelTrust)
    }
}
