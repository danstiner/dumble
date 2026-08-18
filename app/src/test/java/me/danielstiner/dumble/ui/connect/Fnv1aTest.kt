package me.danielstiner.dumble.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors from `docs/avatar-color.md`.
 *
 * The empty string and `"a"` are FNV-1a's own published vectors — a failure on those is a broken
 * hash, not a changed table.
 */
class Fnv1aTest {

    private val vectors = listOf(
        "" to 2166136261u,
        "a" to 3826002220u,
        "alice" to 2267157479u,
        "bob" to 2261164244u,
        "DanDesktop" to 3818523584u,
        "DanRelease" to 2530977605u,
        "Zoë" to 3265445340u,
        "日本語" to 2153733351u,
    )

    @Test fun matchesThePublishedVectors() {
        for ((text, hash) in vectors) {
            assertEquals("FNV-1a of \"$text\"", hash, fnv1a32(text))
        }
    }

    @Test fun caseIsNotFolded() {
        assertTrue(fnv1a32("dan") != fnv1a32("Dan"))
    }
}
