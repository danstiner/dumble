package me.danielstiner.dumble.mumble.voice

import java.util.concurrent.LinkedBlockingQueue

/** Scripted stand-in for the native engine: each entry is one pollFrame outcome. */
class FakeCaptureHandle : VoiceSender.CaptureHandle {
    sealed interface Step {
        data class Frame(val bytes: ByteArray, val frameNumber: Long, val terminator: Boolean) : Step
        data object Retry : Step
        data object Unavailable : Step
        data object Shutdown : Step
        /** A return value the pump has no case for — native gaining an outcome ahead of Kotlin. */
        data class Unknown(val code: Int) : Step
    }

    private val steps = LinkedBlockingQueue<Step>()
    var gateOpen = false; private set
    var stopped = false; private set

    fun script(vararg s: Step) = s.forEach { steps.put(it) }
    override fun setGateOpen(open: Boolean) { gateOpen = open }
    override fun stop() { stopped = true; steps.put(Step.Shutdown) }

    override fun pollFrame(out: ByteArray, meta: LongArray): Int = when (val s = steps.take()) {
        is Step.Frame -> {
            s.bytes.copyInto(out)
            meta[0] = s.frameNumber
            meta[1] = if (s.terminator) NativeCapture.FLAG_TERMINATOR else 0L
            s.bytes.size
        }
        Step.Retry -> NativeCapture.POLL_RETRY
        Step.Unavailable -> NativeCapture.POLL_UNAVAILABLE
        Step.Shutdown -> NativeCapture.POLL_SHUTDOWN
        is Step.Unknown -> s.code
    }
}
