package me.danielstiner.dumble.mumble.chat

import java.time.Instant

/**
 * A chat log line. [receiveTime] is when we received or sent it locally — we assume receive order
 * tracks wall-clock and never re-sort, so it is not the sender's clock. The protocol layer never
 * interprets message content: remote bodies stay in server HTML format and are decoded at render,
 * locally-generated notices are plaintext.
 */
sealed interface ChatMessage {
    val receiveTime: Instant

    data class Remote(
        // null for a server/system broadcast — a TextMessage with no actor, not a real user.
        val actorSession: Int?,
        // The sender's name captured at receive time — a chat log is a transcript, so a later rename
        // or disconnect must not rewrite past lines. Null for a server broadcast or an unknown actor.
        val senderName: String?,
        // Server HTML format; decoded to display text at render time.
        val htmlBody: String,
        override val receiveTime: Instant,
    ) : ChatMessage

    // A locally-surfaced send rejection. Structured, not pre-worded: the protocol layer translates
    // the wire DenyType into this, and the UI decides the (someday localized) wording.
    data class Denied(
        val reason: DenyReason,
        override val receiveTime: Instant,
    ) : ChatMessage
}

/** Why the server rejected a send — the cases we surface, decoupled from the protobuf enum. */
sealed interface DenyReason {
    data object TooLong : DenyReason
    // The channel name captured at rejection time; null when the server omitted it or it's unknown.
    data class NoPostPermission(val channelName: String?) : DenyReason
    // A server-authored reason string (already plaintext, and not something we can localize).
    data class Other(val serverReason: String?) : DenyReason
}
