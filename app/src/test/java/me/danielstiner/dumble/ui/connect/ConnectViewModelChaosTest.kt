package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TestTimeSource
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Concurrent chaos test for [ConnectViewModel]'s permission and transmit state.
 *
 * Sibling to [me.danielstiner.dumble.mumble.connection.CaptureLifecycleChaosTest] and
 * [me.danielstiner.dumble.mumble.connection.TelecomLifecycleChaosTest] in shape (seeded per-round
 * storms, a shared violations sink, `awaitTrue` polling instead of a bare `delay`), but a different
 * concurrency model on purpose. Those two hammer a coordinator meant to be called from anywhere.
 * [ConnectViewModel] is not: every public method here is a UI-thread call in production (a Compose
 * click handler, a permission-result callback), so racing it from several threads would pin an
 * interleaving Android never produces. The real race is UI-thread actions against connection events
 * — status, speakingSessions, messages — landing on background threads and flowing into the
 * `uiState` combine. [storm] drives the UI side as a single coroutine confined to the ViewModel's
 * own dispatcher (real Compose callbacks never run anywhere else) and the connection side as three
 * real threads, one per field, each free-running against it.
 */
class ConnectViewModelChaosTest {

    private class Counters {
        val violations = CopyOnWriteArrayList<String>()
    }

    // Disjoint ranges, not overlapping pools: OWN is the only source of Connected(sessionId) in this
    // test and OTHER is the only source of the connection's (server-reported) speakingSessions, so
    // "our session id is in speakingSessions" can only ever be explained by uiState's own `me = ...`
    // merge — never by coincidence with an unrelated speaker sharing the id.
    private val OWN_SESSION_IDS = listOf(101, 102, 103)
    private val OTHER_SESSION_IDS = listOf(1, 2, 3, 4, 5)

    /**
     * One randomized concurrent storm against a fresh ViewModel, then a deterministic tail that
     * forces one legitimate self-speaking window — see the comment at that call site for why the
     * tail exists rather than trusting the random storm to align on its own.
     */
    private fun storm(seed: Long, c: Counters) = runBlocking {
        // A real single-threaded executor standing in for Android's main thread: viewModelScope
        // resolves to Dispatchers.Main.immediate, and setMain is what makes that resolve to
        // anything at all on the JVM. Single-threaded, not StandardTestDispatcher's virtual-time
        // queue, because this storm's whole point is real background threads racing real timing
        // against it.
        val uiExecutor = Executors.newSingleThreadExecutor { Thread(it, "chaos-ui").apply { isDaemon = true } }
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(uiDispatcher)
        try {
            val heldInitially = Random(seed).nextBoolean()
            val conn = FakeConnection()
            val vm = ConnectViewModel(conn, FakeConfigStore(null), TestTimeSource()) { heldInitially }

            // A connectable draft so onConnect() picks below actually reach conn.connect() instead
            // of dead-ending on port/host validation, which is not this test's concern.
            withContext(Dispatchers.Main) {
                vm.onHostChange("chaos.example"); vm.onUsernameChange("u"); vm.onPortChange("64738")
            }

            val lastStatus = AtomicReference<ConnectionStatus>(ConnectionStatus.Idle)
            val stop = AtomicBoolean(false)

            // Connection events: one thread per field, not a shared pool. A shared writer would
            // race itself for which value "the last one" was — the same reasoning
            // CaptureLifecycleChaosTest's storm() gives for pulling connect/disconnect onto their
            // own thread — and the status-convergence check below needs that to be well-defined.
            val statusThread = Thread {
                val r = Random(seed * 1_000_003L + 1)
                while (!stop.get()) {
                    try {
                        val s = randomStatus(r)
                        lastStatus.set(s)
                        conn.status.value = s
                    } catch (e: Throwable) {
                        c.violations += "seed=$seed thread=status: op threw $e"
                    }
                    Thread.sleep(r.nextLong(1, 3))
                }
            }.apply { isDaemon = true; start() }

            val speakingThread = Thread {
                val r = Random(seed * 1_000_003L + 2)
                while (!stop.get()) {
                    try {
                        conn.speakingSessions.value = OTHER_SESSION_IDS.filter { r.nextBoolean() }.toSet()
                    } catch (e: Throwable) {
                        c.violations += "seed=$seed thread=speaking: op threw $e"
                    }
                    Thread.sleep(r.nextLong(1, 3))
                }
            }.apply { isDaemon = true; start() }

            val messagesThread = Thread {
                val r = Random(seed * 1_000_003L + 3)
                while (!stop.get()) {
                    try {
                        conn.messages.value = List(r.nextInt(0, 4)) {
                            ChatMessage.Remote(OTHER_SESSION_IDS.random(r), "peer", "hi", Instant.EPOCH)
                        }
                    } catch (e: Throwable) {
                        c.violations += "seed=$seed thread=messages: op threw $e"
                    }
                    Thread.sleep(r.nextLong(1, 3))
                }
            }.apply { isDaemon = true; start() }

            // The safety-net checker: read-only, so it needs no confinement to any one thread, and
            // runs on its own for the whole storm (including the deterministic tail) rather than
            // sharing a stop flag with the UI role, so it watches that window too.
            val checkerStop = AtomicBoolean(false)
            val checkerThread = Thread {
                while (!checkerStop.get()) {
                    checkNeverSpeaksWithoutMic(vm, c, seed)
                    Thread.sleep(1)
                }
                checkNeverSpeaksWithoutMic(vm, c, seed)
            }.apply { isDaemon = true; start() }

            // The UI role: one coroutine confined to the ViewModel's own dispatcher, since a real
            // screen never calls these from anywhere else — see the class doc for why this is not
            // a hammer-thread pool like the sibling tests use for their coordinator-level ops.
            val uiJob = launch(Dispatchers.Main) {
                try {
                    val r = Random(seed * 1_000_003L + 4)
                    var expectedMic = heldInitially
                    repeat(UI_OPS) {
                        val pick = r.nextInt(8)
                        try {
                            when (pick) {
                                0 -> { expectedMic = true; vm.onMicrophonePermissionResult(true) }
                                1 -> { expectedMic = false; vm.onMicrophonePermissionResult(false) }
                                2 -> vm.onTransmitting(true)
                                3 -> vm.onTransmitting(false)
                                4 -> vm.onConnect()
                                5 -> vm.onDisconnect()
                                6 -> vm.openChat()
                                else -> vm.closeChat()
                            }
                        } catch (e: Throwable) {
                            c.violations += "seed=$seed thread=ui: op $pick threw $e"
                        }
                        if (pick == 0 || pick == 1) {
                            // The core invariant: the answer just supplied is the answer shown,
                            // promptly, no matter what the three threads above are doing to
                            // status/speaking/messages at the same moment. Boolean has no "not
                            // asked" value to regress to any more — convergence to the last answer
                            // is the whole property left to pin.
                            awaitTrue(
                                c.violations,
                                "seed=$seed: microphoneGranted did not converge to $expectedMic " +
                                    "after onMicrophonePermissionResult($expectedMic)",
                            ) { vm.uiState.value.microphoneGranted == expectedMic }
                        }
                        delay(r.nextLong(1, 3))
                    }
                } catch (e: Throwable) {
                    c.violations += "seed=$seed thread=ui: fatal $e"
                }
            }
            uiJob.join()
            stop.set(true)

            listOf(statusThread, speakingThread, messagesThread).forEach { it.join(10_000) }
            listOf(statusThread, speakingThread, messagesThread).forEach {
                if (it.isAlive) c.violations += "seed=$seed: a worker thread did not finish"
            }

            // Quiescence: status converges to whatever the status thread's very last write was —
            // the one-writer-per-field design above is what makes "very last write" well-defined.
            awaitTrue(
                c.violations,
                "seed=$seed: uiState.status did not converge to ${lastStatus.get()} once the storm settled",
            ) { vm.uiState.value.status == lastStatus.get() }

            // Deterministic tail, not left to the storm's chance: the random mix above almost
            // always produces *some* Connected+transmitting+granted overlap, but "almost always"
            // is worthless as a coverage floor — a broken merge that never fires would still pass
            // every round vacuously. Force the positive case once, cleanly, after the background
            // threads are already quiet so nothing is racing this specific window.
            val ownId = OWN_SESSION_IDS[0]
            conn.status.value = ConnectionStatus.Connected(ownId)
            awaitTrue(c.violations, "seed=$seed: status did not settle to Connected for the deterministic tail") {
                vm.uiState.value.status == ConnectionStatus.Connected(ownId)
            }
            withContext(Dispatchers.Main) { vm.onMicrophonePermissionResult(true) }
            awaitTrue(c.violations, "seed=$seed: microphoneGranted did not converge to true in the deterministic tail") {
                vm.uiState.value.microphoneGranted
            }
            withContext(Dispatchers.Main) { vm.onTransmitting(true) }
            awaitTrue(
                c.violations,
                "seed=$seed: self session never appeared in speakingSessions once " +
                    "Connected+transmitting+granted all held",
            ) { ownId in vm.uiState.value.speakingSessions }
            withContext(Dispatchers.Main) { vm.onTransmitting(false) }

            checkerStop.set(true)
            checkerThread.join(5_000)
            if (checkerThread.isAlive) c.violations += "seed=$seed: checker thread did not finish"
        } finally {
            Dispatchers.resetMain()
            uiExecutor.shutdownNow()
        }
    }

    /**
     * The lie the combine's `takeIf` exists to prevent: our own session must never show as
     * speaking while microphoneGranted is false. Airtight regardless of scheduling — both fields
     * come from the same `combine` lambda invocation in [ConnectViewModel.uiState], so there is no
     * interleaving that could desync them; a failure here means the `takeIf` logic itself is wrong,
     * not that this poll got unlucky.
     */
    private fun checkNeverSpeaksWithoutMic(vm: ConnectViewModel, c: Counters, seed: Long) {
        val s = vm.uiState.value
        val status = s.status
        if (status is ConnectionStatus.Connected && status.sessionId in s.speakingSessions && !s.microphoneGranted) {
            c.violations += "seed=$seed: session ${status.sessionId} shown speaking with " +
                "microphoneGranted=false — speakingSessions=${s.speakingSessions}"
        }
    }

    // Biased toward Connected: that is the only status the invariants above care about, and a
    // storm that spent most of its time in Idle/Connecting would rarely exercise them.
    private fun randomStatus(r: Random): ConnectionStatus = when (r.nextInt(10)) {
        0 -> ConnectionStatus.Idle
        1 -> ConnectionStatus.Connecting
        2 -> ConnectionStatus.Handshaking
        in 3..7 -> ConnectionStatus.Connected(OWN_SESSION_IDS.random(r))
        else -> ConnectionStatus.Error(ErrorKind.entries.random(r), null)
    }

    @Test fun chaosStormNeverLetsPermissionOrTransmitStateLie() {
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
        const val BASE_SEED = 3_000_000L
        const val ROUNDS = 8
        const val UI_OPS = 24
    }
}
