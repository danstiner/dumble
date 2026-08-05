package me.danielstiner.dumble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.ui.about.AboutScreen
import me.danielstiner.dumble.ui.connect.Route
import me.danielstiner.dumble.ui.settings.SettingsScreen
import me.danielstiner.dumble.ui.connect.ChatScreen
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
    // No Scaffold here. Every screen owns one, and a Scaffold whose content is another Scaffold
    // applies the system-bar insets twice — the inner screen's top bar then sits a status bar below
    // where it belongs, and its bottom bar floats above the navigation bar.
    val m = Modifier.fillMaxSize()
    // Settings and About overlay whatever the connection is doing, so they are checked before
    // status rather than nested inside one branch of it.
    when (state.route) {
        Route.About -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME,
            onBack = vm::back,
            modifier = m,
        )
        Route.Settings -> SettingsScreen(
            onBack = vm::back,
            onAbout = vm::openAbout,
            modifier = m,
        )
        Route.Main -> when (val s = state.status) {
            is ConnectionStatus.Connected ->
                if (state.showChat) ChatScreen(
                    messages = state.messages,
                    mySession = s.sessionId,
                    draft = state.chatDraft,
                    onDraftChange = vm::onChatDraftChange,
                    onSend = vm::sendMessage,
                    onBack = vm::closeChat,
                    modifier = m,
                ) else ConnectedScreen(
                    server = "${state.draft.host}:${state.draft.port}",
                    sessionId = s.sessionId,
                    connectedSinceMillis = state.connectedSinceMillis,
                    rttMs = state.rttMs,
                    channelTree = state.channelTree,
                    speaking = state.speakingSessions,
                    unread = state.unread,
                    microphoneGranted = state.microphoneGranted,
                    onOpenChat = vm::openChat,
                    onDisconnect = vm::onDisconnect,
                    onSettings = vm::openSettings,
                    onMicrophoneReady = vm::onMicrophoneReady,
                    onTransmitting = vm::onTransmitting,
                    modifier = m,
                )
            else -> ConnectScreen(
                state = state,
                onHost = vm::onHostChange, onPort = vm::onPortChange,
                onUsername = vm::onUsernameChange, onPassword = vm::onPasswordChange,
                onConnect = vm::onConnect,
                onMicrophonePermissionResult = vm::onMicrophonePermissionResult,
                onTrust = vm::onTrust, onCancelTrust = vm::onCancelTrust,
                onSettings = vm::openSettings,
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
            onConnect = {}, onMicrophonePermissionResult = {},
            onTrust = {}, onCancelTrust = {}, onSettings = {},
        )
    }
}
