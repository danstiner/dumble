package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.net.*
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Output stream whose write() always throws, used to simulate a dead socket. */
private class ThrowingOutputStream(private val message: String = "boom") : OutputStream() {
    override fun write(b: Int) = throw IOException(message)
    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException(message)
}

/** Captured onClosed(cause) invocation; wrapped so we can carry a null cause through a queue. */
private data class ClosedEvent(val cause: Throwable?)

/** Polls [predicate] until true or [timeoutMs] elapses, then asserts it one last time. */
private fun awaitTrue(timeoutMs: Long = 2000, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(10)
    }
    assertTrue(predicate())
}

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

    @Test fun writerFailureTearsDownAndReports() {
        val transport = MumbleTcpTransport(InMemoryPinStore())
        // Reader side stays open and empty — only the writer should fail in this test.
        val inboundPipe = PipedOutputStream()
        val input = DataInputStream(PipedInputStream(inboundPipe, 64 * 1024))
        val output = DataOutputStream(ThrowingOutputStream())
        val closedEvents = LinkedBlockingQueue<ClosedEvent>()
        transport.startLoops(input, output, object : MumbleTcpTransport.Listener {
            override fun onFrame(frame: TcpFrame) {}
            override fun onClosed(cause: Throwable?) { closedEvents.add(ClosedEvent(cause)) }
        })

        assertTrue(transport.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder().setTimestamp(1L).build()))

        val event = closedEvents.poll(2, TimeUnit.SECONDS)
        assertNotNull("onClosed should fire after a write failure", event)
        assertNotNull("cause should be non-null for a real write error, not a local close", event!!.cause)

        // The writer's catch block calls close() right after reporting; give that a moment
        // to land, then confirm the transport is torn down (no longer accepting sends).
        awaitTrue { !transport.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder().setTimestamp(2L).build()) }
    }

    @Test fun onClosedFiresAtMostOnceOnDualFailure() {
        val transport = MumbleTcpTransport(InMemoryPinStore())
        val inboundPipe = PipedOutputStream()
        val input = DataInputStream(PipedInputStream(inboundPipe, 64 * 1024))
        val output = DataOutputStream(ThrowingOutputStream())
        val closedCount = AtomicInteger(0)
        transport.startLoops(input, output, object : MumbleTcpTransport.Listener {
            override fun onFrame(frame: TcpFrame) {}
            override fun onClosed(cause: Throwable?) { closedCount.incrementAndGet() }
        })

        // Break the writer (write() throws) and the reader (pipe EOF -> EOFException) at
        // roughly the same time so both coroutines race to report onClosed.
        transport.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder().setTimestamp(1L).build())
        inboundPipe.close()

        awaitTrue { closedCount.get() >= 1 }
        // Grace window to catch a spurious second invocation from the other coroutine.
        Thread.sleep(300)
        assertEquals(1, closedCount.get())
    }

    @Test fun closeBeforeLoopsIsClean() {
        val transport = MumbleTcpTransport(InMemoryPinStore())
        transport.close()
        assertFalse(transport.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder().setTimestamp(1L).build()))

        // Simulate the connect-race outcome: startLoops() invoked directly against an
        // already-closed transport (this is the shape connect() would produce if it ever
        // started loops after close() had already run — which the connect()/close() lock now
        // prevents on the real socket path). Feed an idle, never-written-to pipe: nothing
        // should ever be delivered, and the transport must stay closed.
        val inboundPipe = PipedOutputStream()
        val input = DataInputStream(PipedInputStream(inboundPipe, 64 * 1024))
        val outBytes = ByteArrayOutputStream()
        val received = LinkedBlockingQueue<TcpFrame>()
        transport.startLoops(input, DataOutputStream(outBytes), object : MumbleTcpTransport.Listener {
            override fun onFrame(frame: TcpFrame) { received.add(frame) }
            override fun onClosed(cause: Throwable?) {}
        })

        assertNull("no frames should ever be delivered", received.poll(300, TimeUnit.MILLISECONDS))
        assertFalse(transport.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder().setTimestamp(2L).build()))
        assertEquals("writer must not emit anything against an already-closed sendQueue", 0, outBytes.size())
    }
}
