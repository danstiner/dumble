package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.model.MumbleChannel
import me.danielstiner.dumble.mumble.model.ServerModel
import me.danielstiner.dumble.mumble.model.serverLabel

data class UserVm(
    val session: Int,           // the UI derives a deterministic avatar color from this
    val initial: String,
    val name: String,
    val isYou: Boolean,
    val speaking: Boolean,
    val selfMute: Boolean,      // neutral badge
    val serverMute: Boolean,    // error badge (server mute or suppress)
    val selfDeaf: Boolean,      // neutral deaf badge (takes priority over mute — can't hear you)
    val serverDeaf: Boolean,    // error deaf badge
    val recording: Boolean,     // recording privacy indicator
)

data class ChannelVm(
    val id: Int,
    val name: String,
    val isActive: Boolean,      // contains you
    val depth: Int,             // tree depth from root, for indentation
    val users: List<UserVm>,
)

data class CallScreenState(
    val serverName: String,
    val channels: List<ChannelVm>,
)

/**
 * Pure assembly of the call screen. Walks the channel tree depth-first (siblings ordered by
 * position then name — Mumble's `position` is only meaningful between siblings), carrying a depth for
 * indentation; hides empty channels except your own; floats your row to the top of your channel; and
 * prefers LOCAL mute/deaf/transmit for your own row (the server round-trips your UserState with a lag).
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
    val serverName = serverLabel(model, configHostFallback)

    val usersByChannel = model.users.values.groupBy { it.channelId }
    val myChannelId = myId?.let { model.users[it]?.channelId }

    fun userVm(session: Int): UserVm {
        val u = model.users.getValue(session)
        val you = session == myId
        val speaking = if (you) selfTransmitting && !localMuted else session in speakingSessions
        return UserVm(
            session = session,
            initial = u.name.trim().firstOrNull()?.uppercase() ?: "?",
            name = u.name,
            isYou = you,
            speaking = speaking && !(you && localDeafened),   // deafened self isn't "speaking"
            selfMute = if (you) localMuted else u.selfMute,
            serverMute = u.mute || u.suppress,
            selfDeaf = if (you) localDeafened else u.selfDeaf,
            serverDeaf = u.deaf,
            recording = u.recording,
        )
    }

    // DFS the channel tree from the root(s), sibling-relative order, carrying depth. Orphan channels
    // (parent missing) and any cycle are handled defensively so no channel is dropped or loops forever.
    val childrenOf = model.channels.values.groupBy { it.parentId }
    val known = model.channels.keys
    fun sortedSiblings(list: List<MumbleChannel>) =
        list.sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
    val orderedChannels = ArrayList<Pair<MumbleChannel, Int>>()
    val visited = HashSet<Int>()
    fun visit(ch: MumbleChannel, depth: Int) {
        if (!visited.add(ch.id)) return
        orderedChannels.add(ch to depth)
        sortedSiblings(childrenOf[ch.id].orEmpty()).forEach { visit(it, depth + 1) }
    }
    val roots = model.channels.values.filter { it.parentId == null || it.parentId !in known }
    sortedSiblings(roots).forEach { visit(it, 0) }

    val channels = orderedChannels.mapNotNull { (ch, depth) ->
        val members = usersByChannel[ch.id].orEmpty().map { it.session }
        val isActive = ch.id == myChannelId
        if (members.isEmpty() && !isActive) return@mapNotNull null
        // float self to the top of its own channel; others keep name order for stability
        val ordered = if (isActive && myId != null && myId in members) {
            listOf(myId) + members.filter { it != myId }.sortedBy { model.users.getValue(it).name.lowercase() }
        } else {
            members.sortedBy { model.users.getValue(it).name.lowercase() }
        }
        ChannelVm(id = ch.id, name = ch.name, isActive = isActive, depth = depth, users = ordered.map(::userVm))
    }

    return CallScreenState(serverName = serverName, channels = channels)
}
