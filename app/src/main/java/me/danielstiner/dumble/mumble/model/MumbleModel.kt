package me.danielstiner.dumble.mumble.model

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MumbleChannel(
    val id: Int,
    val parentId: Int?,
    val name: String,
    val position: Int = 0,
    val temporary: Boolean = false,
)

data class MumbleUser(
    val session: Int,
    val name: String,
    val channelId: Int = 0,
    val mute: Boolean = false,
    val deaf: Boolean = false,
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    val suppress: Boolean = false,
    val recording: Boolean = false,
    val tcpPingMs: Float? = null,   // server-reported ping-to-server (via UserStats); null = unknown
    val udpPingMs: Float? = null,
)

data class ServerModel(
    val channels: Map<Int, MumbleChannel> = emptyMap(),
    val users: Map<Int, MumbleUser> = emptyMap(),
    val sessionId: Int? = null,
    val maxBandwidth: Int? = null,
    val welcomeText: String? = null,
)

/** Pure reducers: proto2 has-bits decide field-by-field whether to overwrite. */
object ModelReducers {
    fun applyChannelState(m: ServerModel, msg: MumbleProtos.ChannelState): ServerModel {
        val old = m.channels[msg.channelId]
        val ch = MumbleChannel(
            id = msg.channelId,
            parentId = if (msg.hasParent()) msg.parent else old?.parentId,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            position = if (msg.hasPosition()) msg.position else old?.position ?: 0,
            temporary = if (msg.hasTemporary()) msg.temporary else old?.temporary ?: false,
        )
        return m.copy(channels = m.channels + (ch.id to ch))
    }

    fun applyChannelRemove(m: ServerModel, msg: MumbleProtos.ChannelRemove): ServerModel =
        m.copy(channels = m.channels - msg.channelId)

    fun applyUserState(m: ServerModel, msg: MumbleProtos.UserState): ServerModel {
        val old = m.users[msg.session]
        val u = MumbleUser(
            session = msg.session,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            channelId = if (msg.hasChannelId()) msg.channelId else old?.channelId ?: 0,
            mute = if (msg.hasMute()) msg.mute else old?.mute ?: false,
            deaf = if (msg.hasDeaf()) msg.deaf else old?.deaf ?: false,
            selfMute = if (msg.hasSelfMute()) msg.selfMute else old?.selfMute ?: false,
            selfDeaf = if (msg.hasSelfDeaf()) msg.selfDeaf else old?.selfDeaf ?: false,
            suppress = if (msg.hasSuppress()) msg.suppress else old?.suppress ?: false,
            recording = if (msg.hasRecording()) msg.recording else old?.recording ?: false,
            tcpPingMs = old?.tcpPingMs,   // preserve — UserStats writes these, UserState must not wipe them
            udpPingMs = old?.udpPingMs,
        )
        return m.copy(users = m.users + (u.session to u))
    }

    fun applyUserRemove(m: ServerModel, msg: MumbleProtos.UserRemove): ServerModel =
        m.copy(users = m.users - msg.session)

    fun applyUserStats(m: ServerModel, msg: MumbleProtos.UserStats): ServerModel {
        val old = m.users[msg.session] ?: return m       // stats for an unknown user → ignore
        val u = old.copy(
            tcpPingMs = if (msg.hasTcpPingAvg()) msg.tcpPingAvg else old.tcpPingMs,
            udpPingMs = if (msg.hasUdpPingAvg()) msg.udpPingAvg else old.udpPingMs,
        )
        return m.copy(users = m.users + (u.session to u))
    }

    fun applyServerSync(m: ServerModel, msg: MumbleProtos.ServerSync): ServerModel = m.copy(
        sessionId = if (msg.hasSession()) msg.session else m.sessionId,
        maxBandwidth = if (msg.hasMaxBandwidth()) msg.maxBandwidth else m.maxBandwidth,
        welcomeText = if (msg.hasWelcomeText()) msg.welcomeText else m.welcomeText,
    )
}

/** Mutable holder exposing immutable snapshots. Single-writer (the session dispatcher). */
class MumbleModel {
    private val _state = MutableStateFlow(ServerModel())
    val state: StateFlow<ServerModel> = _state.asStateFlow()

    fun onChannelState(msg: MumbleProtos.ChannelState) { _state.value = ModelReducers.applyChannelState(_state.value, msg) }
    fun onChannelRemove(msg: MumbleProtos.ChannelRemove) { _state.value = ModelReducers.applyChannelRemove(_state.value, msg) }
    fun onUserState(msg: MumbleProtos.UserState) { _state.value = ModelReducers.applyUserState(_state.value, msg) }
    fun onUserRemove(msg: MumbleProtos.UserRemove) { _state.value = ModelReducers.applyUserRemove(_state.value, msg) }
    fun onUserStats(msg: MumbleProtos.UserStats) { _state.value = ModelReducers.applyUserStats(_state.value, msg) }
    fun onServerSync(msg: MumbleProtos.ServerSync) { _state.value = ModelReducers.applyServerSync(_state.value, msg) }
    fun reset() { _state.value = ServerModel() }
}
