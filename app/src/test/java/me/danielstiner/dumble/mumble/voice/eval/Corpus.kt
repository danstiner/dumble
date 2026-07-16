package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import java.io.File

enum class Kind { SPEECH, PAUSE, SILENCE, NOISE }

data class Segment(val startMs: Int, val endMs: Int, val kind: Kind)

data class Thresholds(
    val minCoverage: Double = 1.0,
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

/**
 * Builds the VAD eval corpus from **real recorded speech + human-verified labels** — LibriSpeech
 * `dev-other` clips (CC BY 4.0; see NOTICE in the corpus dir), resampled to 48 kHz mono, with
 * Audacity `.manual.txt` region annotations. Ground truth is the human labels — NOT an energy
 * heuristic — so a gate disagreement means the gate is wrong, not the label.
 *
 * ACCEPTANCE MODEL (asymmetric, per user requirement):
 *  - Every tagged SPEECH region MUST be fully inside gate-open: coverage == 1.0, zero mid-region
 *    dropout. This is the hard bar.
 *  - The gate opening slightly BEFORE a region (lead) is fine but should be short — it is pre-roll
 *    latency. Measured as [Metrics.onsetMs] (late-open, which eats coverage) staying ~0.
 *  - The gate staying open AFTER a region (hangover) is fine and may be long, even bridging a PAUSE
 *    into the next region — hence PAUSE captures are exempt from coverage AND false-activation.
 *  - Only a truly spurious open (gate open in SILENCE not adjacent to speech: preroll, deep trail)
 *    is a false activation.
 *
 * Per clip: a warm-up preroll (tiled from the clip's own pre-speech room tone) is prepended so
 * RNNoise reaches steady state before the scored onset — a real call pays cold-start latency once,
 * not once per utterance. Scoring starts after the preroll ([Clip.scoreFromMs]).
 *
 * Labels map to segments: each region -> SPEECH; the gap between two regions -> PAUSE; lead/trail
 * -> SILENCE. Deterministic.
 */
object CorpusBuilder {
    private const val MS = SAMPLE_RATE / 1000          // 48 samples per ms
    private const val CORPUS_DIR = "src/test/resources/LibriSpeech-ASR-corpus"
    private const val PREROLL_MS = 1000                // RNNoise warm-up before scoring

    private fun readWav(base: String) = WavReader.read(File("$CORPUS_DIR/$base.wav"))
    private fun readLabels(base: String) = Labels.read(File("$CORPUS_DIR/$base.manual.txt"))
    private fun durMs(a: ShortArray) = a.size / MS

    /** Tile the clip's own pre-speech lead into a [PREROLL_MS] warm-up (real room tone, not
     *  zeros, so RNNoise's noise estimate settles the way it would mid-call). */
    private fun preroll(pcm: ShortArray, firstSpeechMs: Int): ShortArray {
        val need = PREROLL_MS * MS
        val leadLen = (firstSpeechMs * MS).coerceIn(0, pcm.size)
        if (leadLen == 0) return ShortArray(need)           // no lead -> silence
        return ShortArray(need) { pcm[it % leadLen] }       // tile real background
    }

    /** A real utterance clip: warm-up preroll + full recording, labeled from its `.manual.txt`. */
    private fun utterance(base: String, thresholds: Thresholds): Clip {
        val pcm = readWav(base)
        val regions = readLabels(base)
        require(regions.isNotEmpty()) { "$base has no labels" }

        val pre = preroll(pcm, regions.first().startMs)
        val p = durMs(pre)
        val full = ShortArray(pre.size + pcm.size)
        System.arraycopy(pre, 0, full, 0, pre.size)
        System.arraycopy(pcm, 0, full, pre.size, pcm.size)

        val clipMs = durMs(pcm)
        val segs = ArrayList<Segment>()
        segs.add(Segment(0, p + regions.first().startMs, Kind.SILENCE))     // preroll + lead
        for (i in regions.indices) {
            val r = regions[i]
            segs.add(Segment(p + r.startMs, p + r.endMs, Kind.SPEECH))
            if (i + 1 < regions.size)
                segs.add(Segment(p + r.endMs, p + regions[i + 1].startMs, Kind.PAUSE))
        }
        segs.add(Segment(p + regions.last().endMs, p + clipMs, Kind.SILENCE)) // trail
        return Clip(base, full, segs, scoreFromMs = p, thresholds = thresholds)
    }

    // Bars encode the acceptance model above and are UNIFORM across clips — a requirement, not a
    // per-clip tuning knob:
    //   minCoverage=1.0 + maxMidUtteranceDropoutMs=0  -> every tagged region fully inside gate-open.
    // These are INTENTIONALLY RED today. Measured coverage is 0.947-0.990: the gate opens 23-78 ms
    // LATE on region onsets (openLevel doesn't trip until energy builds), clipping the front of each
    // region. Closing that gap needs a pre-roll buffer that flushes ~80-100 ms of buffered audio
    // when the gate opens (task #35). This is a TDD target: the test goes green when the gate meets
    // the requirement, not by loosening the bar.
    //
    // maxOnsetMs / maxFalseOpeningsPer10s are LOOSE secondary diagnostics (not the requirement).
    // onset is already subsumed by coverage=1.0 (full coverage implies onset 0); its bar is kept
    // slack so the red surfaces only the coverage+dropout requirement, not a redundant onset axis.
    // maxFalseOpeningsPer10s guards truly-spurious opens (all clips measure 0); hangover bridging
    // PAUSEs is exempt by construction, and an acceptable short lead-in open is NOT yet distinguished
    // from a spurious one — revisit when the pre-roll buffer lands and the gate starts opening early.
    //
    // TODO(#34): re-add a dedicated noise-only false-activation clip (real MUSAN noise) — the gate
    // must stay shut on ~10 s of pure non-speech.
    private val REQUIRE = Thresholds(
        minCoverage = 1.0, maxMidUtteranceDropoutMs = 0,
        maxOnsetMs = 100, maxFalseOpeningsPer10s = 5.0)

    fun build(): List<Clip> = listOf(
        // 3.39s, moderate loudness (-22 dBFS), 2 regions with one real 0.20s pause.
        utterance("dev-other-116-288045-0000-trim", REQUIRE),
        // 4.89s, quiet talker (-31.5 dBFS), one continuous region — the sustained-coverage case
        // and the future AGC loudness target (spec 2).
        utterance("dev-other-700-122866-0000", REQUIRE),
        // 8.45s, -24.5 dBFS, 4 regions across 3 real pauses (0.23/0.66/0.51s) — the
        // hangover-across-pauses case: gate may close in a gap but must re-cover each region.
        utterance("dev-other-1255-138279-0002", REQUIRE),
    )
}
