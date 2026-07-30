package me.danielstiner.dumble.mumble.voice

/**
 * Payload convention: byte 0 is the number of 10 ms frames the packet represents, remaining
 * bytes are ignored. Decoding emits that many frames' worth of a constant sample equal to
 * byte 0, so tests can assert both length and provenance.
 */
class FakeOpusCodec : OpusCodec {
    var decodersCreated = 0
        private set
    var decodersClosed = 0
        private set
    var decodeCalls = 0
        private set

    /** Stands in for "a packet was admitted into a playout" — offer is the only caller. */
    var packetSamplesCalls = 0
        private set

    /**
     * When non-negative, the next decode returns this many samples instead of the packet's full
     * span, then resets. Models a decode that came up short — the only way a tick produces real
     * audio without filling a quantum, since every packet here spans whole 10 ms frames.
     * Volatile: set on the test thread, read on the playback thread.
     */
    @Volatile
    var nextDecodeSamples = -1

    override fun newDecoder(): OpusDecoder {
        decodersCreated++
        return object : OpusDecoder {
            override fun decode(opusData: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
                decodeCalls++
                if (opusData == null) return 0
                val short = nextDecodeSamples
                if (short >= 0) {
                    nextDecodeSamples = -1
                    out.fill(1, 0, short)
                    return short
                }
                val frames = opusData[offset].toInt()
                // Clamped like the real decoder, which caps frameSize at the out buffer.
                val n = minOf(frames * 480, out.size)
                out.fill(frames.toShort(), 0, n)
                return n
            }
            override fun close() { decodersClosed++ }
        }
    }

    override fun packetSamples(opusData: ByteArray, offset: Int, length: Int): Int {
        packetSamplesCalls++
        return opusData[offset].toInt() * 480
    }

    companion object {
        /** A packet spanning [tenMsFrames] * 10 ms. */
        fun packet(tenMsFrames: Int) = byteArrayOf(tenMsFrames.toByte(), 0, 0, 0)
    }
}
