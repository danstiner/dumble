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
    // Native gave up reopening after repeated failures (see OboeCapture::retryReopen's backoff
    // loop) — distinct from POLL_RETRY so the pump can tell "still trying" from "never coming
    // back" and surface "transmit unavailable" instead of polling forever.
    const val POLL_UNAVAILABLE = -3
    // Both mean a bug here, not a condition to handle: a null handle, and an `out` array too small
    // to hold a largest-case packet. Separate from POLL_SHUTDOWN so neither can retire the pump
    // thread looking like an orderly stop.
    const val POLL_NO_SESSION = -4
    const val POLL_BUFFER_TOO_SMALL = -5
    const val FLAG_TERMINATOR = 1L

    /** Smallest [pollFrame] `out` array native will accept: libopus's own ceiling for a
     *  single-frame packet. A 32 kb/s packet is nearer 80 bytes; this is the worst case, not the
     *  expected one. */
    const val MAX_PACKET_BYTES = 1276

    /** Sample rate and frame size are owned by the native side (CaptureConstants.h): OboeCapture
     *  opens the stream from those constants, so passing them from here could only introduce a
     *  disagreement between what the stream captures and what the encoder was configured for.
     *  Bitrate stays a parameter because it is policy, not hardware truth.
     *
     *  Returns 0 if native could not build an engine — libopus refusing to create an encoder is
     *  the only way that happens. There is no degraded mode: treat it as capture being
     *  unavailable for the session rather than retrying. */
    external fun create(bitrate: Int): Long
    external fun start(handle: Long): Int
    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun setGateOpen(handle: Long, open: Boolean)

    /**
     * Blocks until a packet is ready, the wait elapses, or the engine shuts down. Returns bytes
     * written into [out], 0 if the wait elapsed with nothing to send, [POLL_RETRY] while the
     * stream is down and reopening, [POLL_UNAVAILABLE] once native has given up reopening for
     * good, or [POLL_SHUTDOWN] once [stop] has been called.
     *
     * [out] must be at least [MAX_PACKET_BYTES] long — native refuses a shorter one with
     * [POLL_BUFFER_TOO_SMALL] rather than encoding into less room than Opus may ask for.
     * `meta[0]` is `frame_number`, `meta[1]` is flags.
     */
    external fun pollFrame(handle: Long, out: ByteArray, meta: LongArray): Int

    // Instrumentation counters, each an independent relaxed atomic — reading them together was
    // never a consistent snapshot, so they are read one at a time rather than packed into a
    // long[] whose index-to-meaning mapping had to be maintained by hand on both sides.
    /** Oboe bursts the ring had no room for: capture overruns. */
    external fun overrunBursts(handle: Long): Long
    /** Samples discarded to bound staleness when the pump fell behind. */
    external fun skippedSamples(handle: Long): Long
    external fun encodedPackets(handle: Long): Long
    /** Non-zero means libopus is failing; without it a broken encoder and an idle gate both
     *  look like [pollFrame] returning 0. */
    external fun encodeErrors(handle: Long): Long
}
