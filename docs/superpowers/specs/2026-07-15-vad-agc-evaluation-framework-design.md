# VAD / AGC Evaluation Framework — Design

**Date:** 2026-07-15
**Status:** Approved (brainstorm), pending implementation plan
**Feature:** A consistent, real-audio evaluation harness that runs the **actual** transmit DSP
(host-built RNNoise + the production `TransmitGate`) over a committed corpus of labeled clips,
computes VAD and gain metrics against ground truth, asserts CI thresholds, and emits a comparison
report — a fixed yardstick for every future VAD/AGC change.

Sub-project **4 of 4** in the audio-quality effort. It is the measurement backstop for sub-project 2
(AGC) and future VAD swaps (RNNoise tuning, TEN VAD, Silero). Related: spec 1 (activation modes) and
spec 2 (AGC) both land their transmit DSP in the `TransmitProcessor` this spec extracts.

---

## Goal

Make transmit-path DSP changes measurable instead of subjective. Given a corpus of clips with known
speech/silence regions, assert in CI that the gate transmits the **full utterance** (hysteresis +
hangover working end-to-end on real audio, no mid-utterance cut-offs) and record objective loudness
numbers so the AGC work has a baseline and a target.

## Scope

**In:**
- Host build of the pinned RNNoise (+ existing JNI shim) so the **real** VAD runs in JVM unit tests.
- A **simple** extraction of the per-capture transmit-decision core into `TransmitProcessor`, shared
  by `AudioVoiceEngine` and the evaluator (so the harness tests the real logic, not a copy).
- A committed **constructed-composite** corpus with a manifest of exact ground-truth labels.
- `VadEvaluator` computing **VAD metrics** (coverage, onset, hangover, mid-utterance dropouts,
  false-activation) and **gain metrics** (output loudness, cross-clip consistency, clipping).
- A JUnit test asserting per-category CI thresholds + a machine-readable/markdown report artifact.
- A **minimal GitHub Actions workflow** running the unit suite + host build + eval.

**Out (deferred):**
- Perceptual quality scores (PESQ/STOI/DNSMOS) — the research doc's go/no-go metric for *denoise*
  quality; out of scope for this gate/gain harness (possible later).
- On-device / instrumented RNNoise validation — the host build is numerically faithful (below); an
  instrumented cross-check is a future nicety, not required.
- Full-pipeline Opus encode in the harness — the host lib is scoped to RNNoise; Opus can be added if
  encode-path metrics are ever wanted.

---

## Verified constraint: run the real RNNoise in JVM CI via a host build

Fable-verified against the actual `libdumble.so` and the vendored sources (2026-07-15):
- The Android NDK `.so` links **Bionic** (`DT_NEEDED libc.so/libm.so/libdl.so`, `LIBC` symbol
  versions); it **cannot** load in a glibc JVM, and `qemu-aarch64` fixes the ISA, not the libc — so
  even the x86_64 Android ABI `.so` won't load host-side. Every "run the Android build" route
  (libhybris, redroid, houdini, qemu + system image) needs a Bionic userspace and is heavier than a
  host rebuild. A KVM emulator is possible but costs minutes/run vs seconds.
- The pinned RNNoise (`6cbfd53`) is **pure scalar C, libm-only**; `rnnoise_jni.c` includes **zero
  Android headers**. It rebuilds for the host with `find_package(JNI)` unchanged.
- **Determinism:** host vs Android VAD probabilities differ only ~1e-6–1e-4 (RNNoise uses table-based
  activations, avoiding libm `tanh` divergence). **The harness compares metrics with an epsilon at
  the gate threshold — never bit-equality — and both builds keep `-ffast-math`/`-Ofast` OFF** (the
  one flag that would make divergence material).

---

## Architecture

### 1. Host-native build
- `app/src/main/cpp/CMakeLists.txt`: guard Android-only pieces behind `if(ANDROID)` — the
  `find_library(log …)`/liblog link and `-Wl,-z,max-page-size=16384`. In the `else()` (host) branch,
  `find_package(JNI REQUIRED)` and add its include dirs. Build target `dumble` from the RNNoise
  sources (FetchContent, same pinned commit) + `rnnoise_jni.c`. **No `-ffast-math`.** (Opus stays
  Android-only for now; the host lib needs only RNNoise + the shim.)
- `app/build.gradle.kts`: a `buildHostRnnoise` task invokes `cmake -B build/host-native
  -DANDROID=OFF …` + `cmake --build`, producing `build/host-native/libdumble.{so,dylib}`. The unit
  test task depends on it and runs with `jvmArgs("-Djava.library.path=<abs>/build/host-native")`.
- Consequence: the **production** `NativeRnnoise` (`System.loadLibrary("dumble")`) and
  `RnnoiseSuppressor` load the host lib **unchanged** in tests — the eval uses the real binding.

### 2. `TransmitProcessor` extraction (kept simple)
Pull the per-capture decision core out of `AudioVoiceEngine.nextOutgoingFrame` into one small class:
```kotlin
class TransmitProcessor(
    private val suppressor: NoiseSuppressor,
    private val vad: VadDetector,
    private val gate: TransmitGate,
) {
    private val subLevels = FloatArray(FRAMES_PER_PACKET)
    /** Denoise the capture in place, then decide send/terminator for this 20 ms capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            subLevels[i] = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
        }
        return gate.update(subLevels)
    }
    fun reset() = gate.reset()
}
```
`AudioVoiceEngine` holds a `TransmitProcessor` and calls `process(capturePcm)`; **mute handling,
`frameNumber`, Opus encode, and terminator emission stay in the engine** (unchanged behavior). This
is the natural home for spec 1's activation-mode branch (the `subLevels` source) and spec 2's AGC
stage — both land here later, in one place, rather than in the engine loop. v1 keeps it minimal
(suppressor → vad → gate).

### 3. Corpus (`app/src/test/resources/vad-corpus/`)
- Committed license-clean base speech snippets + noise beds (the ingredients), and the **constructed
  composite WAVs** (48 kHz mono PCM16) built from them, plus `manifest.json`:
  ```json
  { "clips": [
    { "file": "clean_contiguous.wav", "sampleRate": 48000,
      "segments": [ {"startMs": 0, "endMs": 300, "kind": "silence"},
                    {"startMs": 300, "endMs": 2100, "kind": "speech"} ],
      "snrDb": null, "category": "clean",
      "thresholds": { "minCoverage": 0.99, "maxMidUtteranceDropoutMs": 0, "maxOnsetMs": 60 } } ] }
  ```
- A `CorpusBuilder` util constructs each composite from ingredients + programmatic silence/noise so
  labels are **exact by construction** and the corpus is reproducible/extendable (committed WAVs are
  the source of truth for CI; the builder regenerates them).
- Categories that stress the gate: **contiguous speech**, **speech with known intra-utterance pauses**
  (hangover must bridge gaps ≤ its 200 ms window), **quiet-onset speech**, **noisy speech** at known
  SNR, **noise-only** and **pure silence** (false-activation). Plus 1–2 real recordings for realism.

### 4. `VadEvaluator` + metrics
Feeds each clip's 20 ms captures through a `TransmitProcessor` (RNNoise host lib + gate), recording
per-capture `send`/`terminator` and the in-place-denoised PCM. Decisions map onto ground-truth
segments (20 ms capture granularity — the gate's natural decision unit):

| Metric | Definition | Category threshold example |
|---|---|---|
| **Speech coverage** | fraction of speech-labeled captures with `send=true` | ≥ 0.99 (clean), ≥ 0.95 (noisy) |
| **Onset latency** | ms from a speech segment's start to its first `send` | ≤ 60 ms (clean) |
| **Hangover/tail** | ms `send` stays true past a speech segment's end | ≥ 100, ≤ ~260 ms |
| **Mid-utterance dropouts** | count/ms of `send=false` runs *inside* a speech segment after onset | 0 ms (contiguous); ≤ pause length (paused) |
| **False-activation** | fraction of silence/noise captures with `send=true` | ≤ 0.02 (noise-only) |
| **Output loudness** | RMS dBFS of the transmitted (gated, denoised) PCM | baseline now; AGC target later |
| **Level consistency** | stdev of output loudness across clips of differing input level | shrinks under AGC |
| **Clipping** | count of full-scale samples in transmitted PCM | 0 |

Emits `build/reports/vad-eval/metrics.{json,md}` — a per-clip table for comparing VAD/AGC variants.

### 5. CI
- `VadEvaluationTest` (JUnit, in `testDebugUnitTest`) loads the corpus, runs the evaluator, asserts
  each clip's per-category thresholds from the manifest, and writes the report.
- `.github/workflows/ci.yml` (ubuntu-latest): checkout, set up JDK + Android SDK + `cmake`/build
  tools, `./gradlew testDebugUnitTest` (depends on `buildHostRnnoise`), upload the report artifact.
  Scoped to tests + eval, not a release pipeline.

---

## Components / files
- **Modify:** `app/src/main/cpp/CMakeLists.txt` (host `if(ANDROID)` guards + `find_package(JNI)`);
  `app/build.gradle.kts` (`buildHostRnnoise`, test `jvmArgs`/dependsOn); `AudioVoiceEngine.kt`
  (delegate the per-capture core to `TransmitProcessor`).
- **New (main):** `mumble/voice/TransmitProcessor.kt`.
- **New (test):** `mumble/voice/eval/WavReader.kt` (PCM16 WAV read/write), `CorpusBuilder.kt`,
  `VadEvaluator.kt`, `EvalReport.kt`, `VadEvaluationTest.kt`; `app/src/test/resources/vad-corpus/`
  (ingredients + composite WAVs + `manifest.json`).
- **New (infra):** `.github/workflows/ci.yml`.

## Testing
- The eval itself is the headline CI test. Supporting unit tests: `WavReader` round-trips PCM16;
  metric computations are correct on hand-built decision/label fixtures (coverage, dropout detection,
  onset, false-activation, loudness); `CorpusBuilder` produces the labeled segments it claims.
- A small **drift guard**: assert `AudioVoiceEngine` and a direct `TransmitProcessor` produce the same
  send-decision sequence on one scripted input (guarantees the extraction stayed faithful).

## Determinism & caveats
- Metrics-with-epsilon, never bit-equality; no fast-math (both builds). Host RNNoise ≈ Android to
  ~1e-4 — immaterial except for frames sitting exactly on the threshold, which the epsilon absorbs.
- Corpus is committed (deterministic CI); the builder makes it reproducible, not test-time-random.
- Thresholds live in the manifest per category so noisy/quiet clips have appropriate bars and a change
  in bar is a reviewable diff.

## Interaction with other sub-projects
- **Spec 1 (activation modes):** its activation-mode `subLevels` source (VAD prob vs PTT 1/0) lands
  inside `TransmitProcessor`; the eval framework then covers both modes.
- **Spec 2 (AGC):** its makeup-gain stage lands inside `TransmitProcessor` (post-denoise), and the
  gain metrics here become its before/after scoreboard — build this before, or alongside, spec 2.
- **Spec 3 (call screen):** unaffected; shares no code.
