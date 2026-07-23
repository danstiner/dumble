package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.flow.StateFlow
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.protocol.ServerVersion

/** The coordinator surface the UI depends on — narrow so the ViewModel can be tested with a fake. */
interface Connection {
    val status: StateFlow<ConnectionStatus>
    val serverVersion: StateFlow<ServerVersion?>
    val roundTripMillis: StateFlow<Double?>
    val channelTree: StateFlow<ChannelTree>
    fun connect(endpoint: MumbleEndpoint, username: String, password: String?)
    fun trustAndConnect()
    fun cancelTrust()
    fun disconnect()
}
