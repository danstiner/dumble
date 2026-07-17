package me.danielstiner.dumble.mumble.voice.eval

import java.io.File

object EvalReport {
    fun write(dir: File, results: List<Pair<Clip, Metrics>>) {
        dir.mkdirs()
        val md = buildString {
            appendLine("| clip | coverage | onsetMs | hangMs | midDropMs | falseOpen/10s | loudnessDbFS | clipping |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for ((c, m) in results) appendLine(
                "| ${c.name} | %.3f | %d | %d | %d | %.2f | %.1f | %d |"
                    .format(m.coverage, m.onsetMs, m.hangoverMs, m.midDropoutMs, m.falseOpenings, m.speechLoudnessDbFs, m.clipping)
            )
        }
        File(dir, "metrics.md").writeText(md)
    }

    /**
     * Three-way comparison: RNNoise (the regression reference) vs Silero-raw (Silero on the clip's
     * raw PCM) vs Silero-denoised (Silero on RNNoise-denoised PCM). Silero is REPORTED here, not
     * asserted — [VadEvaluationTest] only asserts the RNNoise thresholds; this table is for a human
     * to pick the default engine + threshold.
     */
    fun writeComparison(
        dir: File,
        rnnoise: List<Pair<Clip, Metrics>>,
        sileroRaw: List<Pair<Clip, Metrics>>,
        sileroDenoised: List<Pair<Clip, Metrics>>,
    ) {
        dir.mkdirs()
        val md = buildString {
            appendLine("# VAD engine comparison")
            appendLine()
            appendLine("Silero reported, not asserted — pick the default engine + threshold from these numbers.")
            appendLine()
            appendLine("| clip | engine | coverage | onsetMs | midDropMs | falseOpen/10s |")
            appendLine("|---|---|---|---|---|---|")
            val byClip = rnnoise.indices.toList()
            for (i in byClip) {
                val (c, rn) = rnnoise[i]
                val (_, sr) = sileroRaw[i]
                val (_, sd) = sileroDenoised[i]
                appendLine(row(c.name, "RNNoise", rn))
                appendLine(row(c.name, "Silero-raw", sr))
                appendLine(row(c.name, "Silero-denoised", sd))
            }
        }
        File(dir, "metrics.md").writeText(md)
    }

    private fun row(name: String, engine: String, m: Metrics): String =
        "| $name | $engine | %.3f | %d | %d | %.2f |"
            .format(m.coverage, m.onsetMs, m.midDropoutMs, m.falseOpenings)
}
