package me.danielstiner.dumble.ui.connect

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.danielstiner.dumble.mumble.channeltree.ChannelTree

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    server: String,
    sessionId: Int,
    connectedSinceMillis: Long?,
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
            // Idempotent: a Chat/Connected remount re-runs this, and requestCapture ignores a
            // session that already has a sender.
            true -> onMicrophoneReady()
            false -> {}
        }
    }

    // Ticks once a second while connected. Keyed on the anchor so a reconnect restarts it, and the
    // loop ends with the composition rather than running against a stale anchor.
    var elapsedSeconds by remember(connectedSinceMillis) { mutableStateOf<Long?>(null) }
    LaunchedEffect(connectedSinceMillis) {
        if (connectedSinceMillis == null) return@LaunchedEffect
        while (true) {
            // Same clock the anchor was taken from — see MonotonicClock.
            elapsedSeconds = (SystemClock.elapsedRealtime() - connectedSinceMillis) / 1000
            delay(1000)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // 24dp glyph in a 40dp circle, matching Settings' back button. The padding is
                    // both sides on purpose: TopAppBar adds 4dp of its own and then starts the
                    // title at this slot's full measured width, so a filled circle — which has no
                    // inset of its own, unlike the 24dp glyph inside a stock 48dp icon button —
                    // leaves the title 6dp away unless the slot is padded to stand in for it.
                    Box(
                        Modifier.padding(horizontal = 12.dp).size(40.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.HeadsetMic, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                // Style and colour for both lines come from the slot's own tokens.
                title = {
                    Text(
                        server, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // Roboto's digits are tabular, so `1` — the narrowest of them — carries the
                        // font's largest left side bearing: 4.8px at 22sp against 1.9px for the
                        // subtitle's `C`, which reads as the status line hanging left of the host.
                        // Proportional digits cut it to 2.5px. Title only, so the elapsed timer
                        // below keeps tabular digits and does not jitter as it counts.
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "pnum"),
                    )
                },
                subtitle = {
                    Text(
                        statusLine(elapsedSeconds, rttMs),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenChat) {
                        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Tune, "Settings") }
                },
            )
        },
        bottomBar = {
            CallControls(
                microphoneGranted = microphoneGranted,
                onTransmitting = onTransmitting,
                onHangUp = onDisconnect,
            )
        },
    ) { padding ->
        ChannelTreeView(
            channelTree,
            mySession = sessionId,
            speaking = speaking,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
