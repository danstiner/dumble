package me.danielstiner.dumble.mumble.voice

interface OpusEncoder {
    fun encode(pcm: ShortArray, frameSamples: Int): ByteArray
    fun close()
}
interface OpusDecoder {
    fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int
    fun close()
}
interface OpusCodec {
    fun newEncoder(): OpusEncoder
    fun newDecoder(): OpusDecoder
    fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int
}

/** libopus-backed factory. Each encoder/decoder owns one native handle, used by a single thread. */
class LibOpusCodec(
    private val bitrate: Int = 24_000,
    private val complexity: Int = 5,
) : OpusCodec {
    override fun newEncoder(): OpusEncoder = LibOpusEncoder(bitrate, complexity)
    override fun newDecoder(): OpusDecoder = LibOpusDecoder()
    override fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int =
        NativeOpus.packetGetNbSamples(opus, offset, length, SAMPLE_RATE).coerceAtLeast(0)
}

class LibOpusEncoder(bitrate: Int, complexity: Int) : OpusEncoder {
    private val enc = NativeOpus.createEncoder(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP)
        .also { require(it != 0L) { "opus_encoder_create failed" } }
    private val encBuf = ByteArray(MAX_ENCODED_BYTES)
    init { NativeOpus.configureEncoder(enc, bitrate, complexity) }
    override fun encode(pcm: ShortArray, frameSamples: Int): ByteArray {
        val n = NativeOpus.encode(enc, pcm, frameSamples, encBuf, MAX_ENCODED_BYTES)
        require(n >= 0) { "opus_encode failed: $n" }
        return encBuf.copyOf(n)
    }
    override fun close() { NativeOpus.destroyEncoder(enc) }
}

class LibOpusDecoder : OpusDecoder {
    private val dec = NativeOpus.createDecoder(SAMPLE_RATE, CHANNELS)
        .also { require(it != 0L) { "opus_decoder_create failed" } }
    override fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
        val frameSize = if (opus == null) plcFrameSamples else MAX_FRAME_SAMPLES
        return NativeOpus.decode(dec, opus, offset, length, out, frameSize, 0).coerceAtLeast(0)
    }
    override fun close() { NativeOpus.destroyDecoder(dec) }
}
