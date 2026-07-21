package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.net.NetStats
import me.danielstiner.dumble.mumble.voice.SpeakerJitter

/** Debug detail for one user: their ping-to-server (via UserStats) + their downlink jitter (via the
 *  per-speaker snapshot). [jitter] is null when the user has no active stream (not speaking); [user]
 *  is null if they left the server while this page was open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserStatsDetailScreen(
    user: MumbleUser?, jitter: SpeakerJitter?, net: NetStats, onBack: () -> Unit,
) {
    fun rtt(v: Double) = if (v >= 0) "%.1f ms".format(v) else "—"
    fun ping(v: Float?) = if (v != null && v >= 0f) "%.1f ms".format(v) else "—"   // 1 decimal: sub-ms LAN pings shouldn't read as "0 ms"
    Scaffold(topBar = {
        TopAppBar(title = { Text(user?.name ?: "User") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (user == null) { Text("User is no longer connected."); return@Column }
            Text("Ping to server")
            Text("  TCP: ${ping(user.tcpPingMs)}")
            Text("  UDP: ${ping(user.udpPingMs)}")
            Text("")
            Text("Jitter buffer  (p95 = raw unclamped delay spike)")
            if (jitter != null) {
                Text("  Target:     ${jitter.targetMs} ms")
                Text("  p95 (raw):  ${jitter.p95Ms} ms")
                Text("  Buffered:   ${jitter.bufferedMs} ms")
                Text("  Late drops: ${jitter.lateDrops}")
            } else {
                Text("  — not currently speaking —")
            }
            Text("")
            Text("Your link (for comparison)")
            Text("  TCP RTT: " + rtt(net.tcpRttMs))
            Text("  UDP RTT: " + rtt(net.udpRttMs))
        }
    }
}
