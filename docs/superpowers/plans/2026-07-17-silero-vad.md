# Silero v6 VAD Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Silero v6 as a first-class, user-selectable, persisted voice-detection engine running on-device via ONNX Runtime, behind the existing `VadDetector` seam, plus a configurable onset-recovery lookahead delay.

**Architecture:** A self-contained `SileroVadDetector : VadDetector` owns a 48k→16k anti-alias decimator, a 512-sample ring buffer, an ORT session over the 1.3 MB 16k ONNX, the recurrent state/context, and a probability hold. It slots in as `vad = silero` while `suppressor = rnnoise` stays for (now-optional) denoising. Two small engine/processor changes (raw-mic VAD input + a `reset()` seam) and an engine-agnostic configurable lookahead-delay queue round it out. The eval harness runs Silero alongside RNNoise to pick defaults from measured numbers.

**Tech Stack:** Kotlin, ONNX Runtime Android/JVM 1.27.0, Silero VAD v6 ONNX (MIT), JUnit, Jetpack Compose (Material 3), existing Opus/RNNoise CMake/JNI pipeline.

**User decisions (already made):**
- "Full production engine" — persisted VAD-source selector, polished now.
- Silero v6, ONNX Runtime Android (full) + desktop jar for eval; model `silero_vad_16k_op15.onnx` (~1.3 MB).
- "Decide after eval numbers" — default engine chosen by the user from `metrics.md` *after* this lands; ship selectable with `vad_engine` default `"rnnoise"`.
- VAD input = **raw mic** (pre-denoise); eval harness compares raw vs denoised.
- RNNoise denoise **default OFF**.
- Engine switching = **live-switch mid-call**.
- Onset = **configurable lookahead delay**, K **default 0** (provable no-op).

**Deferred decision (post-merge, not a plan task):** the *default VAD engine* and the *good Silero threshold* are chosen by the user from the eval `metrics.md` this plan produces (Task 9). The plan ships `vad_engine` defaulting to `"rnnoise"` and does not assert Silero metrics — flipping the default is a one-line follow-up once the numbers are in. This is recorded, not re-asked.

**Spec:** `docs/superpowers/specs/2026-07-17-silero-vad-design.md` (committed a6943bd on branch `silero-vad`).

**Environment:** every gradle invocation must `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` first. Never stage `.idea/gradle.xml`. Work on branch `silero-vad` (already checked out). Each task stages only its own files.

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `.../voice/Decimator.kt` (new) | 3:1 anti-alias FIR decimator, 48k→16k, stateful | 1 |
| `.../voice/SileroOnnxSession.kt` (new) | ORT session wrapper: load, input-name validation, run one 576-wide chunk, close | 2 |
| `.../voice/SileroVadDetector.kt` (new) | `VadDetector`: decimate → ring buffer → window → ORT → prob-hold → reset | 3 |
| `.../voice/VadDetector.kt` (mod) | add `reset()` default no-op | 3 |
| `.../voice/TransmitProcessor.kt` (mod) | raw-input snapshot to `vad.level()` | 4 |
| `.../voice/AudioVoiceEngine.kt` (mod) | `vad.reset()` at discontinuities; `setVadDetector()`; lookahead delay wiring | 4, 5, 6 |
| `.../voice/LookaheadDelay.kt` (new) | K-capture delay ring + gate lookahead (K=0 identity) | 5 |
| `.../mumble/MumbleManager.kt` (mod) | engine selection + persistence + RNNoise default off + live-switch + lookahead setting | 6 |
| `.../ui/SettingsScreen.kt`, `ui/DumbleApp.kt` (mod) | engine selector + lookahead slider | 7 |
| `.../VadDebugActivity.kt` (mod) | add Silero to the debug bench | 8 |
| `.../voice/eval/VadEvaluator.kt` (mod), `eval/VadEvaluationTest.kt` (mod) | run Silero (raw + denoised) on the corpus; comparative metrics | 9 |
| `app/src/main/assets/silero_vad_16k_op15.onnx` (new) | the model | 2 |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` (mod) | ORT deps | 2 |

---

### Task 1: `Decimator` — 48 kHz → 16 kHz anti-alias FIR

**Goal:** A stateful 3:1 polyphase anti-alias decimator turning 480 samples @48k into 160 samples @16k, with a windowed-sinc low-pass that keeps aliased noise out of the speech band.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/Decimator.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/DecimatorTest.kt`

**Acceptance Criteria:**
- [ ] `decimate(ShortArray(480))` returns 160 `Float` samples normalized to [-1, 1].
- [ ] A pure tone above 8 kHz is attenuated ≥ 40 dB in the output (anti-alias); a 1 kHz tone passes through within 1 dB.
- [ ] Output is continuous across calls (FIR tap history carried); `reset()` zeros the history.
- [ ] Deterministic: same input twice (after reset) → identical output.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*DecimatorTest*"` → all pass.

**Steps:**

- [ ] **Step 1: Write failing tests**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DecimatorTest {
    private fun tone(freqHz: Double, n: Int, ampl: Double = 0.5): ShortArray =
        ShortArray(n) { (sin(2 * PI * freqHz * it / SAMPLE_RATE) * ampl * 32767).toInt().toShort() }

    private fun rms(x: FloatArray): Double {
        var s = 0.0; for (v in x) s += v.toDouble() * v; return sqrt(s / x.size)
    }

    @Test fun producesThirdSampleCount() {
        val d = Decimator()
        val out = d.decimate(ShortArray(480), 0, 480)
        assertEquals(160, out.size)
    }

    @Test fun passesSpeechBandTone() {
        val d = Decimator()
        // Feed enough frames to prime the FIR, measure a steady-state block.
        val inp = tone(1000.0, 480 * 8)
        var last = FloatArray(0)
        for (b in 0 until 8) last = d.decimate(inp, b * 480, 480)
        // 1 kHz half-amplitude sine → RMS ≈ 0.5/sqrt(2) ≈ 0.354, within ~1 dB.
        assertTrue("1kHz RMS ${rms(last)}", rms(last) in 0.30..0.40)
    }

    @Test fun attenuatesAboveNyquist() {
        val d = Decimator()
        // 11 kHz is above the 8 kHz Nyquist of 16 kHz — must be knocked down hard.
        val inp = tone(11000.0, 480 * 8)
        var last = FloatArray(0)
        for (b in 0 until 8) last = d.decimate(inp, b * 480, 480)
        // 0.5 ampl → passband RMS would be ~0.354; require ≥40 dB down (~<0.0035).
        assertTrue("11kHz RMS ${rms(last)} not attenuated", rms(last) < 0.0035)
    }

    @Test fun resetMakesDeterministic() {
        val d = Decimator()
        val inp = tone(1000.0, 480 * 4)
        var a = FloatArray(0); for (b in 0 until 4) a = d.decimate(inp, b * 480, 480)
        d.reset()
        var c = FloatArray(0); for (b in 0 until 4) c = d.decimate(inp, b * 480, 480)
        assertTrue(a.toList() == c.toList())
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL** (`Decimator` unresolved).

- [ ] **Step 3: Implement `Decimator`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * 3:1 decimator (48 kHz → 16 kHz), stateful across calls. A windowed-sinc (Hann) anti-alias
 * low-pass at ~7 kHz precedes take-every-3rd so broadband HF noise (keyboard, hiss, fan) can't
 * alias into the speech band and inflate VAD false positives. Single-thread (send thread).
 */
class Decimator(
    numTaps: Int = 33,               // odd → integer group delay
    cutoffHz: Double = 7000.0,
) {
    private val taps: FloatArray = designLowpass(numTaps, cutoffHz, SAMPLE_RATE.toDouble())
    private val history = FloatArray(numTaps)   // circular delay line of recent input samples
    private var histPos = 0
    private var phase = 0                        // 0..2, which input samples become outputs

    /** Decimate [n] samples at pcm[off] (n must be a multiple of 3) → n/3 float samples in [-1,1]. */
    fun decimate(pcm: ShortArray, off: Int, n: Int): FloatArray {
        val out = FloatArray(n / 3)
        var oi = 0
        for (i in 0 until n) {
            // push sample into the delay line
            history[histPos] = pcm[off + i] / 32768f
            histPos = (histPos + 1) % history.size
            // emit a filtered output on every 3rd input sample
            if (phase == 0) out[oi++] = filter()
            phase = (phase + 1) % 3
        }
        return out
    }

    fun reset() { history.fill(0f); histPos = 0; phase = 0 }

    /** FIR dot product over the delay line (most-recent-first alignment is irrelevant for a symmetric LPF). */
    private fun filter(): Float {
        var acc = 0f
        var idx = histPos - 1
        for (k in taps.indices) {
            if (idx < 0) idx += history.size
            acc += taps[k] * history[idx]
            idx--
        }
        return acc
    }

    private companion object {
        fun designLowpass(numTaps: Int, cutoffHz: Double, fs: Double): FloatArray {
            val fc = cutoffHz / fs                       // normalized cutoff (cycles/sample)
            val mid = (numTaps - 1) / 2.0
            val h = FloatArray(numTaps)
            var sum = 0.0
            for (k in 0 until numTaps) {
                val x = k - mid
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val hann = 0.5 - 0.5 * kotlin.math.cos(2 * PI * k / (numTaps - 1))
                val v = sinc * hann
                h[k] = v.toFloat(); sum += v
            }
            for (k in 0 until numTaps) h[k] = (h[k] / sum).toFloat()   // unity DC gain
            return h
        }
    }
}
```

- [ ] **Step 4: Run tests — expect PASS.** If `attenuatesAboveNyquist` is marginal, raise `numTaps` to 41 (still trivial cost).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/Decimator.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/DecimatorTest.kt
git commit -m "feat(voice): 3:1 anti-alias decimator (48k→16k) for Silero VAD"
```

---

### Task 2: ORT dependency + model asset + `SileroOnnxSession`

**Goal:** Add ONNX Runtime (Android + desktop-JVM-for-tests), bundle the 1.3 MB 16k Silero model as an asset, and wrap the session so callers feed one 576-sample chunk + state and get back a probability + new state — with input-name validation and a wrong-width guard test proving `[1,512]` collapses.

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create asset: `app/src/main/assets/silero_vad_16k_op15.onnx`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SileroOnnxSession.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SileroOnnxSessionTest.kt`

**Acceptance Criteria:**
- [ ] `onnxruntime-android:1.27.0` on `implementation`, `onnxruntime:1.27.0` on `testImplementation` (via version catalog).
- [ ] Model asset present (~1.3 MB); a load-from-bytes path exists.
- [ ] `SileroOnnxSession.run(FloatArray(576), state)` returns `prob in 0f..1f` and a new `[2,1,128]` state.
- [ ] Feeding 576 zeros yields `prob < 0.15` (silence sanity).
- [ ] **Wrong-width guard:** running the raw 512 window WITHOUT the 64-sample context (i.e. a 512-wide input) yields a markedly lower/collapsed probability than the correct 576-wide input on the same speech-like data — the test asserts the difference, proving the 576 contract is load-bearing.
- [ ] Determinism: same input + state → identical output.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*SileroOnnxSessionTest*"` → pass.

**Steps:**

- [ ] **Step 1: Fetch the model asset**

```bash
mkdir -p app/src/main/assets
curl -L -o app/src/main/assets/silero_vad_16k_op15.onnx \
  https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad_16k_op15.onnx
# Expect ~1.3 MB. If the path 404s, list the repo's src/silero_vad/data/ and pick the 16k op15 file;
# fall back to silero_vad_op18_ifless.onnx (~2.85 MB) and set MODEL_ASSET accordingly.
ls -l app/src/main/assets/silero_vad_16k_op15.onnx
```

- [ ] **Step 2: Add ORT deps**

In `gradle/libs.versions.toml` add under `[versions]`: `onnxruntime = "1.27.0"`, and under `[libraries]`:
```toml
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
onnxruntime-jvm = { group = "com.microsoft.onnxruntime", name = "onnxruntime", version.ref = "onnxruntime" }
```
In `app/build.gradle.kts` `dependencies {}` add:
```kotlin
implementation(libs.onnxruntime.android)
testImplementation(libs.onnxruntime.jvm)
```

- [ ] **Step 3: Write failing test** (host JVM; ORT native comes from the jvm jar)

```kotlin
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

    private fun modelBytes(): ByteArray =
        File("src/main/assets/silero_vad_16k_op15.onnx").readBytes()

    // Speech-like 16k content: mix of formant-ish tones, half amplitude.
    private fun speechish(n: Int): FloatArray = FloatArray(n) {
        (0.3 * sin(2 * PI * 220 * it / 16000) +
         0.2 * sin(2 * PI * 700 * it / 16000) +
         0.1 * sin(2 * PI * 2500 * it / 16000)).toFloat()
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
        // 576 = 64 context (zeros) ++ 512 speech-like. The correct contract.
        val speech = speechish(512)
        val correct = FloatArray(576).also { System.arraycopy(speech, 0, it, 64, 512) }
        val pCorrect = session.run(correct, SileroOnnxSession.newState()).prob
        // Feeding a bare 512-wide input is the silent-failure trap fable found.
        val pWrong = session.runRaw(speech, SileroOnnxSession.newState()).prob
        assertTrue("correct=$pCorrect wrong=$pWrong should differ markedly",
            pCorrect - pWrong > 0.2f || (pCorrect > 0.3f && pWrong < 0.05f))
    }
}
```

- [ ] **Step 4: Implement `SileroOnnxSession`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Thin ORT wrapper over the Silero v6 16k ONNX. Inputs: `input` [1,576] (64 carried context ++ 512
 * window), `state` [2,1,128], and `sr`=16000 (int64) IF the model declares it (the 16k-only export may
 * not). Outputs: probability [1,1] + new state [2,1,128]. Single-thread; NOT reused across threads.
 *
 * fable's traps: the sequence dim is dynamic so metadata can't reveal the 576 width — we hardcode it;
 * feeding [1,512] runs but returns collapsed probabilities (see [runRaw], used only by the guard test).
 * Copy state out before closing the Result (it's native memory).
 */
class SileroOnnxSession(modelBytes: ByteArray) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    private val hasSr = session.inputNames.contains("sr")

    data class Result(val prob: Float, val state: FloatArray)

    /** Correct path: [input576] must be 576 wide (64 context ++ 512 window). */
    fun run(input576: FloatArray, state: FloatArray): Result {
        require(input576.size == INPUT_WIDTH) { "expected 576, got ${input576.size}" }
        return infer(input576, INPUT_WIDTH, state)
    }

    /** Guard-test only: feed a bare [1,size] input to demonstrate the wrong-width collapse. */
    fun runRaw(input: FloatArray, state: FloatArray): Result = infer(input, input.size, state)

    private fun infer(input: FloatArray, width: Int, state: FloatArray): Result {
        val inT = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, width.toLong()))
        val stT = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val srT = if (hasSr)
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()) else null
        val feeds = HashMap<String, OnnxTensor>().apply {
            put("input", inT); put("state", stT); if (srT != null) put("sr", srT)
        }
        try {
            session.run(feeds).use { res ->
                val prob = (res[0].value as Array<FloatArray>)[0][0]
                val outState = flatten(res[1].value)          // copy OUT before res closes
                return Result(prob, outState)
            }
        } finally {
            inT.close(); stT.close(); srT?.close()
        }
    }

    fun close() { session.close() }

    companion object {
        const val INPUT_WIDTH = 576
        fun newState() = FloatArray(2 * 1 * 128)
        /** [2,1,128] nested Float arrays → flat FloatArray(256). */
        private fun flatten(v: Any?): FloatArray {
            @Suppress("UNCHECKED_CAST")
            val a = v as Array<Array<FloatArray>>
            val out = FloatArray(2 * 1 * 128); var i = 0
            for (p in a) for (q in p) for (x in q) out[i++] = x
            return out
        }
    }
}
```

- [ ] **Step 5: Run tests — expect PASS.** If ORT can't load the model, confirm the asset downloaded fully (~1.3 MB, not an HTML 404 body). If `wrongWidthCollapses` fails to differ, the export may handle context internally — then set `INPUT_WIDTH=512`, drop the context prepend in Task 3, and re-pin the guard against a bad asset instead.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/assets/silero_vad_16k_op15.onnx \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/SileroOnnxSession.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SileroOnnxSessionTest.kt
git commit -m "feat(voice): ONNX Runtime + Silero 16k model + session wrapper (576 contract, wrong-width guard)"
```

---

### Task 3: `SileroVadDetector` + `VadDetector.reset()` seam

**Goal:** The `VadDetector` that composes the decimator + ORT session: buffers decimated 16k samples, runs one inference per completed 512-sample window (carrying state + 64-sample context), holds the last probability between inferences, and re-zeros everything on `reset()`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VadDetector.kt` (add `reset()` default)
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SileroVadDetector.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SileroVadDetectorTest.kt`

**Acceptance Criteria:**
- [ ] `VadDetector` gains `fun reset() {}` (default no-op); `EnergyVadDetector`/`RnnoiseSuppressor` unchanged behaviorally.
- [ ] `level(pcm,off,480)` returns a held `Float` in [0,1] every call; a new inference occurs once per ~3.2 calls (once per 512 accumulated 16k samples).
- [ ] Over 16 consecutive 480-sample calls exactly 5 inferences run (512×5 = 160×16).
- [ ] Sustained speech-like input drives the held probability up; silence keeps it low.
- [ ] `reset()` returns state/context/buffer/held-prob to the fresh-detector baseline (post-reset output on a fixed sequence equals a brand-new detector's).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*SileroVadDetectorTest*"` → pass.

**Steps:**

- [ ] **Step 1: Add `reset()` to the seam** — in `VadDetector.kt`, inside `interface VadDetector`, add after `level(...)`:

```kotlin
    /** Reset any streaming state on a capture discontinuity (start / unmute / mode change). Default no-op. */
    fun reset() {}
```

- [ ] **Step 2: Write failing tests** (counts inferences via a spy session; drives cadence)

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class SileroVadDetectorTest {
    private fun bytes() = File("src/main/assets/silero_vad_16k_op15.onnx").readBytes()
    private fun det() = SileroVadDetector(SileroOnnxSession(bytes()))
    private fun silence() = ShortArray(480)
    private fun speech(seed: Int) = ShortArray(480) {
        ((0.4 * sin(2 * PI * 200 * (it + seed * 480) / 48000) +
          0.2 * sin(2 * PI * 900 * (it + seed * 480) / 48000)) * 32767).toInt().toShort()
    }

    @Test fun returnsHeldProbabilityEveryCall() {
        val d = det()
        repeat(3) { assertEquals(0f, d.level(silence(), 0, 480), 0f) } // no inference yet → initial 0
        d.close()
    }

    @Test fun fiveInferencesPerSixteenCalls() {
        val spy = CountingSession(bytes())
        val d = SileroVadDetector(spy)
        repeat(16) { i -> d.level(speech(i), 0, 480) }
        assertEquals(5, spy.runs)
        d.close()
    }

    @Test fun speechRaisesHeldProbability() {
        val d = det()
        var last = 0f
        repeat(20) { i -> last = d.level(speech(i), 0, 480) }
        assertTrue("speech prob $last", last > 0.4f)
        d.close()
    }

    @Test fun resetRestoresBaseline() {
        val d = det()
        repeat(20) { i -> d.level(speech(i), 0, 480) }
        d.reset()
        val fresh = det()
        val a = FloatArray(8) { d.level(speech(it), 0, 480) }
        val b = FloatArray(8) { fresh.level(speech(it), 0, 480) }
        assertTrue(a.toList() == b.toList())
        d.close(); fresh.close()
    }
}

/** Test double that counts inferences while delegating to the real ORT session. */
private class CountingSession(bytes: ByteArray) : SileroOnnxSession(bytes) {
    var runs = 0
    override fun run(input576: FloatArray, state: FloatArray): Result { runs++; return super.run(input576, state) }
}
```

Make `SileroOnnxSession.run` and the class `open` (or extract a `SileroInference` interface) so `CountingSession` can subclass. Simplest: mark `class SileroOnnxSession` and `fun run` as `open`.

- [ ] **Step 3: Implement `SileroVadDetector`**

```kotlin
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
    private val ring = FloatArray(RING_CAP)      // simple linear buffer, compacted after each window
    private var ringLen = 0
    private val context = FloatArray(CONTEXT)    // last 64 samples of the previous window
    private var state = SileroOnnxSession.newState()
    private var held = 0f

    override fun level(pcm: ShortArray, off: Int, n: Int): Float {
        val ds = decimator.decimate(pcm, off, n)              // 160 samples @16k
        System.arraycopy(ds, 0, ring, ringLen, ds.size)
        ringLen += ds.size
        while (ringLen >= WINDOW) {
            val input = FloatArray(SileroOnnxSession.INPUT_WIDTH)  // 576
            System.arraycopy(context, 0, input, 0, CONTEXT)       // 64 context
            System.arraycopy(ring, 0, input, CONTEXT, WINDOW)     // 512 window
            val r = session.run(input, state)
            held = r.prob; state = r.state
            System.arraycopy(ring, WINDOW - CONTEXT, context, 0, CONTEXT)  // new context = window tail
            // compact the ring (drop the consumed 512)
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
        const val RING_CAP = WINDOW + 160   // ≤512 residual + one 160-sample push
    }
}
```

- [ ] **Step 4: Run tests — expect PASS.** `fiveInferencesPerSixteenCalls` proves the cadence; `resetRestoresBaseline` proves the discontinuity reset.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/VadDetector.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/SileroVadDetector.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/SileroOnnxSession.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SileroVadDetectorTest.kt
git commit -m "feat(voice): SileroVadDetector (window/state/context/hold) + VadDetector.reset() seam"
```

---

### Task 4: Raw-mic VAD input + engine reset wiring

**Goal:** Feed the VAD the raw mic sub-frame (pre-denoise) and call `vad.reset()` at capture discontinuities, so Silero sees in-distribution audio and re-zeros cleanly after gaps.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorRawInputTest.kt`

**Acceptance Criteria:**
- [ ] `TransmitProcessor` snapshots the raw sub-frame before `suppressor.process()` and passes the raw copy to `vad.level()`; the transmitted `capturePcm` is still the denoised/gained buffer.
- [ ] A spy `VadDetector` records that the samples it received equal the pre-denoise input, not the post-denoise output (proven with a suppressor that mutates the buffer).
- [ ] `AudioVoiceEngine` calls `vad.reset()` on `start()`, on the mute→unmute edge, and on transmit-mode change.
- [ ] Existing `TransmitProcessor`/`AudioVoiceEngine` tests still pass (RNNoise-as-VAD unaffected: its `level()` ignores the buffer arg).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*TransmitProcessor*" --tests "*AudioVoiceEngine*"` → pass.

**Steps:**

- [ ] **Step 1: Write failing test** proving VAD sees raw

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TransmitProcessorRawInputTest {
    /** Suppressor that overwrites every sample with 1 (denoise "in place"). */
    private class OverwriteSuppressor : NoiseSuppressor {
        override fun process(pcm: ShortArray, off: Int, n: Int) { for (i in off until off + n) pcm[i] = 1 }
        override fun close() {}
    }
    /** VAD that records the first sample it was handed. */
    private class RecordingVad : VadDetector {
        var firstSample: Short = 0
        override fun level(pcm: ShortArray, off: Int, n: Int): Float { firstSample = pcm[off]; return 0f }
    }

    @Test fun vadSeesRawNotDenoised() {
        val vad = RecordingVad()
        val proc = TransmitProcessor(OverwriteSuppressor(), vad, TransmitGate())
        val cap = ShortArray(CAPTURE_SAMPLES) { 500 }   // raw sample value 500
        proc.process(cap)
        assertEquals("VAD must see raw (500), not denoised (1)", 500.toShort(), vad.firstSample)
        assertEquals("output stays denoised", 1.toShort(), cap[0])
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (VAD currently sees the denoised buffer → 1).

- [ ] **Step 3: Edit `TransmitProcessor`** — add a reusable raw scratch and snapshot before denoise in BOTH loops.

Add field: `private val rawFrame = ShortArray(FRAME_SAMPLES_10MS)`.

In `process()`, replace the loop body's first two lines:
```kotlin
            val off = i * FRAME_SAMPLES_10MS
            System.arraycopy(capturePcm, off, rawFrame, 0, FRAME_SAMPLES_10MS)   // raw snapshot
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            val prob = vad.level(rawFrame, 0, FRAME_SAMPLES_10MS)                 // VAD on RAW
            subLevels[i] = prob
            gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
```
In `denoise()`, mirror the snapshot for the gain-enabled path:
```kotlin
            val off = i * FRAME_SAMPLES_10MS
            System.arraycopy(capturePcm, off, rawFrame, 0, FRAME_SAMPLES_10MS)
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            if (gain.enabled) {
                val prob = vad.level(rawFrame, 0, FRAME_SAMPLES_10MS)
                lastVadProb = prob
                gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
            }
```
Update the stale comment in `denoise()` that warned against stateful VADs — replace it with:
```kotlin
            // VAD reads the RAW snapshot (pre-denoise). A stateful VAD (Silero) is fine here IF the
            // engine resets it on discontinuities; in PTT the gate is unused so a missed advance is benign.
```

- [ ] **Step 4: Edit `AudioVoiceEngine`** — call `vad.reset()` at discontinuities. The engine already holds `vad` (constructor param). Add resets:
  - In `start()`, after `running = true`: `vad.reset()`.
  - In `computeOutgoing()`, the mode-change branch (`if (mode != lastMode)`) already calls `gate.reset()`; add `vad.reset()` next to it.
  - On the unmute edge: in the `wasMuted = false` transition. Currently `wasMuted` is set false unconditionally after the mute block; guard it: capture the prior value and reset on the falling edge:
```kotlin
        if (wasMuted) { vad.reset(); wasMuted = false }   // unmute edge → VAD discontinuity
```
  Place this immediately after the `if (muted) { ... return ... }` block (replacing the bare `wasMuted = false`).

- [ ] **Step 5: Run tests — expect PASS** (new test + existing suites).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorRawInputTest.kt
git commit -m "feat(voice): VAD reads raw mic (pre-denoise) + reset on capture discontinuities"
```

---

### Task 5: Configurable lookahead delay (onset recovery, K=0 = no-op)

**Goal:** An engine-agnostic K-capture delay ring with gate lookahead that, when K>0, transmits buffered pre-onset captures so talkspurt onsets aren't clipped — and at K=0 is a provable identity (byte-for-byte unchanged output).

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/LookaheadDelay.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/LookaheadDelayTest.kt`

**Acceptance Criteria:**
- [ ] `LookaheadDelay(k=0)` is identity: `offer(pcm, open)` immediately returns that same `pcm` with the same `open`.
- [ ] With `k=2`, `offer` returns the capture from 2 ticks earlier (buffering), and once any `open` inside the k-window is true, the emitted (older) captures carry `send=true` — i.e. pre-onset captures transmit.
- [ ] `flush()` drains buffered captures in order and clears the ring.
- [ ] Frame numbering stays contiguous (a constant K-shift) when wired into the engine.
- [ ] An `AudioVoiceEngine` integration test: with K=0 the emitted frame sequence for a fixed input equals the pre-change behavior (golden sequence captured before wiring); with K=2 the onset capture that was previously dropped is now emitted.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*LookaheadDelay*" --tests "*AudioVoiceEngine*"` → pass.

**Steps:**

- [ ] **Step 1: Write failing tests** for the pure unit

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookaheadDelayTest {
    private fun cap(v: Int) = ShortArray(CAPTURE_SAMPLES) { v.toShort() }

    @Test fun kZeroIsIdentity() {
        val d = LookaheadDelay(0)
        val out = d.offer(cap(7), open = true, frameNumber = 100)
        assertEquals(7, out!!.pcm[0].toInt())
        assertTrue(out.send)
        assertEquals(100L, out.frameNumber)
    }

    @Test fun kTwoBuffersAndRecoversOnset() {
        val d = LookaheadDelay(2)
        // ticks 0,1: buffering, nothing emitted yet
        assertNull(d.offer(cap(0), open = false, frameNumber = 0))
        assertNull(d.offer(cap(1), open = false, frameNumber = 1))
        // tick 2: gate opens now; the emitted capture is the OLD one (tick 0), and it must send
        val e2 = d.offer(cap(2), open = true, frameNumber = 2)!!
        assertEquals(0, e2.pcm[0].toInt())      // 2-capture delay
        assertTrue("pre-onset capture must transmit", e2.send)
    }

    @Test fun flushDrainsInOrder() {
        val d = LookaheadDelay(2)
        d.offer(cap(0), false, 0); d.offer(cap(1), false, 1)
        val drained = d.flush()
        assertEquals(listOf(0, 1), drained.map { it.pcm[0].toInt() })
    }
}
```

- [ ] **Step 2: Implement `LookaheadDelay`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Fixed lookahead-delay ring for onset recovery. The gate `open` boolean is computed on LIVE captures;
 * the TX path is delayed by [k] captures, so when the gate opens the pre-onset captures still in the
 * ring are transmitted (jitter-safe: in-order, contiguous frame numbers, constant k-capture shift).
 * k=0 is the identity (no buffering). Single-thread (send thread).
 */
class LookaheadDelay(private val k: Int) {
    class Emit(val pcm: ShortArray, val send: Boolean, val frameNumber: Long)
    private class Slot(val pcm: ShortArray, val open: Boolean, val frameNumber: Long)

    private val ring = ArrayDeque<Slot>()

    /** Offer the live capture; returns the capture to emit now (or null while still filling at k>0). */
    fun offer(pcm: ShortArray, open: Boolean, frameNumber: Long): Emit? {
        if (k == 0) return Emit(pcm, open, frameNumber)
        ring.addLast(Slot(pcm.copyOf(), open, frameNumber))
        if (ring.size <= k) return null                 // still priming the delay line
        val head = ring.removeFirst()
        // Lookahead: emit the (old) head as sending if ANY buffered capture (head..newest) is open.
        val send = head.open || ring.any { it.open }
        return Emit(head.pcm, send, head.frameNumber)
    }

    /** Drain everything still buffered, oldest-first (mute / mode-change / stop). */
    fun flush(): List<Emit> {
        val out = ring.map { Emit(it.pcm, it.open || ring.any { s -> s.open }, it.frameNumber) }
        ring.clear()
        return out
    }
}
```

- [ ] **Step 3: Run unit tests — expect PASS.**

- [ ] **Step 4: Capture the K=0 golden sequence, then wire into the engine.** Before editing, add an `AudioVoiceEngine` test that records the emitted `(frameNumber, isTerminator, firstSample)` sequence for a scripted input (a few captures of silence then speech then silence) with the *current* engine. Save that as the expected. Then introduce `LookaheadDelay` in `computeOutgoing`:
  - Add constructor param `initialLookaheadCaptures: Int = 0` and `@Volatile private var lookahead = LookaheadDelay(initialLookaheadCaptures)`.
  - Add `fun setLookaheadMs(ms: Int) { lookahead = LookaheadDelay((ms / 20).coerceAtLeast(0)) }` (20 ms per capture).
  - In VOICE_ACTIVATED, compute the gate decision as today to get `open` (= `d.send`), but route the *emit* through `lookahead.offer(capturePcm.copyOf(), open, fn)`; encode/emit the returned capture (null → return null this tick). On mute/mode-change/stop, `lookahead.flush()` the buffered captures (encode each, then the terminator).
  - Assert the K=0 golden sequence is unchanged; add a K=2 assertion that the pre-onset capture now emits.

  Keep the wiring minimal and behind K: when `lookahead` has k=0, `offer` returns the same capture immediately, so the emit path is identical to today (the golden test enforces this).

- [ ] **Step 5: Run tests — expect PASS** (K=0 identity golden + K=2 onset).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/LookaheadDelay.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/LookaheadDelayTest.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineLookaheadTest.kt
git commit -m "feat(voice): configurable lookahead-delay for onset recovery (K=0 identity)"
```

---

### Task 6: MumbleManager wiring — engine select, RNNoise default off, live-switch

**Goal:** Persist and apply the VAD engine choice, flip the RNNoise-denoise default to off, hot-swap the detector live via `AudioVoiceEngine.setVadDetector`, and expose the lookahead setting.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` (`setVadDetector`; `TransmitProcessor.vad` → `@Volatile var`)
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt` (`vad` becomes reassignable)
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineSwapTest.kt`

**Acceptance Criteria:**
- [ ] `MumbleManager` exposes `vadEngine: StateFlow<String>` and `setVadEngine(String)`, persisting `vad_engine` in `dumble_audio` (values `"energy"|"rnnoise"|"silero"`, default `"rnnoise"`).
- [ ] `rnnoise_enabled` default is now `false` (both the `_rnnoiseEnabled` initial value and the `getBoolean("rnnoise_enabled", false)` read).
- [ ] `AudioVoiceEngine.setVadDetector(vad)` swaps the detector on the running `TransmitProcessor` (`@Volatile var`) and closes the previous Silero session (if any); the send thread's next `level()` uses the new detector.
- [ ] Selecting `"silero"` builds a `SileroVadDetector` from the bundled asset off the send thread; a load failure keeps the current detector and logs (no crash).
- [ ] `MumbleManager.setLookaheadMs(Int)` + `lookaheadMs: StateFlow<Int>` persisted as `lookahead_ms` (default 0), forwarded to `engine.setLookaheadMs`.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*AudioVoiceEngineSwap*"` → pass; `./gradlew assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: `TransmitProcessor.vad` → swappable.** Change the constructor `private val vad: VadDetector` to a `@Volatile var vad: VadDetector` (keep it a constructor param via `class TransmitProcessor(private val suppressor: NoiseSuppressor, @Volatile var vad: VadDetector, ...)`).

- [ ] **Step 2: `AudioVoiceEngine.setVadDetector`.** Add:
```kotlin
    /** Hot-swap the VAD detector (built off-thread by the caller). Closes a previous Silero session. */
    fun setVadDetector(newVad: VadDetector) {
        val old = processor.vad
        processor.vad = newVad
        (old as? SileroVadDetector)?.let { if (it !== newVad) it.close() }
    }
```
(Leave the initial `suppressor.close()` in `stop()`; also close a Silero vad there: `(processor.vad as? SileroVadDetector)?.close()`.)

- [ ] **Step 3: Write failing swap test**
```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioVoiceEngineSwapTest {
    private class TagVad(val tag: Float) : VadDetector {
        override fun level(pcm: ShortArray, off: Int, n: Int) = tag
    }
    @Test fun swapRoutesLevelToNewDetector() {
        val gate = TransmitGate()
        val proc = TransmitProcessor(NoiseSuppressor.None, TagVad(0.1f), gate)
        assertEquals(0.1f, proc.process(ShortArray(CAPTURE_SAMPLES)).let { proc.lastVadProb }, 0f)
        proc.vad = TagVad(0.9f)
        proc.process(ShortArray(CAPTURE_SAMPLES))
        assertEquals(0.9f, proc.lastVadProb, 0f)
    }
}
```

- [ ] **Step 4: MumbleManager wiring.** Following the existing `_rnnoiseEnabled`/`setRnnoiseEnabled`/prefs pattern (around lines 75–142, 188–236):
  - Add `_vadEngine = MutableStateFlow("rnnoise")` + `val vadEngine` + `setVadEngine(v)` persisting `putString("vad_engine", v)` and calling `applyVadEngine(v)`.
  - Add `_lookaheadMs = MutableStateFlow(0)` + `setLookaheadMs(v)` persisting `putInt("lookahead_ms", v)` + `engine.setLookaheadMs(v)`.
  - In init/load: `_vadEngine.value = audioPrefs.getString("vad_engine", "rnnoise")!!`; `_rnnoiseEnabled.value = audioPrefs.getBoolean("rnnoise_enabled", false)`; `_lookaheadMs.value = audioPrefs.getInt("lookahead_ms", 0)`. Change the field default `private val _rnnoiseEnabled = MutableStateFlow(false)`.
  - `applyVadEngine(v)` builds the detector off the send thread and calls `engine.setVadDetector`:
```kotlin
    private fun applyVadEngine(engineName: String) {
        val newVad: VadDetector = when (engineName) {
            "silero" -> runCatching {
                val bytes = appContext!!.assets.open("silero_vad_16k_op15.onnx").readBytes()
                SileroVadDetector(SileroOnnxSession(bytes))
            }.getOrElse { android.util.Log.w("MumbleManager", "Silero load failed; keeping current VAD", it); return }
            "energy" -> EnergyVadDetector()
            else -> rnnoise   // RNNoise-as-VAD (kept alive for its prob even with denoise off)
        }
        engine.setVadDetector(newVad)
    }
```
  - At engine construction, choose the initial `vad` from `_vadEngine.value` (build Silero if selected, else `rnnoise`), and pass `initialLookaheadCaptures = _lookaheadMs.value / 20` and `initialRnnoiseEnabled = _rnnoiseEnabled.value` (already wired).

- [ ] **Step 5: Run tests + `assembleDebug`.** Expect the swap test green and a clean APK build (ORT AAR linked).

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineSwapTest.kt
git commit -m "feat(voice): VAD engine selection + live-switch + RNNoise default off + lookahead setting"
```

---

### Task 7: Settings UI — engine selector + lookahead slider

**Goal:** Surface the VAD-engine choice and the lookahead-delay in `SettingsScreen`, wired through `DumbleApp`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] A "Voice detection engine" single-choice control (Energy / RNNoise / Silero) reflects and sets `vadEngine`.
- [ ] A "Lookahead delay" slider (0–100 ms, 20 ms steps) reflects and sets `lookaheadMs`; label shows the ms value and notes "0 = lowest latency".
- [ ] `DumbleApp` collects `vadEngine`/`lookaheadMs` and passes state + callbacks to `SettingsScreen`.
- [ ] `./gradlew assembleDebug` succeeds; a Compose preview or manual smoke shows the controls.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: `DumbleApp` collectors + params.** Alongside the existing `rnnoiseEnabled` collection (line ~40) add:
```kotlin
    val vadEngine by MumbleManager.vadEngine.collectAsStateWithLifecycle()
    val lookaheadMs by MumbleManager.lookaheadMs.collectAsStateWithLifecycle()
```
Pass to `SettingsScreen(...)`: `vadEngine = vadEngine, onVadEngineChange = { MumbleManager.setVadEngine(it) }, lookaheadMs = lookaheadMs, onLookaheadChange = { MumbleManager.setLookaheadMs(it) }`.

- [ ] **Step 2: `SettingsScreen` params + controls.** Add params `vadEngine: String, onVadEngineChange: (String) -> Unit, lookaheadMs: Int, onLookaheadChange: (Int) -> Unit`. In the Voice Activity section add a labeled single-choice row and a slider:
```kotlin
    Text("Voice detection engine", style = MaterialTheme.typography.titleSmall)
    val engines = listOf("energy" to "Energy", "rnnoise" to "RNNoise", "silero" to "Silero")
    Row(Modifier.selectableGroup()) {
        engines.forEach { (key, label) ->
            Row(Modifier.selectable(selected = vadEngine == key, onClick = { onVadEngineChange(key) })
                    .padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = vadEngine == key, onClick = null)
                Text(label)
            }
        }
    }
    Text("Lookahead delay: ${lookaheadMs} ms" + if (lookaheadMs == 0) " (lowest latency)" else "")
    Slider(value = lookaheadMs.toFloat(), onValueChange = { onLookaheadChange((it / 20).toInt() * 20) },
        valueRange = 0f..100f, steps = 4)   // 0,20,40,60,80,100
```
(Match the file's existing imports/spacing; reuse whatever section container the RNNoise toggle uses.)

- [ ] **Step 3: `assembleDebug`.** Expect BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): VAD engine selector + lookahead-delay slider in Settings"
```

---

### Task 8: VadDebugActivity — add Silero to the bench

**Goal:** Let the VAD debug bench run Silero live alongside Energy and RNNoise.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/VadDebugActivity.kt`

**Acceptance Criteria:**
- [ ] `vadNames` includes `"Silero"`; selecting it drives a `SileroVadDetector` and shows its live probability.
- [ ] Silero session is created on start and closed on stop (no leak).
- [ ] `./gradlew assembleDebug` succeeds.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1:** Change `private val vadNames = arrayOf("Energy", "RNNoise")` to `arrayOf("Energy", "RNNoise", "Silero")` (drop the `// Silero to be added` comment).

- [ ] **Step 2:** In the capture loop (around line 292–307) add the Silero branch. Build a `SileroVadDetector(SileroOnnxSession(assets.open("silero_vad_16k_op15.onnx").readBytes()))` when `vadSource == 2`, feed it the raw 480-sample sub-frame via `level(pcm, off, 480)`, and display the returned probability. Close it when the source changes / activity stops (mirror how `suppressor` is managed).

- [ ] **Step 3:** `assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/me/danielstiner/dumble/VadDebugActivity.kt
git commit -m "feat(debug): add Silero to the VAD debug bench"
```

---

### Task 9: Eval-harness comparison (RNNoise vs Silero-raw vs Silero-denoised)

**Goal:** Run Silero through the existing corpus alongside RNNoise, emitting a comparative `metrics.md` the user reads to pick the default engine and threshold — without asserting Silero (RNNoise stays the regression guard).

**Files:**
- Modify: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluator.kt`
- Modify: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluationTest.kt`
- Modify: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/EvalReport.kt` (comparative table)

**Acceptance Criteria:**
- [ ] `VadEvaluator.evaluate` accepts a detector/suppressor factory; RNNoise path unchanged (default).
- [ ] Two Silero paths run the same corpus: **raw** (VAD on the clip's raw PCM) and **denoised** (VAD on the RNNoise-denoised PCM), reusing `TransmitProcessor`'s raw-snapshot semantics.
- [ ] `build/reports/vad-eval/metrics.md` gains a comparison table: per-clip coverage / onset / mid-dropout / false-openings for RNNoise, Silero-raw, Silero-denoised.
- [ ] Existing RNNoise threshold asserts still run and pass; Silero is reported, not asserted.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "*VadEvaluationTest*"` → pass; open `app/build/reports/vad-eval/metrics.md` and confirm the three-way table.

**Steps:**

- [ ] **Step 1: Parametrize `VadEvaluator.evaluate`.** Add an overload that takes a `vadFactory: () -> VadDetector` and a `suppressorForVad: NoiseSuppressor` (None → Silero sees raw; RnnoiseSuppressor → Silero sees denoised). The existing RNNoise path stays as the default `evaluate(clip, gain)`. For Silero-raw, construct `TransmitProcessor(NoiseSuppressor.None, silero, gate)`; for Silero-denoised, `TransmitProcessor(RnnoiseSuppressor(), silero, gate)` — the raw-snapshot in TransmitProcessor means "None" yields raw and RNNoise yields denoised into `vad.level()` automatically. Load the model via `File("src/main/assets/silero_vad_16k_op15.onnx").readBytes()`.

- [ ] **Step 2: `VadEvaluationTest` runs all three.** Build the clips once; run `RNNoise`, `Silero-raw`, `Silero-denoised`; pass all three result sets to `EvalReport.write`. Keep the existing per-clip asserts on the RNNoise results only.

- [ ] **Step 3: `EvalReport` comparative table.** Extend the writer to emit a markdown table with one row per clip and grouped columns per engine (coverage/onset/midDropout/falseOpenings). Header notes: "Silero reported, not asserted — pick the default + threshold from these numbers."

- [ ] **Step 4: Run — expect PASS** and a populated three-way `metrics.md`.

- [ ] **Step 5: Commit**
```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluator.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluationTest.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/EvalReport.kt
git commit -m "test(voice): eval-harness three-way VAD comparison (RNNoise vs Silero raw/denoised)"
```

---

## Self-Review

**Spec coverage:** every spec component maps to a task — reset seam + detector (T3), raw input (T4), decimator (T1), ORT/session/asset/golden (T2), MumbleManager wiring + live-switch + RNNoise default off (T6), settings (T7), debug (T8), eval three-way (T9), lookahead §10 (T5). Error handling (silent-width guard T2, load-failure fallback T6), threading (volatile swap T6), build deps (T2). ✔

**Deferred decision** (default engine / Silero threshold) is documented in the header as post-merge, chosen from T9's `metrics.md`; the plan ships `vad_engine="rnnoise"` and asserts only RNNoise — no contradiction, no re-ask. ✔

**Type consistency:** `SileroOnnxSession(bytes)` → `.run(FloatArray(576), state): Result(prob, state)`; `SileroVadDetector(session)` with `.level/.reset/.close`; `Decimator().decimate(pcm,off,n): FloatArray` + `.reset()`; `LookaheadDelay(k).offer(pcm,open,fn): Emit?` + `.flush()`; `AudioVoiceEngine.setVadDetector/.setLookaheadMs`; `TransmitProcessor.vad` (var); `MumbleManager.vadEngine/setVadEngine/lookaheadMs/setLookaheadMs`. Names consistent across tasks. ✔

**No user-gate tasks** — the eval produces numbers for a *post-merge* human decision; there is no in-plan "verify X before all Y" ordering, so no gate banners (avoids banner-flood).
