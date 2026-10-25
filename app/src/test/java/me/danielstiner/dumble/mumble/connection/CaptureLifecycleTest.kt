package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.hangGuard
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.DeafenState
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakePlayoutEngine
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.NativeCapture
import me.danielstiner.dumble.mumble.voice.NoVoiceCall
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import me.danielstiner.dumble.mumble.voice.VoiceSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The capture lifecycle's regression suite. Each case here reproduced a real defect before the
 * serialised-reconcile redesign; they now assert those defects are absent.
 */
class CaptureLifecycleTest {

    @get:Rule val timeout = hangGuard()

    /**
     * A connection on the rig's scheduler, torn down by it, whose capture pumps the rig steps: a
     * release and the engine's free land in one `assertSettled`, and the speaking hold runs on
     * the scheduler's clock. Every handle here returns 0 where the engine would block.
     */
    private fun Rig.connection(
        newCapture: () -> VoiceSender.CaptureHandle? = { null },
        call: VoiceCall = NoVoiceCall,
        stuckPumpMillis: Long = 1_000L,
        newPlayout: () -> VoiceReceiver.PlayoutEngine? = { null },
        newTransport: () -> FakeControlTransport = { FakeControlTransport { _, _ -> } },
        blocking: CoroutineDispatcher = dispatcher,
    ) = own(MumbleConnection(
        InMemoryPinStore(), newCapture = newCapture, newPlayout = newPlayout, call = call,
        stuckPumpMillis = stuckPumpMillis, startPump = startPump, captureClock = clock,
        udpClock = clock, context = dispatcher, blocking = blocking,
    ) { newTransport() })

    /** Connects and settles in Handshaking, where every test here stays: none sends a ServerSync. */
    private fun Rig.connectTo(conn: MumbleConnection, host: String = "localhost") {
        conn.connect(MumbleEndpoint.parse(host), "user", null)
        handshaking(conn)
    }

    /**
     * A native engine whose pump cannot be woken. Stands in for the real failure the production
     * code already anticipates and logs — "capture pump has not exited after 1000ms" — without
     * needing to reproduce whatever wedges Oboe.
     */
    private class WedgedCaptureHandle : VoiceSender.CaptureHandle {
        private var released = false
        var stopCalled = false; private set
        var destroyed = false; private set

        // stop() deliberately does not end the poll: it keeps coming back empty until release().
        override fun pollPacket(out: ByteArray, meta: LongArray) =
            if (released) NativeCapture.POLL_SHUTDOWN else 0
        override fun setGateOpen(open: Boolean) = Unit
        override fun setTransmitMode(mode: TransmitMode) = Unit
        override fun stop() { stopCalled = true }
        override fun destroy() { destroyed = true }
        override fun stats(): CaptureStats? = null

        /** The wedge clears: the pump's next poll returns, and it exits. */
        fun release() { released = true }
    }

    /**
     * Teardown must not free the engine while a poll is in flight. stop()'s join gives up after a
     * second, so the engine is released by the pump's own exit — which cannot happen while it is
     * parked. Nor may that wait hold the call, its foreground service and the audio mode open.
     */
    @Test fun teardownDoesNotDestroyTheEngineWhileThePumpIsStillPolling() = deterministic {
        val handle = WedgedCaptureHandle()
        val call = FakeVoiceCall()
        var opens = 0
        val conn = connection(newCapture = { opens++; handle }, call = call, stuckPumpMillis = 100L)
        connectTo(conn)
        conn.requestCapture()
        assertSettled("the engine must open") { opens == 1 }

        conn.disconnect()
        assertEquals("disconnect must end the call without waiting for the pump", 1, call.ends)
        assertSettled("teardown must request a stop") { handle.stopCalled }
        elapse(500.milliseconds)   // well past the wedge deadline
        assertFalse("the engine must not be freed while the pump is still polling", handle.destroyed)

        handle.release()
        assertSettled("the pump's exit must free the engine") { handle.destroyed }
    }

    /**
     * The same non-destruction-under-a-live-pump via the hold path, not just disconnect. Worth its
     * own case because teardown() and a hold's release are separate call sites with the same shape,
     * and a hold is the common one — every incoming cellular call takes it.
     */
    @Test fun holdDoesNotDestroyTheEngineWhileThePumpIsStillPolling() = deterministic {
        val handle = WedgedCaptureHandle()
        val call = FakeVoiceCall()
        var opens = 0
        val conn = connection(newCapture = { opens++; handle }, call = call, stuckPumpMillis = 100L)
        connectTo(conn)
        conn.requestCapture()
        assertSettled("the engine must open") { opens == 1 }

        call.hold()
        assertSettled("a hold must request a stop") { handle.stopCalled }
        elapse(500.milliseconds)   // well past the wedge deadline
        assertFalse("the engine must not be freed while the pump is still polling", handle.destroyed)

        handle.release()
        assertSettled("the pump's exit must free the engine") { handle.destroyed }
    }

    /** Records how many engines exist at once. */
    private class CountingHandle(
        private val live: AtomicInteger,
        private val peak: AtomicInteger,
    ) : VoiceSender.CaptureHandle {
        init {
            val now = live.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
        }

        private var stopped = false
        override fun pollPacket(out: ByteArray, meta: LongArray) = if (stopped) NativeCapture.POLL_SHUTDOWN else 0
        override fun setGateOpen(open: Boolean) = Unit
        override fun setTransmitMode(mode: TransmitMode) = Unit
        override fun stop() { stopped = true }
        override fun destroy() { live.decrementAndGet() }
        override fun stats(): CaptureStats? = null
    }

    /**
     * A requestCapture during a hold must open nothing. reconcile only checked
     * `session.capture == null`, which a hold had just made true, so a Chat/Connected remount
     * during a cellular call opened the microphone — and could do it while the first engine
     * was still live.
     */
    @Test fun requestCaptureDuringAHoldOpensNothing() = deterministic {
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { CountingHandle(live, peak) }, call = call)

        connectTo(conn)
        conn.requestCapture()
        assertSettled("the first engine must open") { live.get() == 1 }

        call.hold()
        assertSettled("the hold must release the engine") { live.get() == 0 }

        conn.requestCapture()
        assertSettled("a hold must refuse a start") { live.get() == 0 }

        call.resume()
        assertSettled("resuming must rebuild") { live.get() == 1 }
        assertEquals("never two microphone streams at once", 1, peak.get())

        conn.disconnect()
        assertSettled("disconnect must release the engine") { live.get() == 0 }
    }

    /**
     * The receiver's output stream follows the hold the same way the microphone does: paused while
     * the platform has the device, started again by the next claim once it is given back.
     */
    @Test fun theReceiverPausesOnHoldAndStartsOnResume() = deterministic {
        val playout = FakePlayoutEngine()
        val call = FakeVoiceCall()
        lateinit var fake: FakeControlTransport
        val conn = connection(
            newPlayout = { playout }, call = call,
            newTransport = { FakeControlTransport { _, _ -> }.also { fake = it } },
        )
        connectTo(conn)
        playout.liveSessions = setOf(9)
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .build()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        assertSettled("a live speaker must start the stream") { playout.started }

        call.hold()
        elapse(VoiceReceiver.POLL_MILLIS.milliseconds)   // the next poll reads the hold
        assertEquals("a hold must pause the stream", "pause", playout.calls.last())

        call.resume()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        elapse(VoiceReceiver.POLL_MILLIS.milliseconds)
        assertEquals("a resume must let the next claim start the stream", "start", playout.calls.last())
    }

    /**
     * The hold reaches the receiver straight from the call, not through the capture consumer, so
     * the stream pauses even while that consumer is stuck opening the microphone — here, on a
     * dispatcher nothing advances.
     */
    @Test fun aHoldPausesTheStreamWhileTheMicrophoneIsStillOpening() = deterministic {
        val playout = FakePlayoutEngine()
        val call = FakeVoiceCall()
        val stuck = TestCoroutineScheduler()
        lateinit var fake: FakeControlTransport
        val conn = connection(
            newPlayout = { playout }, call = call, blocking = StandardTestDispatcher(stuck),
            newTransport = { FakeControlTransport { _, _ -> }.also { fake = it } },
        )
        connectTo(conn)
        playout.liveSessions = setOf(9)
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(9)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1)))
            .build()
        fake.listener!!.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0) + audio.toByteArray()))
        assertSettled("a live speaker must start the stream") { playout.started }

        try {
            call.hold()
            elapse(VoiceReceiver.POLL_MILLIS.milliseconds)
            assertEquals("a hold must pause the stream without the capture consumer", "pause", playout.calls.last())
        } finally {
            conn.disconnect()
            stuck.runCurrent()   // the receiver's stop is queued there; it ends the poll
        }
    }

    /** Counts destroy() so a double free would be visible rather than assumed impossible. */
    private class DestroyCountingHandle : VoiceSender.CaptureHandle {
        val destroys = AtomicInteger()
        val stops = AtomicInteger()
        override fun pollPacket(out: ByteArray, meta: LongArray) =
            if (stops.get() > 0) NativeCapture.POLL_SHUTDOWN else 0
        override fun setGateOpen(open: Boolean) = Unit
        override fun setTransmitMode(mode: TransmitMode) = Unit
        override fun stop() { stops.incrementAndGet() }
        override fun destroy() { destroys.incrementAndGet() }
        override fun stats(): CaptureStats? = null
    }

    /**
     * Hold-then-disconnect must free the engine exactly once: the hold's release destroys it via
     * the pump's exit, and the disconnect's Release then reconciles a session whose `capture` is
     * already null. A second destroy() on the real engine is the native use-after-free, so "found
     * nothing to free" is the behaviour being pinned.
     */
    @Test fun holdThenDisconnectDestroysTheEngineExactlyOnce() = deterministic {
        val handle = DestroyCountingHandle()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { handle }, call = call)
        connectTo(conn)
        conn.requestCapture()

        call.hold()
        assertSettled("the hold must release the engine") { handle.destroys.get() == 1 }
        conn.disconnect()

        assertSettled("the engine must be freed exactly once") { handle.destroys.get() == 1 }
    }

    /** Reports create/stop/destroy order across several engines, so a test can assert sequencing. */
    private class RecordingHandle(private val log: ConcurrentLinkedQueue<String>, val name: String) :
        VoiceSender.CaptureHandle {
        private var stopped = false
        val destroys = AtomicInteger()
        init { log += "$name:create" }
        override fun pollPacket(out: ByteArray, meta: LongArray) = if (stopped) NativeCapture.POLL_SHUTDOWN else 0
        override fun setGateOpen(open: Boolean) = Unit
        override fun setTransmitMode(mode: TransmitMode) = Unit
        override fun stop() { log += "$name:stop"; stopped = true }
        override fun destroy() { destroys.incrementAndGet(); log += "$name:destroy" }
        override fun stats(): CaptureStats? = null
    }

    /**
     * The one-microphone invariant across sessions. connect() queues the prior's Release under the
     * lock, ahead of anything the new session can ask, and the release stops the engine inline on
     * the consumer (beginRelease: "the entire one-microphone invariant") — so the first engine is
     * stopped before the second is created.
     *
     * The second Acquire is queued before the scheduler runs, right behind that Release, so a
     * release that stopped asynchronously would let the consumer create the second engine first.
     * On one scheduler thread that ordering is exact, and every run checks it.
     */
    @Test fun reconnectWhileCapturingClosesTheFirstStreamBeforeOpeningTheSecond() = deterministic {
        val log = ConcurrentLinkedQueue<String>()
        val handles = CopyOnWriteArrayList<RecordingHandle>()
        val conn = connection(newCapture = { RecordingHandle(log, "e${handles.size}").also { handles += it } })

        connectTo(conn, "first")
        conn.requestCapture()
        assertSettled("the first engine must open") { handles.size == 1 }

        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        conn.requestCapture()
        assertSettled("the second engine must open") { handles.size == 2 }
        assertEquals("the first engine must be released", 1, handles[0].destroys.get())

        val order = log.toList()
        assertTrue(
            "the first stream must close before the second opens: $order",
            order.indexOf("e0:stop") < order.indexOf("e1:create"),
        )

        conn.disconnect()
        assertSettled("disconnect must release the second engine") { handles[1].destroys.get() == 1 }
    }

    /** A hold from a superseded call must reach neither the live session's capture nor its banner. */
    @Test fun aStaleHoldDoesNotTouchTheLiveSession() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn, "first")
        val staleGen = 1   // the first connect's generation

        connectTo(conn, "second")
        conn.requestCapture()
        assertSettled("the live attempt must be capturing") { handles.any { !it.destroyed } }

        call.holdFor(staleGen)
        assertSettled("a stale hold must not release the live engine") { handles.any { !it.destroyed } }
        assertFalse("a stale hold must not raise the banner", conn.callHeld.value)
        conn.disconnect()
    }

    /**
     * A Talk press made during a hold must transmit once the call comes back. Transmit intent used
     * to be an edge straight to the live session — during a hold there is none, so the press was
     * dropped, and the session reconcile() then built came up with its gate closed under a button
     * the user was still holding. Found on-device: the first press after a cellular call transmitted
     * nothing for as long as it was held, and only the second worked.
     */
    @Test fun aTalkPressDuringAHoldTransmitsOnResume() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn)
        conn.setTransmitMode(TransmitMode.PushToTalk)
        conn.requestCapture()
        assertSettled("the first engine must open") { handles.size == 1 }

        call.hold()
        assertSettled("the hold must release the engine") { handles[0].destroyed }

        // While held there is no capture session for the press to reach; it lands on the level.
        conn.setTransmitting(true)
        assertSettled("a press while held must open nothing") { handles.size == 1 }

        call.resume()
        assertSettled("the resume must rebuild") { handles.size == 2 }
        assertSettled("the rebuilt session must come up transmitting") { handles[1].gateOpen }

        // The release still closes it: the level is user intent, not a latch.
        conn.setTransmitting(false)
        assertSettled("releasing Talk must close the gate") { !handles[1].gateOpen }

        conn.disconnect()
    }

    /** Voice activity arms by "not muted", push-to-talk by the thumb. A switch must re-derive the
     *  gate, or the armed voice-activity session survives with nobody holding a button. */
    @Test fun switchingToPushToTalkDisarmsAVoiceActivitySession() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } })

        connectTo(conn)
        conn.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }

        conn.setTransmitMode(TransmitMode.VoiceActivity)
        assertSettled("voice activity must arm the gate") { handles[0].gateOpen }
        assertEquals(TransmitMode.VoiceActivity, handles[0].transmitMode)

        conn.setTransmitMode(TransmitMode.PushToTalk)
        assertFalse("push-to-talk must not inherit voice activity's armed gate", handles[0].gateOpen)
        assertEquals(TransmitMode.PushToTalk, handles[0].transmitMode)

        conn.disconnect()
    }

    /** A rebuilt engine defaults to push-to-talk, so the mode must be reapplied to every session
     *  or every cellular call silently loses voice activity. */
    @Test fun voiceActivitySurvivesAHold() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn)
        conn.requestCapture()
        assertSettled("the first engine must open") { handles.size == 1 }
        conn.setTransmitMode(TransmitMode.VoiceActivity)
        assertSettled("voice activity must arm the gate") { handles[0].gateOpen }

        call.hold()
        assertSettled("the hold must release the engine") { handles[0].destroyed }
        assertTrue("the hold must publish", conn.callHeld.value)
        call.resume()
        assertSettled("the resume must rebuild") { handles.size == 2 }
        assertFalse("the resume clears it", conn.callHeld.value)

        assertSettled("the rebuilt engine must come up in voice activity") { handles[1].transmitMode == TransmitMode.VoiceActivity }
        assertSettled("and armed, since nothing muted us") { handles[1].gateOpen }

        conn.disconnect()
    }

    /** A mute must lower the level, not just close the gate: the next rebuild — a cellular call
     *  is enough — reads the level. */
    @Test fun aMuteUnderAHeldTalkSurvivesARebuild() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn)
        conn.setTransmitting(true)
        assertSettled("the press must open an engine, transmitting") { handles.size == 1 && handles[0].gateOpen }

        conn.setMuted(true)
        assertSettled("the mute must close the gate") { !handles[0].gateOpen }

        call.hold()
        assertSettled("the hold must release the engine") { handles[0].destroyed }
        call.resume()
        assertSettled("the resume must rebuild") { handles.size == 2 }
        assertFalse("the rebuilt session must be running", handles[1].stopped)
        assertFalse("a muted rebuild must not come up transmitting", handles[1].gateOpen)

        conn.disconnect()
    }

    /** Mute has no engine-side existence, and the Talk button is only disabled once the server
     *  echoes `self_mute` — every press before that echo lands here with the control still live. */
    @Test fun aPressWhileMutedMustNotArm() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } })

        connectTo(conn)
        conn.setTransmitMode(TransmitMode.PushToTalk)
        conn.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }

        conn.setMuted(true)
        conn.setTransmitting(true)
        assertFalse("a muted microphone must stay shut under the thumb", handles[0].gateOpen)

        conn.setTransmitting(false)
        conn.setMuted(false)
        assertFalse("an unmute is not a press", handles[0].gateOpen)
        conn.setTransmitting(true)
        assertSettled("a fresh press works") { handles[0].gateOpen }

        conn.disconnect()
    }

    /** Push-to-talk has no Mute control, so a self-mute carried into it would disable Talk with
     *  nothing to lift it. */
    @Test fun switchingToPushToTalkLiftsASelfMute() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } })

        connectTo(conn)
        conn.setTransmitMode(TransmitMode.VoiceActivity)
        assertSettled("voice activity must open an armed engine") { handles.size == 1 && handles[0].gateOpen }
        conn.setMuted(true)
        assertFalse(handles[0].gateOpen)

        conn.setTransmitMode(TransmitMode.PushToTalk)
        assertFalse("push-to-talk's gate is the thumb", handles[0].gateOpen)
        conn.setTransmitting(true)
        assertSettled("a press must open the gate, the mute having been lifted") { handles[0].gateOpen }

        conn.disconnect()
    }

    /** The mute it lifts is the user's own. A deafen stands and takes over a mute set before it,
     *  so the undeafen is what clears Talk. */
    @Test fun switchingToPushToTalkLeavesADeafenStanding() = deterministic {
        val conn = connection()
        connectTo(conn)
        conn.setTransmitMode(TransmitMode.VoiceActivity)
        conn.setMuted(true)
        conn.setSelfDeaf(true)

        conn.setTransmitMode(TransmitMode.PushToTalk)
        assertTrue("the deafen stands", conn.selfState.value.deafened)
        conn.setSelfDeaf(false)
        assertEquals("and the undeafen lifts the mute with it", DeafenState(), conn.selfState.value)

        conn.disconnect()
    }

    /** An unmute satisfies voice activity's condition for the gate and not push-to-talk's. */
    @Test fun unmutingReArmsOnlyUnderVoiceActivity() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } })

        connectTo(conn)
        conn.setTransmitMode(TransmitMode.PushToTalk)
        conn.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }

        conn.setMuted(true)
        conn.setMuted(false)
        assertFalse("under push-to-talk an unmute must not press the button", handles[0].gateOpen)

        conn.setTransmitMode(TransmitMode.VoiceActivity)
        assertSettled("voice activity must arm the gate") { handles[0].gateOpen }
        conn.setMuted(true)
        assertFalse("a mute must disarm", handles[0].gateOpen)
        conn.setMuted(false)
        assertSettled("under voice activity an unmute must re-arm") { handles[0].gateOpen }

        conn.disconnect()
    }

    /** A resume from a superseded call must not lift the live session's hold, nor its banner. */
    @Test fun aStaleResumeDoesNotClearTheLiveHold() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn, "first")
        val staleGen = 1   // the first connect's generation

        connectTo(conn, "second")
        conn.requestCapture()
        assertSettled("the live attempt must open") { handles.size == 1 }

        call.hold()   // holds the live generation (2)
        assertSettled("the hold must release the engine") { handles[0].destroyed }

        call.resumeFor(staleGen)   // a resume for the superseded call (1), not the live hold
        assertSettled("a stale resume must not reopen capture the platform still holds") { handles.size == 1 }
        assertTrue("a stale resume must not clear the banner", conn.callHeld.value)

        call.resume()   // the genuine resume, for the live generation
        assertSettled("the genuine resume must rebuild") { handles.size == 2 }
        conn.disconnect()
    }

    /**
     * Connecting during a phone call: the call reports the hold as it starts, before the link is up
     * and the receiver started. A capture asked for afterwards stays refused, and the receiver's
     * poll never starts the output stream, until the resume.
     */
    @Test fun aCallThatStartsHeldHoldsCaptureAndPlayout() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val playout = FakePlayoutEngine()
        var engines = 0
        val call = FakeVoiceCall()
        val conn = connection(
            newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } },
            newPlayout = { engines++; playout },
            call = call,
        )
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        call.hold()
        handshaking(conn)

        conn.requestCapture()
        elapse(VoiceReceiver.POLL_MILLIS.milliseconds)
        assertEquals("the receiver must be polling", 1, engines)
        assertTrue("a held call must open no capture engine", handles.isEmpty())
        assertEquals("a held call must never start the stream", 0, playout.startAttempts.get())

        call.resume()
        elapse(VoiceReceiver.POLL_MILLIS.milliseconds)
        assertEquals("resuming must open one capture engine", 1, handles.size)
        assertTrue("resuming must start the stream", playout.started)
        conn.disconnect()
    }

    /**
     * A resume arriving while a release is still in flight must not be dropped. reconcile refuses to
     * open while `releasing`, so the only thing that rebuilds is onPumpExited's trailing reconcile —
     * this is what pins it.
     */
    @Test fun aResumeDuringAReleaseRebuildsExactlyOnce() = deterministic {
        val handle = WedgedCaptureHandle()
        val rebuilt = CopyOnWriteArrayList<FakeCaptureHandle>()
        var first = true
        val call = FakeVoiceCall()
        val conn = connection(
            newCapture = {
                if (first) { first = false; handle }
                else FakeCaptureHandle(blocking = false).also { rebuilt += it }
            },
            call = call, stuckPumpMillis = 100L,
        )
        connectTo(conn)
        conn.requestCapture()
        assertSettled("the engine must open") { !first }

        call.hold()
        assertSettled("the hold must request a stop") { handle.stopCalled }
        // The pump is still parked, so the release has not completed.
        call.resume()
        assertSettled("nothing may be built while the release is in flight") { rebuilt.isEmpty() }

        handle.release()
        assertSettled("the exit must rebuild") { rebuilt.size == 1 }
        elapse(300.milliseconds)   // past the wedge check, the one timer the release armed
        assertEquals("exactly one rebuild", 1, rebuilt.size)
        conn.disconnect()
    }

    /**
     * A terminal engine failure must not become an automatic reopen loop — the cause has not
     * changed, and each retry is a full HAL open. The retry stays user-driven.
     */
    @Test fun aTerminalPumpExitDoesNotReopenButAStartCaptureDoes() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(
            newCapture = {
                FakeCaptureHandle(blocking = false).also {
                    // Only the first engine fails: a reopen loop then shows as a second engine
                    // instead of spinning the settle.
                    if (handles.isEmpty()) it.script(FakeCaptureHandle.Step.Unavailable)
                    handles += it
                }
            },
            call = call,
        )

        connectTo(conn)
        conn.requestCapture()
        assertSettled("the failed engine must be released") { handles.size == 1 && handles[0].destroyed }
        assertEquals("a terminal exit must not reopen on its own", 1, handles.size)

        conn.requestCapture()
        assertSettled("a user retry must rebuild") { handles.size == 2 }
        conn.disconnect()
    }

    /** A handle that reports a gate touched after it was freed — the use-after-free, made loud. */
    private class GateAfterDestroyHandle : VoiceSender.CaptureHandle {
        private val unblock = CountDownLatch(1)
        @Volatile private var destroyed = false
        @Volatile var gateAfterDestroy = false; private set
        override fun pollPacket(out: ByteArray, meta: LongArray): Int {
            unblock.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) { if (destroyed) gateAfterDestroy = true }
        override fun setTransmitMode(mode: TransmitMode) = Unit
        override fun stop() { unblock.countDown() }
        override fun destroy() { destroyed = true }
        override fun stats(): CaptureStats? = null
    }

    /**
     * STRESS CASE, not a proof. setGateOpen reaches a member of the Session that destroy() deletes,
     * so a push-to-talk edge racing a release is a use-after-free; CaptureSession's monitor closes
     * it. Absence of a race is not deterministically provable from a JVM test — this raises the odds
     * of catching a regression, it does not pin it.
     *
     * Released via a hold, not disconnect(): disconnect() nulls `current` synchronously, and
     * setTransmitting() checks `current` before it ever reaches the session, which would starve this
     * race of its window regardless of whether CaptureSession's own guard exists. A hold releases the
     * session while `current` stays put, so the monitor is the only thing left standing in the way.
     */
    @Test fun settingTransmittingDuringAReleaseDoesNotReachADestroyedHandle() = runBlocking {
        repeat(20) {
            val handle = GateAfterDestroyHandle()
            val call = FakeVoiceCall()
            val conn = MumbleConnection(
                InMemoryPinStore(),
                newCapture = { handle },
                call = call,
            ) { FakeControlTransport { _, _ -> } }
            conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()

            val hammer = Thread { repeat(500) { i -> conn.setTransmitting(i % 2 == 0) } }
            hammer.start()
            // No wait for the engine to open first: Acquire and this hold's Held share one serial
            // queue, so openCapture() has already run by the time Held is dispatched.
            call.hold()
            hammer.join(2_000)
            assertFalse("the gate reached a freed handle", handle.gateAfterDestroy)
            conn.disconnect()
        }
    }

    /** Tracks whether the pump ever touched the handle, to prove a rejected open never attached it. */
    private class PollRecordingHandle : VoiceSender.CaptureHandle {
        var stopped = false; private set
        var destroyed = false; private set
        @Volatile var pollFrameCalled = false; private set
        override fun pollPacket(out: ByteArray, meta: LongArray): Int {
            pollFrameCalled = true
            return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun setTransmitMode(mode: TransmitMode) = Unit
        override fun stop() { stopped = true }
        override fun destroy() { destroyed = true }
        override fun stats(): CaptureStats? = null
    }

    /**
     * Pins openCapture's isLive recheck after newCapture() returns. newCapture() blocks on the HAL
     * and `current` is still mutated on caller threads while it does, so a disconnect
     * landing in that window has already moved the world by the time the handle comes back. Without
     * the recheck the stale handle would attach and its pump would start — a leaked engine racing
     * a `current` that no longer points at it. The fake lands the disconnect inside that window.
     */
    @Test fun aDisconnectDuringNewCaptureLeavesTheStaleHandleUnattached() = deterministic {
        val handle = PollRecordingHandle()
        lateinit var conn: MumbleConnection
        conn = connection(newCapture = { conn.disconnect(); handle })
        connectTo(conn)

        conn.requestCapture()

        assertSettled("the stale handle must be stopped") { handle.stopped }
        assertTrue("the stale handle must be destroyed", handle.destroyed)
        // No wait: a wrongly started pump gets its poll inside the same assertSettled, so it
        // would already show.
        assertFalse(
            "a stale open must never start the pump against a superseded attempt",
            handle.pollFrameCalled,
        )
    }

    /** A handler that throws must not kill the consumer — a dead one fails silently and forever. */
    @Test fun aThrowingHandlerDoesNotKillTheConsumer() = deterministic {
        val opened = AtomicInteger()
        val conn = connection(newCapture = {
            // First open throws from inside the consumer; later ones behave.
            if (opened.getAndIncrement() == 0) throw IllegalStateException("boom")
            FakeCaptureHandle(blocking = false)
        })

        connectTo(conn)
        conn.requestCapture()
        assertSettled("the throwing open must have been attempted") { opened.get() == 1 }

        conn.requestCapture()
        assertSettled("the consumer must still be alive") { opened.get() == 2 }
        conn.disconnect()
    }

    private suspend fun awaitTrue(what: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timed out waiting: $what")
    }

    /** Speaking follows packets on the wire, not the button: voice activity has no press, and a
     *  press whose session never opened is not speech. */
    @Test fun speakingFollowsThePacketsAndNotTheGate() = deterministic {
        val handle = FakeCaptureHandle(blocking = false)
        val conn = connection(newCapture = { handle })

        connectTo(conn)
        conn.setTransmitting(true)
        assertSettled("the engine must open, transmitting") { handle.gateOpen }
        assertFalse("an open gate alone is not speech", conn.selfSpeaking.value)

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(1, 2, 3), 0L, terminator = false))
        assertSettled("a packet on the wire is") { conn.selfSpeaking.value }

        elapse(1.seconds)   // well past the hold, with no packet since
        assertFalse("and released once they stop", conn.selfSpeaking.value)

        conn.disconnect()
    }

    @Test fun aTerminatorIsNotSpeech() = deterministic {
        val handle = FakeCaptureHandle(blocking = false)
        lateinit var fake: FakeControlTransport
        val conn = connection(
            newCapture = { handle }, newTransport = { FakeControlTransport { _, _ -> }.also { fake = it } },
        )

        connectTo(conn)
        conn.setTransmitting(true)
        assertSettled("the engine must open") { handle.gateOpen }

        handle.script(FakeCaptureHandle.Step.Frame(byteArrayOf(9), 4L, terminator = true))
        assertSettled("the terminator must reach the wire") { fake.sentRaw.size == 1 }
        assertFalse("a terminator must not light the halo", conn.selfSpeaking.value)

        conn.disconnect()
    }

    /** Left standing across a release, the hold would light the next session's halo for audio
     *  the previous one sent. */
    @Test fun releasingCaptureClearsSpeaking() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn)
        conn.setTransmitting(true)
        assertSettled("the engine must open") { handles.size == 1 && handles[0].gateOpen }

        handles[0].script(FakeCaptureHandle.Step.Frame(byteArrayOf(1), 0L, terminator = false))
        assertSettled("a packet lights it") { conn.selfSpeaking.value }

        // No time passes, so only the release can have lowered it.
        call.hold()
        assertSettled("the hold must release the engine") { handles[0].destroyed }
        assertFalse("the release must clear it, ahead of the hold expiring", conn.selfSpeaking.value)

        conn.disconnect()
    }

    /** Re-applying the mode a session already has must leave a held press alone. */
    @Test fun reApplyingPushToTalkDoesNotDropAHeldPress() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } })

        connectTo(conn)
        conn.setTransmitting(true)
        assertSettled("the press must open an engine, transmitting") { handles.size == 1 && handles[0].gateOpen }

        conn.setTransmitMode(TransmitMode.PushToTalk)
        assertTrue("re-applying the mode must leave the press alone", handles[0].gateOpen)

        conn.disconnect()
    }

    /** The hold already released the session, so this disconnect runs no release of its own:
     *  retiring the session is what has to clear the signal. */
    @Test fun disconnectClearsSpeaking() = deterministic {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = connection(newCapture = { FakeCaptureHandle(blocking = false).also { handles += it } }, call = call)

        connectTo(conn)
        conn.requestCapture()
        assertSettled("the engine must open") { handles.size == 1 }
        call.hold()
        assertSettled("the hold must publish") { conn.callHeld.value }

        conn.disconnect()
        assertSettled("disconnect must clear the hold") { !conn.callHeld.value }
        assertFalse(conn.selfSpeaking.value)
    }
}
