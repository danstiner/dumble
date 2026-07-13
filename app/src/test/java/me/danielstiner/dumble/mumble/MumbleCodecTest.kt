package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.MumbleCodec
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class MumbleCodecTest {
    @Test fun tcpFrameRoundTrip() {
        val ping = MumbleProtos.Ping.newBuilder().setTimestamp(123L).build()
        val bos = ByteArrayOutputStream()
        MumbleCodec.writeFrame(DataOutputStream(bos), TcpMessageType.Ping.id, ping.toByteArray())
        val frame = MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
        assertEquals(TcpMessageType.Ping.id, frame.type)
        assertEquals(123L, MumbleProtos.Ping.parseFrom(frame.payload).timestamp)
    }

    @Test fun tcpFrameWireLayout() { // [u16 type][u32 len] big-endian
        val bos = ByteArrayOutputStream()
        MumbleCodec.writeFrame(DataOutputStream(bos), 5, byteArrayOf(0x7F))
        val b = bos.toByteArray()
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 0, 1, 0x7F), b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFrameRejected() {
        val header = byteArrayOf(0, 3, 0x7F, -1, -1, -1) // len = 0x7FFFFFFF
        MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(header)))
    }

    @Test fun udpPlaintextRoundTrip() {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setTarget(31).setFrameNumber(7L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(ByteArray(40) { it.toByte() }))
            .build()
        val buf = ByteArray(1024)
        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, buf)
        assertEquals(MumbleCodec.UDP_TYPE_AUDIO, buf[0].toInt())
        val parsed = MumbleUdpProtos.Audio.parser().parseFrom(buf, 1, n - 1)
        assertEquals(7L, parsed.frameNumber)
        assertEquals(31, parsed.target)
    }

    @Test fun typeRegistry() {
        assertEquals(TcpMessageType.Version, TcpMessageType.from(0))
        assertEquals(TcpMessageType.UDPTunnel, TcpMessageType.from(1))
        assertEquals(TcpMessageType.CryptSetup, TcpMessageType.from(15))
        assertEquals(TcpMessageType.PluginDataTransmission, TcpMessageType.from(26))
        assertNull(TcpMessageType.from(99))
    }
}
