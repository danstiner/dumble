package me.danielstiner.dumble.mumble.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which transport carries our own voice, decided on the UDP ping alone. A reply proves both
 * directions at once, and the server answers our ping over UDP whichever path voice is on, so the
 * evidence keeps arriving while tunneled. The cipher's counters are deliberately not read: our
 * encrypt counter advances whether or not a datagram lands, so it says nothing about the uplink.
 *
 * Receiving is not a choice. The server sends our downlink over UDP while our own audio last
 * arrived that way, and either socket feeds the same receiver.
 *
 * Replies land on the UDP receive thread, the unanswered report on the ping ticker, a failed
 * send on the voice pump.
 */
class VoicePath {
    /**
     * The label and the number as one record, so a demote clears both in one emission and a
     * stale UDP round trip can never sit under a tunnel label. [roundTrip] is the last accepted
     * reply, present while tunneled too once a reply has arrived, so read [onUdp], not nullness.
     */
    data class State(val onUdp: Boolean = false, val roundTrip: Duration? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // The first promotion of a call takes one reply; every one after a demote takes two, or a
    // path answering half its pings flaps, and on such a path the tunnel is where voice belongs.
    private var answeredSinceDemote = 0
    private var everDemoted = false

    /** A reply dated by its own echo. One implausibly old is not ours, the same guard the TCP ping
     *  uses: the stamp is the only matching there is. */
    @Synchronized
    fun onPingAnswered(roundTrip: Duration) {
        if (roundTrip.isNegative() || roundTrip > MAX_PLAUSIBLE_ROUND_TRIP) return
        answeredSinceDemote++
        val needed = if (everDemoted) 2 else 1
        _state.value = State(onUdp = _state.value.onUdp || answeredSinceDemote >= needed, roundTrip = roundTrip)
    }

    /** The transport's report: two pings in a row unanswered. Ends a re-promotion streak too. */
    @Synchronized
    fun onPingsUnanswered() {
        answeredSinceDemote = 0
        if (_state.value.onUdp) demote()
    }

    /** The socket refused a datagram: a route gone from under us, not worth two pings' wait. */
    @Synchronized
    fun onSendFailed() {
        if (_state.value.onUdp) demote()
    }

    private fun demote() {
        _state.value = State()
        answeredSinceDemote = 0
        everDemoted = true
    }

    companion object {
        val MAX_PLAUSIBLE_ROUND_TRIP = 30.seconds
    }
}
