package me.danielstiner.dumble.mumble.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/** One control-channel frame. [type] stays a raw id so unknown types survive to be ignored. */
class TcpFrame(val type: Int, val payload: ByteArray)

object MumbleCodec {
    /**
     * Sanity bound on inbound frames. Real control messages are orders of magnitude smaller;
     * this exists so a hostile or corrupt length cannot drive a huge allocation.
     */
    const val MAX_TCP_PAYLOAD = 8 * 1024 * 1024

    /** `[u16 type][u32 length][payload]`, big-endian — the Data streams are big-endian by contract. */
    fun writeFrame(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeShort(type)
        out.writeInt(payload.size)
        out.write(payload)
    }

    fun readFrame(inp: DataInputStream): TcpFrame {
        val type = inp.readUnsignedShort()
        val len = inp.readInt()
        // Corrupt/hostile length must surface as IOException, same family as a truncated read,
        // so callers can catch every wire-parse failure uniformly.
        if (len !in 0..MAX_TCP_PAYLOAD) throw java.io.StreamCorruptedException("bad frame length $len")
        val payload = ByteArray(len)
        inp.readFully(payload)
        return TcpFrame(type, payload)
    }
}
