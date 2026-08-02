package me.danielstiner.dumble.ui.connect

/**
 * The one badge an avatar carries, or null for none.
 *
 * A pure function rather than logic inside the composable: this is the only place a user's protocol
 * state becomes something visible, and with no Compose coverage of the roster it would otherwise be
 * the one part that could be deleted with the whole suite still green. It replaces `userLabel` in
 * that role.
 */
data class UserBadge(val kind: Kind, val server: Boolean) {
    enum class Kind { MUTE, DEAF }
}

/**
 * Deaf outranks mute — not being able to hear you is the more useful fact — and server-imposed state
 * is distinguished from self-imposed so the two can be styled differently. `suppress` is the server
 * refusing to carry your audio, so it collapses into a server mute.
 */
fun userBadge(u: ChannelTreeRow.UserRow): UserBadge? = when {
    u.deaf -> UserBadge(UserBadge.Kind.DEAF, server = true)
    u.selfDeaf -> UserBadge(UserBadge.Kind.DEAF, server = false)
    u.mute || u.suppress -> UserBadge(UserBadge.Kind.MUTE, server = true)
    u.selfMute -> UserBadge(UserBadge.Kind.MUTE, server = false)
    else -> null
}
