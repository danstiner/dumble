package me.danielstiner.dumble.mumble.protocol

/**
 * One user's round trip to the server, as the server measures it — the half of the path we cannot
 * see from here. Ours is [SessionStateMachine.roundTripTime]; together they approximate the path
 * between two people in a call.
 *
 * Carries its own [session] because it arrives asynchronously: a reply for the user whose sheet
 * was just closed must not be read as belonging to whoever is on screen now.
 *
 * Both legs, because which one is populated says how that peer is carrying voice: [udpMillis] is
 * their real audio path, and a null there against a live [tcpMillis] means they are tunnelling.
 * Every peer of ours reads that way today — dumble has no UDP path yet — so this is built for the
 * measurement rather than by it. Null is "no reading", which the server reports as a zero average:
 * for a peer with no UDP, and for anyone the server has not pinged yet.
 */
data class UserPing(val session: Int, val tcpMillis: Float?, val udpMillis: Float?)
