package me.danielstiner.dumble.mumble.voice

/**
 * A finished reading rather than raw fields: only [AndroidAudioOut] knows how many frames it has
 * written, so it is the only place that can turn a hardware timestamp into a latency.
 *
 * [latencyMs] is null when the platform reports no timestamp or a stale one — expected at the
 * start of a spurt and permanent on routes that never report one. [underrunsTotal] is cumulative
 * since the track started; making it meaningful needs talk-spurt boundaries, which the caller has
 * and this does not.
 */
data class OutputStats(val latencyMs: Double?, val underrunsTotal: Int)

/**
 * Abstracts the Android playback device so the receiver's logic stays JVM-testable.
 *
 * AutoCloseable for the same reason [OpusDecoder] is: it marks the type as owning a resource, for
 * readers and for tooling. Nothing uses `use {}` on it — the playback loop acquires and releases
 * across a try/finally it already needs for other reasons, and wrapping the loop body in a lambda
 * would only add nesting.
 */
interface AudioOut : AutoCloseable {
    /**
     * How far ahead of playout this sink holds audio, in samples: a blocking [write] returns once
     * the sink is this full, so audio is handed over this long before it is heard. The engine
     * adds it to the jitter target — see `PlayoutEngine::setWriteAheadSamples` — because audio
     * already handed over is delay but not margin. Fixed for the life of the sink.
     */
    val writeAheadSamples: Int

    /**
     * Blocking. Paces the playback loop off the audio clock — see VoiceReceiver. Returns false on
     * failure (e.g. AudioTrack.write returning a negative error code such as ERROR_DEAD_OBJECT
     * after an audioserver restart). Unlike a blocking success, a failed write does not block, so
     * an ignored return value turns into the playback loop busy-spinning at
     * THREAD_PRIORITY_URGENT_AUDIO — the caller must stop rather than keep calling.
     */
    fun write(pcm: ShortArray, n: Int): Boolean

    /**
     * Called from the playback thread: roughly once a second during a steady spurt, but also once
     * at every spurt's open and close, so choppy speech can drive this to ~15-30 calls/sec — an
     * implementation must stay cheap at that rate, not just once a second. Abstract rather than
     * defaulted: with one production implementation and one fake, a default would save two lines
     * in the fake and let a production implementation silently report nothing forever.
     */
    fun outputStats(): OutputStats
}
