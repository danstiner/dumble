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
 * [underruns], [concealedGaps] and [droppedPackets] are counts within the current talk spurt,
 * not cumulative: a cumulative underrun count would report the silence we deliberately leave
 * between spurts as glitches. [underruns] is null when the spurt's baseline could not be read.
 *
 * [concealedGaps] is the more trustworthy of the two platform-adjacent numbers, because we
 * generate it: it counts gaps in a speaker's audio, whether the fill carried real audio short of
 * its quantum — the rest zero-padded, speech spliced with silence — or nothing from a sender still
 * mid-spurt, which is the same gap at full width. Counted once per gap, not per fill. A platform
 * underrun counter cannot distinguish either from our own idling.
 *
 * [droppedPackets] is the other end of the pipeline. Where [concealedGaps] counts audio that
 * arrived too late to fill its quantum, this counts audio the jitter queue threw away before it
 * could be decoded at all — past `kMaxQueuedPackets` or `kHighWaterSamples` — plus packets refused
 * because every speaker slot was taken, which have no queue to charge them to. It is the only
 * instrument that shows the 32-slot pool capping a 10 ms sender's backlog at 320 ms, so a nonzero
 * reading means the network delivered a burst faster than the queue's bounds allow. A payload the
 * engine refused outright — oversized, or not parseable as Opus — is deliberately not in it: each
 * already answers with its own `OFFER_*` code and is logged on its own, so counting it here would
 * report one packet twice.
 */
data class PlayoutStats(
    val latencyMs: Double?,
    val underruns: Int?,
    val concealedGaps: Int,
    val droppedPackets: Int,
    val bufferedSamples: Map<Int, Int>,
) {
    /**
     * Sibling of [CaptureStats.summary]; the two are read side by side in one logcat, so they
     * share a shape. Latency is the floor on mouth-to-ear.
     *
     * [bufferedSamples] reads 0 on a healthy link and is not the jitter margin, which is the
     * intuitive reading and the wrong one. Measured on a Pixel 7a: the playback loop writes ahead
     * until AudioTrack's own buffer blocks it, so the prebuffered 60 ms ends up downstream of this
     * measurement, inside the track — visible in [latencyMs] at 120-180 ms rather than here. What
     * a nonzero depth means is that packets arrived faster than the loop drained them: a burst
     * still being absorbed, or playback failing to keep up.
     */
    fun summary(): String =
        "playout: latency=${latencyMs?.let { "%.1fms".format(Locale.ROOT, it) } ?: "n/a"} " +
            "underruns=${underruns ?: "n/a"} concealed=$concealedGaps dropped=$droppedPackets " +
            "depths=$bufferedSamples"
}
