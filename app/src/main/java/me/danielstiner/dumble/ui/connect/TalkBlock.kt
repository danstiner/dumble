package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.channeltree.User

/**
 * Why Talk is unavailable, or null when it is available.
 *
 * A pure function rather than a condition inside the composable, for the same reason [userBadge] is
 * one: this is the only place a user's protocol state decides whether a control works, and inside a
 * composable it would have no direct coverage.
 */
enum class TalkBlock { NO_MICROPHONE, DEAFENED, MUTED, RECONNECTING }

/**
 * The wire half is murmur's own drop condition, copied from `Server::processMsg`
 * (`u->bMute || u->bSuppress || u->bSelfMute`), so Talk is disabled exactly when the server would
 * discard what we send. Deafen is one cause of that rather than a special case — an admin mute and a
 * channel suppress reach it too, and both otherwise let the user hold Talk, watch the encoder run,
 * and be heard by nobody.
 *
 * [me] is our own row, or null in the window after Connected where the tree has not caught up —
 * status and tree are republished by separate collectors. Nothing blocks here on that absence:
 * pressing Talk is harmless, and disabling on absent data flickers the control at every connect.
 * A tree that has other users but not us is a different thing — a dead link's tree read under a
 * new link's session id — and the caller blocks on that.
 *
 * [TalkBlock.DEAFENED] is tested before the mute disjunction because murmur sets `self_mute`
 * alongside `self_deaf`, so a deafened user would otherwise be told the true-but-useless "Muted".
 */
fun talkBlock(me: User?, microphoneGranted: Boolean): TalkBlock? = when {
    !microphoneGranted -> TalkBlock.NO_MICROPHONE
    me == null -> null
    me.selfDeaf -> TalkBlock.DEAFENED
    me.mute || me.suppress || me.selfMute -> TalkBlock.MUTED
    else -> null
}
