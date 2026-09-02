package me.danielstiner.dumble.mumble.voice

/**
 * One audio output the platform can route call media through.
 *
 * Ours rather than core-telecom's `CallEndpointCompat` — not because building one is hard (it has
 * a public 3-arg constructor, and this project already runs Robolectric, so a JVM test can
 * construct one same as any other Android type). The reason is narrower: that type is
 * `@RequiresApi(O)` and carries a real `ParcelUuid`, so anything depending on it inherits an
 * Android-runtime dependency it does not otherwise need. This type keeps the UI, the view model
 * and the fakes free of that.
 *
 * [id] is the platform identifier stringified, and is what every layer keys on. Never the type:
 * two Bluetooth headsets paired at once are meant to differ only here, and below API 34 they do not.
 * There `EndpointUtils` catches the SecurityException from an ungranted BLUETOOTH_CONNECT and names
 * every paired device the same literal "Bluetooth Device", which `CallEndpointUuidTracker.getUuid`
 * then keys the id on (`CallEndpointUuidTracker.kt:72-88`), so two such devices collapse to one id;
 * `RouteState.publish` dedupes the pair to a single row rather than render a second, identical one
 * that a tap could never reach. At 34+ the name comes straight from the platform's own `CallEndpoint`
 * with no lookup of ours, so the collapse cannot happen there — measured on a Pixel 7a at API 37,
 * where revoking the permission still left the row reading its real device name. minSdk is 31, so
 * the old path stays live and the dedupe earns its keep.
 *
 * Withholding that permission is not ours to do, incidentally: `core-telecom` declares it in its own
 * manifest, so manifest merger puts it in Dumble regardless. We simply never request it, which is
 * what leaves it ungranted at runtime.
 */
data class AudioRoute(
    val id: String,
    val type: Type,
    val name: String = "",
) : Comparable<AudioRoute> {

    /** Declaration order is the sort rank [compareTo] uses, and the order rows appear in the menu. */
    enum class Type { WIRED_HEADSET, BLUETOOTH, SPEAKER, EARPIECE, STREAMING, UNKNOWN }

    /**
     * Bluetooth is the only type whose platform name says anything; the rest are named by what they
     * are. The blank fallback is defensive, not observed: below API 34 the platform never hands us
     * blank, only a real name or the literal "Bluetooth Device" (`EndpointUtils.kt:446-448`); above
     * API 34 the name comes straight from the platform's own `CallEndpoint` with no substitution of
     * ours (`CallSession.kt:136-152`), and whether it can be blank there is unmeasured.
     */
    val label: String
        get() = when (type) {
            Type.BLUETOOTH -> name.trim().ifEmpty { "Bluetooth" }
            Type.WIRED_HEADSET -> "Wired headset"
            Type.SPEAKER -> "Speaker"
            Type.EARPIECE -> "Earpiece"
            Type.STREAMING -> "Streaming"
            Type.UNKNOWN -> "Unknown"
        }

    /** Same rank order `CallEndpointCompat.compareTo` uses, ties broken by label. */
    override fun compareTo(other: AudioRoute): Int =
        compareValuesBy(this, other, { it.type.ordinal }, { it.label })
}

/** What the platform offers and what it is using. Both empty/null when no call is registered. */
data class AudioRoutes(
    val available: List<AudioRoute> = emptyList(),
    val current: AudioRoute? = null,
)
