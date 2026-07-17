package me.danielstiner.dumble.mumble.voice

/**
 * Silero v6 VAD behind the [VadDetector] seam. Decimates each 10 ms @48k sub-frame to 160 samples
 * @16k, buffers to 512-sample windows, runs one ORT inference per completed window carrying the
 * recurrent [state] + 64-sample [context], and HOLDS the last probability so [level] returns a value
 * every 10 ms even though inferences land ~every 32 ms. Single-thread (send thread).
 */
class SileroVadDetector(
    private val session: SileroOnnxSession,
) : VadDetector {
    private val decimator = Decimator()
    private val ring = FloatArray(RING_CAP)
    private var ringLen = 0
    private val context = FloatArray(CONTEXT)
    private var state = SileroOnnxSession.newState()
    private var held = 0f

    override fun level(pcm: ShortArray, off: Int, n: Int): Float {
        require(n == FRAME_SAMPLES_10MS) { "SileroVadDetector requires 480-sample frames, got $n" }
        val ds = decimator.decimate(pcm, off, n)
        System.arraycopy(ds, 0, ring, ringLen, ds.size)
        ringLen += ds.size
        while (ringLen >= WINDOW) {
            val input = FloatArray(SileroOnnxSession.INPUT_WIDTH)
            System.arraycopy(context, 0, input, 0, CONTEXT)
            System.arraycopy(ring, 0, input, CONTEXT, WINDOW)
            val r = session.run(input, state)
            held = r.prob; state = r.state
            System.arraycopy(ring, WINDOW - CONTEXT, context, 0, CONTEXT)
            System.arraycopy(ring, WINDOW, ring, 0, ringLen - WINDOW)
            ringLen -= WINDOW
        }
        return held
    }

    override fun reset() {
        decimator.reset(); ringLen = 0; context.fill(0f)
        state = SileroOnnxSession.newState(); held = 0f
    }

    fun close() = session.close()

    private companion object {
        const val WINDOW = 512
        const val CONTEXT = 64
        const val RING_CAP = WINDOW + 160   // ringLen < WINDOW before each call (loop postcondition), so one 160-sample chunk always fits
    }
}
