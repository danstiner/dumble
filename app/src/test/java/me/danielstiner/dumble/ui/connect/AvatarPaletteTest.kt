package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The spec's vectors minus the hash column, which is generic FNV-1a and lives in [Fnv1aTest].
 */
class AvatarPaletteTest {

    private data class Vector(val name: String, val index: Int, val color: Int)

    private val vectors = listOf(
        Vector("", 5, 0xFF727734.toInt()),
        Vector("a", 12, 0xFF5D6FA6.toInt()),
        Vector("alice", 7, 0xFF3F815D.toInt()),
        Vector("bob", 4, 0xFF866F2C.toInt()),
        Vector("DanDesktop", 0, 0xFF9C5A6F.toInt()),
        Vector("DanRelease", 5, 0xFF727734.toInt()),
        Vector("Zoë", 12, 0xFF5D6FA6.toInt()),
        Vector("日本語", 7, 0xFF3F815D.toInt()),
    )

    @Test fun indexMatchesThePublishedVectors() {
        for (v in vectors) {
            assertEquals("index of \"${v.name}\"", v.index, avatarColorIndex(v.name))
        }
    }

    @Test fun colorMatchesThePublishedVectors() {
        for (v in vectors) {
            assertEquals("color of \"${v.name}\"", v.color, avatarColor(v.name).toArgb())
        }
    }

    /** The vectors hit only five distinct indices; this covers the other eleven entries. */
    @Test fun paletteMatchesTheSpecVerbatim() {
        assertEquals(
            listOf(
                0xFF9C5A6F.toInt(), 0xFFA05B59.toInt(), 0xFF9D6044.toInt(), 0xFF946733.toInt(),
                0xFF866F2C.toInt(), 0xFF727734.toInt(), 0xFF5A7D46.toInt(), 0xFF3F815D.toInt(),
                0xFF218373.toInt(), 0xFF128188.toInt(), 0xFF277C99.toInt(), 0xFF4376A3.toInt(),
                0xFF5D6FA6.toInt(), 0xFF7367A2.toInt(), 0xFF856196.toInt(), 0xFF935C84.toInt(),
            ),
            AvatarColors.map { it.toArgb() },
        )
    }

    @Test fun paletteHasSixteenEntries() {
        assertEquals(16, AvatarColors.size)
    }

    @Test fun everyNameLandsInsideThePalette() {
        for (name in listOf("", "a", "dan", "Zoë", "日本語", "x".repeat(500), "🙂bob")) {
            val i = avatarColorIndex(name)
            assertTrue("index $i out of range for \"$name\"", i in AvatarColors.indices)
        }
    }

    /** The gate on any palette swap. Thinnest shipped margin is 4.607:1, so this is not slack. */
    @Test fun everyEntryIsLegibleUnderTheInitial() {
        for ((i, color) in AvatarColors.withIndex()) {
            val ratio = contrastRatio(color, AvatarInitialColor)
            assertTrue("entry $i is only ${"%.2f".format(ratio)}:1 against the initial", ratio >= 4.5)
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG 2.1 relative luminance. */
    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
