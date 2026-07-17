package me.danielstiner.dumble.mumble.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Thin ORT wrapper over the Silero v6 16k ONNX. Inputs: `input` [1,576] (64 carried context ++ 512
 * window), `state` [2,1,128], and `sr`=16000 (int64) IF the model declares it (16k-only export may
 * not). Outputs: probability [1,1] + new state [2,1,128]. Single-thread; not reused across threads.
 * Dynamic sequence dim means metadata can't reveal 576 — we hardcode it; [1,512] runs but collapses
 * (see runRaw, guard-test only). Copy state out before closing the Result (native memory).
 */
open class SileroOnnxSession(modelBytes: ByteArray) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    private val hasSr = session.inputNames.contains("sr")

    class Result(val prob: Float, val state: FloatArray)

    open fun run(input576: FloatArray, state: FloatArray): Result {
        require(input576.size == INPUT_WIDTH) { "expected 576, got ${input576.size}" }
        return infer(input576, INPUT_WIDTH, state)
    }

    fun runRaw(input: FloatArray, state: FloatArray): Result = infer(input, input.size, state)

    private fun infer(input: FloatArray, width: Int, state: FloatArray): Result {
        require(state.size == 2 * 1 * 128) { "state must be 256 floats, got ${state.size}" }
        var inT: OnnxTensor? = null
        var stT: OnnxTensor? = null
        var srT: OnnxTensor? = null
        try {
            inT = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, width.toLong()))
            stT = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
            srT = if (hasSr)
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()) else null
            val feeds = HashMap<String, OnnxTensor>().apply {
                put("input", inT); put("state", stT); if (srT != null) put("sr", srT)
            }
            session.run(feeds).use { res ->
                @Suppress("UNCHECKED_CAST")
                val prob = (res[0].value as Array<FloatArray>)[0][0]
                val outState = flatten(res[1].value)
                return Result(prob, outState)
            }
        } finally {
            inT?.close(); stT?.close(); srT?.close()
        }
    }

    fun close() { session.close() }

    companion object {
        const val INPUT_WIDTH = 576
        fun newState() = FloatArray(2 * 1 * 128)
        private fun flatten(v: Any?): FloatArray {
            @Suppress("UNCHECKED_CAST")
            val a = v as Array<Array<FloatArray>>
            val out = FloatArray(2 * 1 * 128); var i = 0
            for (p in a) for (q in p) for (x in q) out[i++] = x
            return out
        }
    }
}
