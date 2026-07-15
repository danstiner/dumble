package me.danielstiner.dumble.mumble.voice.eval

import java.io.File

object EvalReport {
    fun write(dir: File, results: List<Pair<Clip, Metrics>>) {
        dir.mkdirs()
        val md = buildString {
            appendLine("| clip | coverage | onsetMs | hangMs | midDropMs | falseOpen/10s | loudnessDbFS | clip |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for ((c, m) in results) appendLine(
                "| ${c.name} | %.3f | %d | %d | %d | %.2f | %.1f | %d |"
                    .format(m.coverage, m.onsetMs, m.hangoverMs, m.midDropoutMs, m.falseOpenings, m.speechLoudnessDbFs, m.clipping)
            )
        }
        File(dir, "metrics.md").writeText(md)
    }
}
