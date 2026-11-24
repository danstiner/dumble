package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import me.danielstiner.dumble.mumble.voice.SendDelay
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** What only the device can say, read by the caller so the text stays JVM-testable. */
data class DeviceFacts(val model: String, val android: String)

/** A check's outcome, one `name = value` per line; durations in ms. */
object LatencyResults {
    fun text(outcome: CheckOutcome, device: DeviceFacts): String {
        val found = outcome is CheckOutcome.Measured && outcome.measurements.heard?.at != null
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
                            if (m.heard == null) {
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
            row("voice_call_volume", m.callVolume?.let { "${it.index}/${it.max}" })
            row("capture.frames_per_burst", m.captureStats?.framesPerBurst)
            row("effects", m.effects.joinToString().ifEmpty { "none" })
            row("effects_off", m.effectsAllOff)

            val returnedAt = m.returned?.let { it.at - m.sent }
            val ping = if (m.voicePath.onUdp) m.voicePath.roundTrip else m.roundTripTime
            val jitter = m.returned?.aheadSamples?.toLong()?.sampleDuration()
            val codec = m.codecDelaySamples.toLong().sampleDuration()
            val output = m.playoutStats?.latencyMs?.milliseconds
            val send = SendDelay.of(m.captureStats, m.voicePath, m.roundTripTime)
            // t2 − t1.
            val loop = if (m.heard?.at != null && m.returned != null) m.heard.at - m.returned.at else null
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
            // The depth the probe's spurt waited for, not counted in unaccounted. From the stats the
            // spurt closed with; missing when its speaker had retired before they were read.
            val gate = m.returned?.let { m.playoutStats?.targetSamples?.get(it.session) }
            row("receive.gate", gate?.toLong()?.sampleDuration()?.ms())
            // Either above zero and playout dropped audio to catch up during the check: the onset
            // may have waited less than receive.jitter_buffer says.
            row("receive.shrunk_packets", m.playoutStats?.shrunkPackets)
            row("receive.catch_up_packets", m.playoutStats?.catchUpPackets)
            row("receive.codec_delay", codec.ms())
            row("receive.audio_output", output?.ms())
            row("send.input_buffer", send.inputBuffer?.ms())
            row("send.encode", send.encode?.ms())
            row("unaccounted", unaccounted?.ms())
            row("detection.peak", m.heard?.peak?.twoDecimals())
            row("detection.next_peak", m.heard?.nextPeak?.twoDecimals())
        }
    }

    private fun Long.sampleDuration() = seconds / SAMPLE_RATE
    private fun Duration.ms() = toDouble(DurationUnit.MILLISECONDS).twoDecimals()
    private fun Double.twoDecimals() = "%.2f".format(Locale.ROOT, this)
}
