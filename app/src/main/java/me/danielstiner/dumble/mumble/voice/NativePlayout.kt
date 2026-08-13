package me.danielstiner.dumble.mumble.voice

import android.util.Log

/**
 * Thin binding over the native playout engine. Bound by symbol name from C++, so these
 * declarations must not be renamed without updating `playout_jni.cpp` — R8 keeps them via AGP's
 * default `-keepclasseswithmembernames class * { native <methods>; }` rule.
 */
object NativePlayout {
    init { System.loadLibrary("dumble") }

    /** [offer] outcomes. Only [OFFER_ENGINE_UNUSABLE] is terminal for the session. */
    const val OFFER_ACCEPTED = 0
    const val OFFER_SPEAKER_CAP = 1
    const val OFFER_PACKET_TOO_LARGE = 2
    const val OFFER_ENGINE_UNUSABLE = 3

    /** Negative return from [fillQuantum] or [readStats]: the arrays this side passed are too
     *  small, or `frames` is out of range. A bug here, never a condition to handle — it is a
     *  distinct code for the reason `CaptureConstants.h`'s `kPollBufferTooSmall` is, so an
     *  allocation mistake cannot masquerade as "nobody is speaking" and leave the app silently
     *  mute with no diagnostic. */
    const val ERROR_BUFFER_TOO_SMALL = -1

    /** Index into [fillQuantum]'s `status`; producing sessions start at 1. The engine itself takes
     *  the two as separate outputs — this flat layout exists so one array crossing the JNI
     *  boundary carries both. */
    const val STATUS_ACTIVE_SPEAKERS = 0

    /** Indices into [readStats]' `counters`. Monotonic since the engine was created — the caller
     *  subtracts a talk-spurt baseline, as it already does for the platform's underrun count. */
    const val COUNTER_CONCEALED_TICKS = 0
    const val COUNTER_DROPPED_PACKETS = 1
    const val COUNTER_COUNT = 2

    /** Deliberate twin of `SlotSet::kCapacity`: it sizes the arrays handed across the boundary,
     *  and [create] refuses a larger value rather than letting the two drift. */
    const val MAX_SPEAKERS = 64

    /** Returns 0 if native could not build an engine — libopus being unreachable is the only way
     *  that happens, and there is no degraded mode. */
    external fun create(sampleRate: Int, maxQuantumSamples: Int, maxSpeakers: Int): Long

    external fun offer(handle: Long, session: Int, opusData: ByteArray, terminator: Boolean): Int
    external fun fillQuantum(handle: Long, pcm: ShortArray, status: IntArray): Int
    external fun readStats(
        handle: Long,
        sessions: IntArray,
        depths: IntArray,
        counters: LongArray,
    ): Int
    external fun destroy(handle: Long)
}

/** The real [VoiceReceiver.PlayoutEngine], one native engine per receiver. */
class NativePlayoutEngine(private val handle: Long) : VoiceReceiver.PlayoutEngine {
    override fun offer(session: Int, opusData: ByteArray, terminator: Boolean) =
        NativePlayout.offer(handle, session, opusData, terminator)

    override fun fillQuantum(pcm: ShortArray, status: IntArray) =
        NativePlayout.fillQuantum(handle, pcm, status)

    override fun readStats(sessions: IntArray, depths: IntArray, counters: LongArray) =
        NativePlayout.readStats(handle, sessions, depths, counters)

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
        NativePlayout.create(SAMPLE_RATE, QUANTUM_SAMPLES, NativePlayout.MAX_SPEAKERS)
    }.getOrElse {
        Log.e("VoiceReceiver", "playout engine unavailable, voice receive disabled", it)
        0L
    }
    if (handle == 0L) return null
    return NativePlayoutEngine(handle)
}
