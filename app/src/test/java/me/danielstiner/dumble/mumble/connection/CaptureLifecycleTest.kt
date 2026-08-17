package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.FakeAudioOut
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.NativeCapture
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The capture lifecycle's regression suite. Each case here reproduced a real defect before the
 * serialised-reconcile redesign; they now assert those defects are absent.
 */
class CaptureLifecycleTest {

    /**
     * A native engine whose pump cannot be woken. Stands in for the real failure the production
     * code already anticipates and logs — "send thread did not exit within 1000ms" — without
     * needing to reproduce whatever wedges Oboe.
     */
    private class WedgedCaptureHandle : VoiceSender.CaptureHandle {
        private val unblock = CountDownLatch(1)
        val pollsInFlight = AtomicInteger()

        /** Polls in flight at the moment destroy() ran; -1 if destroy() was never called. */
        @Volatile var pollsInFlightAtDestroy = -1; private set
        @Volatile var stopCalled = false; private set

        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            pollsInFlight.incrementAndGet()
            try {
                // Blocks like the real pollFrame does, and stop() deliberately does not release it.
                unblock.await()
                return NativeCapture.POLL_SHUTDOWN
            } finally {
                pollsInFlight.decrementAndGet()
            }
        }

        override fun setGateOpen(open: Boolean) = Unit
        override fun stop() { stopCalled = true }
        override fun destroy() { pollsInFlightAtDestroy = pollsInFlight.get() }
        override fun stats(): CaptureStats? = null

        /** Test cleanup only — lets the parked pump thread exit so it does not outlive the run. */
        fun release() = unblock.countDown()
    }

    /**
     * Teardown must not free the engine while a poll is in flight. stop() no longer joins, so the
     * engine is released by the pump's own exit — which cannot happen while it is parked.
     */
    @Test fun teardownDoesNotDestroyTheEngineWhileThePumpIsStillPolling() = runBlocking {
        val handle = WedgedCaptureHandle()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { handle },
            call = call,
            stuckPumpMillis = 100L,
        ) { FakeControlTransport { _, _ -> } }
        try {
            conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()
            awaitTrue("the pump must be parked in pollFrame") { handle.pollsInFlight.get() == 1 }

            conn.disconnect()
            awaitTrue("teardown must request a stop") { handle.stopCalled }
            delay(500)   // well past the wedge deadline, so a destroy would have happened by now
            assertEquals(
                "the engine must not be freed with a poll still in flight",
                -1, handle.pollsInFlightAtDestroy,
            )

            handle.release()
            awaitTrue("the pump's exit must free the engine") { handle.pollsInFlightAtDestroy >= 0 }
            assertEquals(
                "freed only after the poll returned", 0, handle.pollsInFlightAtDestroy,
            )
        } finally {
            handle.release()
        }
    }

    /**
     * The same non-destruction-under-a-live-pump via the hold path, not just disconnect. Worth its
     * own case because teardown() and a hold's release are separate call sites with the same shape,
     * and a hold is the common one — every incoming cellular call takes it.
     */
    @Test fun holdDoesNotDestroyTheEngineWhileThePumpIsStillPolling() = runBlocking {
        val handle = WedgedCaptureHandle()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { handle },
            call = call,
            stuckPumpMillis = 100L,
        ) { FakeControlTransport { _, _ -> } }
        try {
            conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()
            awaitTrue("the pump must be parked in pollFrame") { handle.pollsInFlight.get() == 1 }

            call.hold()
            awaitTrue("a hold must request a stop") { handle.stopCalled }
            delay(500)   // well past the wedge deadline, so a destroy would have happened by now
            assertEquals(
                "the engine must not be freed with a poll still in flight",
                -1, handle.pollsInFlightAtDestroy,
            )

            handle.release()
            awaitTrue("the pump's exit must free the engine") { handle.pollsInFlightAtDestroy >= 0 }
            assertEquals(
                "freed only after the poll returned", 0, handle.pollsInFlightAtDestroy,
            )
        } finally {
            handle.release()
        }
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

        private val unblock = CountDownLatch(1)
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            unblock.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun stop() { unblock.countDown() }
        override fun destroy() { live.decrementAndGet() }
        override fun stats(): CaptureStats? = null
    }

    /**
     * A requestCapture during a hold must open nothing. openCapture only checked `att.sender == null`,
     * which a hold had just made true, so a Chat/Connected remount during a cellular call opened the
     * microphone — and could do it while the first engine was still live.
     */
    @Test fun requestCaptureDuringAHoldOpensNothing() = runBlocking {
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { CountingHandle(live, peak) },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the first engine must open") { live.get() == 1 }

        call.hold()
        awaitTrue("the hold must release the engine") { live.get() == 0 }

        conn.requestCapture()
        delay(300)
        assertEquals("a hold must refuse a start", 0, live.get())

        call.resume()
        awaitTrue("resuming must rebuild") { live.get() == 1 }
        assertEquals("never two microphone streams at once", 1, peak.get())

        conn.disconnect()
        awaitTrue("disconnect must release the engine") { live.get() == 0 }
    }

    /** Counts destroy() so a double free would be visible rather than assumed impossible. */
    private class DestroyCountingHandle : VoiceSender.CaptureHandle {
        private val unblock = CountDownLatch(1)
        val destroys = AtomicInteger()
        val stops = AtomicInteger()
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            unblock.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun stop() { stops.incrementAndGet(); unblock.countDown() }
        override fun destroy() { destroys.incrementAndGet() }
        override fun stats(): CaptureStats? = null
    }

    /**
     * Hold-then-disconnect must free the engine exactly once: the hold's release destroys it via
     * the pump's exit, and the disconnect's Release then reconciles an attempt whose `capture` is
     * already null. A second destroy() on the real engine is the native use-after-free, so "found
     * nothing to free" is the behaviour being pinned.
     */
    @Test fun holdThenDisconnectDestroysTheEngineExactlyOnce() = runBlocking {
        val handle = DestroyCountingHandle()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { handle },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the engine must open") { handle.stops.get() == 0 && handle.destroys.get() == 0 }

        call.hold()
        awaitTrue("the hold must release the engine") { handle.destroys.get() == 1 }
        conn.disconnect()
        delay(1_000)

        assertEquals("the engine must be freed exactly once", 1, handle.destroys.get())
    }

    /** Reports create/stop/destroy order across several engines, so a test can assert sequencing. */
    private class RecordingHandle(private val log: ConcurrentLinkedQueue<String>, val name: String) :
        VoiceSender.CaptureHandle {
        private val unblock = CountDownLatch(1)
        val destroys = AtomicInteger()
        init { log += "$name:create" }
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            unblock.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun stop() { log += "$name:stop"; unblock.countDown() }
        override fun destroy() { destroys.incrementAndGet(); log += "$name:destroy" }
        override fun stats(): CaptureStats? = null
    }

    /**
     * The one-microphone invariant across attempts, which nothing covered before. teardown queues
     * the prior release synchronously, ahead of anything the new attempt can produce, and the
     * release closes the stream before returning — so the first engine is always stopped before
     * the second is created.
     *
     * This pins ordering only loosely. Measured: this test's own setup — a full second `connect()`
     * → `Handshaking` round trip before `requestCapture()` can run — gives `e0:stop` such a head
     * start that a merely-asynchronous release still finishes first and does not flip the
     * assertion, whether that means wrapping `stop()` in a `launch` or moving the prior attempt's
     * `teardown()` send off the caller's thread. Only a release slow enough to lose that race
     * outright (an artificial `delay(400)` ahead of `stop()`, in testing) reliably does.
     */
    @Test fun reconnectWhileCapturingClosesTheFirstStreamBeforeOpeningTheSecond() = runBlocking {
        val log = ConcurrentLinkedQueue<String>()
        val handles = CopyOnWriteArrayList<RecordingHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { RecordingHandle(log, "e${handles.size}").also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the first engine must open") { handles.size == 1 }

        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the second engine must open") { handles.size == 2 }
        awaitTrue("the first engine must be released") { handles[0].destroys.get() == 1 }

        val order = log.toList()
        assertTrue(
            "the first stream must close before the second opens: $order",
            order.indexOf("e0:stop") < order.indexOf("e1:create"),
        )
        // A plain check, not a wait: the default auto-grant supersedes during start(). Pins the
        // supersede, not the Release handler's call.end — callEndFiresEvenWhenThePumpIsWedged
        // pins that one, with no second call.start around to mask it.
        assertEquals("the superseded call must end exactly once", 1, call.ends)

        conn.disconnect()
        awaitTrue("disconnect must release the second engine") { handles[1].destroys.get() == 1 }
    }

    /** A handle whose stop() takes a while, like a slow HAL close. */
    private class SlowStopHandle(private val stopMillis: Long) : VoiceSender.CaptureHandle {
        private val pollGate = CountDownLatch(1)
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            pollGate.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun stop() { Thread.sleep(stopMillis); pollGate.countDown() }
        override fun destroy() = Unit
        override fun stats(): CaptureStats? = null
    }

    /**
     * STRESS CASE, not a proof — see below for why a single trial cannot be one.
     *
     * Pins beginRelease's synchronous, inline session.stop() (the "entire one-microphone
     * invariant" per its own comment): the gap between the second attempt's Acquire being sent and
     * its engine actually opening must be at least as long as the first engine's own slow stop(),
     * because a synchronous release cannot process anything queued behind it until stop() returns.
     * reconnectWhileCapturingClosesTheFirstStreamBeforeOpeningTheSecond cannot catch an async stop()
     * because its own round trip to the second Handshaking gives a merely-launched stop() such a
     * head start that it still finishes first; a slow stop() removes that head start.
     *
     * Why repeated rather than single-shot: measured directly (repeated manual runs, thread dumps
     * included) that a single trial's gap is bimodal under the async mutation — either near-zero
     * (correctly caught) or, about half the time, equal to the full sleep anyway, indistinguishable
     * from the synchronous case. The cause is environmental, not a flaw in the ordering being
     * tested: `beginRelease`'s mutated `scope.launch { session.stop() }` runs on
     * [kotlinx.coroutines.Dispatchers.Default], which shares its worker pool with the capture
     * consumer's own [kotlinx.coroutines.Dispatchers.IO]; a raw `Thread.sleep` inside that launched
     * coroutine occasionally ends up serialised behind the consumer's own queued work on the same
     * shared pool regardless of the mutation. Confirmed independent of dispatcher pool size (tried
     * up to 128 threads) and of sleep duration (reproduced at 80ms, 300ms and 600ms alike) — this
     * is a scheduler artifact of the simulation, not of the code under test. It never produces a
     * false failure under correct code (six separate manual runs, 6/6 green), so repeating below
     * only buys detection power, not risk: correct code blocks the consumer inline regardless of
     * this dispatcher-sharing quirk, so the gap is always the full sleep, every trial.
     */
    @Test fun aSlowFirstStopBlocksTheSecondAttemptsOpenUntilItReturns() = runBlocking {
        repeat(20) {
            val t0 = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - t0) / 1_000_000
            val secondOpenAtMs = AtomicLong(-1)
            val handles = CopyOnWriteArrayList<VoiceSender.CaptureHandle>()
            var first = true
            val call = FakeVoiceCall()
            val conn = MumbleConnection(
                InMemoryPinStore(), { FakeAudioOut() },
                newCapture = {
                    if (first) {
                        first = false
                        SlowStopHandle(150).also { handles += it }
                    } else {
                        secondOpenAtMs.set(elapsedMs())
                        FakeCaptureHandle().also { handles += it }
                    }
                },
                call = call,
            ) { FakeControlTransport { _, _ -> } }

            conn.connect(MumbleEndpoint.parse("first"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()
            awaitTrue("the first engine must open") { handles.size == 1 }

            conn.connect(MumbleEndpoint.parse("second"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()
            val secondSentAtMs = elapsedMs()

            awaitTrue("the second engine must eventually open", timeoutMillis = 5_000) {
                handles.size == 2
            }
            val gapMs = secondOpenAtMs.get() - secondSentAtMs
            assertTrue(
                "trial $it: the second engine opened only ${gapMs}ms after its Acquire — the first " +
                    "engine's stop() must be awaited inline, not raced",
                gapMs >= 100,
            )

            conn.disconnect()
        }
    }

    /**
     * A hold callback from a superseded call must not touch the live attempt. The platform does not
     * fence a callback already in flight, and an earlier draft keyed the hold level on the attempt
     * rather than the generation — so a late hold latched onto the successor and killed transmit for
     * the whole session with nothing able to clear it.
     */
    @Test fun aStaleHoldDoesNotTouchTheLiveAttempt() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        val staleGen = 1   // the first connect's generation

        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the live attempt must be capturing") { handles.any { !it.destroyed } }

        call.holdFor(staleGen)
        delay(300)
        assertTrue(
            "a stale hold must not release the live engine",
            handles.any { !it.destroyed },
        )
        conn.disconnect()
    }

    /**
     * A Talk press held across a hold must transmit once the call comes back. Transmit intent used
     * to be an edge straight to the live session — during a hold there is none, so the press was
     * dropped, and the session reconcile() then built came up with its gate closed under a button
     * the user was still holding. Found on-device: the first press after a cellular call transmitted
     * nothing for as long as it was held, and only the second worked.
     */
    @Test fun aTalkPressAloneRebuildsAndTransmits() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the first engine must open") { handles.size == 1 }

        call.hold()
        awaitTrue("the hold must release the engine") { handles[0].destroyed }

        // The press and nothing else — no paired requestCapture(), because the connection owes both
        // halves: ask the platform for the call back, and remember that the button is down. There
        // is no session for the gate to reach, so the intent lands on the level alone.
        conn.setTransmitting(true)
        awaitTrue("the press alone must ask for the call back") { call.activeRequests == listOf(1) }

        call.resume()
        awaitTrue("the resume must rebuild") { handles.size == 2 }
        awaitTrue("the rebuilt session must come up transmitting") { handles[1].gateOpen }

        // The release still closes it: the level is user intent, not a latch.
        conn.setTransmitting(false)
        awaitTrue("releasing Talk must close the gate") { !handles[1].gateOpen }

        conn.disconnect()
    }

    /** A native stop() that throws — the JNI call into OboeCapture::close() is not exception-free. */
    private class ThrowingStopHandle : VoiceSender.CaptureHandle {
        private val unblock = CountDownLatch(1)
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            unblock.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun stop(): Unit = throw RuntimeException("stop blew up")
        override fun destroy() = Unit
        override fun stats(): CaptureStats? = null
        fun release() = unblock.countDown()
    }

    /**
     * A throw out of the release must not strand the platform call. The consumer loop wraps the
     * whole dispatch in runCatching, so when `call.end` sat at the tail of the Release handler a
     * throw in reconcile skipped it silently: the telecom call stayed registered with its microphone
     * notification, and the only way out was hanging up the ghost from system UI — which is wired to
     * onEnded -> disconnect(). Ending first also keeps the call off an unbounded HAL close.
     */
    @Test fun aThrowingReleaseStillEndsThePlatformCall() = runBlocking {
        val handle = ThrowingStopHandle()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { handle },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        delay(200)

        conn.disconnect()
        awaitTrue("the platform call must end even though the release threw") { call.ends == 1 }
        assertEquals(listOf(VoiceCall.Reason.USER), call.endReasons.toList())

        handle.release()
    }

    /**
     * A resume from a superseded call must not clear a hold that is legitimately protecting
     * the live attempt. aStaleHoldDoesNotTouchTheLiveAttempt above only ever delivers a stale
     * *hold*, which sets heldGen to a generation that already differs from the live one — harmless
     * by coincidence (heldGen != att.gen was already true), not because cmd.gen == attempt did
     * anything. The staleness check's real job is guarding a stale *resume*: unchecked, it would
     * clear heldGen back to NO_GEN while the platform still holds the live call, reopening capture
     * against a device the platform owns.
     */
    @Test fun aStaleResumeDoesNotClearTheLiveHold() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        val staleGen = 1   // the first connect's generation

        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the live attempt must open") { handles.size == 1 }

        call.hold()   // holds the live generation (2)
        awaitTrue("the hold must release the engine") { handles[0].destroyed }

        call.resumeFor(staleGen)   // a resume for the superseded call (1), not the live hold
        delay(300)
        assertEquals(
            "a stale resume must not reopen capture the platform still holds",
            1, handles.size,
        )

        call.resume()   // the genuine resume, for the live generation
        awaitTrue("the genuine resume must rebuild") { handles.size == 2 }
        conn.disconnect()
    }

    /**
     * A hold delivered between call.start and the attempt publishing must still be honoured: the
     * hold level is keyed by generation precisely because the two do not have the same lifetime.
     */
    @Test fun aHoldBeforeTheAttemptPublishesLeavesItNotCapturing() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        // call.start runs synchronously inside connect(), so the closure exists immediately —
        // before the coroutine that publishes the attempt has necessarily run.
        call.hold()
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        conn.requestCapture()
        delay(300)
        assertEquals("a held call must open no engine", 0, handles.size)

        call.resume()
        awaitTrue("resuming must open one") { handles.size == 1 }
        conn.disconnect()
    }

    /**
     * A resume arriving while a release is still in flight must not be dropped. reconcile refuses to
     * open while `releasing`, so the only thing that rebuilds is onPumpExited's trailing reconcile —
     * this is what pins it.
     */
    @Test fun aResumeDuringAReleaseRebuildsExactlyOnce() = runBlocking {
        val handle = WedgedCaptureHandle()
        val rebuilt = CopyOnWriteArrayList<FakeCaptureHandle>()
        var first = true
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = {
                if (first) { first = false; handle }
                else FakeCaptureHandle().also { rebuilt += it }
            },
            call = call,
            stuckPumpMillis = 100L,
        ) { FakeControlTransport { _, _ -> } }
        try {
            conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()
            awaitTrue("the pump must be parked") { handle.pollsInFlight.get() == 1 }

            call.hold()
            awaitTrue("the hold must request a stop") { handle.stopCalled }
            // The pump is still parked, so the release has not completed.
            call.resume()
            delay(300)
            assertEquals("nothing may be built while the release is in flight", 0, rebuilt.size)

            handle.release()
            awaitTrue("the exit must rebuild") { rebuilt.size == 1 }
            delay(300)
            assertEquals("exactly one rebuild", 1, rebuilt.size)
        } finally {
            handle.release()
        }
        conn.disconnect()
    }

    /**
     * A Talk press while held is the only resume signal core-telecom leaves us: it does not tell us
     * when the interrupting cellular call ends, so without this the session stays ON_HOLD forever
     * after one. Fails without the fix — before requestActive() existed, a held Acquire just re-ran
     * reconcile(), which no-ops while heldGen is set, and the platform was never asked again.
     */
    @Test fun aTalkPressWhileHeldAsksThePlatformToResume() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { FakeCaptureHandle().also { handles += it } },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the first engine must open") { handles.size == 1 }

        call.hold()
        awaitTrue("the hold must release the engine") { handles[0].destroyed }

        // Simulates a Talk press: onTransmitting(true) calls requestCapture() regardless of hold state.
        conn.requestCapture()
        awaitTrue("a Talk press while held must re-request active") { call.activeRequests.isNotEmpty() }
        assertEquals("exactly the live generation", listOf(1), call.activeRequests)
        assertEquals("no engine until the platform grants it", 1, handles.size)

        // The platform grants the request — same callback path as any other resume.
        call.resume()
        awaitTrue("a granted resume must rebuild") { handles.size == 2 }

        conn.disconnect()
    }

    /** A press while active must not touch the platform — only a held generation asks. */
    @Test fun aTalkPressWhileActiveDoesNotRequestResume() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { FakeCaptureHandle() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        conn.requestCapture()
        delay(300)
        assertTrue("no request while never held", call.activeRequests.isEmpty())

        conn.disconnect()
    }

    /**
     * A terminal engine failure must not become an automatic reopen loop — the cause has not
     * changed, and each retry is a full HAL open. The retry stays user-driven.
     */
    @Test fun aTerminalPumpExitDoesNotReopenButAStartCaptureDoes() = runBlocking {
        val handles = CopyOnWriteArrayList<FakeCaptureHandle>()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = {
                FakeCaptureHandle().also {
                    it.script(FakeCaptureHandle.Step.Unavailable)
                    handles += it
                }
            },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the failed engine must be released") { handles.size == 1 && handles[0].destroyed }
        delay(300)
        assertEquals("a terminal exit must not reopen on its own", 1, handles.size)

        conn.requestCapture()
        awaitTrue("a user retry must rebuild") { handles.size == 2 }
        conn.disconnect()
    }

    /**
     * A wedged pump must not hold the platform call open. Deferring call.end until the engine was
     * freed meant a pump that never exits never ended the call — a permanent foreground service and
     * telecom UI showing an active call until process death.
     *
     * Also the observable half of the wedge watchdog: `wedged` does not exist as state, so this
     * pollsInFlightAtDestroy == -1 check — the engine is not freed while the pump is in flight — is
     * what stands in for it.
     */
    @Test fun callEndFiresEvenWhenThePumpIsWedged() = runBlocking {
        val handle = WedgedCaptureHandle()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { handle },
            call = call,
            stuckPumpMillis = 100L,
        ) { FakeControlTransport { _, _ -> } }
        try {
            conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()
            awaitTrue("the pump must be parked") { handle.pollsInFlight.get() == 1 }

            conn.disconnect()
            awaitTrue("the call must end despite the wedged pump") { call.ends == 1 }
            assertEquals(
                "a wedged pump's engine must not be freed",
                -1, handle.pollsInFlightAtDestroy,
            )
            delay(300)
            assertEquals("the call must end exactly once", 1, call.ends)
        } finally {
            handle.release()
        }
    }

    /** A handle that reports a gate touched after it was freed — the use-after-free, made loud. */
    private class GateAfterDestroyHandle : VoiceSender.CaptureHandle {
        private val unblock = CountDownLatch(1)
        @Volatile private var destroyed = false
        @Volatile var gateAfterDestroy = false; private set
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            unblock.await(); return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) { if (destroyed) gateAfterDestroy = true }
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
                InMemoryPinStore(), { FakeAudioOut() },
                newCapture = { handle },
                call = call,
            ) { FakeControlTransport { _, _ -> } }
            conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
            withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
            conn.requestCapture()

            val hammer = Thread { repeat(500) { i -> conn.setTransmitting(i % 2 == 0) } }
            hammer.start()
            // No wait for the engine to open first: Acquire and this hold's Held share one serial
            // queue, so openSession() has already run by the time Held is dispatched.
            call.hold()
            hammer.join(2_000)
            assertFalse("the gate reached a freed handle", handle.gateAfterDestroy)
            conn.disconnect()
        }
    }

    /** Tracks whether the pump ever touched the handle, to prove a rejected open never attached it. */
    private class GatedHandle(private val gate: CountDownLatch) : VoiceSender.CaptureHandle {
        @Volatile var enteredNewCapture = false
        @Volatile var stopped = false; private set
        @Volatile var destroyed = false; private set
        @Volatile var pollFrameCalled = false; private set
        fun awaitGate() { enteredNewCapture = true; gate.await() }
        override fun pollFrame(out: ByteArray, meta: LongArray): Int {
            pollFrameCalled = true
            return NativeCapture.POLL_SHUTDOWN
        }
        override fun setGateOpen(open: Boolean) = Unit
        override fun stop() { stopped = true }
        override fun destroy() { destroyed = true }
        override fun stats(): CaptureStats? = null
    }

    /**
     * Pins openSession's isLive recheck after newCapture() returns. newCapture() blocks on the HAL
     * and `attempt`/`current` are still mutated on caller threads while it does, so a disconnect
     * landing in that window has already moved the world by the time the handle comes back. Without
     * the recheck the stale handle would attach and its pump would start — a leaked engine racing
     * a `current` that no longer points at it.
     */
    @Test fun aDisconnectDuringNewCaptureLeavesTheStaleHandleUnattached() = runBlocking {
        val gate = CountDownLatch(1)
        lateinit var handle: GatedHandle
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = { handle.awaitGate(); handle },
            call = call,
        ) { FakeControlTransport { _, _ -> } }
        handle = GatedHandle(gate)

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the consumer must be parked inside newCapture()") { handle.enteredNewCapture }

        conn.disconnect()   // moves `current`/`attempt` while newCapture() is still blocked
        gate.countDown()    // let the now-stale handle come back

        awaitTrue("the stale handle must be stopped") { handle.stopped }
        awaitTrue("the stale handle must be destroyed") { handle.destroyed }
        delay(200)
        assertFalse(
            "a stale open must never start the pump against a superseded attempt",
            handle.pollFrameCalled,
        )
    }

    /** A handler that throws must not kill the consumer — a dead one fails silently and forever. */
    @Test fun aThrowingHandlerDoesNotKillTheConsumer() = runBlocking {
        val opened = AtomicInteger()
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = {
                // First open throws from inside the consumer; later ones behave.
                if (opened.getAndIncrement() == 0) throw IllegalStateException("boom")
                FakeCaptureHandle()
            },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.requestCapture()
        awaitTrue("the throwing open must have been attempted") { opened.get() >= 1 }

        conn.requestCapture()
        awaitTrue("the consumer must still be alive") { opened.get() >= 2 }
        conn.disconnect()
    }

    /**
     * The connect-failure path — a refused server, or a trust prompt the user leaves sitting —
     * fires call.end() while addCall may not have granted control yet. That used to cancel our own
     * coroutine and tell the platform nothing, wedging a DIALING call for the ~125 s until the
     * anomaly watchdog reaped it and blocking every connect in between.
     */
    @Test fun aConnectFailureBeforeTheGrantStillEndsTheCall() = runBlocking {
        val call = FakeVoiceCall(autoGrant = false)
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            call = call,
        ) { FakeControlTransport { _, _ -> throw java.io.IOException("refused") } }

        conn.connect(MumbleEndpoint.parse("host"), "user", null)
        awaitTrue("the connection must report a failure") { conn.status.value is ConnectionStatus.Error }
        assertEquals("nothing may end before the platform grants control", 0, call.ends)

        call.grantPending()
        awaitTrue("the grant must release the pending end") { call.ends == 1 }
        assertEquals(
            "a failed session is not a hang-up",
            listOf(VoiceCall.Reason.SESSION_FAILED), call.endReasons.toList(),
        )
    }

    /*
     * The design also called for "a supersede while ungranted ends the prior call first" and "a
     * Talk press while held and ungranted does not strand a resume". Verified unreachable, not
     * forgotten: TelecomCall.handleStart ends on `granted.await()`, which suspends the single
     * command consumer until the grant resolves, so no later command can ever observe an
     * ungranted Start.
     */

    /**
     * The platform can hang up a call we have already superseded — its callbacks are silenced only
     * once the supersede cancels its job. Ungated, that hangup retired whichever session had
     * replaced it, killing a connection the user had just asked for.
     */
    @Test fun aPlatformHangupOfASupersededCallDoesNotRetireTheSuccessor() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(MumbleEndpoint.parse("first"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        conn.connect(MumbleEndpoint.parse("second"), "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        call.endedBySystemFor(call.startedGens[0])

        // A retire would take the status to Idle; the live session must be untouched.
        delay(100)
        assertTrue(
            "the successor must survive the superseded call's hangup: ${conn.status.value}",
            conn.status.value is ConnectionStatus.Handshaking,
        )
        conn.disconnect()
    }

    /**
     * connect()'s catch ends the platform call on purpose while leaving AwaitingTrust up, so a
     * late hangup for that generation used to take the fingerprint decision off screen and null
     * `current`, which is what trustAndConnect needs.
     */
    @Test fun aHangupWhileAwaitingTrustDoesNotDismissThePrompt() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            call = call,
        ) { FakeControlTransport { _, _ -> throw UntrustedCertificateException("aa:bb") } }

        conn.connect(MumbleEndpoint.parse("host"), "user", null)
        awaitTrue("the connection must stop for a trust decision") {
            conn.status.value is ConnectionStatus.AwaitingTrust
        }
        val prompt = conn.status.value

        call.endedBySystemFor(call.startedGens[0])

        delay(100)
        assertEquals("a late hangup must not dismiss the trust prompt", prompt, conn.status.value)
    }

    /**
     * retire() keeps the terminal Error up and does not bump `attempt`, so a later platform
     * hangup still matches the generation — a gen-only guard let it overwrite the Error with a
     * bare Idle, losing the reason the connect screen shows.
     */
    @Test fun aHangupAfterASessionFailureDoesNotEraseTheReason() = runBlocking {
        val call = FakeVoiceCall()
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            call = call,
        ) { FakeControlTransport { _, _ -> throw java.io.IOException("refused") } }

        conn.connect(MumbleEndpoint.parse("host"), "user", null)
        awaitTrue("the connection must report a failure") { conn.status.value is ConnectionStatus.Error }
        val failure = conn.status.value

        call.endedBySystemFor(call.startedGens[0])

        delay(100)
        assertEquals("a late hangup must not overwrite the failure", failure, conn.status.value)
    }

    private suspend fun awaitTrue(what: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timed out waiting: $what")
    }
}
