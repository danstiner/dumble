package me.danielstiner.dumble.mumble.voice

interface OpusDecoder {
    /** Decodes into [out], returning the sample count. [opus] null requests loss concealment. */
    fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int
    fun close()
}

/** The seam tests substitute, so nothing above it loads native code. */
interface OpusCodec {
    fun newDecoder(): OpusDecoder
    /** Samples this packet will decode to, without decoding it. 0 if unparseable. */
    fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int
}

/** libopus-backed. Each decoder owns one native handle and is used by a single thread. */
class LibOpusCodec : OpusCodec {
    override fun newDecoder(): OpusDecoder = LibOpusDecoder()
    override fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int =
        NativeOpus.packetGetNbSamples(opus, offset, length, SAMPLE_RATE).coerceAtLeast(0)
}

class LibOpusDecoder : OpusDecoder {
    private val handle = NativeOpus.createDecoder(SAMPLE_RATE, CHANNELS)
        .also { require(it != 0L) { "opus_decoder_create failed" } }

    override fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
        // With real data libopus needs room for the largest frame the packet might carry; with
        // null it synthesises exactly plcFrameSamples.
        val frameSize = if (opus == null) plcFrameSamples else MAX_FRAME_SAMPLES
        return NativeOpus.decode(handle, opus, offset, length, out, frameSize, 0).coerceAtLeast(0)
    }

    override fun close() = NativeOpus.destroyDecoder(handle)
}
