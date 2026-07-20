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
import me.danielstiner.dumble.mumble.net.NetStats
import me.danielstiner.dumble.mumble.voice.AudioDiagnostics
import me.danielstiner.dumble.mumble.voice.JitterStats
import me.danielstiner.dumble.mumble.voice.LatencyStats
import me.danielstiner.dumble.mumble.voice.VoiceStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticsScreen(
    diagnostics: AudioDiagnostics, net: NetStats, voice: VoiceStats, latency: LatencyStats, jitter: JitterStats, onBack: () -> Unit,
) {
    fun db(v: Float) = if (v.isFinite()) "%.1f dBFS".format(v) else "—"
    fun rtt(v: Double) = if (v >= 0) "%.1f ms".format(v) else "—"
    fun lat(v: Double) = if (v.isFinite()) "%.1f ms".format(v) else "—"
    Scaffold(topBar = {
        TopAppBar(title = { Text("Audio diagnostics") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Platform effects (device self-report)")
            diagnostics.effects.forEach { e ->
                val en = when (e.enabled) { true -> "ON"; false -> "off"; null -> if (e.available) "unknown" else "n/a" }
                Text("  ${e.kind}: available=${e.available}, default=$en")
            }
            Text("  Unprocessed source: ${diagnostics.unprocessedSupported ?: "—"}")
            Text("  Device: ${diagnostics.deviceModel.ifBlank { "—" }}")
            Text("")
            Text("Stage levels (ground truth)" + if (diagnostics.connected) "" else " — not connected")
            Text("  Raw capture:   ${db(diagnostics.rawDbFs)}")
            Text("  Post-RNNoise:  ${db(diagnostics.postDenoiseDbFs)}")
            Text("  Post-gain:     ${db(diagnostics.postGainDbFs)}")
            Text("  RNNoise atten: " + if (diagnostics.rnnoiseAttenuationDb.isNaN()) "—" else "%.1f dB".format(diagnostics.rnnoiseAttenuationDb))
            Text("  AGC gain:      %.1f dB".format(diagnostics.agcGainDb))
            Text("  VAD prob:      %.2f".format(diagnostics.vadProb))
            Text("")
            Text("Network")
            Text("  Transport:  ${net.mode}")
            Text("  TCP RTT:    " + rtt(net.tcpRttMs))
            Text("  UDP RTT:    " + rtt(net.udpRttMs))
            Text("  UDP jitter: %.2f ms".format(net.udpJitterMs))
            Text("")
            Text("Latency")
            Text("  Capture:  " + lat(latency.captureMs))
            Text("  Playout:  " + lat(latency.playoutMs))
            Text("  (best-effort pipeline latency — excludes delay the HAL doesn't report, e.g. Bluetooth; not acoustic)")
            Text("")
            Text("Jitter (adaptive prebuffer)")
            Text("  Target:     ${jitter.targetMs} ms")
            Text("  p95 delay:  ${jitter.p95Ms} ms")
            Text("")
            Text("Voice")
            Text("  Sent:       ${voice.sent}")
            Text("  Received:   ${voice.received}")
            Text("  Lost:       ${voice.lost}")
            Text("  Concealed:  ${voice.concealed}")
            Text("  Buffer:     ${voice.bufferMs} ms")
            Text("  Speakers:   ${voice.activeSpeakers}")
            Text("")
            Text("Effect state is the audiofx self-report; on some devices it may differ from the actual HAL processing. The stage levels are measured.")
        }
    }
}
