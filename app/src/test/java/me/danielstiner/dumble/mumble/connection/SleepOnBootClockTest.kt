package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.danielstiner.dumble.time.AtomicTimeSource
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The relink ladder's waits and its deadline have to agree on what a second is, or a device that
 * dozes inside a rung comes back to a wait still counting and a deadline already spent.
 */
class SleepOnBootClockTest {

    @Test fun aClockThatStandsStillHoldsTheWait() = runBlocking {
        val clock = AtomicTimeSource()
        val job = launch(Dispatchers.Default) { sleepOnBootClock(10.seconds, clock, slice = 20.milliseconds) }
        delay(100)                                   // several slices, none of which the clock counts
        assertTrue("the wait must still be running", job.isActive)
        clock += 10.seconds
        withTimeout(1_000) { job.join() }
    }

    /** The doze: the boot clock jumps, and the wait is over the moment the slice notices. */
    @Test fun aJumpInTheClockEndsTheWait() = runBlocking {
        val clock = AtomicTimeSource()
        val job = launch(Dispatchers.Default) { sleepOnBootClock(2.minutes, clock, slice = 20.milliseconds) }
        delay(40)
        clock += 2.minutes
        withTimeout(1_000) { job.join() }
    }

    /** Rung 0 is a wait of nothing, and has to cost nothing. */
    @Test fun aZeroWaitReturnsAtOnce() = runBlocking {
        val clock = AtomicTimeSource()
        withTimeout(500) { sleepOnBootClock(Duration.ZERO, clock) }
    }
}
