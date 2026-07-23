package me.danielstiner.dumble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.ui.connect.ConnectScreen
import me.danielstiner.dumble.ui.connect.ConnectUiState
import me.danielstiner.dumble.ui.connect.ConnectViewModel
import me.danielstiner.dumble.ui.connect.ConnectedScreen
import me.danielstiner.dumble.ui.theme.DumbleTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DumbleTheme {
                DumbleAppContent()
            }
        }
    }
}

@Composable
private fun DumbleAppContent(vm: ConnectViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        val m = Modifier.fillMaxSize().padding(padding)
        when (val s = state.status) {
            is ConnectionStatus.Connected -> ConnectedScreen(
                server = "${state.draft.host}:${state.draft.port}",
                sessionId = s.sessionId,
                serverVersion = state.serverVersion,
                rttMs = state.rttMs,
                onDisconnect = vm::onDisconnect,
                modifier = m,
            )
            else -> ConnectScreen(
                state = state,
                onHost = vm::onHostChange, onPort = vm::onPortChange,
                onUsername = vm::onUsernameChange, onPassword = vm::onPasswordChange,
                onConnect = vm::onConnect, onTrust = vm::onTrust, onCancelTrust = vm::onCancelTrust,
                modifier = m,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectPreview() {
    DumbleTheme {
        ConnectScreen(
            state = ConnectUiState(),
            onHost = {}, onPort = {}, onUsername = {}, onPassword = {},
            onConnect = {}, onTrust = {}, onCancelTrust = {},
        )
    }
}
