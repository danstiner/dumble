package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal canonical (44-byte header) 48 kHz mono PCM16 little-endian WAV read/write. */
object WavReader {
    fun read(file: File): ShortArray {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44) { "WAV too short" }
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV" }
        var pos = 12; var channels = 0; var bits = 0; var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { channels = bb.getShort(body + 2).toInt(); bits = bb.getShort(body + 14).toInt() }
                "data" -> { dataOff = body; dataLen = len }
            }
            pos = body + len + (len and 1)
        }
        require(channels == 1 && bits == 16) { "expected mono PCM16, got ch=$channels bits=$bits" }
        require(dataOff >= 0) { "no data chunk" }
        val out = ShortArray(dataLen / 2)
        for (i in out.indices) out[i] = bb.getShort(dataOff + i * 2)
        return out
    }

    fun write(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        file.writeBytes(bb.array())
    }
}
