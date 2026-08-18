package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.FakeAudioOut
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.NativeCapture
import me.danielstiner.dumble.mumble.voice.VoiceSender
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Concurrent chaos test for the capture lifecycle. The rest of this suite pins one interleaving at
 * a time; this drives requestCapture/setTransmitting/hold/resume/connect/disconnect from several
 * real threads at once, varying the schedule every round, and checks the invariants the
 * serialised-reconcile design (docs/architecture.md, "Audio capture") claims must survive any
 * interleaving.
 *
 * Two thread roles, not one undifferentiated pool — see [storm] for why connect/disconnect are
 * pulled onto their own thread rather than sharing the same random draw as the cheap ops.
 *
 * Cost: [ROUNDS] rounds, each running [HAMMER_THREADS] hammer threads for as long as one lifecycle
 * thread takes to fire [LIFECYCLE_OPS] connect/disconnect calls with a real 5-20ms gap between
 * each (~65-150ms per round); measured end to end at ~1.6-1.8s per standalone run of this file,
 * over twenty-five consecutive clean runs. The
 * lifecycle thread's gaps dominate that, not the hammer threads, whose ops are all non-blocking
 * (trySend or a lock-guarded field read). In suite, its own share of wall-clock is this number,
 * not the ~23s the full suite takes.
 */
class CaptureLifecycleChaosTest {

    /**
     * A native engine stand-in built to be hammered concurrently, not scripted: it tracks two
     * different lifetimes rather than one, because the design guarantees two *different* things
     * about them.
     *
     * [streamLive]/[streamPeak] count construct→[stop]: the audio *stream*, which the design
     * guarantees is never doubly open (`beginRelease`'s stop() is synchronous, and the channel
     * orders it ahead of any superseding attempt's open). This is the real "never two microphones
     * at once" invariant, and it is schedule-independent: asserting it should never flake under
     * correct code.
     *
     * [engineLive] counts construct→[destroy]: the engine *object*. The design allows two of these
     * alive at once across attempts (old one releasing, new one open) because destroy() only runs
     * once the old pump physically exits, which is asynchronous and not ordered against the new
     * attempt's open. Peak-counting *this* one and asserting <=1 would be asserting something the
     * design never promised, and would flip under real scheduling variance depending on whether
     * the old pump's exit happens to win the race — a coin-flip, not an invariant.
     * It exists here only to drive the quiescence check: every constructed engine must eventually be
     * destroyed, i.e. this returns to zero once the storm settles.
     */
    private class ChaosCaptureHandle(
        private val streamLive: AtomicInteger,
        private val streamPeak: AtomicInteger,
        private val engineLive: AtomicInteger,
        private val enginePeak: AtomicInteger,
        private val violations: MutableCollection<String>,
        private val id: Int,
    ) : VoiceSender.CaptureHandle {
        private val pollsInFlight = AtomicInteger()
        private val destroyed = AtomicBoolean(false)
        private val shutdown = AtomicBoolean(false)

        init {
            streamPeak.updateAndGet { maxOf(it, streamLive.incrementAndGet()) }
            enginePeak.updateAndGet { maxOf(it, engineLive.incrementAndGet()) }
        }

        override fun pollPacket(out: ByteArray, meta: LongArray): Int {
            pollsInFlight.incrementAndGet()
            try {
                // Bounded like the real engine's kPollWaitMillis wait, not indefinite: a chaos storm
                // needs its pumps to actually exit so settling is reachable at all.
                Thread.sleep(POLL_WAIT_MILLIS)
                return if (shutdown.get()) NativeCapture.POLL_SHUTDOWN else NativeCapture.POLL_RETRY
            } finally {
                pollsInFlight.decrementAndGet()
            }
        }

        override fun setGateOpen(open: Boolean) {
            // setGateOpen reaches a member of the C++ Session that destroy() deletes, so this
            // firing after destroyed is the use-after-free defect 5 fixed with CaptureSession's
            // monitor. It should never fire — the monitor already guards it at the Kotlin level —
            // so this is a second, independent witness rather than the only one.
            if (destroyed.get()) violations += "handle $id: setTransmitting reached a destroyed handle"
        }

        override fun stop() {
            // Idempotent like the real stopping_ latch: a second Release can land while a hold's
            // release is already in flight, and a second stop() on the same handle is expected.
            if (shutdown.compareAndSet(false, true)) streamLive.decrementAndGet()
        }

        override fun destroy() {
            if (!destroyed.compareAndSet(false, true)) {
                violations += "handle $id: destroy called twice"
                return
            }
            // The original use-after-free: teardown freeing the engine while pollPacket was still
            // executing on it. Checked at the exact moment of the call that would have raced it.
            if (pollsInFlight.get() > 0) violations += "handle $id: destroyed while pollPacket was in flight"
            engineLive.decrementAndGet()
        }

        override fun stats(): CaptureStats? = null

        private companion object {
            const val POLL_WAIT_MILLIS = 4L
        }
    }

    /** Counters shared across every round, so a violation from round 3 does not evaporate by round 8. */
    private class Counters {
        val streamLive = AtomicInteger()
        val streamPeak = AtomicInteger()
        val engineLive = AtomicInteger()
        val enginePeak = AtomicInteger()
        val violations = CopyOnWriteArrayList<String>()
    }

    /**
     * One randomized concurrent storm against a fresh connection: [HAMMER_THREADS] threads firing
     * requestCapture/setTransmitting/hold/resume in random order and mix, plus one dedicated thread
     * firing connect/disconnect on its own schedule, then a settle phase that checks the consumer
     * is still alive and every engine it built is gone.
     */
    private fun storm(seed: Long, c: Counters) = runBlocking {
        val call = FakeVoiceCall()
        val created = AtomicInteger()
        val endpoint = MumbleEndpoint.parse("chaos")
        val conn = MumbleConnection(
            InMemoryPinStore(), { FakeAudioOut() },
            newCapture = {
                ChaosCaptureHandle(
                    c.streamLive, c.streamPeak, c.engineLive, c.enginePeak, c.violations,
                    created.incrementAndGet(),
                )
            },
            call = call,
            stuckPumpMillis = 300L,
        ) { FakeControlTransport { _, _ -> } }

        conn.connect(endpoint, "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }

        // The cheap, high-frequency ops a real session sees constantly: a PTT press, a hold/resume
        // pair. None of these block, so HAMMER_THREADS racing them concurrently is the point.
        val cheapOps: List<() -> Unit> = listOf(
            { conn.requestCapture() },
            { conn.setTransmitting(true) },
            { conn.setTransmitting(false) },
            { call.hold() },
            { call.resume() },
        )
        // Runs until the lifecycle thread below finishes, not a fixed op count: a fixed count made
        // the hammer threads finish their whole quota in single-digit milliseconds (nothing here
        // blocks), so whenever the lifecycle thread's own random schedule opened its first
        // connected window a little late, every hammer thread had already gone quiet and no op was
        // left to land in it — see the coverage-floor failures this fixed below.
        val stop = AtomicBoolean(false)
        val hammerThreads = (0 until HAMMER_THREADS).map { t ->
            Thread {
                val r = Random(seed * 1_000_003L + t)
                while (!stop.get()) {
                    try {
                        cheapOps[r.nextInt(cheapOps.size)]()
                    } catch (e: Throwable) {
                        c.violations += "seed=$seed thread=$t: op threw $e"
                    }
                    Thread.sleep(r.nextLong(1, 3))
                }
            }.apply { isDaemon = true; start() }
        }

        // connect()/disconnect() get one dedicated thread, not a slot in the same random draw as
        // the cheap ops above. Both are non-blocking calls that bump the attempt generation, so
        // several threads free to fire them at unbounded rate raced each other for the *right* to
        // ever publish `current` — connect()'s own async pipeline (a pinStore suspend hop, building
        // the transport/state machine, then a synchronized publish) takes long enough, relative to
        // how fast a bare Kotlin call can be re-issued, that a second reconnect/disconnect routinely
        // won that race and discarded the attempt before it ever went live. Measured: with
        // connect/disconnect as two slots in a 17-way draw across 6 unthrottled threads, 2 of 8
        // rounds built zero engines during the whole storm — not a fluke, a livelock this test's own
        // op mix could produce on its own, independent of anything under test. One thread with a
        // real gap between iterations still reconnects mid-capture on every round — the
        // cross-attempt half of the one-stream invariant — without racing itself out of ever landing.
        val lifecycleThread = Thread {
            val r = Random(seed * 1_000_003L + HAMMER_THREADS)
            repeat(LIFECYCLE_OPS) {
                try {
                    if (r.nextBoolean()) conn.connect(endpoint, "user", null) else conn.disconnect()
                } catch (e: Throwable) {
                    c.violations += "seed=$seed thread=lifecycle: op threw $e"
                }
                Thread.sleep(r.nextLong(5, 20))
            }
            stop.set(true)
        }.apply { isDaemon = true; start() }

        val threads = hammerThreads + lifecycleThread
        threads.forEach { it.join(10_000) }
        threads.forEach { if (it.isAlive) c.violations += "seed=$seed: a worker thread did not finish" }

        // Coverage floor: every assertion below runs in a clean tail after a forced disconnect()+
        // connect(), so without this check a storm that happened to no-op almost entirely — every
        // op landing on a disconnected connection, `requestCapture` and `setTransmitting` both
        // hitting `?: return` — would still pass every one of them. At least one engine must have
        // been built during the chaotic middle itself, not just the clean tail that follows it.
        val builtDuringStorm = created.get()
        if (builtDuringStorm == 0) {
            c.violations += "seed=$seed: no engines were created during the storm itself (coverage floor)"
        }

        // Settle into a known state — the storm can leave the connection live, held, or idle at
        // random — then check the two things that must be true no matter which: the consumer is
        // still alive, and it leaked nothing.
        conn.disconnect()
        awaitTrue(c.violations, "seed=$seed: engine count did not reach zero after the storm settled") {
            c.engineLive.get() == 0
        }

        // Consumer survival: a dead dispatch loop fails silently and permanently (see the
        // consumer's runCatching comment in MumbleConnection),
        // so this is the one invariant that would otherwise never announce its own failure.
        conn.connect(endpoint, "user", null)
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        val before = created.get()
        conn.requestCapture()
        awaitTrue(c.violations, "seed=$seed: the consumer did not build an engine for the final requestCapture()") {
            created.get() > before
        }

        conn.disconnect()
        awaitTrue(c.violations, "seed=$seed: engine count did not reach zero after the final disconnect") {
            c.engineLive.get() == 0
        }
    }

    /**
     * Drives the six lifecycle operations concurrently from several threads across several rounds,
     * reseeding every round so the schedule differs each time, and asserts the invariants that must
     * hold regardless of interleaving: never two live streams, no double free, no destroy while a
     * poll is in flight, no gate call reaching a freed handle, the consumer survives the storm, and
     * every engine it built is eventually destroyed.
     */
    @Test fun chaosStormNeverBreaksTheCaptureLifecycleInvariants() {
        val c = Counters()
        repeat(ROUNDS) { round ->
            val seed = BASE_SEED + round
            storm(seed, c)
            // Asserted after every round, not once at the end: streamPeak/violations are monotonic,
            // so whichever round first produces one fails here — with the seed that reproduces it —
            // rather than being buried in a summary after nine more rounds have run.
            assertTrue("round $round (seed=$seed) produced violations: ${c.violations}", c.violations.isEmpty())
            assertTrue(
                "round $round (seed=$seed): two live capture streams at once (peak=${c.streamPeak.get()})",
                c.streamPeak.get() <= 1,
            )
        }
        // Surfaces engineLive's peak rather than leaving it write-only: this is the empirical
        // check on the ChaosCaptureHandle doc comment's claim that the design permits two engine
        // *objects* alive across a reconnect (old releasing, new open) even though the stream
        // count above never does. Not asserted — the design permits it rather than requiring it — but worth
        // a line in the record either way.
        println(
            "CHAOS peak stream-open=${c.streamPeak.get()} (asserted <=1) " +
                "peak engine-alive=${c.enginePeak.get()} (informational; design permits >1 across a reconnect)",
        )
    }

    private suspend fun awaitTrue(
        violations: MutableList<String>,
        what: String,
        timeoutMillis: Long = 5_000,
        cond: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        violations += what
    }

    private companion object {
        const val BASE_SEED = 1_000_000L
        const val ROUNDS = 8
        const val HAMMER_THREADS = 5
        const val LIFECYCLE_OPS = 12
    }
}
