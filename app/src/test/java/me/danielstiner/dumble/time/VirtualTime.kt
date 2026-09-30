package me.danielstiner.dumble.time

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/** Virtual time passes; every timer due in it fires, in order, the ones due at its last instant
 *  included — which advanceTimeBy alone leaves queued. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.elapse(d: Duration) {
    advanceTimeBy(d)
    runCurrent()
}
