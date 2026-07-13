package me.danielstiner.dumble.mumble.voice

/** Pure-JVM codec factory for unit tests. Packets encode the sample count in a 4-byte header. */
class FakeOpusCodec : OpusCodec {
    override fun newEncoder(): OpusEncoder = FakeEncoder()
    override fun newDecoder(): OpusDecoder = FakeDecoder()
    override fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int =
        ((opus[offset].toInt() and 0xFF) shl 24) or ((opus[offset + 1].toInt() and 0xFF) shl 16) or
        ((opus[offset + 2].toInt() and 0xFF) shl 8) or (opus[offset + 3].toInt() and 0xFF)
}

class FakeEncoder : OpusEncoder {
    override fun encode(pcm: ShortArray, frameSamples: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (frameSamples ushr 24).toByte(); b[1] = (frameSamples ushr 16).toByte()
        b[2] = (frameSamples ushr 8).toByte();  b[3] = frameSamples.toByte()
        return b
    }
    override fun close() {}
}

class FakeDecoder : OpusDecoder {
    override fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
        val n = if (opus == null) plcFrameSamples else
            ((opus[offset].toInt() and 0xFF) shl 24) or ((opus[offset + 1].toInt() and 0xFF) shl 16) or
            ((opus[offset + 2].toInt() and 0xFF) shl 8) or (opus[offset + 3].toInt() and 0xFF)
        for (i in 0 until n) out[i] = if (opus == null) 0 else ((i % 100) - 50).toShort()
        return n
    }
    override fun close() {}
}
