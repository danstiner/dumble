package me.danielstiner.dumble.mumble.voice

/**
 * Release-hold for the local "you are transmitting" indicator. Fed whether a real (non-terminator)
 * frame was sent on each capture; stays true on a sending capture and for [holdTicks] further captures
 * after the last send (consistent with [SpeakingHold]), so the indicator doesn't flicker between
 * talkspurt frames. Pure, single-threaded (send thread only).
 */
class TransmitHold(private val holdTicks: Int = TRANSMIT_HOLD_TICKS) {
    private var remaining = -1   // <0 = not held

    /** Advance one capture; [sending] = a real (non-terminator) frame went out this capture. */
    fun update(sending: Boolean): Boolean {
        if (sending) remaining = holdTicks
        if (remaining < 0) return false
        remaining--          // present this capture, then age (holdTicks further after a send)
        return true
    }

    fun clear() { remaining = -1 }

    companion object { const val TRANSMIT_HOLD_TICKS = 10 }   // ~200 ms at 20 ms/capture
}
