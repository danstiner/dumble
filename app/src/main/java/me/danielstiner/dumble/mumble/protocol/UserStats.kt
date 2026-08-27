package me.danielstiner.dumble.mumble.protocol

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.microseconds

/**
 * What the server measures about one user — the half of the path we cannot see from here. Ours is
 * [SessionStateMachine.roundTripTime]; together they approximate the path between two people.
 *
 * Carries its own [session] because it arrives asynchronously: a reply for the user whose sheet
 * was just closed must not be read as belonging to whoever is on screen now.
 *
 * Both ping legs, because which one the server has says how that peer carries voice: murmur
 * exchanges UDP pings only with a peer that has a working UDP path. Null is "no reading", which the
 * server reports as a zero average — for a peer with no UDP, and for anyone it has not pinged yet.
 *
 * Jitter is a standard deviation, from a wire field the proto documents as a variance, so it is
 * the square root of what arrives. Held per leg and read beside the one carrying voice.
 */
data class UserStats(
    val session: Int,
    val tcpPing: Duration?,
    val udpPing: Duration?,
    val tcpJitter: Duration?,
    val udpJitter: Duration?,
    val bandwidthBitsPerSecond: Int?,
) {
    /** Jitter on the leg actually carrying voice — see [udpPing] for how that is decided. */
    val jitter: Duration? get() = if (udpPing != null) udpJitter else tcpJitter

    companion object {
        /** Zero is how the server reports "no reading" — a leg it has not pinged — not a round
         *  trip of no time, so every zero below becomes null. */
        fun from(p: MumbleProtos.UserStats) = UserStats(
            session = p.session,
            tcpPing = wireMillis(p.tcpPingAvg).takeUnless { it == ZERO },
            udpPing = wireMillis(p.udpPingAvg).takeUnless { it == ZERO },
            // The wire carries a variance; jitter is its square root.
            tcpJitter = wireMillis(sqrt(p.tcpPingVar)).takeUnless { it == ZERO },
            udpJitter = wireMillis(sqrt(p.udpPingVar)).takeUnless { it == ZERO },
            bandwidthBitsPerSecond = p.bandwidth.takeUnless { it == 0 },
        )

        /**
         * A wire float of milliseconds, rounded to the microsecond. Not `toDouble().milliseconds`:
         * that is exact, and exactness is the problem — 18.2f is 18.200000762939453, and a Duration
         * to the nanosecond would keep digits the wire never measured. A microsecond is finer than
         * the average and coarser than the float's noise.
         */
        private fun wireMillis(millis: Float): Duration = (millis * 1000f).roundToLong().microseconds
    }
}
