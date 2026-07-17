package me.danielstiner.dumble.mumble.voice

/**
 * Hot-mic-safe deafen ↔ mute coupling (mirrors Mumble's bAutoUnmute). Deafen forces mute; un-deafen
 * auto-unmutes ONLY if the deafen was what set the mute — a pre-existing manual mute must survive a
 * `mute → deafen → un-deafen` sequence so the mic never silently reopens.
 */
object DeafenLogic {
    data class Result(val muted: Boolean, val deafenSetMute: Boolean)

    fun onSetDeafened(deafen: Boolean, curMuted: Boolean, curDeafenSetMute: Boolean): Result =
        if (deafen) {
            Result(muted = true, deafenSetMute = !curMuted)          // "we set it" only if it wasn't already muted
        } else {
            Result(muted = if (curDeafenSetMute) false else curMuted, deafenSetMute = false)
        }
}
