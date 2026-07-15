package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import java.io.File
import kotlin.math.sqrt

enum class Kind { SPEECH, PAUSE, SILENCE, NOISE }

data class Segment(val startMs: Int, val endMs: Int, val kind: Kind)

data class Thresholds(
    val minCoverage: Double = 0.99,
    val maxMidUtteranceDropoutMs: Int = 0,
    val maxOnsetMs: Int = 60,
    val maxFalseOpeningsPer10s: Double = 1.0,
)

data class Clip(
    val name: String,
    val pcm: ShortArray,
    val segments: List<Segment>,
    val scoreFromMs: Int,
    val thresholds: Thresholds,
)

/** Builds labeled composites from a committed speech ingredient + programmatic silence/noise.
 *  Deterministic (fixed LCG for noise). Labels are exact by construction. */
object CorpusBuilder {
    private const val MS = SAMPLE_RATE / 1000
    private const val GRID_MS = 20

    private fun speech(): ShortArray =
        WavReader.read(File("src/test/resources/vad-corpus/speech_a.wav"))

    private fun silence(ms: Int) = ShortArray(ms * MS)

    private fun noise(ms: Int, amp: Int, seed: Long): ShortArray {
        var s = seed
        return ShortArray(ms * MS) {
            s = s * 6364136223846793005L + 1442695040888963407L
            (((s ushr 40).toInt() % (2 * amp + 1)) - amp).toShort()
        }
    }

    private fun rms(a: ShortArray): Double {
        if (a.isEmpty()) return 0.0
        var acc = 0.0; for (v in a) acc += v.toDouble() * v; return sqrt(acc / a.size)
    }

    private fun addNoise(sig: ShortArray, snrDb: Double, seed: Long): ShortArray {
        val n = noise(sig.size / MS, 12000, seed)
        val sPow = rms(sig); val nPow = rms(n).coerceAtLeast(1.0)
        val scale = (sPow / nPow) / Math.pow(10.0, snrDb / 20.0)
        return ShortArray(sig.size) { i ->
            (sig[i] + (n.getOrElse(i) { 0 } * scale)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun durMs(a: ShortArray) = a.size / MS

    fun build(): List<Clip> {
        val sp = speech()
        val spMs = (durMs(sp) / GRID_MS) * GRID_MS
        val spTrim = sp.copyOf(spMs * MS)

        val c1 = concat(silence(400), spTrim)
        val clip1 = Clip("clean_contiguous", c1,
            listOf(Segment(0, 400, Kind.SILENCE), Segment(400, 400 + spMs, Kind.SPEECH)),
            scoreFromMs = 300, thresholds = Thresholds())

        val half = spTrim.copyOf((spMs / 2 / GRID_MS) * GRID_MS * MS)
        val halfMs = durMs(half)
        val c2 = concat(silence(400), half, silence(160), half)
        val clip2 = Clip("speech_pause", c2,
            listOf(
                Segment(0, 400, Kind.SILENCE),
                Segment(400, 400 + halfMs, Kind.SPEECH),
                Segment(400 + halfMs, 560 + halfMs, Kind.PAUSE),
                Segment(560 + halfMs, 560 + 2 * halfMs, Kind.SPEECH),
            ),
            scoreFromMs = 300, thresholds = Thresholds(maxMidUtteranceDropoutMs = 0))

        val quiet = ShortArray(spTrim.size) { (spTrim[it] / 4).toShort() }
        val c3 = concat(silence(400), quiet)
        val clip3 = Clip("quiet_onset", c3,
            listOf(Segment(0, 400, Kind.SILENCE), Segment(400, 400 + spMs, Kind.SPEECH)),
            scoreFromMs = 300, thresholds = Thresholds(minCoverage = 0.90, maxOnsetMs = 120))

        val noisy = addNoise(concat(silence(400), spTrim), snrDb = 10.0, seed = 42)
        val clip4 = Clip("noisy_10db", noisy,
            listOf(Segment(0, 400, Kind.NOISE), Segment(400, 400 + spMs, Kind.SPEECH)),
            scoreFromMs = 300, thresholds = Thresholds(minCoverage = 0.90))

        val nOnlyMs = 10000
        val c5 = noise(nOnlyMs, 6000, seed = 7)
        val clip5 = Clip("noise_only", c5,
            listOf(Segment(0, nOnlyMs, Kind.NOISE)),
            scoreFromMs = 300, thresholds = Thresholds(maxFalseOpeningsPer10s = 1.0))

        return listOf(clip1, clip2, clip3, clip4, clip5)
    }
}
