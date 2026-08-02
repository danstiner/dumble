package me.danielstiner.dumble.ui.connect

/**
 * Milliseconds from an arbitrary origin, only ever increasing.
 *
 * Injected rather than called directly so [ConnectViewModel] stays plain-JVM testable, and
 * monotonic rather than wall clock because it anchors the call duration: `currentTimeMillis` moves
 * under an NTP correction or a user clock change, which would drag the displayed duration with it
 * — including backwards. Production binds `SystemClock.elapsedRealtime`, which also counts deep
 * sleep, unlike `uptimeMillis`/`nanoTime`; a call can run with the screen off.
 */
fun interface MonotonicClock {
    fun millis(): Long
}
