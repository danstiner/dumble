package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.protocol.ServerVersion

@Composable
fun ConnectedScreen(
    server: String,
    sessionId: Int,
    serverVersion: ServerVersion?,
    rttMs: Double?,
    channelTree: ChannelTree,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connected to $server")
        Text("Session #$sessionId")
        Text("Server version: ${serverVersion?.toString() ?: "—"}")
        Text("Ping: ${rttMs?.let { "%.1f ms".format(it) } ?: "—"}")
        ChannelTreeView(channelTree, mySession = sessionId, modifier = Modifier.weight(1f).fillMaxWidth())
        Button(onClick = onDisconnect) { Text("Disconnect") }
    }
}
