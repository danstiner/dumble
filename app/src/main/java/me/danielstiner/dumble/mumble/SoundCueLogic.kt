package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.model.ServerModel

/** Snapshot of who is in OUR current channel (excluding self). */
data class ChannelPresence(val channelId: Int?, val members: Set<Int>)
data class PresenceDiff(val joins: Set<Int>, val leaves: Set<Int>, val next: ChannelPresence)

/**
 * Diff the members of our current channel (excluding ourselves) against [prev]. Returns empty
 * joins/leaves plus a fresh baseline when [prev] is null (first snapshot) or our OWN channel changed
 * (we moved / just synced) — so the initial roster and our own channel hops never fire cues.
 */
fun diffChannelPresence(prev: ChannelPresence?, model: ServerModel): PresenceDiff {
    val myId = model.sessionId
    val myChannel = myId?.let { model.users[it]?.channelId }
    val members = model.users.values
        .filter { it.session != myId && it.channelId == myChannel }
        .map { it.session }.toSet()
    val next = ChannelPresence(myChannel, members)
    if (prev == null || prev.channelId != myChannel) return PresenceDiff(emptySet(), emptySet(), next)
    return PresenceDiff(joins = members - prev.members, leaves = prev.members - members, next = next)
}
