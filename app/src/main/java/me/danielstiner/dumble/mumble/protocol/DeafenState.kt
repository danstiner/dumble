package me.danielstiner.dumble.mumble.protocol

/**
 * What the user last asked for: a deafen, and a mute of their own. Deliberately the ask rather than
 * the server's echo, which lags a tap by a round trip and is absent through a reconnect.
 *
 * The wire's `self_mute` is derived rather than stored: murmur forces it on with `self_deaf`, and a
 * stored copy cannot tell that mute from the user's own, which is what decides whether an undeafen
 * reopens the microphone — under voice activity, a hot mic. Desktop Mumble's rules and this
 * class's one departure from them: `docs/mumble-protocol.md`, Self mute and deafen.
 */
data class DeafenState(
    val deafened: Boolean = false,
    val ownMute: Boolean = false,
) {
    /** `self_mute` on the wire: a deafen mutes too. */
    val muted: Boolean get() = ownMute || deafened

    fun deafen(on: Boolean): DeafenState = copy(deafened = on)

    /** Unmuting while deafened undeafens too, as murmur forces. A mute asked for under a deafen
     *  outlives it. */
    fun mute(on: Boolean): DeafenState = if (on) copy(ownMute = true) else DeafenState()
}
