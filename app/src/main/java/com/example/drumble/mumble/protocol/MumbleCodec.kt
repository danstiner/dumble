package com.example.drumble.mumble.protocol

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import java.io.DataInputStream
import java.io.DataOutputStream

/** TCP control-channel message types (stable protocol IDs). */
enum class TcpMessageType(val id: Int) {
    Version(0), UDPTunnel(1), Authenticate(2), Ping(3), Reject(4), ServerSync(5),
    ChannelRemove(6), ChannelState(7), UserRemove(8), UserState(9), BanList(10),
    TextMessage(11), PermissionDenied(12), ACL(13), QueryUsers(14), CryptSetup(15),
    ContextActionModify(16), ContextAction(17), UserList(18), VoiceTarget(19),
    PermissionQuery(20), CodecVersion(21), UserStats(22), RequestBlob(23),
    ServerConfig(24), SuggestConfig(25), PluginDataTransmission(26);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun from(id: Int): TcpMessageType? = byId[id]
    }
}

class TcpFrame(val type: Int, val payload: ByteArray)

object MumbleCodec {
    /** New-protocol UDP plaintext type bytes (verified against real server in integration task). */
    const val UDP_TYPE_AUDIO = 0
    const val UDP_TYPE_PING = 1

    /** Sanity bound for inbound control frames (server messages are far smaller). */
    const val MAX_TCP_PAYLOAD = 8 * 1024 * 1024

    fun writeFrame(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeShort(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readFrame(inp: DataInputStream): TcpFrame {
        val type = inp.readUnsignedShort()
        val len = inp.readInt()
        require(len in 0..MAX_TCP_PAYLOAD) { "bad frame length $len" }
        val buf = ByteArray(len)
        inp.readFully(buf)
        return TcpFrame(type, buf)
    }

    /** Serializes [u8 type][protobuf] into dst without allocating a wire buffer. Returns bytes written. */
    fun writeUdpPlaintext(type: Int, message: MessageLite, dst: ByteArray): Int {
        val size = message.serializedSize
        require(dst.size >= size + 1) { "buffer too small: need ${size + 1}" }
        dst[0] = type.toByte()
        val cos = CodedOutputStream.newInstance(dst, 1, size)
        message.writeTo(cos)
        cos.checkNoSpaceLeft()
        return size + 1
    }
}
