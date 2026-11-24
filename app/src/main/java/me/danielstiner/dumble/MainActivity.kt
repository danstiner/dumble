package me.danielstiner.dumble

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.activity.viewModels
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import me.danielstiner.dumble.mumble.voice.PlayoutDelay
import me.danielstiner.dumble.mumble.voice.SendDelay
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.mySession
import me.danielstiner.dumble.ui.about.AboutScreen
import me.danielstiner.dumble.ui.connect.Route
import me.danielstiner.dumble.ui.settings.SettingsScreen
import me.danielstiner.dumble.ui.connect.ChatScreen
import me.danielstiner.dumble.ui.connect.ConnectScreen
import me.danielstiner.dumble.ui.connect.ConnectUiState
import me.danielstiner.dumble.ui.connect.ConnectViewModel
import me.danielstiner.dumble.ui.connect.ConnectedScreen
import me.danielstiner.dumble.ui.latency.LatencyCheckScreen
import me.danielstiner.dumble.ui.latency.LatencyCheckState
import me.danielstiner.dumble.ui.latency.LatencyCheckViewModel
import me.danielstiner.dumble.ui.theme.DumbleTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val latencyCheck: LatencyCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch through the alias: a rotation or process restore has a saved state,
        // and a task reopened from Recents is started again with the first launch's extras.
        // A constant, not "$packageName.LatencyCheck": an applicationIdSuffix changes the package
        // but not the alias's class name.
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh && intent.component?.className == LATENCY_CHECK_ALIAS) {
            latencyCheck.start(
                intent.getStringExtra("host"),
                // Either an int or a string extra: `--ei` and `--es` are both easy to type.
                intent.getStringExtra("port") ?: intent.getIntExtra("port", -1).takeIf { it != -1 }?.toString(),
                intent.getStringExtra("fingerprint"),
            )
        }
        setContent {
            DumbleTheme {
                val check by latencyCheck.state.collectAsStateWithLifecycle()
                if (check != LatencyCheckState.Idle) {
                    LatencyCheckScreen(check, onBack = latencyCheck::dismiss, modifier = Modifier.fillMaxSize())
                } else {
                    DumbleAppContent()
                }
            }
        }
    }

    private companion object {
        const val LATENCY_CHECK_ALIAS = "me.danielstiner.dumble.LatencyCheck"
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
            transmitMode = state.transmitMode,
            onSelectTransmitMode = vm::onSelectTransmitMode,
            modifier = m,
        )
        // Our session row is what the controls and the chat read, and it outlives the link that
        // earned it, so a reconnect stays on the call screen.
        Route.Main -> when (val mySession = state.status.mySession) {
            null -> ConnectScreen(
                state = state,
                onHost = vm::onHostChange, onPort = vm::onPortChange,
                onUsername = vm::onUsernameChange, onPassword = vm::onPasswordChange,
                onConnect = vm::onConnect,
                onLocalNetworkAsked = vm::onLocalNetworkAsked,
                onMicrophonePermissionResult = vm::onMicrophonePermissionResult,
                onTrust = vm::onTrust, onCancelTrust = vm::onCancelTrust,
                onSettings = vm::openSettings,
                modifier = m,
            )
            else -> {
                if (state.showChat) ChatScreen(
                    messages = state.messages,
                    mySession = mySession,
                    draft = state.chatDraft,
                    onDraftChange = vm::onChatDraftChange,
                    onSend = vm::sendMessage,
                    onBack = vm::closeChat,
                    modifier = m,
                ) else ConnectedScreen(
                    // Empty until the saved profile loads, which a recreated activity can render
                    // mid-call.
                    server = runCatching { state.draft.endpoint.label }.getOrDefault(""),
                    sessionId = mySession,
                    reconnecting = state.status is ConnectionStatus.Reconnecting,
                    connectedSince = state.connectedSince,
                    roundTripTime = state.roundTripTime,
                    voicePath = state.voicePath,
                    lastServerReplyAt = state.lastServerReplyAt,
                    channelTree = state.channelTree,
                    speaking = state.speakingSessions,
                    unread = state.unread,
                    microphoneGranted = state.microphoneGranted,
                    talkBlock = state.talkBlock,
                    deafened = state.deafened,
                    transmitMode = state.transmitMode,
                    muted = state.muted,
                    inaudible = state.inaudible,
                    callHeld = state.callHeld,
                    audioRoutes = state.audioRoutes,
                    selectedSession = state.selectedSession,
                    selectedDelay = state.selectedSession?.let {
                        PlayoutDelay.of(it, state.playoutStats, state.userStats,
                                        state.roundTripTime)
                    },
                    selectedStats = state.userStats,
                    selfDelay = SendDelay.of(state.captureStats, state.voicePath, state.roundTripTime),
                    captureStats = state.captureStats,
                    onUserClick = vm::openUserDetail,
                    onRefreshUserStats = vm::refreshUserStats,
                    onDismissUserDetail = vm::closeUserDetail,
                    onOpenChat = vm::openChat,
                    onDisconnect = vm::onDisconnect,
                    onSettings = vm::openSettings,
                    onMicrophoneReady = vm::onMicrophoneReady,
                    onTransmitting = vm::onTransmitting,
                    onToggleDeafen = vm::onToggleDeafen,
                    onToggleMute = vm::onToggleMute,
                    onSelectRoute = vm::onSelectRoute,
                    modifier = m,
                )
            }
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
            onConnect = {}, onLocalNetworkAsked = {}, onMicrophonePermissionResult = {},
            onTrust = {}, onCancelTrust = {}, onSettings = {},
        )
    }
}
