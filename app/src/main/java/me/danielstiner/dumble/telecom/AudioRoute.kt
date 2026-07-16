package me.danielstiner.dumble.telecom

import android.telecom.CallEndpoint

/**
 * Pure mapping of a Telecom [CallEndpoint]'s type to a human-readable audio-route label, shown as a
 * read-only route indicator on the call screen (a stopgap until the call-screen redesign hosts a
 * first-class route control). The referenced `TYPE_*` values are compile-time `int` constants, so
 * this has no Android runtime dependency and is JVM-unit-testable.
 */
object AudioRoute {
    /**
     * Display label for an endpoint [type], preferring the Bluetooth device [name] when present
     * (a null/blank name falls back to the generic "Bluetooth" label).
     */
    fun label(type: Int, name: CharSequence? = null): String = when (type) {
        CallEndpoint.TYPE_BLUETOOTH ->
            name?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
        CallEndpoint.TYPE_WIRED_HEADSET -> "Wired headset"
        CallEndpoint.TYPE_EARPIECE -> "Earpiece"
        CallEndpoint.TYPE_SPEAKER -> "Speaker"
        else -> "Unknown"
    }
}
