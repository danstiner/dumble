package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.flow.StateFlow
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.protocol.ServerVersion

/** The coordinator surface the UI depends on — narrow so the ViewModel can be tested with a fake. */
interface Connection {
    val status: StateFlow<ConnectionStatus>
    val serverVersion: StateFlow<ServerVersion?>
    val roundTripMillis: StateFlow<Double?>
    val channelTree: StateFlow<ChannelTree>
    val messages: StateFlow<List<ChatMessage>>
    val speakingSessions: StateFlow<Set<Int>>
    fun connect(endpoint: MumbleEndpoint, username: String, password: String?)
    fun trustAndConnect()
    fun cancelTrust()
    fun disconnect()
    fun sendText(text: String): Boolean

    /**
     * Start the microphone service and the capture session behind it. Call once RECORD_AUDIO is
     * granted, from a visible activity — a microphone foreground service cannot be started from
     * the background. At most once per session; a no-op with nothing connected.
     */
    fun startCapture()

    /** Push-to-talk. No-op until [startCapture] has a session running. */
    fun setTransmitting(on: Boolean)
}
