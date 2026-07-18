package me.danielstiner.dumble.mumble.voice

/** Aggregate adaptive-jitter readout (max across active speakers), for the diagnostics HUD. */
data class JitterStats(
    val targetMs: Int = 10,
    val p95Ms: Int = 0,
)
