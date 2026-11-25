package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.net.MumbleEndpoint

/** What a connection is opened with. */
internal class ConnectionOptions(
    val endpoint: MumbleEndpoint,
    val username: String,
    val password: String?,
    /** The certificate fingerprint to hold the server to, or null on first contact. */
    val pin: String?,
    /** What our voice costs as the server accounts it, for its max_bandwidth warning. */
    val voiceBitrate: Int,
)
