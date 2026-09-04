package me.danielstiner.dumble.time

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A test clock one thread advances while another reads it, which `TestTimeSource` is not built
 * for. Starts away from zero so a mark taken at construction is not confused with an unset one.
 */
class AtomicTimeSource(start: Duration = 1.seconds) : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    private val reading = AtomicLong(start.inWholeNanoseconds)
    override fun read(): Long = reading.get()
    operator fun plusAssign(step: Duration) { reading.addAndGet(step.inWholeNanoseconds) }
}
