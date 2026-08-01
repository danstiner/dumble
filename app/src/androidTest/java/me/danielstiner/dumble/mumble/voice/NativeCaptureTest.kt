package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class NativeCaptureTest {

    /** Exercises the real encoder on a real ABI — unreachable from any JVM test. */
    @Test
    fun stopUnblocksAParkedPollFrame() {
        val h = NativeCapture.create(SAMPLE_RATE, 960, 40000)
        try {
            val out = ByteArray(4000)
            val meta = LongArray(2)
            @Volatile var last = 0
            val t = thread {
                // No stream started, so the gate produces nothing and this parks in the timed wait.
                while (true) {
                    val n = NativeCapture.pollFrame(h, out, meta)
                    if (n == NativeCapture.POLL_SHUTDOWN) { last = n; break }
                }
            }
            Thread.sleep(50)
            NativeCapture.stop(h)
            t.join(1_000)
            assertTrue("pollFrame did not unblock", !t.isAlive)
            assertEquals(NativeCapture.POLL_SHUTDOWN, last)
        } finally {
            NativeCapture.destroy(h)
        }
    }
}
