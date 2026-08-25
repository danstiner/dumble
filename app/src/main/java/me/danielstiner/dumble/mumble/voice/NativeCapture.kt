package me.danielstiner.dumble.mumble.voice

/**
 * Thin binding over the native capture engine. Bound by symbol name from C, so these declarations
 * must not be renamed without updating `capture_jni.cpp` — R8 keeps them via AGP's default
 * `-keepclasseswithmembernames class * { native <methods>; }` rule.
 */
object NativeCapture {
    init { System.loadLibrary("dumble") }

    /** Byte count is non-negative; these three are the negative returns. */
    const val POLL_RETRY = -1
    const val POLL_SHUTDOWN = -2
    // Terminal: native exhausted reopen attempts. Distinct from POLL_RETRY.
    const val POLL_UNAVAILABLE = -3
    // Contract violations, not conditions to handle. Separate from POLL_SHUTDOWN.
    const val POLL_NO_SESSION = -4
    const val POLL_BUFFER_TOO_SMALL = -5
    const val FLAG_TERMINATOR = 1L

    const val META_FRAME_NUMBER = 0
    const val META_FLAGS = 1

    /** libopus ceiling for a single-frame packet. */
    const val MAX_PACKET_BYTES = 1276

    /** Returns 0 when the encoder cannot be built or the Silero blob ([weights]) will not load —
     *  there is no degraded mode. */
    external fun create(bitrate: Int, weights: ByteArray): Long
    external fun start(handle: Long): Int
    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun setGateOpen(handle: Long, open: Boolean)

    /** Blocks until a packet is ready or the engine shuts down. Returns byte count, 0 (nothing
     *  to send), [POLL_RETRY], [POLL_UNAVAILABLE], or [POLL_SHUTDOWN]. [out] must be at least
     *  [MAX_PACKET_BYTES]; meta carries frame_number and flags. */
    external fun pollPacket(handle: Long, out: ByteArray, meta: LongArray): Int

    /** Oboe bursts the ring had no room for: capture overruns. */
    external fun overrunBursts(handle: Long): Long
    /** Samples discarded to bound staleness when the pump fell behind. */
    external fun skippedSamples(handle: Long): Long
    external fun encodedPackets(handle: Long): Long
    /** Non-zero means libopus is failing; without this a broken encoder and an idle gate both
     *  look like [pollPacket] returning 0. */
    external fun encodeErrors(handle: Long): Long

    /** Microseconds per encode, against a 20 ms packet budget. Both are 0 before the first one. */
    external fun encodeMicrosMean(handle: Long): Long
    external fun encodeMicrosMax(handle: Long): Long

    /** Oboe's own count of the callback missing its deadline. 0 with no stream open. */
    external fun xRunCount(handle: Long): Long

    external fun framesPerBurst(handle: Long): Long
}
