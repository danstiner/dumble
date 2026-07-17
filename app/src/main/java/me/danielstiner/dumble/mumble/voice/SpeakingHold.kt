package me.danielstiner.dumble.mumble.voice

/**
 * Release-hold for the per-speaker "speaking" indicator. Fed the set of sessions that produced audio
 * on each playback tick; keeps a session in the "speaking" set for [holdTicks] further ticks after its
 * last produced frame so the indicator doesn't strobe on the ~20 ms tick cadence. Pure, single-threaded
 * (playback thread only); JVM-unit-testable.
 */
class SpeakingHold(private val holdTicks: Int = SPEAKING_HOLD_TICKS) {
    private val remaining = HashMap<Int, Int>()

    /**
     * Advance one tick with the sessions that produced audio this tick; return the held set. A
     * produced session is present this tick and for [holdTicks] further silent ticks, then dropped.
     */
    fun tick(produced: Set<Int>): Set<Int> {
        for (s in produced) remaining[s] = holdTicks   // (re)arm produced sessions to the full hold
        val present = HashSet(remaining.keys)          // present THIS tick (incl. just-armed)
        val it = remaining.entries.iterator()          // then age the holds for the next tick
        while (it.hasNext()) {
            val e = it.next()
            if (e.value <= 0) it.remove() else e.setValue(e.value - 1)
        }
        return present
    }

    /** Forget a session immediately (its stream retired). */
    fun drop(session: Int) { remaining.remove(session) }

    fun clear() { remaining.clear() }

    companion object { const val SPEAKING_HOLD_TICKS = 10 }   // ~200 ms at 20 ms/tick
}
