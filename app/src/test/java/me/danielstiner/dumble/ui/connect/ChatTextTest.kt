package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.chat.DenyReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {

    @Test fun wordsTooLong() {
        assertEquals("Message too long", denyReasonText(DenyReason.TooLong))
    }

    @Test fun wordsPermissionDenialWithTheChannelName() {
        assertEquals("Not allowed to post in Gaming", denyReasonText(DenyReason.NoPostPermission("Gaming")))
    }

    @Test fun fallsBackWhenTheChannelIsUnknown() {
        assertEquals("Not allowed to post here", denyReasonText(DenyReason.NoPostPermission(null)))
    }

    @Test fun prefersTheServerReasonThenFallsBack() {
        assertEquals("go away", denyReasonText(DenyReason.Other("go away")))
        assertEquals("Message rejected", denyReasonText(DenyReason.Other(null)))
        assertEquals("Message rejected", denyReasonText(DenyReason.Other("  ")))
    }
}
