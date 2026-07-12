package com.example.drumble.mumble

import com.example.drumble.mumble.net.*
import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MumbleTcpTransportTest {
    @Test fun readerDeliversAndWriterFrames() {
        val transport = MumbleTcpTransport(InMemoryPinStore())
        val inboundPipe = PipedOutputStream()
        val input = DataInputStream(PipedInputStream(inboundPipe, 64 * 1024))
        val outBytes = ByteArrayOutputStream()
        val received = LinkedBlockingQueue<TcpFrame>()
        transport.startLoops(input, DataOutputStream(outBytes), object : MumbleTcpTransport.Listener {
            override fun onFrame(frame: TcpFrame) { received.add(frame) }
            override fun onClosed(cause: Throwable?) {}
        })
        // inbound: server → client
        val ping = MumbleProtos.Ping.newBuilder().setTimestamp(9L).build()
        MumbleCodec.writeFrame(DataOutputStream(inboundPipe), TcpMessageType.Ping.id, ping.toByteArray())
        val f = received.poll(2, TimeUnit.SECONDS)
        assertNotNull(f); assertEquals(TcpMessageType.Ping.id, f!!.type)
        assertEquals(9L, MumbleProtos.Ping.parseFrom(f.payload).timestamp)
        // outbound: client → server
        assertTrue(transport.send(TcpMessageType.Version, MumbleProtos.Version.newBuilder().setRelease("t").build()))
        val deadline = System.currentTimeMillis() + 2000
        while (outBytes.size() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val out = MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(outBytes.toByteArray())))
        assertEquals(TcpMessageType.Version.id, out.type)
        transport.close(); transport.close() // idempotent
    }
}
