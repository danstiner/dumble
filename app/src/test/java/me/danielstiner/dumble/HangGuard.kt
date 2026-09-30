package me.danielstiner.dumble

import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Fails a real-thread test that hangs, with the stuck thread's stack, instead of letting it eat
 * the CI job. A hang guard only: never raised to get a slow test through. Not for Robolectric
 * classes: the rule runs each test on a thread of its own, off Robolectric's main thread.
 */
fun hangGuard(): Timeout = Timeout.builder()
    .withTimeout(60, TimeUnit.SECONDS)
    .withLookingForStuckThread(true)
    .build()
