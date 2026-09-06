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
 *
 * [uplink] is the server's own count of the datagrams it received from this user, sorted by its
 * cipher — a measurement of their voice arriving, where the pings are each client's self-report
 * echoed back. Murmur sends it for ourselves and for a peer in our channel. A 1.5 server also
 * sends a rolling window, which is the one kept: a bad minute an hour ago is not what a sheet
 * open now is asking about.
 */
data class UserStats(
    val session: Int,
    val tcpPing: Duration?,
    val udpPing: Duration?,
    val tcpJitter: Duration?,
    val udpJitter: Duration?,
    val bandwidthBitsPerSecond: Int?,
    val uplink: PacketCounts? = null,
) {
    /** Jitter on the leg actually carrying voice — see [udpPing] for how that is decided. */
    val jitter: Duration? get() = if (udpPing != null) udpJitter else tcpJitter

    /** Datagrams as the server's cipher sorted them; [lost] are the sequence gaps it never filled. */
    data class PacketCounts(val good: Int, val late: Int, val lost: Int, val resync: Int) {
        /** [lost] as a share of everything the server expected; null before it has seen any. */
        val lossFraction: Double?
            get() = (good + late + lost).takeIf { it > 0 }?.let { lost.toDouble() / it }
    }

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
            uplink = when {
                p.hasRollingStats() && p.rollingStats.hasFromClient() -> p.rollingStats.fromClient
                p.hasFromClient() -> p.fromClient
                else -> null
            }?.let { PacketCounts(it.good, it.late, it.lost, it.resync) },
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
