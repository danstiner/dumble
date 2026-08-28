package me.danielstiner.dumble.mumble.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class NativeCaptureTest {

    /** Exercises the real encoder on a real ABI — unreachable from any JVM test. */
    @Test
    fun stopUnblocksAParkedPollFrame() {
        // The bundled blob: an engine refuses anything else, and a refused engine is handle 0,
        // which polls as POLL_NO_SESSION forever rather than parking.
        val weights = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("silero_vad_weights.bin").use { it.readBytes() }
        val h = NativeCapture.create(40_000, weights)
        assertNotEquals("no engine", 0L, h)
        try {
            val out = ByteArray(NativeCapture.MAX_PACKET_BYTES)
            val meta = LongArray(2)
            val last = AtomicInteger()
            val t = thread {
                // No stream started, so the gate produces nothing and this parks in the timed wait.
                while (true) {
                    val n = NativeCapture.pollPacket(h, out, meta)
                    if (n == NativeCapture.POLL_SHUTDOWN) { last.set(n); break }
                }
            }
            Thread.sleep(50)
            NativeCapture.stop(h)
            t.join(1_000)
            assertTrue("pollPacket did not unblock", !t.isAlive)
            assertEquals(NativeCapture.POLL_SHUTDOWN, last.get())
        } finally {
            NativeCapture.destroy(h)
        }
    }
}
