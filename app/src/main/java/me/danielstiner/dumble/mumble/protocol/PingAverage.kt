package me.danielstiner.dumble.mumble.protocol

import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Mean and variance of one leg's last [WINDOW] ping replies, in milliseconds, for the report our
 * TCP ping carries. The server stores what we send in our UserStats, so the UDP average being
 * present is how a peer learns our voice has a UDP path.
 *
 * A window rather than the desktop's session-long accumulator: a session's first reply lands
 * while the client is still busy with the roster (350–620 ms on the emulator against 3 ms after)
 * and would sit in a session mean, and its variance, for many minutes. Twenty replies is a
 * hundred seconds at the ping interval. The count is still the session's, as the desktop's
 * `udp_packets`/`tcp_packets` are — ping replies rather than voice, whatever the field names say.
 */
class PingAverage {
    private val window = DoubleArray(WINDOW)
    private var count = 0

    @Synchronized
    fun add(roundTrip: Duration) {
        window[count % WINDOW] = roundTrip.toDouble(DurationUnit.MILLISECONDS)
        count++
    }

    /** Population variance, as the desktop reports it. Both floats are 0 until something has
     *  been counted, and the wire leaves them unset then rather than claim a zero round trip. */
    @Synchronized
    fun report(): Report {
        val n = minOf(count, WINDOW)
        if (n == 0) return Report(0, 0f, 0f)
        val mean = (0 until n).sumOf { window[it] } / n
        val variance = (0 until n).sumOf { (window[it] - mean) * (window[it] - mean) } / n
        return Report(count, mean.toFloat(), variance.toFloat())
    }

    data class Report(val count: Int, val meanMillis: Float, val varianceMillis: Float)

    companion object {
        const val WINDOW = 20
    }
}
