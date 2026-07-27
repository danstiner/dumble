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

    /** Stands in for "a packet was accepted into a queue" — offer is the only caller. */
    var packetSamplesCalls = 0
        private set

    override fun newDecoder(): OpusDecoder {
        decodersCreated++
        return object : OpusDecoder {
            override fun decode(opusData: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
                decodeCalls++
                if (opusData == null) return 0
                val frames = opusData[offset].toInt()
                val n = frames * 480
                java.util.Arrays.fill(out, 0, n, frames.toShort())
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
