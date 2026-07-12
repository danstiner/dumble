package com.example.drumble.mumble.voice

/** One encoded voice frame crossing the seam. */
class VoiceFrame(val opusData: ByteArray, val length: Int, val frameNumber: Long)

/**
 * THE audio seam — this interface is shaped as the permanent JNI boundary
 * ("decrypted Opus frame in / encoded Opus frame out").
 * Future native engine: nextOutgoingFrame parks on a semaphore posted by the
 * Oboe capture callback at frame boundaries (timeout = missed-signal backstop);
 * onIncomingFrame down-calls into the native jitter buffer (never blocks).
 */
interface VoiceEngine {
    fun start()
    fun stop()
    /** Blocking up to timeoutNanos. Returns null on timeout or when stopped. Voice-send-thread context. */
    fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame?
    /** Must not block; called from UDP receive thread or TCP reader (tunneled). */
    fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                        frameNumber: Long, senderSession: Int, arrivalNanos: Long)
}
