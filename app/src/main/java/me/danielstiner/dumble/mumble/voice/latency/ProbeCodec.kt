package me.danielstiner.dumble.mumble.voice.latency

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The probe as packets and the search over packets: [NativeProbeCodec] on a device, a fake in JVM tests. */
interface ProbeCodec {
    fun encodeProbeSpurt(peak: Int, lead: Duration, tail: Duration, bitrate: Int): List<ByteArray>
    fun codecDelaySamples(bitrate: Int): Int

    /** Null when there are no [packets]; whether the probe counts as found is [Located.found].
     *  The offset is in samples from the earliest frame's first sample. */
    fun locate(packets: List<CapturedPacket>, codecDelaySamples: Int): Located?
}

data class Located(val offsetSamples: Long, val peak: Double, val nextPeak: Double, val found: Boolean)

object NativeProbeCodec : ProbeCodec {
    override fun encodeProbeSpurt(peak: Int, lead: Duration, tail: Duration, bitrate: Int): List<ByteArray> =
        NativeLatencyProbe.encodeProbeSpurt(peak, lead.samples(), tail.samples(), bitrate).asList()

    override fun codecDelaySamples(bitrate: Int): Int = NativeLatencyProbe.codecDelaySamples(bitrate)

    override fun locate(packets: List<CapturedPacket>, codecDelaySamples: Int): Located? {
        if (packets.isEmpty()) return null
        val frames = LongArray(packets.size) { packets[it].frameNumber }
        val out = DoubleArray(3)
        val found = NativeLatencyProbe.locate(frames, Array(packets.size) { packets[it].opusData }, codecDelaySamples, out)
        return Located(out[0].toLong(), out[1], out[2], found)
    }

    private fun Duration.samples(): Int = (this / 1.seconds * SAMPLE_RATE).roundToInt()
}
