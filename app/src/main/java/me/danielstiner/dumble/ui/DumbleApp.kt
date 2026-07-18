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
import me.danielstiner.dumble.telecom.AudioRoute
import me.danielstiner.dumble.telecom.CallManager

@Composable
fun DumbleApp(
    onConnect: (MumbleServerConfig) -> Unit,
    onHangUp: () -> Unit,
    onLaunchEchoTest: () -> Unit,
    onLaunchVadDebug: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ConnectViewModel = viewModel {
        ConnectViewModel(SharedPrefsServerConfigStore(context.applicationContext))
    }

    val state by MumbleManager.state.collectAsStateWithLifecycle()
    val callActive by CallManager.callActive.collectAsStateWithLifecycle()
    val muted by MumbleManager.muted.collectAsStateWithLifecycle()
    val vadThreshold by MumbleManager.vadThreshold.collectAsStateWithLifecycle()
    val hysteresisGap by MumbleManager.hysteresisGap.collectAsStateWithLifecycle()
    val transmitMode by MumbleManager.transmitMode.collectAsStateWithLifecycle()
    val agcEnabled by MumbleManager.agcEnabled.collectAsStateWithLifecycle()
    val agcTargetDbFs by MumbleManager.agcTargetDbFs.collectAsStateWithLifecycle()
    val rnnoiseEnabled by MumbleManager.rnnoiseEnabled.collectAsStateWithLifecycle()
    val vadEngine by MumbleManager.vadEngine.collectAsStateWithLifecycle()
    val prerollMs by MumbleManager.prerollMs.collectAsStateWithLifecycle()
    val audioDiagnostics by MumbleManager.audioDiagnostics.collectAsStateWithLifecycle()
    val netStats by MumbleManager.netStats.collectAsStateWithLifecycle()
    val voiceStats by MumbleManager.voiceStats.collectAsStateWithLifecycle()
    val serverModel by MumbleManager.model.state.collectAsStateWithLifecycle()
    val speakingSessions by MumbleManager.speakingSessions.collectAsStateWithLifecycle()
    val selfTransmitting by MumbleManager.selfTransmitting.collectAsStateWithLifecycle()
    val deafened by MumbleManager.deafened.collectAsStateWithLifecycle()
    val speaker by CallManager.isSpeaker.collectAsStateWithLifecycle()
    val activeEndpoint by CallManager.activeEndpoint.collectAsStateWithLifecycle()
    val endpoints by CallManager.endpoints.collectAsStateWithLifecycle()
    val routeOptions = endpoints.map { ep ->
        RouteOption(
            type = ep.type,
            icon = AudioRoute.icon(ep.type),
            label = ep.name.toString().trim().takeIf { it.isNotEmpty() } ?: AudioRoute.label(ep.type),
        )
    }
    // Prefer the framework's own endpoint name — it's localized to the device language and, for
    // Bluetooth, is the device name. Fall back to our hardcoded label only if it's ever blank.
    val activeRouteLabel = activeEndpoint?.let { ep ->
        ep.name.toString().trim().takeIf { it.isNotEmpty() } ?: AudioRoute.label(ep.type)
    }
    val form by vm.form.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Failures arrive on a non-conflated SharedFlow (MumbleManager self-heals Failed -> Disconnected
    // too fast to read off the conflated state flow). Collect it for the whole lifetime of the app.
    LaunchedEffect(Unit) {
        MumbleManager.failures.collect { f ->
            snackbarHostState.showSnackbar(
                "Connection failed: ${f.reason}" + (f.detail?.let { " – $it" } ?: "")
            )
        }
    }

    val inCall = callActive ||
        state is ConnectionState.Connecting ||
        state is ConnectionState.Handshaking ||
        state is ConnectionState.Synchronized

    var connectedSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state) {
        connectedSince = if (state is ConnectionState.Synchronized) {
            connectedSince ?: System.currentTimeMillis()
        } else null
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (connectedSince != null) { nowMillis = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val connectedText = connectedSince?.let { "Connected · " + formatElapsed(nowMillis - it) } ?: "Connecting…"

    when {
        showDiagnostics -> {
            BackHandler { showDiagnostics = false }
            AudioDiagnosticsScreen(diagnostics = audioDiagnostics, net = netStats, voice = voiceStats,
                onBack = { showDiagnostics = false })
        }
        inCall && !showSettings -> {
            val callState = buildCallScreenState(
                serverModel, speakingSessions, selfTransmitting, muted, deafened,
                configHostFallback = form.host,
            )
            val routeIcon = activeEndpoint?.let { AudioRoute.icon(it.type) } ?: AudioRoute.RouteIcon.SPEAKER
            ActiveCallScreen(
                state = callState,
                connectedText = connectedText,
                connecting = connectedSince == null,
                muted = muted, deafened = deafened, speaker = speaker,
                routeIcon = routeIcon, routeLabel = activeRouteLabel ?: "Speaker",
                transmitMode = transmitMode,
                onToggleMute = { MumbleManager.setMuted(!muted) },
                onToggleDeafen = { MumbleManager.setDeafened(!deafened) },
                onToggleSpeaker = { CallManager.setSpeaker(!speaker) },
                routeOptions = routeOptions,
                activeRouteType = activeEndpoint?.type,
                onSelectRoute = { CallManager.selectRoute(it) },
                onPttPress = { MumbleManager.setPttHeld(true) },
                onPttRelease = { MumbleManager.setPttHeld(false) },
                onHangUp = onHangUp,
                onOpenSettings = { showSettings = true },
            )
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(
                onBack = { showSettings = false },
                onLaunchEchoTest = onLaunchEchoTest,
                onLaunchVadDebug = onLaunchVadDebug,
                transmitMode = transmitMode,
                onTransmitModeChange = { MumbleManager.setTransmitMode(it) },
                vadThreshold = vadThreshold,
                onVadThresholdChange = { MumbleManager.setVadThreshold(it) },
                hysteresisGap = hysteresisGap,
                onHysteresisGapChange = { MumbleManager.setHysteresisGap(it) },
                agcEnabled = agcEnabled,
                onAgcEnabledChange = { MumbleManager.setAgcEnabled(it) },
                agcTargetDbFs = agcTargetDbFs,
                onAgcTargetChange = { MumbleManager.setAgcTargetDbFs(it) },
                rnnoiseEnabled = rnnoiseEnabled,
                onRnnoiseEnabledChange = { MumbleManager.setRnnoiseEnabled(it) },
                vadEngine = vadEngine,
                onVadEngineChange = { MumbleManager.setVadEngine(it) },
                prerollMs = prerollMs,
                onPrerollChange = { MumbleManager.setPrerollMs(it) },
                onOpenDiagnostics = { showDiagnostics = true },
            )
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

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
