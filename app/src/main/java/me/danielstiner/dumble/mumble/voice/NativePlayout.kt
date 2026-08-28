package me.danielstiner.dumble.mumble.voice

import android.util.Log

/**
 * Thin binding over the native playout session — the engine and the Oboe output stream that
 * pulls from it. Bound by symbol name from C++, so these declarations must not be renamed without
 * updating `playout_jni.cpp` — R8 keeps them via AGP's default
 * `-keepclasseswithmembernames class * { native <methods>; }` rule.
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

    /** Negative return from [readStats]: the arrays this side passed are too small. A bug here,
     *  never a condition to handle — it is a distinct code for the reason `CaptureConstants.h`'s
     *  `kPollBufferTooSmall` is, so an allocation mistake cannot masquerade as "nobody is
     *  speaking" and leave the app silently mute with no diagnostic. */
    const val ERROR_BUFFER_TOO_SMALL = -1

    /** Layout of [fillQuantum]'s `status`: the live speaker count, then one entry per producing
     *  session. Leaves with the AudioTrack loop. */
    const val STATUS_ACTIVE_SPEAKERS = 0
    const val STATUS_SESSIONS = 1
    const val STATUS_LENGTH = STATUS_SESSIONS + MAX_SPEAKERS

    /** Indices into [readStats]' `counters`: `PlayoutEngine::Stats` is a struct, and JNI carries
     *  primitive arrays. Monotonic since the session was created unless noted — the caller
     *  subtracts a talk-spurt baseline. */
    const val COUNTER_CONCEALED_GAPS = 0
    const val COUNTER_DROPPED_PACKETS = 1

    /** Packets the engine discarded on purpose to shed standing delay. `PlayoutEngine::Stats`
     *  states why these are split from each other and from [COUNTER_DROPPED_PACKETS]. */
    const val COUNTER_SHRUNK_PACKETS = 2
    const val COUNTER_CATCH_UP_PACKETS = 3

    /** Fills the callback answered with silence because the reader held the engine's mutex; see
     *  `PlayoutEngine::setRealtime`. */
    const val COUNTER_CONTENDED_FILLS = 4

    /** Wall time per fill in microseconds, max and mean. A window since the last [readStats],
     *  not a total. */
    const val COUNTER_FILL_MICROS_MAX = 5
    const val COUNTER_FILL_MICROS_MEAN = 6

    /** Bursts the stream played as silence because the callback did not fill them in time. */
    const val COUNTER_UNDERRUNS = 7

    /** Audio in flight between the callback and the speaker, from the stream's timestamp; -1
     *  while the stream is not started. A reading, not a count. */
    const val COUNTER_LATENCY_MICROS = 8
    const val COUNTER_COUNT = 9

    /** Returns 0 if native could not build an engine — libopus being unreachable is the only way
     *  that happens, and there is no degraded mode. The stream is opened but not started. */
    external fun create(sampleRate: Int): Long

    /** True when a started stream exists on return; see `OboePlayout::start`. */
    external fun start(handle: Long): Boolean
    external fun pause(handle: Long)

    external fun offer(
        handle: Long,
        session: Int,
        opusData: ByteArray,
        frameNumber: Long,
        terminator: Boolean,
    ): Int

    external fun readStats(
        handle: Long,
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        audible: IntArray,
        counters: LongArray,
    ): Int

    /** The AudioTrack loop's two calls; they leave with it. */
    external fun fillQuantum(handle: Long, pcm: ShortArray, status: IntArray): Int
    external fun setWriteAhead(handle: Long, samples: Int)

    external fun destroy(handle: Long)
}

/** The real [VoiceReceiver.PlayoutEngine], one native session per receiver. Still the AudioTrack
 *  loop's seam: the stream the session opened stays unstarted until the receiver drives it. */
class NativePlayoutEngine(private val handle: Long) : VoiceReceiver.PlayoutEngine {
    private val audible = IntArray(MAX_SPEAKERS)

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
    ) = NativePlayout.readStats(handle, sessions, depths, targets, audible, counters)

    override fun destroy() = NativePlayout.destroy(handle)
}

/**
 * Build a native playout session, or null if one could not be built. Mirrors [openNativeCapture]
 * in shape: the class initializer this reaches runs `System.loadLibrary`, so an
 * `UnsatisfiedLinkError` on a device missing the .so is an Error, not an Exception, and voice
 * receive is additive on top of chat and channels — it must degrade to silence rather than fail
 * the connection.
 */
fun openNativePlayout(): VoiceReceiver.PlayoutEngine? {
    val handle = runCatching {
        NativePlayout.create(SAMPLE_RATE)
    }.getOrElse {
        Log.e("VoiceReceiver", "playout engine unavailable, voice receive disabled", it)
        0L
    }
    if (handle == 0L) return null
    return NativePlayoutEngine(handle)
}
