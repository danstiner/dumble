package me.danielstiner.dumble.time

import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The clock that keeps counting while the device is asleep.
 *
 * This is the one to anchor anything measured against the outside world — a call's duration, how
 * long a link has been silent — because those are judged in real elapsed time and a phone with the
 * screen off spends most of its life suspended. The alternatives both stop with the CPU:
 * `System.nanoTime` and [TimeSource.Monotonic] built on it, and `SystemClock.uptimeMillis`. Use
 * those only to measure something about our own execution, where sleep genuinely is not elapsed
 * time — see `SessionStateMachine`'s ticker-stall check, which wants exactly that.
 *
 * Backed by `SystemClock.elapsedRealtimeNanos`, which on Android is `clock_gettime(CLOCK_BOOTTIME)`
 * — see `system/core/libutils/SystemClock.cpp`, where `uptimeNanos` reads `SYSTEM_TIME_MONOTONIC`
 * by contrast. The behaviour is the contract and the clock id is the mechanism: Android's own
 * javadoc promises only "include deep sleep" and names no POSIX clock, and the same native file
 * falls back to a monotonic source off Linux. That fallback cannot reach us — this is an
 * Android-only module — but it is why the name describes what the clock counts rather than which
 * syscall produced it.
 *
 * Nanoseconds rather than milliseconds because `elapsedRealtime()` is itself
 * `nanoseconds_to_milliseconds(elapsedRealtimeNano())`, so milliseconds would be a lossy division
 * of the same reading. Callers never see the unit — they hold a [kotlin.time.TimeMark] and get a
 * [kotlin.time.Duration] — so there is nothing to be gained by rounding first, and this matches
 * `TestTimeSource`, which is nanosecond-granularity too.
 *
 * [TimeSource.WithComparableMarks] rather than plain [TimeSource]: marks are held in UI state,
 * which is compared for equality on every emission, and a plain `TimeMark` has no `equals`.
 */
object BootTimeSource : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}
