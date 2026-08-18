package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.graphics.Color

/*
 * Normative spec: docs/avatar-color.md
 */

/**
 * The name's slot in [AvatarColors].
 */
fun avatarColorIndex(name: String): Int =
    (fnv1a32(name) % AvatarColors.size.toUInt()).toInt()

/** The color to fill [name]'s avatar with. */
fun avatarColor(name: String): Color = AvatarColors[avatarColorIndex(name)]

/**
 * Table of sixteen easily differentiable colors. See spec for details on color choice reasoning.
 */
val AvatarColors: List<Color> = listOf(
    Color(0xFF9C5A6F), Color(0xFFA05B59), Color(0xFF9D6044), Color(0xFF946733),
    Color(0xFF866F2C), Color(0xFF727734), Color(0xFF5A7D46), Color(0xFF3F815D),
    Color(0xFF218373), Color(0xFF128188), Color(0xFF277C99), Color(0xFF4376A3),
    Color(0xFF5D6FA6), Color(0xFF7367A2), Color(0xFF856196), Color(0xFF935C84),
)

/** White on every entry, which is what forces the table mid-dark enough to serve both themes. */
val AvatarInitialColor = Color.White
