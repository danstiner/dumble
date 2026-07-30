package me.danielstiner.dumble.mumble.voice

import java.util.Locale

/**
 * One second of receive-path measurement, or a talk spurt's final tally.
 *
 * Receive-side only, and named for it: capture latency will arrive as a sibling from the transmit
 * path, and composing the two is [me.danielstiner.dumble.mumble.connection.MumbleConnection]'s job,
 * since it is the only place that also sees ping. Nothing consumes this yet — it exists because the
 * planned stats pages are a stated destination and the shape is cheap to get right now.
 *
 * [underruns] and [concealedTicks] are counts within the current talk spurt, not cumulative: a
 * cumulative underrun count would report the silence we deliberately leave between spurts as
 * glitches. [underruns] is null when the spurt's baseline could not be read.
 *
 * [concealedTicks] is the more trustworthy of the two, because we generate it: it counts ticks
 * where a speaker produced real audio but less than a full quantum, so the rest was zero-padded —
 * speech spliced with silence. A platform underrun counter cannot distinguish that from our own
 * idling.
 */
data class PlayoutStats(
    val latencyMs: Double?,
    val underruns: Int?,
    val concealedTicks: Int,
    val bufferedSamples: Map<Int, Int>,
) {
    /**
     * Sibling of [CaptureStats.summary]; the two are read side by side in one logcat, so they
     * share a shape. Latency is the floor on mouth-to-ear, and a depth climbing across samples is
     * the network adding delay the jitter buffer is absorbing.
     */
    fun summary(): String =
        "playout: latency=${latencyMs?.let { "%.1fms".format(Locale.ROOT, it) } ?: "n/a"} " +
            "underruns=${underruns ?: "n/a"} concealed=$concealedTicks depths=$bufferedSamples"
}
