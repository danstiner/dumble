package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.model.ServerModel

data class UserVm(
    val session: Int,           // the UI derives a deterministic avatar color from this
    val initial: String,
    val name: String,
    val isYou: Boolean,
    val speaking: Boolean,
    val selfMute: Boolean,      // neutral badge
    val serverMute: Boolean,    // error badge (server mute or suppress)
)

data class ChannelVm(
    val id: Int,
    val name: String,
    val isActive: Boolean,      // contains you
    val users: List<UserVm>,
)

data class CallScreenState(
    val serverName: String,
    val channels: List<ChannelVm>,
)

/**
 * Pure assembly of the call screen. Groups users by channel (ordered by position), hides empty
 * channels except your own, floats your row to the top of your channel, and prefers LOCAL
 * mute/transmit for your own row (the server round-trips your UserState with a lag).
 */
fun buildCallScreenState(
    model: ServerModel,
    speakingSessions: Set<Int>,
    selfTransmitting: Boolean,
    localMuted: Boolean,
    localDeafened: Boolean,
    configHostFallback: String,
): CallScreenState {
    val myId = model.sessionId
    val rootName = model.channels.values.firstOrNull { it.parentId == null }?.name
    val serverName = rootName?.takeIf { it.isNotBlank() } ?: configHostFallback

    val usersByChannel = model.users.values.groupBy { it.channelId }
    val myChannelId = myId?.let { model.users[it]?.channelId }

    fun userVm(session: Int): UserVm {
        val u = model.users.getValue(session)
        val you = session == myId
        val selfMute = if (you) localMuted else u.selfMute
        val serverMute = u.mute || u.suppress
        val speaking = if (you) selfTransmitting && !localMuted else session in speakingSessions
        return UserVm(
            session = session,
            initial = u.name.trim().firstOrNull()?.uppercase() ?: "?",
            name = u.name,
            isYou = you,
            speaking = speaking && !(you && localDeafened),   // deafened self isn't "speaking"
            selfMute = selfMute,
            serverMute = serverMute,
        )
    }

    val channels = model.channels.values
        .sortedWith(compareBy({ it.position }, { it.id }))
        .mapNotNull { ch ->
            val members = usersByChannel[ch.id].orEmpty().map { it.session }
            val isActive = ch.id == myChannelId
            if (members.isEmpty() && !isActive) return@mapNotNull null
            // float self to the top of its own channel; others keep name order for stability
            val ordered = if (isActive && myId != null && myId in members) {
                listOf(myId) + members.filter { it != myId }.sortedBy { model.users.getValue(it).name.lowercase() }
            } else {
                members.sortedBy { model.users.getValue(it).name.lowercase() }
            }
            ChannelVm(id = ch.id, name = ch.name, isActive = isActive, users = ordered.map(::userVm))
        }

    return CallScreenState(serverName = serverName, channels = channels)
}
