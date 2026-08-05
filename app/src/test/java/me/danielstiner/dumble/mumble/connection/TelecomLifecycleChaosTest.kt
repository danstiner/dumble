package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.FakeAudioOut
import me.danielstiner.dumble.mumble.voice.FakeOpusCodec
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Concurrent chaos test for the telecom call lifecycle seam: [MumbleConnection] driving
 * [me.danielstiner.dumble.mumble.voice.VoiceCall] through [FakeVoiceCall]`(autoGrant = false)`,
 * where the grant is asynchronous and a superseding start or a platform hangup can land before
 * it. Randomized concurrent rounds hunt schedules the design did not anticipate; a deterministic
 * tail pins the orderings it names — see [storm].
 *
 * Sibling to [CaptureLifecycleChaosTest], not folded in: that one hammers the stream/engine
 * invariants, this one [FakeVoiceCall]'s start/end/grant bookkeeping and the connection's
 * generation-gating of platform callbacks. No fixture or invariant is shared, and `newCapture`
 * always returns null — capture is not this test's concern.
 */
class TelecomLifecycleChaosTest {

    private class Counters {
        val violations = CopyOnWriteArrayList<String>()
    }

    /**
     * One randomized concurrent storm, then a deterministic tail pinning the orderings the design
     * names — supersede-while-ungranted, end-before-grant, a stale hangup racing a live one —
     * which a random schedule is not guaranteed to ever produce.
     */
    private fun storm(seed: Long, c: Counters) = runBlocking {
        val call = FakeVoiceCall(autoGrant = false)
        val endpoint = MumbleEndpoint.parse("chaos")
        val conn = MumbleConnection(
            InMemoryPinStore(), FakeOpusCodec(), { FakeAudioOut() },
            newCapture = { null },
            call = call,
        ) { FakeControlTransport { _, _ -> } }

        // The cheap, high-frequency ops a real session sees, plus a late grant and a platform
        // hangup for any known generation — live or superseded — since callbacks are not fenced
        // against having been replaced.
        val stop = AtomicBoolean(false)
        val hammerThreads = (0 until HAMMER_THREADS).map { t ->
            Thread {
                val r = Random(seed * 1_000_003L + t)
                // Per-thread, not shared: a shared closure list would capture one Random across
                // every thread, serialising the draws.
                val cheapOps: List<() -> Unit> = listOf(
                    { conn.requestCapture() },
                    { conn.setTransmitting(true) },
                    { conn.setTransmitting(false) },
                    { call.grantPending() },
                    {
                        // startedGens only grows, so a size snapshot then a bounded index is safe.
                        val gens = call.startedGens
                        if (gens.isNotEmpty()) call.endedBySystemFor(gens[r.nextInt(gens.size)])
                    },
                )
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

        // connect()/disconnect() get one dedicated thread with a real gap between ops, not a
        // hammer slot: unbounded reconnects from several threads livelocked publishing `current`,
        // independent of anything under test — see CaptureLifecycleChaosTest's storm().
        val lifecycleThread = Thread {
            val r = Random(seed * 1_000_003L + HAMMER_THREADS)
            repeat(LIFECYCLE_OPS) {
                try {
                    if (r.nextBoolean()) conn.connect(endpoint, "user", null) else conn.disconnect()
                } catch (e: Throwable) {
                    c.violations += "seed=$seed thread=lifecycle: op threw $e"
                }
                Thread.sleep(r.nextLong(5, 15))
            }
            stop.set(true)
        }.apply { isDaemon = true; start() }

        val threads = hammerThreads + lifecycleThread
        threads.forEach { it.join(10_000) }
        threads.forEach { if (it.isAlive) c.violations += "seed=$seed: a worker thread did not finish" }

        // Coverage floor: a storm that never connected would pass every check below vacuously.
        if (call.startedGens.isEmpty()) {
            c.violations += "seed=$seed: no connect() landed during the storm itself (coverage floor)"
        }

        // Schedule-independent invariant: a generation ends at most once, so ends never exceed starts.
        if (call.ends > call.startedGens.size) {
            c.violations += "seed=$seed: ends (${call.ends}) exceeded starts (${call.startedGens.size})"
        }

        // Settle, then check both sides agree on "no call". grantPending() first, so a
        // still-outstanding start's queued end is not mistaken for a leak.
        conn.disconnect()
        call.grantPending()
        awaitTrue(c.violations, "seed=$seed: fake still shows a live call after the storm settled") {
            !call.hasLiveCall
        }
        if (conn.status.value != ConnectionStatus.Idle) {
            c.violations += "seed=$seed: status was ${conn.status.value}, not Idle, once settled"
        }

        // Deterministic tail from here: the design's named orderings, pinned rather than left to
        // the storm's chance.

        // Consumer survival + live-state agreement. A dead lifecycle consumer fails silently and
        // permanently, so this is the one invariant that would never announce its own failure.
        conn.connect(endpoint, "user", null)
        call.grantPending()
        withTimeout(5_000) { conn.status.first { it is ConnectionStatus.Handshaking } }
        if (!call.hasLiveCall) c.violations += "seed=$seed: connected but the fake shows no live call"
        conn.disconnect()
        call.grantPending()
        awaitTrue(c.violations, "seed=$seed: fake still live after the tail's disconnect") { !call.hasLiveCall }

        // An end() arriving before the grant must be parked, then applied exactly once when the
        // grant lands.
        conn.connect(endpoint, "user", null)
        conn.disconnect()
        awaitTrue(c.violations, "seed=$seed: a queued end never reached the fake before its grant") {
            call.hasPendingEnd
        }
        val endsBeforeGrant = call.ends
        call.grantPending()
        if (call.ends != endsBeforeGrant + 1) {
            c.violations += "seed=$seed: end queued before the grant was not applied exactly once " +
                "(before=$endsBeforeGrant after=${call.ends})"
        }

        // A start() replacing a still-ungranted attempt must record that attempt's queued end
        // rather than lose it.
        conn.connect(endpoint, "user", null)
        conn.disconnect()
        awaitTrue(c.violations, "seed=$seed: a queued end never reached the fake before the supersede") {
            call.hasPendingEnd
        }
        val endsBeforeSupersede = call.ends
        conn.connect(endpoint, "user", null)   // synchronous call.start() must flush the queued end first
        if (call.ends != endsBeforeSupersede + 1) {
            c.violations += "seed=$seed: a still-ungranted attempt's queued end was lost across a " +
                "supersede (before=$endsBeforeSupersede after=${call.ends})"
        }
        call.grantPending()
        conn.disconnect()
        call.grantPending()
        awaitTrue(c.violations, "seed=$seed: fake still live after the queued-end tail's cleanup") {
            !call.hasLiveCall
        }

        // Gen-gating of endedByPlatform: a hangup for a superseded generation must not retire the
        // attempt that replaced it; one for the live generation must. endedBySystemFor runs the
        // onEnded closure inline, so the assertions are immediate — no polling.
        conn.connect(endpoint, "user", null)
        val staleGen = call.startedGens.last()
        conn.connect(endpoint, "user", null)   // supersedes staleGen
        val liveGen = call.startedGens.last()
        call.endedBySystemFor(staleGen)
        if (conn.status.value == ConnectionStatus.Idle) {
            c.violations += "seed=$seed: a hangup for superseded gen=$staleGen retired the live " +
                "attempt gen=$liveGen"
        }
        call.endedBySystemFor(liveGen)
        if (conn.status.value != ConnectionStatus.Idle) {
            c.violations += "seed=$seed: a hangup for the live gen=$liveGen did not retire it " +
                "(status=${conn.status.value})"
        }
    }

    @Test fun chaosStormNeverBreaksTheTelecomLifecycleInvariants() {
        val c = Counters()
        repeat(ROUNDS) { round ->
            val seed = BASE_SEED + round
            storm(seed, c)
            // Per round, so the first violation fails with the seed that reproduces it.
            assertTrue("round $round (seed=$seed) produced violations: ${c.violations}", c.violations.isEmpty())
        }
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
        const val BASE_SEED = 2_000_000L
        const val ROUNDS = 8
        const val HAMMER_THREADS = 4
        const val LIFECYCLE_OPS = 15
    }
}
