package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceSenderTest {

    /** Counts pump exits, which stop()'s bounded join does not report. */
    private class Exits {
        private val count = AtomicInteger()
        private val first = CountDownLatch(1)
        val callback: (VoiceSender) -> Unit = { count.incrementAndGet(); first.countDown() }
        fun awaitFirst() = assertTrue("the pump must exit", first.await(2, TimeUnit.SECONDS))
        fun total() = count.get()
    }

    private val sent = CopyOnWriteArrayList<ByteArray>()

    private fun parse(payload: ByteArray): MumbleUdpProtos.Audio {
        assertEquals("payload must be prefixed with the UDP audio type byte", 0, payload[0].toInt())
        return MumbleUdpProtos.Audio.parseFrom(payload.copyOfRange(1, payload.size))
    }

    @Test
    fun eachFrameBecomesANormalTalkingAudioMessage() {
        val latch = CountDownLatch(2)
        val fake = FakeCaptureHandle()
        fake.script(
            FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), 0L, false),
            FakeCaptureHandle.Step.Frame(byteArrayOf(4, 5, 6), 2L, true),
            FakeCaptureHandle.Step.Shutdown,
        )
        val sender = VoiceSender(fake, { p -> sent += p; latch.countDown(); true }, onExit = { })
        sender.start()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        sender.stop()

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
        val latch = CountDownLatch(1)
        val fake = FakeCaptureHandle()
        fake.script(
            FakeCaptureHandle.Step.Retry,
            FakeCaptureHandle.Step.Retry,
            FakeCaptureHandle.Step.Frame(byteArrayOf(9), 0L, false),
            FakeCaptureHandle.Step.Shutdown,
        )
        val sender = VoiceSender(fake, { p -> sent += p; latch.countDown(); true }, onExit = { })
        sender.start()
        assertTrue("pump exited on POLL_RETRY", latch.await(2, TimeUnit.SECONDS))
        sender.stop()
    }

    @Test
    fun unavailableStopsThePumpAndIsDistinguishableFromARequestedStop() {
        val fake = FakeCaptureHandle()
        fake.script(FakeCaptureHandle.Step.Unavailable)
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.start()
        // The pump exits on its own here; no stop() is involved, so nothing can overwrite the
        // reason it recorded.
        exits.awaitFirst()
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
        // The other half of the name: a caller's generic teardown must not overwrite the reason
        // the pump already recorded.
        sender.stop()
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
    }

    @Test
    fun aRefusedSendIsCountedAndDoesNotStopThePump() {
        val latch = CountDownLatch(2)
        val fake = FakeCaptureHandle()
        fake.script(
            FakeCaptureHandle.Step.Frame(byteArrayOf(1), 0L, false),
            FakeCaptureHandle.Step.Frame(byteArrayOf(2), 2L, false),
            FakeCaptureHandle.Step.Shutdown,
        )
        val exits = Exits()
        val sender = VoiceSender(fake, { latch.countDown(); false }, exits.callback)
        sender.start()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        sender.stop()
        exits.awaitFirst()
        assertEquals(2, sender.droppedFrames)
    }

    @Test
    fun anUnrecognisedCodeStopsThePumpRatherThanPollingOnIt() {
        // Native could grow an outcome before this side learns it. Whether it blocks before
        // returning is unknowable from here, so polling on risks a thread spinning at full speed
        // for the life of the connection.
        val fake = FakeCaptureHandle()
        fake.script(FakeCaptureHandle.Step.Unknown(-99))
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.start()
        exits.awaitFirst()
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
    }

    /**
     * onExit must fire from a finally, not only pump()'s normal return path. pollPacket is a
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
    }

    @Test
    fun startAfterStopIsRefusedRatherThanRunningADoomedPump() {
        // The engine's shutdown latch never resets, so a second pump would exit immediately on
        // POLL_SHUTDOWN — looking like a working sender that transmits nothing. Counting exits
        // rather than checking a flag is what catches a second pump that started and died.
        val fake = FakeCaptureHandle()
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.start()
        sender.stop()
        exits.awaitFirst()
        // A rogue second pump must be able to exit, or it would park in take() forever and the
        // exit count would stay 1 with the guard broken — the fake has no shutdown latch of its
        // own, unlike the real engine.
        fake.script(FakeCaptureHandle.Step.Shutdown)
        sender.start()
        Thread.sleep(200)
        assertEquals("a second pump must not have run", 1, exits.total())
    }

    @Test
    fun stopRequestsShutdownAndThePumpExits() {
        val fake = FakeCaptureHandle()
        val exits = Exits()
        val sender = VoiceSender(fake, { true }, exits.callback)
        sender.start()
        sender.stop()
        exits.awaitFirst()
        assertEquals(VoiceSender.StopReason.REQUESTED, sender.stopReason)
    }
}
