package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import java.io.File

enum class Kind { SPEECH, PAUSE, SILENCE, NOISE }

data class Segment(val startMs: Int, val endMs: Int, val kind: Kind)

/** Per-clip regression-guard bars (pinned below the measured baseline; see CorpusBuilder). */
data class Thresholds(
    val minCoverage: Double = 0.95,
    val maxMidUtteranceDropoutMs: Int = 100,
    val maxOnsetMs: Int = 100,
    val maxFalseOpeningsPer10s: Double = 5.0,
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
 * ACCEPTANCE MODEL — a REGRESSION GUARD pinned at the gate's current behavior, NOT a coverage==1.0
 * requirement. Design decision: Dumble matches Mumble's transmit model. Mumble has NO onset pre-roll
 * and clips soft speech onsets (verified in Mumble's AudioInput.cpp: the encoder is fed only while
 * transmitting; smoothing is tail-only, via hangover / iHoldFrames). So the gate opening 20-80 ms
 * into a region's soft onset — and re-onset after a pause — is EXPECTED and accepted here; coverage
 * tops out ~0.96-0.99, not 1.0. Per-clip bars sit a little below the measured baseline so a genuine
 * degradation trips the test but normal variation does not.
 *  - PAUSE captures are exempt from coverage AND false-activation (hangover may bridge a pause into
 *    the next region).
 *  - Only a truly spurious open (SILENCE not adjacent to speech: preroll, deep trail) is a false
 *    activation.
 *  - Eliminating the onset clip would need a pre-roll ring buffer (flush recent audio on gate-open)
 *    — a deferred OPTIONAL enhancement to revisit when tuning Silero (task #35). Not needed for
 *    Mumble parity.
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

    // Per-clip REGRESSION GUARDS pinned just below the measured baseline (openLevel=0.60), NOT a
    // coverage=1.0 requirement — see the acceptance-model note above. Each `measured:` comment is the
    // current value; the bar leaves a little slack so a genuine gate degradation trips it but normal
    // variation does not. maxOnsetMs/maxFalseOpeningsPer10s are loose (onset is subsumed by coverage;
    // false opens all measure 0). Coverage never reaches 1.0 by design: the gate clips 20-80 ms of
    // each soft onset/re-onset, matching Mumble. A pre-roll buffer to recover that is deferred (#35).
    //
    // TODO(#34): add a dedicated noise-only false-activation clip (real MUSAN noise) — the gate must
    // stay shut on ~10 s of pure non-speech; also the prerequisite for safely lowering openLevel.
    fun build(): List<Clip> = listOf(
        // 3.39s, -22 dBFS, 2 regions / one 0.20s pause. measured: cov 0.983, onset 23ms, midDrop 20ms.
        utterance("dev-other-116-288045-0000-trim", Thresholds(
            minCoverage = 0.97, maxMidUtteranceDropoutMs = 60,
            maxOnsetMs = 80, maxFalseOpeningsPer10s = 5.0)),
        // 4.89s, quiet talker (-31.5 dBFS), 1 continuous region; future AGC target (spec 2).
        // measured: cov 0.990, onset 31ms, midDrop 0.
        utterance("dev-other-700-122866-0000", Thresholds(
            minCoverage = 0.98, maxMidUtteranceDropoutMs = 40,
            maxOnsetMs = 80, maxFalseOpeningsPer10s = 5.0)),
        // 8.45s, -24.5 dBFS, 4 regions / 3 pauses (0.23/0.66/0.51s) — hangover-across-pauses; the
        // gate closes in each gap and re-onsets late. measured: cov 0.960, onset 43ms, midDrop 200ms.
        utterance("dev-other-1255-138279-0002", Thresholds(
            minCoverage = 0.94, maxMidUtteranceDropoutMs = 280,
            maxOnsetMs = 80, maxFalseOpeningsPer10s = 5.0)),
    )
}
