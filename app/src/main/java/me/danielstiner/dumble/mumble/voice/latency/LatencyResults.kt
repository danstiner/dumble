package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.voice.FRAME_SAMPLES
import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import me.danielstiner.dumble.mumble.voice.SendDelay
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** What only the device can say, read by the caller so the text stays JVM-testable. */
data class DeviceFacts(val model: String, val android: String, val voiceCallVolume: Int, val voiceCallVolumeMax: Int)

/** A check's outcome, one `name = value` per line; durations in ms. */
object LatencyResults {
    fun text(outcome: CheckOutcome, device: DeviceFacts): String {
        // A match before capture's first sample is no match: the search subtracts the codec's delay.
        val found = outcome is CheckOutcome.Measured &&
            outcome.measurements.located?.let { it.found && it.offsetSamples >= 0 } == true
        return buildString {
            fun row(name: String, value: Any?) {
                if (value != null) appendLine("$name = $value")
            }
            val m = when (outcome) {
                is CheckOutcome.Refused -> {
                    row("result", "refused")
                    row("reason", outcome.reason)
                    null
                }
                is CheckOutcome.Failed -> {
                    row("result", "failed")
                    row("reason", outcome.reason)
                    outcome.partial
                }
                is CheckOutcome.Measured -> outcome.measurements.also { m ->
                    row("result", if (found) "ok" else "not_detected")
                    if (!found) {
                        row("reason", "the probe was not heard in capture")
                        row(
                            "likely_cause",
                            if (m.located == null) {
                                "capture produced no packets"
                            } else {
                                "volume, background noise, or an echo canceller in the audio hardware" +
                                    if (m.effectsAllOff) "" else ", or effects still on"
                            },
                        )
                    }
                }
            }
            row("device.model", device.model)
            row("device.android", device.android)
            if (m == null) return@buildString
            row("route", m.route?.type?.name?.lowercase(Locale.ROOT))
            row("voice_path", if (m.voicePath.onUdp) "udp" else "tcp")
            row("voice_call_volume", "${device.voiceCallVolume}/${device.voiceCallVolumeMax}")
            row("capture.frames_per_burst", m.captureStats?.framesPerBurst)
            row("effects", m.effects.joinToString().ifEmpty { "none" })
            row("effects_off", m.effectsAllOff)

            val returnedAt = m.returned?.let { it.at - m.sent }
            val ping = if (m.voicePath.onUdp) m.voicePath.roundTrip else m.roundTripTime
            val jitter = m.returned?.aheadSamples?.toLong()?.sampleDuration()
            val codec = m.codecDelaySamples.toLong().sampleDuration()
            val output = m.playoutStats?.latencyMs?.milliseconds
            val send = SendDelay.of(m.captureStats, m.voicePath, m.roundTripTime)
            val loop = if (found) loopPhone(m) else null
            val unaccounted =
                if (loop != null && jitter != null && output != null && send.inputBuffer != null && send.encode != null) {
                    loop - jitter - codec - output - send.inputBuffer - send.encode
                } else {
                    null
                }

            row("network.round_trip", returnedAt?.ms())
            row("network.ping", ping?.ms())
            row("loop.phone", loop?.ms())
            row("receive.jitter_buffer", jitter?.ms())
            // The depth the spurt's gate waited for, not parts of the row above.
            row("receive.gate.target", m.returned?.targetSamples?.toLong()?.sampleDuration()?.ms())
            row("receive.gate.write_ahead", m.returned?.writeAheadSamples?.toLong()?.sampleDuration()?.ms())
            // Either above zero and playout dropped audio to catch up during the check: the onset
            // may have waited less than receive.jitter_buffer says.
            row("receive.shrunk_packets", m.playoutStats?.shrunkPackets)
            row("receive.catch_up_packets", m.playoutStats?.catchUpPackets)
            row("receive.codec_delay", codec.ms())
            row("receive.audio_output", output?.ms())
            row("send.input_buffer", send.inputBuffer?.ms())
            row("send.encode", send.encode?.ms())
            row("unaccounted", unaccounted?.ms())
            row("detection.peak", m.located?.peak?.twoDecimals())
            row("detection.next_peak", m.located?.nextPeak?.twoDecimals())
        }
    }

    /** t2 − t1: the onset's packet, corrected by the onset's place in it, less the return's arrival. */
    private fun loopPhone(m: Measurements): Duration? {
        val returned = m.returned ?: return null
        val offset = m.located?.offsetSamples ?: return null
        val packets = m.captured.sortedBy { it.frameNumber }
        val first = packets.firstOrNull()?.frameNumber ?: return null
        val onsetFrame = first + offset / FRAME_SAMPLES
        val carrier = packets.lastOrNull { it.frameNumber <= onsetFrame } ?: return null
        val inPacket = offset - (carrier.frameNumber - first) * FRAME_SAMPLES
        return carrier.at + inPacket.sampleDuration() - returned.at
    }

    private fun Long.sampleDuration() = seconds / SAMPLE_RATE
    private fun Duration.ms() = toDouble(DurationUnit.MILLISECONDS).twoDecimals()
    private fun Double.twoDecimals() = "%.2f".format(Locale.ROOT, this)
}
