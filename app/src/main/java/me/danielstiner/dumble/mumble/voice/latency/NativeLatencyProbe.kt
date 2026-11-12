package me.danielstiner.dumble.mumble.voice.latency

/**
 * Thin binding over `core/LatencyPackets`: the probe as Dumble's capture would encode it, and the
 * search over voice packets. Bound by symbol name from `latency_jni.cpp`; R8 keeps native methods
 * by AGP's default rule.
 */
object NativeLatencyProbe {
    init { System.loadLibrary("dumble") }

    /** [leadSamples] of silence, the probe at [peak], [tailSamples] of silence, as 20 ms Opus
     *  packets from a fresh encoder at [bitrate]. Empty if the encoder could not be built. */
    external fun encodeProbeSpurt(peak: Int, leadSamples: Int, tailSamples: Int, bitrate: Int): Array<ByteArray>

    /** Opus's delay at [bitrate], in samples, measured on the probe; -1 if it was not found. */
    external fun codecDelaySamples(bitrate: Int): Int

    /** Decodes [packets] at frame numbers [frames] (10 ms units, any order) and finds the probe.
     *  Fills [out] with its offset in samples from the earliest frame's first sample, less
     *  [codecDelaySamples], then its peak and next peak; true when that counts as found. False,
     *  with [out] untouched, when [frames] and [packets] differ in length or [out] is shorter
     *  than 3. */
    external fun locate(frames: LongArray, packets: Array<ByteArray>, codecDelaySamples: Int, out: DoubleArray): Boolean
}
