package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TransmitProcessorTest {
    private class OffsetRecordingSuppressor : NoiseSuppressor {
        val offsets = mutableListOf<Int>()
        override fun process(pcm: ShortArray, off: Int, n: Int) { offsets.add(off) }
        override fun close() {}
    }

    private class CountingVad : VadDetector {
        var calls = 0
        override fun level(pcm: ShortArray, off: Int, n: Int): Float { calls++; return 0f }
    }

    private fun captures(amps: List<Int>): List<ShortArray> = amps.map { amp ->
        ShortArray(CAPTURE_SAMPLES) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }
    }

    private class ListAudioIn(private val caps: List<ShortArray>) : AudioIn {
        var i = 0
        override fun read(out: ShortArray, n: Int): Int {
            val src = caps.getOrElse(i) { caps.last() }; i++
            System.arraycopy(src, 0, out, 0, n); return n
        }
        override fun close() {}
    }

    @Test fun denoiseRunsSuppressorPerSubframeWithoutGating() {
        val sup = OffsetRecordingSuppressor()
        val vad = CountingVad()
        val gate = TransmitGate()
        val proc = TransmitProcessor(sup, vad, gate)

        proc.denoise(ShortArray(CAPTURE_SAMPLES))

        assertEquals("one suppressor call per 10 ms sub-frame",
            listOf(0, FRAME_SAMPLES_10MS), sup.offsets)
        // denoise must be denoise-ONLY: process() would call vad.level once per sub-frame, so a
        // zero VAD-call count discriminates denoise() from the full pipeline (and hence no gating).
        assertEquals("denoise must not run VAD", 0, vad.calls)
    }

    @Test fun engineAndProcessorDecideIdentically() {
        val amps = List(3) { 8000 } + List(20) { 0 } + List(3) { 8000 }
        val caps = captures(amps)

        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { ListAudioIn(caps) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = EnergyVadDetector(),
        ).also { it.start() }
        val engineDecisions = caps.indices.map {
            val f = engine.nextOutgoingFrame(0)
            (f != null) to (f?.isTerminator ?: false)
        }
        engine.stop()

        val proc = TransmitProcessor(NoiseSuppressor.None, EnergyVadDetector(), TransmitGate(openLevel = 0.60f))
        val procDecisions = caps.map { cap ->
            val d = proc.process(cap.copyOf()); d.send to d.terminator
        }

        assertEquals(procDecisions, engineDecisions)
    }
}
