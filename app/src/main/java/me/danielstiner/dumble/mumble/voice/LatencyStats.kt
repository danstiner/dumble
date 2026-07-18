package me.danielstiner.dumble.mumble.voice

/** Capture (mic→PCM) and playout (PCM→speaker) latency in ms. NaN = unavailable (rendered as "—"). */
data class LatencyStats(
    val captureMs: Double = Double.NaN,
    val playoutMs: Double = Double.NaN,
)
