package me.danielstiner.dumble.mumble.voice

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * One second of receive-path measurement, or a talk spurt's final tally.
 *
 * Receive-side only, and named for it: capture latency will arrive as a sibling from the transmit
 * path, and composing the two is [me.danielstiner.dumble.mumble.connection.MumbleConnection]'s job,
 * since it is the only place that also sees ping. The UI reads [depth] and [latencyMs] off it
 * for one speaker at a time; the rest is still logcat-only.
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
 *
 * [shrunkPackets] and [catchUpPackets] are audio we threw away on purpose to shed standing delay,
 * so neither is loss and neither is in [droppedPackets]. [targetSamples] is what each speaker's
 * gate is measuring against, and is read beside [bufferedSamples].
 *
 * [contendedFills] are fills the engine's realtime path answered with silence because the reader
 * held its mutex — see `PlayoutEngine::setRealtime`; per spurt, like the other counts. The fill
 * time pair is the wall time the engine spent per fill since the last sample, decodes included:
 * a device callback's budget is one burst, and this is how much of it the engine used.
 */
data class PlayoutStats(
    val latencyMs: Double?,
    val underruns: Int?,
    val concealedGaps: Int,
    val droppedPackets: Int,
    val shrunkPackets: Int,
    val catchUpPackets: Int,
    val contendedFills: Int,
    val fillMicrosMax: Long,
    val fillMicrosMean: Long,
    val bufferedSamples: Map<Int, Int>,
    val targetSamples: Map<Int, Int>,
) {
    /**
     * One speaker's queued audio, or null if the engine holds no slot for them — it retires a
     * speaker after `kRetireIdleSamples`, so absence means "has not sent recently".
     */
    fun depth(session: Int): Duration? =
        bufferedSamples[session]?.let { (it * 1_000_000L / SAMPLE_RATE).microseconds }

    /**
     * Sibling of [CaptureStats.summary]; the two are read side by side in one logcat, so they
     * share a shape. Latency is the floor on mouth-to-ear.
     *
     * [bufferedSamples] is the margin against a late packet, and the only margin: a packet is
     * popped a track's worth ahead of playout, so audio already in the track is delay without
     * margin. The engine adds that write-ahead to every target — see
     * `PlayoutEngine::setWriteAheadSamples` — which is why [targetSamples] reads above the
     * estimator's own figure, and why depth should sit near it. `depth + latency` is the standing
     * delay; neither alone is.
     */
    fun summary(): String =
        "playout: latency=${latencyMs?.let { "%.1fms".format(Locale.ROOT, it) } ?: "n/a"} " +
            "underruns=${underruns ?: "n/a"} concealed=$concealedGaps dropped=$droppedPackets " +
            "shrunk=$shrunkPackets caughtUp=$catchUpPackets " +
            "depths=$bufferedSamples targets=$targetSamples " +
            "contended=$contendedFills fill=$fillMicrosMean/${fillMicrosMax}us"
}
