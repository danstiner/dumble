package me.danielstiner.dumble.mumble.voice

import java.util.Locale

/**
 * One reading of the capture path. A plain snapshot rather than a flow: this is diagnostic output
 * for a human reading logcat, not app state.
 *
 * The counters are read one at a time from independent relaxed atomics, so a reading can straddle
 * an update and is not a consistent instant. That is fine for what it is for — spotting whether a
 * number is climbing — and cheaper than the coordination a true snapshot would need on the audio
 * callback's path.
 */
data class CaptureStats(
    val encodedPackets: Long,
    val encodeErrors: Long,
    val encodeMicrosMean: Long,
    val encodeMicrosMax: Long,
    val ringOverruns: Long,
    val skippedSamples: Long,
    val streamOverruns: Long,
    val framesPerBurst: Long,
    val droppedFrames: Int,
    /** The device input buffer, in milliseconds: how long a frame sits between the ADC and this
     *  app. Null when no stream is open or the platform has no timestamp for it yet. */
    val inputLatencyMillis: Double?,
) {
    /**
     * The two overruns are the ones that mean microphone audio was lost; the rest is context for
     * them. Encode time is against a 20 ms budget — a mean far below it with a max near it is an
     * encoder that mostly keeps up and occasionally does not.
     */
    fun summary(): String =
        "capture: packets=$encodedPackets encodeErr=$encodeErrors " +
            "encode=${encodeMicrosMean}us/${encodeMicrosMax}us burst=$framesPerBurst " +
            "streamOverruns=$streamOverruns ringOverruns=$ringOverruns skipped=$skippedSamples " +
            "sendDropped=$droppedFrames " +
            "inputLatency=${inputLatencyMillis?.let { "%.1fms".format(Locale.ROOT, it) } ?: "n/a"}"

    companion object {
        /** [droppedFrames] is the sender's to fill in — the engine knows nothing about it. */
        fun read(handle: Long) = CaptureStats(
            encodedPackets = NativeCapture.encodedPackets(handle),
            encodeErrors = NativeCapture.encodeErrors(handle),
            encodeMicrosMean = NativeCapture.encodeMicrosMean(handle),
            encodeMicrosMax = NativeCapture.encodeMicrosMax(handle),
            ringOverruns = NativeCapture.ringOverruns(handle),
            skippedSamples = NativeCapture.skippedSamples(handle),
            streamOverruns = NativeCapture.streamOverruns(handle),
            framesPerBurst = NativeCapture.framesPerBurst(handle),
            droppedFrames = 0,
            // Latency is non-negative; the seam answers a negative for "no reading".
            inputLatencyMillis = NativeCapture.inputLatencyMillis(handle).takeIf { it >= 0 },
        )
    }
}
