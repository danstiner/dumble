package me.danielstiner.dumble.mumble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class PingAverageTest {

    /** Three replies: mean 20, population variance 200/3, as the desktop reports them. */
    @Test fun meanAndVarianceOverTheSession() {
        val a = PingAverage()
        listOf(10, 20, 30).forEach { a.add(it.milliseconds) }
        val r = a.report()
        assertEquals(3, r.count)
        assertEquals(20f, r.meanMillis, 1e-4f)
        assertEquals(200f / 3, r.varianceMillis, 1e-3f)
    }

    /** Sub-millisecond replies keep their fraction: a LAN's 0.3 ms is a reading, not a zero. */
    @Test fun theMeanKeepsTheFraction() {
        val a = PingAverage()
        a.add(300.microseconds)
        assertEquals(0.3f, a.report().meanMillis, 1e-6f)
    }

    /** The window forgets: a session's slow first reply is gone twenty replies later, while the
     *  count still says how many there were. */
    @Test fun theWindowForgetsTheFirstReply() {
        val a = PingAverage()
        a.add(500.milliseconds)
        repeat(PingAverage.WINDOW) { a.add(3.milliseconds) }
        val r = a.report()
        assertEquals(PingAverage.WINDOW + 1, r.count)
        assertEquals(3f, r.meanMillis, 1e-6f)
        assertEquals(0f, r.varianceMillis, 1e-6f)
    }

    @Test fun nothingCountedIsNothingReported() {
        assertEquals(PingAverage.Report(0, 0f, 0f), PingAverage().report())
    }
}
