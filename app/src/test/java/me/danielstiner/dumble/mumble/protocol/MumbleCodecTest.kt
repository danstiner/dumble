package me.danielstiner.dumble.mumble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.StreamCorruptedException

class MumbleCodecTest {

    private fun readFrom(bytes: ByteArray) = MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun frameRoundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = ByteArrayOutputStream()
        MumbleCodec.writeFrame(DataOutputStream(buffer), TcpMessageType.Authenticate.id, payload)

        val frame = readFrom(buffer.toByteArray())

        assertEquals(TcpMessageType.Authenticate.id, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val buffer = ByteArrayOutputStream()
        MumbleCodec.writeFrame(DataOutputStream(buffer), TcpMessageType.Ping.id, ByteArray(0))

        val frame = readFrom(buffer.toByteArray())

        assertEquals(TcpMessageType.Ping.id, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun oversizedLengthIsRejectedBeforeAllocating() {
        // type=0, length = MAX + 1, and no payload follows. If the bound were checked after
        // allocating, this would try for an 8 megabyte array before hitting end of stream.
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeShort(0)
            writeInt(MumbleCodec.MAX_TCP_PAYLOAD + 1)
        }

        assertThrows(StreamCorruptedException::class.java) { readFrom(header.toByteArray()) }
    }

    @Test
    fun negativeLengthIsRejected() {
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeShort(0)
            writeInt(-1)
        }

        assertThrows(StreamCorruptedException::class.java) { readFrom(header.toByteArray()) }
    }

    @Test
    fun truncatedPayloadThrowsEndOfStream() {
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeShort(0)
            writeInt(16)
            write(byteArrayOf(1, 2, 3))   // only 3 of the declared 16 bytes
        }

        assertThrows(EOFException::class.java) { readFrom(header.toByteArray()) }
    }

    @Test
    fun unknownMessageTypeIdMapsToNull() {
        assertNull(TcpMessageType.from(9999))
        assertEquals(TcpMessageType.ServerSync, TcpMessageType.from(5))
    }
}
