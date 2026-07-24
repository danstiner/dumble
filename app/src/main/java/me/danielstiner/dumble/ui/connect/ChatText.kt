package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.chat.DenyReason

/**
 * Human wording for a send rejection. Presentation, not protocol — hardcoded English today, the
 * single place a `stringResource` swap would land for localization. Pure so it stays unit-testable.
 * The channel name was captured at rejection time; this only phrases it.
 */
fun denyReasonText(reason: DenyReason): String = when (reason) {
    DenyReason.TooLong -> "Message too long"
    is DenyReason.NoPostPermission ->
        reason.channelName?.let { "Not allowed to post in $it" } ?: "Not allowed to post here"
    is DenyReason.Other -> reason.serverReason?.ifBlank { null } ?: "Message rejected"
}
