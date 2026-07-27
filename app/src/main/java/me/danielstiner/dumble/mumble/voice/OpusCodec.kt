package me.danielstiner.dumble.mumble.voice

import java.util.concurrent.atomic.AtomicLong

interface OpusDecoder : AutoCloseable {
    /**
     * Decodes [opusData] into [out], returning the sample count.
     *
     * A null [opusData] asks for packet loss concealment: libopus synthesises a plausible
     * continuation from decoder state rather than leaving a hole. [plcFrameSamples] says how much
     * to synthesise, which only the caller can know — the packet that would have declared its own
     * length is the one that went missing. It is ignored for a real packet, whose TOC byte carries
     * the frame duration. Nothing requests concealment while audio arrives over the TCP tunnel,
     * which delivers without loss; the parameter becomes load-bearing when UDP lands.
     */
    fun decode(opusData: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int

    /** Frees the native handle. Repeat calls are no-ops; overlapping a live [decode] is not. */
    override fun close()
}

/** The seam tests substitute, so nothing above it loads native code. */
interface OpusCodec {
    fun newDecoder(): OpusDecoder
    /** Samples this packet will decode to, without decoding it. 0 if unparseable. */
    fun packetSamples(opusData: ByteArray, offset: Int, length: Int): Int
}

/** libopus-backed. Each decoder owns one native handle and is used by a single thread. */
class LibOpusCodec : OpusCodec {
    override fun newDecoder(): OpusDecoder = LibOpusDecoder()
    override fun packetSamples(opusData: ByteArray, offset: Int, length: Int): Int =
        NativeOpus.packetGetNbSamples(opusData, offset, length, SAMPLE_RATE).coerceAtLeast(0)
}

class LibOpusDecoder : OpusDecoder {
    // AtomicLong, not a plain Long, so a repeated close() is a no-op rather than a second
    // opus_decoder_destroy on a freed pointer — that corrupts the heap and throws nothing anyone
    // could catch. It does not make close() safe to race a live decode(); that ordering is still
    // the caller's to enforce.
    private val handle = AtomicLong(NativeOpus.createDecoder(SAMPLE_RATE, CHANNELS))

    init {
        require(handle.get() != 0L) { "opus_decoder_create failed" }
    }

    override fun decode(opusData: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
        val h = handle.get()
        if (h == 0L) return 0
        // With real data libopus needs room for the largest frame the packet might carry; with
        // null it synthesises exactly plcFrameSamples. Clamped to out: the JNI rejects a frameSize
        // past its length with OPUS_BAD_ARG, which coerces to 0 here and reads as permanent
        // silence with nothing thrown or logged.
        val frameSize = if (opusData == null) plcFrameSamples else minOf(out.size, MAX_FRAME_SAMPLES)
        return NativeOpus.decode(h, opusData, offset, length, out, frameSize, 0).coerceAtLeast(0)
    }

    override fun close() {
        val h = handle.getAndSet(0L)
        if (h != 0L) NativeOpus.destroyDecoder(h)
    }
}
