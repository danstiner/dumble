package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.hangGuard
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * The pump, one [VoiceSender.step] at a time on the test's own thread. The last three cases run it
 * on its real thread, since start()'s loop and stop()'s join are theirs to pin.
 */
class VoiceSenderTest {

    @get:Rule val timeout = hangGuard()

    /** Counts pump exits; the real-thread cases wait for the first, under the hang guard. */
    private class Exits {
        private val count = AtomicInteger()
        private val first = CountDownLatch(1)
        val callback: (VoiceSender) -> Unit = { count.incrementAndGet(); first.countDown() }
        fun awaitFirst() = first.await()
        fun total() = count.get()
    }

    private val sent = mutableListOf<ByteArray>()

    private fun parse(payload: ByteArray): MumbleUdpProtos.Audio {
        assertEquals("payload must be prefixed with the UDP audio type byte", 0, payload[0].toInt())
        return MumbleUdpProtos.Audio.parseFrom(payload.copyOfRange(1, payload.size))
    }

    @Test
    fun eachFrameBecomesANormalTalkingAudioMessage() {
        val fake = FakeCaptureHandle(blocking = false)
        fake.script(
            FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), 0L, false),
            FakeCaptureHandle.Step.Frame(byteArrayOf(4, 5, 6), 2L, true),
        )
        val sender = VoiceSender(fake, { p -> sent += p; true }, onExit = { })
        repeat(2) { assertTrue(sender.step()) }

        val first = parse(sent[0])
        assertEquals(0, first.target)
        assertEquals(0L, first.frameNumber)
        assertEquals(3, first.opusData.size())
        // Not required client-to-server, and sending it would be inventing a session id.
        assertEquals(0, first.senderSession)

        val second = parse(sent[1])
        assertEquals(2L, second.frameNumber)
        assertTrue(second.isTerminator)
    }

    @Test
    fun retryKeepsThePumpRunning() {
        // Below API 37 AAudio disconnects on every route change, so treating a retry as terminal
        // would kill transmit on the first headset plug of a session.
        val fake = FakeCaptureHandle(blocking = false)
        fake.script(
            FakeCaptureHandle.Step.Retry,
            FakeCaptureHandle.Step.Retry,
            FakeCaptureHandle.Step.Frame(byteArrayOf(9), 0L, false),
        )
        val sender = VoiceSender(fake, { p -> sent += p; true }, onExit = { })
        repeat(3) { assertTrue("pump exited on POLL_RETRY", sender.step()) }
        assertEquals("the frame after the retries must go out", 1, sent.size)
    }

    @Test
    fun unavailableStopsThePumpAndIsDistinguishableFromARequestedStop() {
        val fake = FakeCaptureHandle(blocking = false)
        fake.script(FakeCaptureHandle.Step.Unavailable)
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        // The pump exits on its own here; no stop() is involved, so nothing can overwrite the
        // reason it recorded.
        assertFalse(sender.step())
        assertEquals(1, exits.total())
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
        // The other half of the name: a caller's generic teardown must not overwrite the reason
        // the pump already recorded.
        sender.stop()
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
    }

    @Test
    fun aRefusedSendIsCountedAndDoesNotStopThePump() {
        val fake = FakeCaptureHandle(blocking = false)
        fake.script(
            FakeCaptureHandle.Step.Frame(byteArrayOf(1), 0L, false),
            FakeCaptureHandle.Step.Frame(byteArrayOf(2), 2L, false),
        )
        val sender = VoiceSender(fake, { false }, onExit = { })
        repeat(2) { assertTrue("a refused send must not end the pump", sender.step()) }
        assertEquals(2, sender.droppedFrames)
    }

    @Test
    fun anUnrecognisedCodeStopsThePumpRatherThanPollingOnIt() {
        // Native could grow an outcome before this side learns it. Whether it blocks before
        // returning is unknowable from here, so polling on risks a thread spinning at full speed
        // for the life of the connection.
        val fake = FakeCaptureHandle(blocking = false)
        fake.script(FakeCaptureHandle.Step.Unknown(-99))
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        assertFalse(sender.step())
        assertEquals(1, exits.total())
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
    }

    @Test
    fun startAfterStopIsRefusedRatherThanRunningADoomedPump() {
        // The engine's shutdown latch never resets, so a second pump would exit immediately on
        // POLL_SHUTDOWN — looking like a working sender that transmits nothing. Counting exits
        // rather than checking a flag is what catches a second pump that started and died.
        val fake = FakeCaptureHandle(blocking = false)
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.stop()
        assertFalse("the first pump ends on the shutdown its stop queued", sender.step())

        // A rogue second pump must be able to exit — the fake has no shutdown latch of its own,
        // unlike the real engine.
        fake.script(FakeCaptureHandle.Step.Shutdown)
        sender.start()
        sender.stop()   // joins the thread a wrongly started pump would be running on
        assertEquals("a second pump must not have run", 1, exits.total())
    }

    /** The sheet reads the counters through this callback, with the pump's own dropped count in. */
    @Test
    fun theCountersGoOutEveryIntervalWithTheDroppedSendsFilledIn() {
        val fake = FakeCaptureHandle(blocking = false)
        fake.stats = CaptureStats(
            encodedPackets = 3, encodeErrors = 0, encodeMicrosMean = 400, encodeMicrosMax = 900,
            ringOverruns = 0, skippedSamples = 0, streamOverruns = 1, framesPerBurst = 96,
            droppedFrames = 0, inputLatencyMillis = 12.0,
        )
        val reported = mutableListOf<CaptureStats>()
        val clock = TestTimeSource()
        fake.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1), 0L, false))
        val sender = VoiceSender(fake, { false }, onExit = { }, onStats = { reported += it },
                                 statsInterval = 20.milliseconds, clock = clock)

        sender.step()   // the refused packet, inside the first interval
        clock += 20.milliseconds
        sender.step()   // an empty poll: the tick is read after every poll, packet or not

        val stats = reported.single()
        assertEquals(1, stats.droppedFrames)
        assertEquals(12.0, stats.inputLatencyMillis)
    }

    /**
     * onExit must fire from a finally, not only the pump's normal return path. pollPacket is a
     * seam into native code and a contract violation there is not implausible; if a throw escaped
     * without running onExit, the owner would never learn the pump died and the engine would leak
     * forever with nothing to release it.
     */
    @Test
    fun onExitFiresEvenWhenPollFrameThrows() {
        val fake = object : VoiceSender.CaptureHandle {
            override fun pollPacket(out: ByteArray, meta: LongArray): Int = throw RuntimeException("boom")
            override fun setGateOpen(open: Boolean) = Unit
            override fun setTransmitMode(mode: TransmitMode) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
            override fun stats(): CaptureStats? = null
        }
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.start()
        exits.awaitFirst()
        assertEquals(1, exits.total())
        assertNull("a throw is not a requested stop: the owner must not reopen on it", sender.stopReason)
    }

    /**
     * stop() waits for the pump to leave: the stream closes on its way out, and the connection's
     * one-microphone invariant opens the next engine only after stop() returns. The wait is
     * bounded at a second, the bound production relies on too.
     */
    @Test
    fun stopRequestsShutdownAndThePumpExits() {
        val fake = FakeCaptureHandle()
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.start()
        sender.stop()
        assertEquals("the pump must have exited by the time stop() returns", 1, exits.total())
        assertEquals(VoiceSender.StopReason.REQUESTED, sender.stopReason)
    }

    /**
     * A pump wedged in native code: stop() gives up at its bound instead of hanging the release,
     * and reports no exit the pump has not made — the owner frees the engine on that exit, and a
     * poll is still in flight.
     */
    @Test
    fun stopGivesUpOnAWedgedPumpWithoutReportingAnExit() {
        val unwedge = CountDownLatch(1)
        val fake = object : VoiceSender.CaptureHandle {
            // Blocks like the real pollPacket, and stop() deliberately does not release it.
            override fun pollPacket(out: ByteArray, meta: LongArray): Int {
                unwedge.await(); return NativeCapture.POLL_SHUTDOWN
            }
            override fun setGateOpen(open: Boolean) = Unit
            override fun setTransmitMode(mode: TransmitMode) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
            override fun stats(): CaptureStats? = null
        }
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        try {
            sender.start()
            sender.stop()   // an unbounded join never returns: the hang guard reports it
            assertEquals("a wedged pump has not exited", 0, exits.total())

            unwedge.countDown()
            exits.awaitFirst()
            assertEquals(1, exits.total())
            assertEquals(VoiceSender.StopReason.REQUESTED, sender.stopReason)
        } finally {
            unwedge.countDown()
        }
    }
}
