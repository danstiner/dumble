package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceSenderTest {

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
        val sender = VoiceSender(fake) { _, p -> sent += p; latch.countDown(); true }
        sender.start()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        sender.stop()

        val first = parse(sent[0])
        assertEquals(0, first.target)
        assertEquals(0L, first.frameNumber)
        assertFalse(first.isTerminator)
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
        val sender = VoiceSender(fake) { _, p -> sent += p; latch.countDown(); true }
        sender.start()
        assertTrue("pump exited on POLL_RETRY", latch.await(2, TimeUnit.SECONDS))
        sender.stop()
    }

    @Test
    fun unavailableStopsThePumpAndIsDistinguishableFromARequestedStop() {
        // Unlike POLL_RETRY, native has given up reopening for good here — there is no clearing
        // path short of destroy()+create(), so the pump must exit rather than spin. It must also
        // read differently from a caller-requested stop so a future caller can surface "transmit
        // unavailable" instead of silently looking like a normal stop().
        val fake = FakeCaptureHandle()
        fake.script(FakeCaptureHandle.Step.Unavailable)
        val sender = VoiceSender(fake) { _, _ -> true }
        sender.start()
        // stop() joins the pump; the fake's queued Shutdown from stop() is never reached because
        // the pump already exited on Unavailable, so this synchronizes on that exit without
        // overwriting the reason it recorded.
        sender.stop()
        assertFalse(sender.isRunning)
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
        val sender = VoiceSender(fake) { _, _ -> latch.countDown(); false }
        sender.start()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        sender.stop()
        assertEquals(2, sender.droppedFrames)
    }

    @Test
    fun anUnrecognisedCodeStopsThePumpRatherThanPollingOnIt() {
        // Native could grow an outcome before this side learns it. Whether it blocks before
        // returning is unknowable from here, so polling on risks a thread spinning at full speed
        // for the life of the connection.
        val fake = FakeCaptureHandle()
        fake.script(FakeCaptureHandle.Step.Unknown(-99))
        val sender = VoiceSender(fake) { _, _ -> true }
        sender.start()
        sender.stop()
        assertFalse(sender.isRunning)
        assertEquals(VoiceSender.StopReason.UNAVAILABLE, sender.stopReason)
    }

    @Test
    fun startAfterStopIsRefusedRatherThanRunningADoomedPump() {
        // The engine's shutdown latch never resets, so a second pump would exit immediately on
        // POLL_SHUTDOWN — looking like a working sender that transmits nothing.
        val fake = FakeCaptureHandle()
        val sender = VoiceSender(fake) { _, _ -> true }
        sender.start()
        sender.stop()
        sender.start()
        assertFalse(sender.isRunning)
    }

    @Test
    fun stopJoinsThePumpThread() {
        val fake = FakeCaptureHandle()
        val sender = VoiceSender(fake) { _, _ -> true }
        sender.start()
        sender.stop()
        assertFalse(sender.isRunning)
        assertEquals(VoiceSender.StopReason.REQUESTED, sender.stopReason)
    }
}
