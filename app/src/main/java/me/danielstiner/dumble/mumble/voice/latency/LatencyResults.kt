package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.voice.FRAME_NUMBER_SAMPLES
import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import me.danielstiner.dumble.mumble.voice.SendDelay
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** What only the device can say, read by the caller so the text stays JVM-testable. */
data class DeviceFacts(val model: String, val android: String, val voiceCallVolume: Int, val voiceCallVolumeMax: Int)

/** A check's outcome as spec §1's stage table, one `name = value` per line; durations in ms. */
object LatencyResults {
    fun text(outcome: CheckOutcome, device: DeviceFacts): String {
        val found = outcome is CheckOutcome.Measured && outcome.m.located?.found == true
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
                is CheckOutcome.Measured -> outcome.m.also { m ->
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
            val jitter = m.returned?.aheadSamples?.samples()
            val codec = m.codecDelaySamples.samples()
            val output = m.playoutStats?.latencyMs?.milliseconds
            val send = SendDelay.of(m.captureStats, m.voicePath, m.roundTripTime)
            val loop = if (found) loopPhone(m) else null
            val unaccounted = listOf(jitter, output, send.inputBuffer, send.encode)
                .takeIf { loop != null && null !in it }
                ?.let { terms -> loop!! - terms.fold(codec) { sum, term -> sum + term!! } }

            row("network.round_trip", returnedAt?.ms())
            row("network.ping", ping?.ms())
            row("loop.phone", loop?.ms())
            row("receive.jitter_buffer", jitter?.ms())
            row("receive.jitter_buffer.target", m.returned?.targetSamples?.samples()?.ms())
            row("receive.jitter_buffer.write_ahead", m.returned?.writeAheadSamples?.samples()?.ms())
            row("receive.codec_delay", codec.ms())
            row("receive.audio_output", output?.ms())
            row("send.input_buffer", send.inputBuffer?.ms())
            row("send.encode", send.encode?.ms())
            row("unaccounted", unaccounted?.ms())
            row("estimate.network_ms", ping?.div(2)?.ms())
            row("detection.peak", m.located?.peak?.two())
            row("detection.next_peak", m.located?.nextPeak?.two())
        }
    }

    /** t2 − t1: the onset's packet, corrected by the onset's place in it, less the return's arrival. */
    private fun loopPhone(m: Measurements): Duration? {
        val returned = m.returned ?: return null
        val offset = m.located?.offsetSamples ?: return null
        val packets = m.captured.sortedBy { it.frameNumber }
        val first = packets.firstOrNull()?.frameNumber ?: return null
        val onsetFrame = first + offset / FRAME_NUMBER_SAMPLES
        val carrier = packets.lastOrNull { it.frameNumber <= onsetFrame } ?: return null
        val inPacket = offset - (carrier.frameNumber - first) * FRAME_NUMBER_SAMPLES
        return carrier.at + inPacket.samples() - returned.at
    }

    private fun Int.samples() = toLong().samples()
    private fun Long.samples() = seconds / SAMPLE_RATE
    private fun Duration.ms() = toDouble(DurationUnit.MILLISECONDS).two()
    private fun Double.two() = "%.2f".format(Locale.ROOT, this)
}
