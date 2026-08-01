package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.protocol.ServerVersion

class FakeConnection : Connection {
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    override val serverVersion = MutableStateFlow<ServerVersion?>(null)
    override val roundTripMillis = MutableStateFlow<Double?>(null)
    override val channelTree = MutableStateFlow(ChannelTree())
    override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val speakingSessions = MutableStateFlow<Set<Int>>(emptySet())

    var connectCalls = 0; private set
    var lastEndpoint: MumbleEndpoint? = null; private set
    var trustCalls = 0; private set
    var cancelCalls = 0; private set
    var disconnectCalls = 0; private set
    val sentTexts = mutableListOf<String>()
    var sendResult = true
    var startCaptureCalls = 0; private set
    val transmitting = mutableListOf<Boolean>()

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        connectCalls++; lastEndpoint = endpoint
    }
    override fun trustAndConnect() { trustCalls++ }
    override fun cancelTrust() { cancelCalls++ }
    override fun disconnect() { disconnectCalls++ }
    override fun sendText(text: String): Boolean { sentTexts += text; return sendResult }
    override fun startCapture() { startCaptureCalls++ }
    override fun setTransmitting(on: Boolean) { transmitting += on }
}
