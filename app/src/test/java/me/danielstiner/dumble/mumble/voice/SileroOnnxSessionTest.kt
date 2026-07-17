package me.danielstiner.dumble.mumble.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class SileroOnnxSessionTest {
    private lateinit var session: SileroOnnxSession
    private fun modelBytes(): ByteArray = File("src/main/assets/silero_vad_16k_op15.onnx").readBytes()
    private fun speechish(n: Int): FloatArray = FloatArray(n) {
        (0.6 * sin(2 * PI * 220 * it / 16000) +
         0.4 * sin(2 * PI * 700 * it / 16000) +
         0.2 * sin(2 * PI * 2500 * it / 16000)).toFloat()
    }
    @Before fun setUp() { session = SileroOnnxSession(modelBytes()) }
    @After fun tearDown() { session.close() }

    @Test fun silenceIsLowProbability() {
        val r = session.run(FloatArray(576), SileroOnnxSession.newState())
        assertTrue("prob ${r.prob}", r.prob in 0f..1f && r.prob < 0.15f)
        assertEquals(2 * 1 * 128, r.state.size)
    }
    @Test fun deterministic() {
        val x = speechish(576)
        val a = session.run(x, SileroOnnxSession.newState())
        val b = session.run(x, SileroOnnxSession.newState())
        assertEquals(a.prob, b.prob, 0f)
    }
    @Test fun wrongWidthCollapses() {
        // Silero is trained to reject non-speech spectral content, so a synthetic tone never
        // drives it to the high absolute confidence real recorded speech would reach. What it
        // does reliably show: feeding exactly 512 samples (missing the 64-sample context prefix
        // the model expects) collapses the output to a near-floor value that stops tracking the
        // input at all (e.g. sine bursts of amplitude 1x and 8x both yield the identical
        // 5.886555E-4), whereas the correct 576-wide input tracks signal content. That
        // content-independent floor vs. content-tracking output is the "collapse" this guards.
        val speech = speechish(512)
        val correct = FloatArray(576).also { System.arraycopy(speech, 0, it, 64, 512) }
        val pCorrect = session.run(correct, SileroOnnxSession.newState()).prob
        val pWrong = session.runRaw(speech, SileroOnnxSession.newState()).prob
        assertTrue("correct=$pCorrect should be non-trivially content-dependent", pCorrect > 0.01f)
        assertTrue("wrong=$pWrong should have collapsed toward the floor", pWrong < 0.01f)
        assertTrue("correct=$pCorrect wrong=$pWrong should differ markedly (>=10x)",
            pCorrect > pWrong * 10f)
    }
}
