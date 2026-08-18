package me.danielstiner.dumble.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [String.firstCodePoint] — unlike [String.take], it does not split a surrogate pair.
 */
class ChannelTreeViewInitialTest {

    @Test fun emptyStringReturnsEmpty() {
        assertEquals("", "".firstCodePoint())
    }

    @Test fun plainAsciiReturnsFirstLetter() {
        assertEquals("a", "alice".firstCodePoint())
    }

    @Test fun bmpNonAsciiCharReturnsFirstChar() {
        // Leading char is itself non-ASCII, unlike "Zoë" whose 'Z' would test nothing new.
        assertEquals("Ë", "Ëlla".firstCodePoint())

        assertEquals("日", "日本語".firstCodePoint())
    }

    @Test fun nonBmpEmojiReturnsWholeCodePoint() {
        // U+1F60A is a surrogate pair; take(1) would return half of it.
        val emoji = "😊bob"
        val result = emoji.firstCodePoint()

        assertEquals("😊", result)

        // length == 2 is what fails on a regression to take(1).
        assertEquals("non-BMP emoji should be 2 UTF-16 code units", 2, result.length)
    }
}
