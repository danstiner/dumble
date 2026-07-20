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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerUserStatsScreen(
    users: List<MumbleUser>, perSpeaker: List<SpeakerJitter>, net: NetStats, onBack: () -> Unit,
) {
    fun rtt(v: Double) = if (v >= 0) "%.1f ms".format(v) else "—"
    fun ping(v: Float?) = if (v != null && v >= 0f) "%.0f ms".format(v) else "—"
    val bySession = perSpeaker.associateBy { it.session }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Per-user stats") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Server (our link)")
            Text("  Transport:  ${net.mode}")
            Text("  TCP RTT:    " + rtt(net.tcpRttMs))
            Text("  UDP RTT:    " + rtt(net.udpRttMs))
            Text("  UDP jitter: %.2f ms".format(net.udpJitterMs))
            Text("")
            Text("Per user  (ping = their ping to server; p95 = raw unclamped jitter spike)")
            if (users.isEmpty()) { Text("  — no users —"); return@Column }
            users.sortedBy { it.name }.forEach { u ->
                val j = bySession[u.session]
                Text("  ${u.name}")
                Text("    ping tcp/udp: ${ping(u.tcpPingMs)} / ${ping(u.udpPingMs)}")
                if (j != null)
                    Text("    jitter: target=${j.targetMs}ms p95=${j.p95Ms}ms buffered=${j.bufferedMs}ms lateDrops=${j.lateDrops}")
                else
                    Text("    jitter: — (no active stream)")
            }
        }
    }
}
