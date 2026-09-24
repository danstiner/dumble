package me.danielstiner.dumble.mumble.voice

/**
 * One audio output the platform can route call media through — a communication device, in our own
 * type so the UI, the view model and the fakes stay plain JVM code, free of AudioDeviceInfo.
 *
 * [id] is the device's id stringified, and is what every layer keys on — never the type: two
 * Bluetooth headsets paired at once differ only here. It changes each time a headset reconnects
 * (measured).
 */
data class AudioRoute(
    val id: String,
    val type: Type,
    val name: String = "",
) : Comparable<AudioRoute> {

    /** Declaration order is the sort rank [compareTo] uses, and the order rows appear in the menu. */
    enum class Type { WIRED_HEADSET, BLUETOOTH, SPEAKER, EARPIECE, UNKNOWN }

    /**
     * Only Bluetooth is labelled by its platform name; the rest are named by what they are, since a
     * built-in reports the phone's model as its name. A device the platform has no name for also
     * reads back as the phone's model, never blank, so the fallback only catches a name of spaces.
     */
    val label: String
        get() = when (type) {
            Type.BLUETOOTH -> name.trim().ifEmpty { "Bluetooth" }
            Type.WIRED_HEADSET -> "Wired headset"
            Type.SPEAKER -> "Speaker"
            Type.EARPIECE -> "Earpiece"
            Type.UNKNOWN -> "Unknown"
        }

    /** Hardware preference — [Type]'s declaration order — ties broken by label. */
    override fun compareTo(other: AudioRoute): Int =
        compareValuesBy(this, other, { it.type.ordinal }, { it.label })
}

/** What the platform offers and what it is using. Both empty/null when no call is live. */
data class AudioRoutes(
    val available: List<AudioRoute> = emptyList(),
    val current: AudioRoute? = null,
)
