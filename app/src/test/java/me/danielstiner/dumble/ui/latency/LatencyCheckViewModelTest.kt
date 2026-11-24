@file:OptIn(ExperimentalCoroutinesApi::class)

package me.danielstiner.dumble.ui.latency

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.latency.CheckOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LatencyCheckViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Rig(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val runs = mutableListOf<Pair<MumbleEndpoint, String>>()
        val reported = mutableListOf<CheckOutcome>()
        var answer = CompletableDeferred<CheckOutcome>()
        val vm = LatencyCheckViewModel(
            run = { endpoint, fingerprint, progress ->
                runs += endpoint to fingerprint
                progress("halfway")
                answer.await()
            },
            report = { reported += it; "report ${reported.size}" },
            dispatcher = dispatcher,
        )
    }

    @Test fun startShowsRunningBeforeItReturnsThenRunsToItsResult() = runTest(dispatcher) {
        val rig = Rig(dispatcher)
        rig.vm.start("127.0.0.1", 9, "ab")
        assertEquals(LatencyCheckState.Running("starting"), rig.vm.state.value)
        runCurrent()
        assertEquals(LatencyCheckState.Running("halfway"), rig.vm.state.value)
        assertEquals(listOf(MumbleEndpoint.parse("127.0.0.1", 9).address to "ab"), rig.runs.map { it.first.address to it.second })

        val failed = CheckOutcome.Failed("no server", null)
        rig.answer.complete(failed)
        runCurrent()
        assertEquals(listOf<CheckOutcome>(failed), rig.reported)
        assertEquals(LatencyCheckState.Done("report 1"), rig.vm.state.value)
    }

    @Test fun missingOrBadExtrasAreRefusedWithTheReasonAndNothingRuns() = runTest(dispatcher) {
        for ((host, port, fingerprint, reason) in listOf(
            Args(null, 9, "ab", "missing extra: host"),
            Args("h", 9, null, "missing extra: fingerprint"),
            Args("h", 70000, "ab", "bad extra: port out of range: 70000"),
        )) {
            val rig = Rig(dispatcher)
            rig.vm.start(host, port, fingerprint)
            runCurrent()
            assertEquals(emptyList<Any>(), rig.runs)
            assertEquals(listOf<CheckOutcome>(CheckOutcome.Refused(reason)), rig.reported)
            assertEquals(LatencyCheckState.Done("report 1"), rig.vm.state.value)
        }
    }

    @Test fun dismissingAFinishedPageReturnsToIdleButARunningOneStays() = runTest(dispatcher) {
        val rig = Rig(dispatcher)
        rig.vm.start("h", 9, "ab")
        runCurrent()
        rig.vm.dismiss()
        assertEquals(LatencyCheckState.Running("halfway"), rig.vm.state.value)
        rig.answer.complete(CheckOutcome.Refused("x"))
        runCurrent()
        rig.vm.dismiss()
        assertEquals(LatencyCheckState.Idle, rig.vm.state.value)
    }

    private data class Args(val host: String?, val port: Int?, val fingerprint: String?, val reason: String)
}
