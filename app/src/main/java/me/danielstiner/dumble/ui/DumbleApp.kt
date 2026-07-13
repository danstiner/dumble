package me.danielstiner.dumble.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.danielstiner.dumble.data.SharedPrefsServerConfigStore
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.MumbleServerConfig
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.telecom.CallManager

@Composable
fun DumbleApp(
    onConnect: (MumbleServerConfig) -> Unit,
    onHangUp: () -> Unit,
    onLaunchEchoTest: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ConnectViewModel = viewModel {
        ConnectViewModel(SharedPrefsServerConfigStore(context.applicationContext))
    }

    val state by MumbleManager.state.collectAsStateWithLifecycle()
    val connection by CallManager.activeConnection.collectAsStateWithLifecycle()
    val net by MumbleManager.netStats.collectAsStateWithLifecycle()
    val loop by MumbleManager.loopbackStats.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    // Failures arrive on a non-conflated SharedFlow (MumbleManager self-heals Failed -> Disconnected
    // too fast to read off the conflated state flow). Collect it for the whole lifetime of the app.
    LaunchedEffect(Unit) {
        MumbleManager.failures.collect { f ->
            snackbarHostState.showSnackbar(
                "Connection failed: ${f.reason}" + (f.detail?.let { " – $it" } ?: "")
            )
        }
    }

    val inCall = connection != null ||
        state is ConnectionState.Connecting ||
        state is ConnectionState.Handshaking ||
        state is ConnectionState.Synchronized

    when {
        inCall -> {
            val statusText = if (state is ConnectionState.Synchronized) "In Call" else "Connecting…"
            val statsText = "state=${state::class.simpleName} mode=${net.mode}\n" +
                "tcpRtt=%.1fms udpRtt=%.1fms jit=%.2fms".format(net.tcpRttMs, net.udpRttMs, net.udpJitterMs) + "\n" +
                "loop: sent=${loop.sent} rcvd=${loop.received} lost=${loop.lost} rtt=%.1fms".format(loop.lastRttMs)
            ActiveCallScreen(statusText = statusText, statsText = statsText, onHangUp = onHangUp)
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(onBack = { showSettings = false }, onLaunchEchoTest = onLaunchEchoTest)
        }
        else -> {
            val errors = validate(form)
            ConnectScreen(
                form = form,
                errors = errors,
                canConnect = errors.isValid,
                onHostChange = { v -> vm.update { it.copy(host = v) } },
                onPortChange = { v -> vm.update { it.copy(port = v) } },
                onUsernameChange = { v -> vm.update { it.copy(username = v) } },
                onPasswordChange = { v -> vm.update { it.copy(password = v) } },
                onConnect = { if (vm.canConnect()) onConnect(vm.persistAndBuild()) },
                onOpenSettings = { showSettings = true },
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
