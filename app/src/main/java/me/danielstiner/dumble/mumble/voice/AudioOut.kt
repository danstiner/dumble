package me.danielstiner.dumble.mumble.voice

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
     * Blocking. Paces the playback loop off the audio clock — see VoiceReceiver. Returns false on
     * failure (e.g. AudioTrack.write returning a negative error code such as ERROR_DEAD_OBJECT
     * after an audioserver restart). Unlike a blocking success, a failed write does not block, so
     * an ignored return value turns into the playback loop busy-spinning at
     * THREAD_PRIORITY_URGENT_AUDIO — the caller must stop rather than keep calling.
     */
    fun write(pcm: ShortArray, n: Int): Boolean
}
