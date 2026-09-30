package me.danielstiner.dumble.ui.connect

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.time.TestTimeSource

/**
 * [ConnectViewModel]'s permission and speaking state under every interleaving a seed picks.
 *
 * The ViewModel is confined to Main: every public method is a UI-thread call in production, and
 * the connection reaches it only through `StateFlow` writes. So the race worth pinning is UI calls
 * against connection writes landing between the `uiState` combine's runs, not threads: each
 * schedule is a seeded mix of both, with the Main dispatcher run at random points so updates
 * arrive singly and coalesced. What it gives up is thread-level parallelism of the `StateFlow`
 * writes themselves, which is `StateFlow`'s contract rather than the ViewModel's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelChaosTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun noScheduleLetsPermissionOrSpeakingStateLie() = runTest(dispatcher) {
        repeat(ROUNDS) { schedule(BASE_SEED + it) }
    }

    private fun TestScope.schedule(seed: Long) {
        val r = Random(seed)
        val heldInitially = r.nextBoolean()
        var granted = heldInitially
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), TestTimeSource()) { heldInitially }
        try {
            // Connectable, so onConnect() reaches the connection instead of failing validation.
            vm.onHostChange("chaos.example"); vm.onUsernameChange("u"); vm.onPortChange("64738")
            repeat(STEPS) { step ->
                val at = "seed=$seed step=$step"
                when (r.nextInt(12)) {
                    0 -> { granted = true; vm.onMicrophonePermissionResult(true) }
                    1 -> { granted = false; vm.onMicrophonePermissionResult(false) }
                    2 -> vm.onTransmitting(r.nextBoolean())
                    3 -> vm.onConnect()
                    4 -> vm.onDisconnect()
                    5 -> if (r.nextBoolean()) vm.openChat() else vm.closeChat()
                    6 -> conn.status.value = randomStatus(r)
                    7 -> conn.speakingSessions.value = OTHER_SESSION_IDS.filter { r.nextBoolean() }.toSet()
                    8 -> conn.messages.value = List(r.nextInt(0, 4)) {
                        ChatMessage.Remote(OTHER_SESSION_IDS.random(r), "peer", "hi", Instant.EPOCH)
                    }
                    9 -> conn.selfSpeaking.value = r.nextBoolean()
                    else -> {
                        runCurrent()
                        assertEquals("$at: microphoneGranted", granted, vm.uiState.value.microphoneGranted)
                        assertEquals("$at: status", conn.status.value, vm.uiState.value.status)
                    }
                }
                assertNeverSpeaksWithoutMic(vm, at)
            }

            // A floor the random mix cannot promise: the merge must fire when all three line up.
            val own = OWN_SESSION_IDS[0]
            conn.status.value = ConnectionStatus.Connected(gen = 1, sessionId = own)
            vm.onMicrophonePermissionResult(true)
            conn.selfSpeaking.value = true
            runCurrent()
            assertTrue("seed=$seed: Connected, granted and speaking must show us speaking",
                own in vm.uiState.value.speakingSessions)
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    /**
     * The lie the combine's `takeIf` exists to prevent: our own session shown speaking while
     * microphoneGranted is false. Both fields come from one run of the combine, so a failure is
     * the logic, never the schedule.
     */
    private fun assertNeverSpeaksWithoutMic(vm: ConnectViewModel, at: String) {
        val s = vm.uiState.value
        val me = (s.status as? ConnectionStatus.Connected)?.sessionId ?: return
        if (me in s.speakingSessions && !s.microphoneGranted) {
            fail("$at: session $me shown speaking without the microphone: ${s.speakingSessions}")
        }
    }

    // Biased toward Connected, the only status the invariant reads.
    private fun randomStatus(r: Random): ConnectionStatus = when (r.nextInt(10)) {
        0 -> ConnectionStatus.Idle
        1 -> ConnectionStatus.Connecting
        2 -> ConnectionStatus.Handshaking
        in 3..7 -> ConnectionStatus.Connected(gen = 1, sessionId = OWN_SESSION_IDS.random(r))
        else -> ConnectionStatus.Error(ErrorKind.entries.random(r), null)
    }

    private companion object {
        const val BASE_SEED = 3_000_000L
        const val ROUNDS = 64
        const val STEPS = 128
        // Disjoint: OWN only ever comes from Connected, OTHER only from the server's speaking set,
        // so one of ours in speakingSessions can only be the ViewModel's own merge.
        val OWN_SESSION_IDS = listOf(101, 102, 103)
        val OTHER_SESSION_IDS = listOf(1, 2, 3, 4, 5)
    }
}
