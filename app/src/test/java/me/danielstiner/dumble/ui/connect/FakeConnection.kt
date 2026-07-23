package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.protocol.ServerVersion

class FakeConnection : Connection {
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    override val serverVersion = MutableStateFlow<ServerVersion?>(null)
    override val roundTripMillis = MutableStateFlow<Double?>(null)

    var connectCalls = 0; private set
    var lastEndpoint: MumbleEndpoint? = null; private set
    var trustCalls = 0; private set
    var cancelCalls = 0; private set
    var disconnectCalls = 0; private set

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        connectCalls++; lastEndpoint = endpoint
    }
    override fun trustAndConnect() { trustCalls++ }
    override fun cancelTrust() { cancelCalls++ }
    override fun disconnect() { disconnectCalls++ }
}
