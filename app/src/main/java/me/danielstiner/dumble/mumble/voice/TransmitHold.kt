package me.danielstiner.dumble.mumble.voice

/**
 * Release-hold for the local "you are transmitting" indicator. Fed whether a real (non-terminator)
 * frame was sent on each capture; stays true for [holdTicks] captures after the last real send so the
 * indicator doesn't flicker between talkspurt frames. Pure, single-threaded (send thread only).
 */
class TransmitHold(private val holdTicks: Int = TRANSMIT_HOLD_TICKS) {
    private var remaining = 0

    /** Advance one capture; [sending] = a real (non-terminator) frame went out this capture. */
    fun update(sending: Boolean): Boolean {
        remaining = if (sending) holdTicks else (remaining - 1).coerceAtLeast(0)
        return remaining > 0
    }

    fun clear() { remaining = 0 }

    companion object { const val TRANSMIT_HOLD_TICKS = 10 }   // ~200 ms at 20 ms/capture
}
