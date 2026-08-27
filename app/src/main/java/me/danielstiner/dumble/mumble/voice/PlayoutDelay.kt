package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.protocol.UserStats
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long one speaker's voice takes to reach this ear, in the steps we can read. A floor: their
 * capture delay — microphone to encoder — is not on the wire, so [total] understates by an
 * unknown amount.
 *
 * [network] is both server hops: each ping is a round trip to the server that relays the audio,
 * so each contributes half. Theirs is what the server reports about them.
 *
 * [jitterBuffer] is what is queued for this speaker now, not the prebuffer their spurt opened on.
 * The playback loop writes ahead until the track blocks it, so the prebuffer drains into the track
 * and is already inside [audioOutput]; the two rows partition the delay rather than overlap.
 * Measured: they trade off one for one from second to second while their sum holds.
 *
 * [audioOutput] is what is written and not yet presented — track fill plus the HAL, see
 * [LatencyMath.outputLatencyMs]. One output stream, so the same figure for every speaker.
 */
data class PlayoutDelay(
    val network: Duration?,
    val jitterBuffer: Duration?,
    val audioOutput: Duration?,
) {
    /** Every step with a reading; null when none has one. A missing step is skipped — the total
     *  is a floor either way — but [network] needs both halves, since half a path is not a path. */
    val total: Duration?
        get() = if (network == null && jitterBuffer == null && audioOutput == null) null
                else (network ?: ZERO) + (jitterBuffer ?: ZERO) + (audioOutput ?: ZERO)

    companion object {
        fun of(
            session: Int,
            playout: PlayoutStats?,
            stats: UserStats?,
            ourRoundTrip: Duration?,
        ): PlayoutDelay {
            // The leg carrying voice, matching how UserStats picks its jitter: murmur only has a
            // UDP ping for a peer with a working UDP path.
            val theirRoundTrip = stats?.udpPing ?: stats?.tcpPing
            return PlayoutDelay(
                network = if (theirRoundTrip == null || ourRoundTrip == null) null
                          else (theirRoundTrip + ourRoundTrip) / 2,
                jitterBuffer = playout?.depth(session),
                audioOutput = playout?.latencyMs?.milliseconds,
            )
        }
    }
}
