package me.danielstiner.dumble.mumble.voice.eval

import java.io.File

/**
 * Reads Audacity "Export Labels" files — the ground-truth speech-region annotations.
 *
 * Format is Audacity-native (tab-separated `startSeconds<TAB>endSeconds<TAB>label`, one region
 * per line), so a human-verified `.manual.txt` and a Silero-generated `.silero.txt` are byte
 * interchangeable. Only the two time columns are used; the label text is ignored (every region
 * is speech). Times are converted to whole milliseconds on the clip's own timeline.
 */
object Labels {
    data class Region(val startMs: Int, val endMs: Int)

    fun read(file: File): List<Region> =
        file.readLines().mapNotNull { line ->
            val cols = line.trim().split("\t")
            if (cols.size < 2) return@mapNotNull null
            val start = cols[0].toDoubleOrNull() ?: return@mapNotNull null
            val end = cols[1].toDoubleOrNull() ?: return@mapNotNull null
            Region((start * 1000).toInt(), (end * 1000).toInt())
        }.sortedBy { it.startMs }
}
