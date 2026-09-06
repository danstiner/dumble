package me.danielstiner.dumble.mumble.net

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which transport carries our own voice, decided on the UDP ping alone. A reply proves both
 * directions at once, and the server answers our ping over UDP whichever path voice is on, so
 * the evidence keeps arriving while tunneled. The cipher's counters are deliberately not read:
 * our encrypt counter advances whether or not a datagram lands.
 *
 * Receiving is not a choice. The server sends our downlink over UDP while our own audio last
 * arrived that way, and either socket feeds the same receiver.
 */
class VoicePath {
    /** The label and the number as one record, so a demote clears both in one emission. Read
     *  [onUdp], not nullness: a reply while tunneled sets [roundTrip] on the way to promotion. */
    data class State(val onUdp: Boolean = false, val roundTrip: Duration? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Seeded at one: the first promotion of a call takes a single reply, every one after a
    // demote takes two, or a path answering half its pings flaps, and on such a path the tunnel
    // is where voice belongs.
    private var replies = 1

    /** A reply dated by its own echo. Negative, or older than any ping still worth answering, is
     *  not ours: the same guard the TCP ping uses, since the stamp is the only matching there is.
     *  Returns whether the reply counted, so the caller can average what was accepted. */
    @Synchronized
    fun onPingAnswered(roundTrip: Duration): Boolean {
        if (roundTrip.isNegative() || roundTrip > MAX_PLAUSIBLE_ROUND_TRIP) return false
        replies++
        val onUdp = _state.value.onUdp || replies >= REPLIES_TO_PROMOTE
        if (onUdp && !_state.value.onUdp) Log.i(TAG, "voice over UDP, ${roundTrip.inWholeMilliseconds} ms")
        _state.value = State(onUdp, roundTrip)
        return true
    }

    /** The transport reported two pings unanswered, or the socket refused a datagram: voice goes
     *  back through the tunnel now, and the count toward UDP starts over. */
    @Synchronized
    fun demote() {
        replies = 0
        if (_state.value.onUdp) Log.i(TAG, "voice back on the tunnel")
        _state.value = State()
    }

    companion object {
        private const val TAG = "VoicePath"
        private const val REPLIES_TO_PROMOTE = 2
        val MAX_PLAUSIBLE_ROUND_TRIP = 30.seconds
    }
}
