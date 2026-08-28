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
 * [jitterBuffer] is what is queued for this speaker now — the margin against a late packet.
 * Whatever has already been handed to the stream is inside [audioOutput] instead, so the two rows
 * partition the delay rather than overlap; the engine sizes its targets so that the queue, not
 * the stream, holds the estimator's margin.
 *
 * [audioOutput] is what the stream holds ahead of the speaker — its buffer plus the HAL, from
 * Oboe's `calculateLatencyMillis`. One output stream, so the same figure for every speaker.
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
