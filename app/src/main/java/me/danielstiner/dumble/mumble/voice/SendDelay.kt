package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.VoicePath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long our own voice takes to reach the server, in the steps we can read. The send-side
 * counterpart of [PlayoutDelay], and a floor like it: the server's relay time is not on the wire.
 *
 * [inputBuffer] is the device's, ADC to this app. [encode] is a packet's duration, since it
 * cannot leave before its last sample is in, plus the encoder's mean. [network] is half our
 * round trip on the leg carrying voice, since a ping is there and back.
 */
data class SendDelay(
    val inputBuffer: Duration?,
    val encode: Duration?,
    val network: Duration?,
) {
    /** Every step with a reading; null when none has one. A missing step is skipped — the total
     *  is a floor either way. */
    val total: Duration?
        get() = if (inputBuffer == null && encode == null && network == null) null
                else (inputBuffer ?: ZERO) + (encode ?: ZERO) + (network ?: ZERO)

    companion object {
        fun of(capture: CaptureStats?, path: VoicePath.State, tcpRoundTrip: Duration?): SendDelay {
            val roundTrip = if (path.onUdp) path.roundTrip else tcpRoundTrip
            return SendDelay(
                inputBuffer = capture?.inputLatencyMillis?.milliseconds,
                encode = capture?.let { TRANSMIT_PACKET_INTERVAL + it.encodeMicrosMean.microseconds },
                network = roundTrip?.let { it / 2 },
            )
        }
    }
}
