package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TransmitProcessorTest {
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
