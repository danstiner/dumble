package me.danielstiner.dumble.mumble.protocol

/**
 * What we last put on the wire for deafen, and whether an undeafen still owes an unmute.
 *
 * [selfDeaf] and [selfMute] are the two `UserState` fields verbatim; [unmuteOnUndeaf] never leaves
 * this process. Deliberately what we *sent* rather than what the server echoed — see [deafen].
 */
data class DeafenState(
    val selfDeaf: Boolean = false,
    val selfMute: Boolean = false,
    val unmuteOnUndeaf: Boolean = false,
) {
    /**
     * The state after deafening or undeafening, mirroring desktop Mumble's `unmuteOnUndeaf`
     * (`src/mumble/MainWindow.cpp`, `on_qaAudioDeaf_triggered`): deafen forces mute, and undeafen
     * unmutes only if the deafen was what set the mute — so a manual mute survives
     * mute -> deafen -> undeafen instead of the microphone reopening silently. Dumble has no mute
     * control yet; the rule is here ahead of one because voice activity detection is what makes the
     * hazard real. Under push-to-talk the gate is closed by default and a cleared mute costs
     * nothing; under VAD the microphone is live and the same slip is a hot mic.
     *
     * Apply to what we last sent, never to what the server last echoed. Applied to the echo-lagged
     * value, a second undeafen arriving inside one round trip runs against state the first one
     * already moved and sends `self_mute=true`, which strands the user muted with nothing able to
     * clear it: every later deafen then computes `unmuteOnUndeaf = false`, so every later undeafen
     * re-sends it, and only a reconnect recovers.
     */
    fun deafen(on: Boolean): DeafenState =
        if (on) DeafenState(selfDeaf = true, selfMute = true, unmuteOnUndeaf = !selfMute)
        else DeafenState(selfDeaf = false, selfMute = !unmuteOnUndeaf && selfMute, unmuteOnUndeaf = false)

    /** Mute or unmute. Unmuting while deafened undeafens too, through [deafen] — murmur forces
     *  the two together — and its `unmuteOnUndeaf` rule decides whether the mute stays. */
    fun mute(on: Boolean): DeafenState =
        if (!on && selfDeaf) deafen(false) else copy(selfMute = on)
}
