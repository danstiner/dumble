package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.channeltree.User
import me.danielstiner.dumble.mumble.protocol.DeafenState

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
 * The self half is read off [asked], which only we can set and the capture gate already obeys; the
 * server's half off [me], our own row, which only it knows. [me] is null in the millisecond-scale
 * window after Connected where the tree has not caught up — status and tree are republished by
 * separate collectors — and reads as not muted: disabling on absent data flickers the control at
 * every connect.
 *
 * [TalkBlock.DEAFENED] is tested before the mute disjunction because a deafen mutes too, so a
 * deafened user would otherwise be told the true-but-useless "Muted".
 */
fun talkBlock(
    me: User?,
    asked: DeafenState,
    microphoneGranted: Boolean,
    reconnecting: Boolean = false,
): TalkBlock? = when {
    // Permission first: a link coming back cannot unblock it, and the Mute control keys off this.
    !microphoneGranted -> TalkBlock.NO_MICROPHONE
    // The held link is dead, and our own row is a link out of date.
    reconnecting -> TalkBlock.RECONNECTING
    asked.selfDeaf -> TalkBlock.DEAFENED
    asked.selfMute || me?.mute == true || me?.suppress == true -> TalkBlock.MUTED
    else -> null
}
