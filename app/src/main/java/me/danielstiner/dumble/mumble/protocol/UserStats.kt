package me.danielstiner.dumble.mumble.protocol

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
    val tcpPingMillis: Float?,
    val udpPingMillis: Float?,
    val tcpJitterMillis: Float?,
    val udpJitterMillis: Float?,
    val bandwidthBitsPerSecond: Int?,
) {
    /** Jitter on the leg actually carrying voice — see [udpPingMillis] for how that is decided. */
    val jitterMillis: Float? get() = if (udpPingMillis != null) udpJitterMillis else tcpJitterMillis
}
