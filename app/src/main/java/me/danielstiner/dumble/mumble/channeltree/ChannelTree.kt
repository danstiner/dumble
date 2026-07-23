package me.danielstiner.dumble.mumble.channeltree

/**
 * Immutable snapshot of the server's channel tree. Stored flat (two maps); the parent/child
 * hierarchy is derived from [Channel.parentId] at render time. Matches Murmur's `Tree`.
 */
data class ChannelTree(
    val channels: Map<Int, Channel> = emptyMap(),
    val users: Map<Int, User> = emptyMap(),
)

/** null [parentId] is the root (channel_id 0); every other channel names its parent. */
data class Channel(
    val id: Int,
    val parentId: Int?,
    val name: String,
    val position: Int,
)

data class User(
    val session: Int,
    val name: String,
    val channelId: Int,
    val mute: Boolean,
    val deaf: Boolean,
    val selfMute: Boolean,
    val selfDeaf: Boolean,
    val suppress: Boolean,
)
