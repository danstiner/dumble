package me.danielstiner.dumble.ui.connect

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
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
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
import me.danielstiner.dumble.mumble.voice.AudioRoutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    server: String,
    sessionId: Int,
    connectedSince: ComparableTimeMark?,
    roundTripTime: Duration?,
    lastServerReplyAt: ComparableTimeMark?,
    channelTree: ChannelTree,
    speaking: Set<Int>,
    unread: Int,
    microphoneGranted: Boolean,
    talkBlock: TalkBlock?,
    deafened: Boolean,
    audioRoutes: AudioRoutes,
    onOpenChat: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onMicrophoneReady: () -> Unit,
    onTransmitting: (Boolean) -> Unit,
    onToggleDeafen: () -> Unit,
    onSelectRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the answer, and driving capture from the *state* rather than from the permission
    // callback, which is raised on the connect screen before this composable exists. The answer
    // outlives the connection it was given for, since it sits in the ViewModel: starting capture
    // from the callback meant the second and later connections in a process never started capture
    // at all, because nothing asked again once the answer was known.
    //
    // Idempotent: a Chat/Connected remount re-runs this, and requestCapture ignores a session that
    // already has a sender.
    LaunchedEffect(microphoneGranted) {
        if (microphoneGranted) onMicrophoneReady()
    }

    // Ticks once a second while connected. Keyed on the anchor so a reconnect restarts it, and the
    // loop ends with the composition rather than running against a stale anchor.
    //
    // Both values are an instant plus the passage of time, so one loop produces both rather than
    // the ping silence being pushed from the session at the ping interval: that would publish a
    // duration already stale when written, and quantise the readout to five seconds when the user
    // is looking at a one-second clock.
    var elapsedSeconds by remember(connectedSince) { mutableStateOf<Long?>(null) }
    var pingAge by remember(connectedSince) { mutableStateOf(Duration.ZERO) }
    LaunchedEffect(connectedSince, lastServerReplyAt) {
        if (connectedSince == null) return@LaunchedEffect
        while (true) {
            // elapsedNow() reads the clock each mark came from, so neither can drift onto another —
            // see BootTimeSource for why it has to be the one that counts sleep.
            elapsedSeconds = connectedSince.elapsedNow().inWholeSeconds
            // null means no ping has gone out yet, which is silence nobody can be blamed for.
            pingAge = lastServerReplyAt?.elapsedNow() ?: Duration.ZERO
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
                        statusLine(elapsedSeconds, roundTripTime, pingAge),
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
                talkBlock = talkBlock,
                deafened = deafened,
                audioRoutes = audioRoutes,
                onTransmitting = onTransmitting,
                onToggleDeafen = onToggleDeafen,
                onSelectRoute = onSelectRoute,
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
