package me.danielstiner.dumble.mumble.voice

import android.util.Log

/**
 * Thin binding over the native playout engine. Bound by symbol name from C++, so these
 * declarations must not be renamed without updating `playout_jni.cpp` — R8 keeps them via AGP's
 * default `-keepclasseswithmembernames class * { native <methods>; }` rule.
 */
object NativePlayout {
    init { System.loadLibrary("dumble") }

    /** [offer] outcomes, mirroring `PlayoutConstants.h`'s `kOffer*`. None is terminal: every one
     *  is a condition a misbehaving server can produce at will, so the caller latches its logs
     *  rather than disabling receive. */
    const val OFFER_ACCEPTED = 0
    const val OFFER_SPEAKER_CAP = 1
    const val OFFER_PACKET_TOO_LARGE = 2

    /** The payload was not a parseable Opus packet, so it was dropped before reaching any queue.
     *  Its own code — and deliberately absent from [PlayoutStats.droppedPackets] — so a peer
     *  sending garbage is loggable and distinct from one whose audio overflows the jitter bounds. */
    const val OFFER_MALFORMED_PACKET = 3

    /** Negative return from [fillQuantum] or [readStats]: the arrays this side passed are too
     *  small, or `samples` is out of range. A bug here, never a condition to handle — it is a
     *  distinct code for the reason `CaptureConstants.h`'s `kPollBufferTooSmall` is, so an
     *  allocation mistake cannot masquerade as "nobody is speaking" and leave the app silently
     *  mute with no diagnostic. */
    const val ERROR_BUFFER_TOO_SMALL = -1

    /** Layout of [fillQuantum]'s `status`: the live speaker count, then one entry per producing
     *  session. The engine hands its two outputs back separately — this flat arrangement is the
     *  seam's, so that one array crossing the boundary carries both. Defined in `playout_jni.cpp`,
     *  which refuses anything shorter than [STATUS_LENGTH], and named here. */
    const val STATUS_ACTIVE_SPEAKERS = 0
    const val STATUS_SESSIONS = 1
    const val STATUS_LENGTH = STATUS_SESSIONS + MAX_SPEAKERS

    /** Indices into [readStats]' `counters`, the same arrangement and for the same reason:
     *  `PlayoutEngine::Stats` is a struct, and JNI carries primitive arrays. Monotonic since the
     *  engine was created — the caller subtracts a talk-spurt baseline, as it already does for the
     *  platform's underrun count. */
    const val COUNTER_CONCEALED_GAPS = 0
    const val COUNTER_DROPPED_PACKETS = 1

    /** Packets the engine discarded on purpose to shed standing delay. `PlayoutEngine::Stats`
     *  states why these are split from each other and from [COUNTER_DROPPED_PACKETS]. */
    const val COUNTER_SHRUNK_PACKETS = 2
    const val COUNTER_CATCH_UP_PACKETS = 3

    /** Fills the realtime path answered with silence because the reader held the engine's mutex;
     *  see `PlayoutEngine::setRealtime`. Monotonic like the four above. */
    const val COUNTER_CONTENDED_FILLS = 4

    /** Wall time per fill in microseconds, max and mean. A window since the last [readStats],
     *  not a total: the only two counters here that are not monotonic. */
    const val COUNTER_FILL_MICROS_MAX = 5
    const val COUNTER_FILL_MICROS_MEAN = 6
    const val COUNTER_COUNT = 7

    /** Returns 0 if native could not build an engine — libopus being unreachable is the only way
     *  that happens, and there is no degraded mode. */
    external fun create(sampleRate: Int, maxQuantumSamples: Int): Long

    external fun offer(
        handle: Long,
        session: Int,
        opusData: ByteArray,
        frameNumber: Long,
        terminator: Boolean,
    ): Int

    external fun fillQuantum(handle: Long, pcm: ShortArray, status: IntArray): Int
    external fun setWriteAhead(handle: Long, samples: Int)
    external fun readStats(
        handle: Long,
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        counters: LongArray,
    ): Int
    external fun destroy(handle: Long)
}

/** The real [VoiceReceiver.PlayoutEngine], one native engine per receiver. */
class NativePlayoutEngine(private val handle: Long) : VoiceReceiver.PlayoutEngine {
    override fun offer(session: Int, opusData: ByteArray, frameNumber: Long, terminator: Boolean) =
        NativePlayout.offer(handle, session, opusData, frameNumber, terminator)

    override fun fillQuantum(pcm: ShortArray, status: IntArray) =
        NativePlayout.fillQuantum(handle, pcm, status)

    override fun setWriteAhead(samples: Int) = NativePlayout.setWriteAhead(handle, samples)

    override fun readStats(
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        counters: LongArray,
    ) = NativePlayout.readStats(handle, sessions, depths, targets, counters)

    override fun destroy() = NativePlayout.destroy(handle)
}

/**
 * Build a native playout engine, or null if one could not be built. Mirrors
 * [openNativeCapture] in shape: the class initializer this reaches runs
 * `System.loadLibrary`, so an `UnsatisfiedLinkError` on a device missing the .so is an Error,
 * not an Exception, and voice receive is additive on top of chat and channels — it must degrade
 * to silence rather than fail the connection. Unlike capture there is no separate start(); the
 * engine is ready to mix as soon as it exists.
 */
fun openNativePlayout(): VoiceReceiver.PlayoutEngine? {
    val handle = runCatching {
        NativePlayout.create(SAMPLE_RATE, FRAME_SAMPLES)
    }.getOrElse {
        Log.e("VoiceReceiver", "playout engine unavailable, voice receive disabled", it)
        0L
    }
    if (handle == 0L) return null
    return NativePlayoutEngine(handle)
}
