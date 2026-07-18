package me.danielstiner.dumble.telecom

import androidx.core.telecom.CallEndpointCompat

/**
 * Pure mapping of a Telecom [CallEndpointCompat]'s type to a human-readable audio-route label, shown
 * as a read-only route indicator on the call screen (a stopgap until the call-screen redesign hosts a
 * first-class route control). The referenced `TYPE_*` values are compile-time `const val` constants, so
 * this has no Android runtime dependency and is JVM-unit-testable.
 */
object AudioRoute {
    /**
     * Display label for an endpoint [type], preferring the Bluetooth device [name] when present
     * (a null/blank name falls back to the generic "Bluetooth" label).
     */
    fun label(type: Int, name: CharSequence? = null): String = when (type) {
        CallEndpointCompat.TYPE_BLUETOOTH ->
            name?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
        CallEndpointCompat.TYPE_WIRED_HEADSET -> "Wired headset"
        CallEndpointCompat.TYPE_EARPIECE -> "Earpiece"
        CallEndpointCompat.TYPE_SPEAKER -> "Speaker"
        else -> "Unknown"
    }

    /** Stable route-icon key for the Speaker control (mapped to a Material icon in the UI). */
    enum class RouteIcon { BLUETOOTH, WIRED, EARPIECE, SPEAKER, UNKNOWN }

    fun icon(type: Int): RouteIcon = when (type) {
        CallEndpointCompat.TYPE_BLUETOOTH -> RouteIcon.BLUETOOTH
        CallEndpointCompat.TYPE_WIRED_HEADSET -> RouteIcon.WIRED
        CallEndpointCompat.TYPE_EARPIECE -> RouteIcon.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> RouteIcon.SPEAKER
        else -> RouteIcon.UNKNOWN
    }
}
