package me.danielstiner.dumble.mumble.protocol

/** Why a connection ended. Only what this layer can actually report; trust failures never reach it. */
enum class FailReason { AUTH_REJECT, TIMEOUT, IO, VERSION_TOO_OLD }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Handshaking : ConnectionState
    data class Synchronized(val sessionId: Int) : ConnectionState
    data class Failed(
        val reason: FailReason,
        val detail: String? = null,
        val cause: Throwable? = null,
    ) : ConnectionState
}

/**
 * Mumble packs the client version into an integer; version 2 is 64-bit, the legacy form 32-bit.
 * Both are sent.
 *
 * Components are clamped rather than masked, matching the desktop client's behaviour, which
 * saturates each component at its maximum. Masking would let an over-range component wrap into the next
 * field and silently change the major/minor that servers gate on: a 1.5.1000 client would advertise
 * legacy 1.7.232. Reachable in practice - version 2 exists precisely because patch can exceed 255.
 */
object MumbleVersion {
    private const val U16 = 0xFFFF
    private const val U8 = 0xFF

    fun encodeV2(major: Int, minor: Int, patch: Int): Long =
        (clamp(major, U16).toLong() shl 48) or
            (clamp(minor, U16).toLong() shl 32) or
            (clamp(patch, U16).toLong() shl 16)

    /** The legacy layout is narrower than version 2's: major is 16 bits, minor and patch only 8. */
    fun encodeV1(major: Int, minor: Int, patch: Int): Int =
        (clamp(major, U16) shl 16) or (clamp(minor, U8) shl 8) or clamp(patch, U8)

    /** Upstream's components are unsigned; ours are [Int], so the floor guards negatives too. */
    private fun clamp(component: Int, max: Int) = component.coerceIn(0, max)
}
