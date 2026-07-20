package me.danielstiner.dumble.mumble.voice

/** One speaker's jitter snapshot for the per-user debug screen. [p95Ms] is the RAW (unclamped)
 *  estimator p95 — the actual prebuffer [targetMs] is clamped [10,400]; a large p95 is that
 *  speaker's worst delay spike, not real buffering. */
data class SpeakerJitter(
    val session: Int,
    val targetMs: Int,
    val p95Ms: Int,
    val bufferedMs: Int,
    val lateDrops: Long,
)

/** Adaptive-jitter readout for the diagnostics HUD. Aggregate [targetMs]/[p95Ms] are the max across
 *  active speakers (back-compat with the existing HUD); [perSpeaker] is the per-speaker breakout. */
data class JitterStats(
    val perSpeaker: List<SpeakerJitter> = emptyList(),
) {
    val targetMs: Int get() = perSpeaker.maxOfOrNull { it.targetMs } ?: 10
    val p95Ms: Int get() = perSpeaker.maxOfOrNull { it.p95Ms } ?: 0
}
