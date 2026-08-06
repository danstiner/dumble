package me.danielstiner.dumble.mumble.voice

/**
 * Whether the audio control is a picker or a plain speaker toggle.
 *
 * The stock phone app switches on Bluetooth *availability*, not on what is active, and not on wired:
 * `SpeakerButtonInfo` computes `nonBluetoothMode` as
 * `(audioState.getSupportedRouteMask() & ROUTE_BLUETOOTH) != ROUTE_BLUETOOTH`, and
 * `CallButtonPresenter.toggleSpeakerphone` refuses to toggle at all once Bluetooth is supported
 * ("toggling speakerphone not allowed when bluetooth supported"). Verified against the AOSP Dialer
 * sources, not inherited from the prototype — the prototype triggered on Bluetooth *or* wired, which
 * stock does not do.
 */
fun routeMenuNeeded(available: List<AudioRoute>) =
    available.any { it.type == AudioRoute.Type.BLUETOOTH }

/**
 * What a speaker toggle switches to, or null when there is nothing to switch to.
 *
 * Mirrors the stock app's `ROUTE_WIRED_OR_EARPIECE`: leaving the speaker lands on a wired headset if
 * one is plugged in, and the earpiece otherwise. Only reachable when [routeMenuNeeded] is false, so
 * no Bluetooth device can be in [available] here.
 */
fun speakerToggleTarget(available: List<AudioRoute>, current: AudioRoute?): AudioRoute? =
    if (current?.type == AudioRoute.Type.SPEAKER) {
        available.firstOrNull { it.type == AudioRoute.Type.WIRED_HEADSET }
            ?: available.firstOrNull { it.type == AudioRoute.Type.EARPIECE }
    } else {
        available.firstOrNull { it.type == AudioRoute.Type.SPEAKER }
    }
