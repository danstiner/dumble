# Automatic Gain Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a device-independent makeup gain after RNNoise that normalizes transmit loudness toward a tunable −18 dBFS RMS target, with user-facing target + on/off settings.

**Architecture:** A pure-Kotlin `GainControl` adapts a smoothed linear gain toward a target RMS, per 10 ms sub-frame in place, inside `TransmitProcessor` (both the voice-activated `process()` and push-to-talk `denoise()` paths), after RNNoise and before Opus encode. The transmit gate still decides *when* to send (from the RNNoise probability, computed pre-gain); the gain only sets *how loud*. Settings (target slider + on/off) mirror the existing `vadThreshold` StateFlow-mirror persistence in `MumbleManager` → `SettingsScreen` → `DumbleApp`.

**Tech Stack:** Kotlin, JUnit4 (JVM unit tests), Jetpack Compose (settings UI), the existing RNNoise-backed eval harness under `app/src/test/.../voice/eval/`.

**User decisions (already made):**
- "I'd like something that works on any android phone, not just mine" → device-independent adaptive makeup gain (approach A); RNNoise stays.
- "Just pick a conventional target" → default −18 dBFS RMS, tuned by ear on-device (no measurement gate).
- "add some settings for the target, at least until we tune it well" → user-facing target slider…
- …plus an AGC on/off toggle ("Sounds good") to make on-device A/B one tap.
- Gain applies in **both** transmit modes and is **bidirectional** ("Sounds good" — settled).
- "check it by fable then roll" → spec `2026-07-16-automatic-gain-control-design.md` fable-verified before commit.

**Spec:** `docs/superpowers/specs/2026-07-16-automatic-gain-control-design.md` (fable-verified).

---

## File Structure

- **`app/src/main/java/me/danielstiner/dumble/mumble/voice/GainControl.kt`** (new) — the makeup-gain DSP unit. Pure Kotlin, single responsibility, runs in JVM tests and the eval harness.
- **`TransmitProcessor.kt`** — gains a `GainControl` (defaulted disabled) and applies it per sub-frame in both `process()` and `denoise()`.
- **`AudioVoiceEngine.kt`** — constructs the `GainControl`, exposes `setAgcTargetDbFs()` / `setAgcEnabled()`, passes it to `TransmitProcessor`.
- **`MumbleManager.kt`** — two `StateFlow`s (`agcTargetDbFs`, `agcEnabled`) + persistence + `ActiveSession` delegation, mirroring `vadThreshold`.
- **`SettingsScreen.kt` + `DumbleApp.kt`** — target slider + on/off toggle, wired to `MumbleManager` (one task; the screen-signature change and its call site must compile together).
- **`eval/VadEvaluator.kt` + `eval/AgcEvaluationTest.kt`** (new test) — AGC scoreboard: loudness converges toward target, cross-clip spread shrinks, zero clipping.

Design note (compile independence): every production edit is **additive with defaults** — `TransmitProcessor`'s new `gain` param defaults to a disabled `GainControl`, and the engine's new AGC params default to `enabled = true, targetDbFs = -18`. So each task's module compiles on its own and existing call sites are untouched until wired.

---

### Task 1: GainControl (pure-Kotlin makeup gain + unit tests)

**Goal:** A standalone `GainControl` that adapts a smoothed gain toward a target RMS, freezes during non-speech, is bidirectional, and soft-limits its output — with deterministic JVM tests.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/GainControl.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/GainControlTest.kt`

**Acceptance Criteria:**
- [ ] Gain converges so a steady quiet tone's output RMS reaches the target (within ±1 dB).
- [ ] Gain stays at 1.0 across many non-speech (prob < threshold) sub-frames (freeze).
- [ ] With gain driven high, a loud sub-frame's output peak stays below full scale (no clip/wrap).
- [ ] Gain never leaves `[minGain, maxGain]`.
- [ ] `enabled = false` is bit-exact passthrough.
- [ ] Deterministic: identical input → identical output across two runs.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*GainControlTest"` → all pass.

**Steps:**

- [ ] **Step 1: Write `GainControl.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Post-RNNoise makeup gain (transmit path). Adapts a smoothed linear gain toward a target RMS so
 * speech lands at a consistent loudness regardless of device / platform-AGC strength. Applied per
 * 10 ms sub-frame IN PLACE, AFTER RNNoise denoise and BEFORE Opus encode. The transmit gate still
 * decides WHEN to send (from the RNNoise probability, computed pre-gain); this only sets HOW LOUD.
 *
 * Mirrors mainline Mumble's Speex AGC (which also runs after RNNoise): adapt only while speaking —
 * freeze during non-speech, because RNNoise passes very-quiet frames at unity gain, so a running
 * gain would otherwise ramp room noise up; asymmetric rate limiting (raise gain slowly, lower it
 * fast); bidirectional (boost quiet speech, trim hot speech); soft-knee tanh limiter after the gain
 * (mirroring [AudioMixer]) so a loud burst can't clip.
 *
 * Single-thread (send thread). Live-tunable fields use the same plain-var convention as
 * [TransmitGate.openLevel] (32-bit reads/writes are atomic on the JVM).
 * See docs/superpowers/specs/2026-07-16-automatic-gain-control-design.md.
 */
class GainControl(
    /** Target speech RMS in dBFS (referenced to full-scale 32768, matching the eval harness). */
    var targetDbFs: Float = DEFAULT_TARGET_DBFS,
    var enabled: Boolean = true,
    var maxGainDb: Float = 30f,
    var minGainDb: Float = -12f,
    var increaseRateDbPerSec: Float = 12f,   // slow up   (Mumble: +12 dB/s while speaking)
    var decreaseRateDbPerSec: Float = 60f,   // fast down (Mumble: -60 dB/s)
    var adaptSpeechThreshold: Float = 0.5f,  // RNNoise prob to count a sub-frame as speech
    var limiterThreshDbFs: Float = -1.9f,    // matches AudioMixer knee (0.8 * full scale)
) {
    /** Current smoothed linear gain (send-thread state). Exposed read-only for tests/diagnostics. */
    var gain: Float = 1f
        private set

    /** Apply gain to [n] samples at [pcm]+[off], adapting from this sub-frame's RNNoise [speechProb]. */
    fun process(pcm: ShortArray, off: Int, n: Int, speechProb: Float) {
        if (!enabled) return

        val minGain = dbToRatio(minGainDb)
        val maxGain = dbToRatio(maxGainDb)

        // Adapt only while speaking; freeze otherwise (RNNoise silence-bypass caveat).
        if (speechProb >= adaptSpeechThreshold) {
            val rms = rms(pcm, off, n)
            if (rms > 1f) {
                val targetRms = FULL_SCALE * dbToRatio(targetDbFs)
                val desired = (targetRms / rms).coerceIn(minGain, maxGain)
                gain = rateLimit(gain, desired, n)
            }
        }

        val limit = FULL_SCALE * dbToRatio(limiterThreshDbFs)
        for (i in off until off + n) {
            val limited = softLimit(pcm[i] * gain, limit)
            pcm[i] = limited.coerceIn(-FULL_SCALE, FULL_SCALE - 1f).toInt().toShort()
        }
    }

    /** Move [current] gain toward [desired] by at most this sub-frame's dB step (asymmetric). */
    private fun rateLimit(current: Float, desired: Float, n: Int): Float {
        val subframeSec = n.toFloat() / SAMPLE_RATE
        val curDb = ratioToDb(current)
        val desDb = ratioToDb(desired)
        val maxStepDb = if (desDb > curDb) increaseRateDbPerSec * subframeSec
                        else decreaseRateDbPerSec * subframeSec
        val steppedDb = curDb + (desDb - curDb).coerceIn(-maxStepDb, maxStepDb)
        return dbToRatio(steppedDb)
    }

    private fun rms(pcm: ShortArray, off: Int, n: Int): Float {
        var sumSq = 0.0
        for (i in off until off + n) { val s = pcm[i].toDouble(); sumSq += s * s }
        return sqrt(sumSq / n).toFloat()
    }

    /** tanh soft knee above [limit], mirroring AudioMixer.finalizeMix. */
    private fun softLimit(x: Float, limit: Float): Float {
        val ax = abs(x)
        if (ax <= limit) return x
        val over = ax - limit
        val comp = limit + (FULL_SCALE - limit) * tanh(over / (FULL_SCALE - limit))
        return if (x < 0) -comp else comp
    }

    private fun dbToRatio(db: Float): Float = 10f.pow(db / 20f)
    private fun ratioToDb(x: Float): Float = if (x < 1e-6f) -120f else 20f * log10(x)

    companion object {
        const val DEFAULT_TARGET_DBFS = -18f
        private const val FULL_SCALE = 32768f
    }
}
```

- [ ] **Step 2: Write `GainControlTest.kt` (write first, run, watch it fail to compile, then Step 1 makes it pass)**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Test

class GainControlTest {
    /** A constant-RMS square sub-frame of amplitude [amp] (RMS == amp). */
    private fun square(amp: Int, n: Int = FRAME_SAMPLES_10MS) =
        ShortArray(n) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }

    private fun rmsDbFs(pcm: ShortArray): Double {
        var s = 0.0; for (v in pcm) s += v.toDouble() * v
        return 20.0 * log10(sqrt(s / pcm.size) / 32768.0)
    }

    private fun peak(pcm: ShortArray) = pcm.maxOf { abs(it.toInt()) }

    @Test fun convergesTowardTargetOnSteadyTone() {
        val agc = GainControl(targetDbFs = -18f)
        lateinit var frame: ShortArray
        repeat(400) { frame = square(2000); agc.process(frame, 0, frame.size, 1.0f) }
        assertEquals("output RMS converges to target", -18.0, rmsDbFs(frame), 1.0)
    }

    @Test fun freezesDuringNonSpeech() {
        val agc = GainControl(targetDbFs = -18f)
        repeat(400) { val f = square(500); agc.process(f, 0, f.size, 0.0f) }
        assertEquals("gain frozen at unity when not speech", 1.0f, agc.gain, 1e-4f)
    }

    @Test fun limiterHoldsPeaksBelowFullScale() {
        val agc = GainControl(targetDbFs = -18f)
        // Drive gain high on a very quiet tone, then hit it with a loud sub-frame.
        repeat(400) { val f = square(40); agc.process(f, 0, f.size, 1.0f) }
        val loud = square(20000); agc.process(loud, 0, loud.size, 1.0f)
        assertTrue("no sample wraps / clips at full scale", peak(loud) < 32767)
    }

    @Test fun staysWithinGainBounds() {
        val agc = GainControl(targetDbFs = -18f, maxGainDb = 30f)
        repeat(2000) { val f = square(10); agc.process(f, 0, f.size, 1.0f) } // wants > +30 dB
        assertTrue("gain capped at maxGain", agc.gain <= 31.7f) // 10^(30/20)=31.62
    }

    @Test fun disabledIsBitExactPassthrough() {
        val agc = GainControl(enabled = false)
        val f = square(3000); val copy = f.copyOf()
        agc.process(f, 0, f.size, 1.0f)
        assertTrue("disabled passes audio through unchanged", f.contentEquals(copy))
    }

    @Test fun deterministic() {
        fun run(): ShortArray {
            val agc = GainControl(); var last = ShortArray(0)
            repeat(50) { val f = square(1500); agc.process(f, 0, f.size, 1.0f); last = f }
            return last
        }
        assertTrue("same input → same output", run().contentEquals(run()))
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*GainControlTest"`
Expected: 6 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/GainControl.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/GainControlTest.kt
git commit -m "feat(agc): GainControl makeup-gain DSP unit + tests"
```

---

### Task 2: Wire GainControl into TransmitProcessor (both paths)

**Goal:** `TransmitProcessor` applies the gain per sub-frame after RNNoise in both `process()` (voice-activated) and `denoise()` (PTT), reading the RNNoise probability through its existing `vad` field, without breaking the existing denoise-only test.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt` (extend)

**Acceptance Criteria:**
- [ ] `TransmitProcessor` takes a `gain: GainControl` constructor param defaulting to a disabled instance (existing 3-arg call sites untouched).
- [ ] `process()` applies the gain per sub-frame **after** `vad.level()` (gate sees pre-gain audio).
- [ ] `denoise()` applies the gain per sub-frame; it reads the prob via `vad.level()` **only when `gain.enabled`** (so the existing `vad.calls == 0` assertion holds with the default disabled gain).
- [ ] Existing `TransmitProcessorTest` (both tests) still pass.
- [ ] New test: with an enabled gain, `denoise()` calls `vad.level()` once per sub-frame and scales the audio.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*TransmitProcessorTest"` → all pass.

**Steps:**

- [ ] **Step 1: Edit `TransmitProcessor.kt`** — add the `gain` param and apply it in both paths.

Replace the class body (constructor + `process` + `denoise`) with:

```kotlin
class TransmitProcessor(
    private val suppressor: NoiseSuppressor,
    private val vad: VadDetector,
    val gate: TransmitGate,
    private val gain: GainControl = GainControl(enabled = false),
) {
    private val subLevels = FloatArray(FRAMES_PER_PACKET)

    /** Denoise [capturePcm] (CAPTURE_SAMPLES) in place, then decide send/terminator for this capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)  // pre-gain (gate input)
            subLevels[i] = prob
            gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)    // makeup gain, in place
        }
        return gate.update(subLevels)
    }

    /**
     * Denoise [capturePcm] (CAPTURE_SAMPLES) in place per 10 ms sub-frame, WITHOUT running the gate.
     * Used by the push-to-talk path. The makeup gain still applies (a quiet talker is quiet in PTT
     * too); the RNNoise probability it needs is read through [vad] only when the gain is enabled, so
     * a disabled gain keeps this a pure denoise (no VAD).
     */
    fun denoise(capturePcm: ShortArray) {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            if (gain.enabled) {
                val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
                gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
            }
        }
    }

    fun reset() = gate.reset()
}
```

- [ ] **Step 2: Add a discriminating test to `TransmitProcessorTest.kt`** (append inside the class):

```kotlin
    private class ScalingGainStub {
        // Not used directly; the real GainControl(enabled=true) is used below.
    }

    @Test fun denoiseAppliesEnabledGainAndReadsProbPerSubframe() {
        val sup = OffsetRecordingSuppressor()
        val vad = CountingVad()
        val gate = TransmitGate()
        val enabledGain = GainControl(enabled = true, targetDbFs = -18f)
        val proc = TransmitProcessor(sup, vad, gate, enabledGain)

        proc.denoise(ShortArray(CAPTURE_SAMPLES) { 6000 })

        assertEquals("one suppressor call per sub-frame", listOf(0, FRAME_SAMPLES_10MS), sup.offsets)
        assertEquals("enabled gain reads prob once per sub-frame", FRAMES_PER_PACKET, vad.calls)
    }
```

- [ ] **Step 3: Run tests to confirm existing + new pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*TransmitProcessorTest"`
Expected: `denoiseRunsSuppressorPerSubframeWithoutGating` (default disabled gain → `vad.calls == 0`), `engineAndProcessorDecideIdentically`, and the new `denoiseAppliesEnabledGainAndReadsProbPerSubframe` all pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt
git commit -m "feat(agc): apply GainControl in TransmitProcessor (both process + denoise paths)"
```

---

### Task 3: Construct + wire GainControl in AudioVoiceEngine

**Goal:** `AudioVoiceEngine` owns a `GainControl`, passes it to its `TransmitProcessor`, and exposes live setters for target + enabled.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineTransmitModeTest.kt` (existing must stay green; no new test required — engine wiring is covered by Task 6's harness)

**Acceptance Criteria:**
- [ ] Constructor gains `initialAgcEnabled: Boolean = true` and `initialAgcTargetDbFs: Float = -18f`.
- [ ] A `GainControl` is built from those and passed into the `TransmitProcessor`.
- [ ] `setAgcEnabled(Boolean)` and `setAgcTargetDbFs(Float)` update the live `GainControl`.
- [ ] All existing engine tests remain green (gain doesn't change gate/terminator decisions).

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioVoiceEngine*"` → pass.

**Steps:**

- [ ] **Step 1: Add constructor params** — extend the `AudioVoiceEngine` constructor (after `initialTransmitMode`):

```kotlin
    gateOpenLevel: Float = 0.60f,
    initialTransmitMode: TransmitMode = TransmitMode.VOICE_ACTIVATED,
    initialAgcEnabled: Boolean = true,
    initialAgcTargetDbFs: Float = GainControl.DEFAULT_TARGET_DBFS,
) : VoiceEngine {
```

- [ ] **Step 2: Build the GainControl and pass it to the processor** — replace the `gate`/`processor` field declarations:

```kotlin
    private val encoder = codec.newEncoder()
    private val gate = TransmitGate(openLevel = gateOpenLevel)
    private val gainControl = GainControl(
        targetDbFs = initialAgcTargetDbFs, enabled = initialAgcEnabled)
    private val processor = TransmitProcessor(suppressor, vad, gate, gainControl)
```

- [ ] **Step 3: Add the live setters** — next to `setVadThreshold`:

```kotlin
    /** Live-adjust the makeup-gain target loudness (dBFS RMS). */
    fun setAgcTargetDbFs(value: Float) { gainControl.targetDbFs = value }

    /** Live-enable/disable the makeup gain (off = unity passthrough). */
    fun setAgcEnabled(value: Boolean) { gainControl.enabled = value }
```

- [ ] **Step 4: Run tests**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioVoiceEngine*"`
Expected: existing engine tests pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt
git commit -m "feat(agc): construct + wire GainControl in AudioVoiceEngine with live setters"
```

---

### Task 4: MumbleManager state, persistence + delegation

**Goal:** `MumbleManager` exposes `agcTargetDbFs` and `agcEnabled` StateFlows, persists them to `"dumble_audio"`, loads them in `init()`, seeds the engine, and delegates live changes — mirroring `vadThreshold`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`

**Acceptance Criteria:**
- [ ] `agcTargetDbFs: StateFlow<Float>` (default −18f) + `setAgcTargetDbFs()` coercing to `[-30f, -9f]`, persisting key `"agc_target_dbfs"`, forwarding to the active engine.
- [ ] `agcEnabled: StateFlow<Boolean>` (default true) + `setAgcEnabled()` persisting key `"agc_enabled"`, forwarding to the active engine.
- [ ] Both loaded in `init()`; the engine is constructed with the persisted values; `ActiveSession` delegates both.
- [ ] Project compiles and existing tests pass.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` → build + tests pass.

**Steps:**

- [ ] **Step 1: Add the DEFAULT + StateFlows** — after the `_transmitMode`/`transmitMode` block (near line 60), and update the companion default constant near `DEFAULT_VAD_THRESHOLD`:

```kotlin
    private const val DEFAULT_VAD_THRESHOLD = 0.5f
    private const val DEFAULT_AGC_TARGET_DBFS = -18f
```

```kotlin
    private val _agcTargetDbFs = MutableStateFlow(DEFAULT_AGC_TARGET_DBFS)
    /** Makeup-gain target loudness (dBFS RMS), persisted and applied live to the active call. */
    val agcTargetDbFs: StateFlow<Float> = _agcTargetDbFs.asStateFlow()
    private val _agcEnabled = MutableStateFlow(true)
    /** Makeup-gain on/off, persisted and applied live to the active call. */
    val agcEnabled: StateFlow<Boolean> = _agcEnabled.asStateFlow()
```

- [ ] **Step 2: Add the setters** — after `setVadThreshold`:

```kotlin
    @Synchronized fun setAgcTargetDbFs(value: Float) {
        val v = value.coerceIn(-30f, -9f)
        _agcTargetDbFs.value = v
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putFloat("agc_target_dbfs", v)?.apply()
        active?.setAgcTargetDbFs(v)
    }

    @Synchronized fun setAgcEnabled(value: Boolean) {
        _agcEnabled.value = value
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("agc_enabled", value)?.apply()
        active?.setAgcEnabled(value)
    }
```

- [ ] **Step 3: Load in `init()`** — after the `transmit_mode` load lines:

```kotlin
        _agcTargetDbFs.value = audioPrefs.getFloat("agc_target_dbfs", DEFAULT_AGC_TARGET_DBFS)
        _agcEnabled.value = audioPrefs.getBoolean("agc_enabled", true)
```

- [ ] **Step 4: Seed the engine** — extend the `AudioVoiceEngine(...)` construction in `ActiveSession`:

```kotlin
        private val engine = AudioVoiceEngine(
            codec, suppressor = rnnoise, vad = rnnoise, gateOpenLevel = _vadThreshold.value,
            initialTransmitMode = _transmitMode.value,
            initialAgcEnabled = _agcEnabled.value,
            initialAgcTargetDbFs = _agcTargetDbFs.value)
```

- [ ] **Step 5: Delegate in `ActiveSession`** — next to `setVadThreshold`:

```kotlin
        fun setAgcTargetDbFs(value: Float) = engine.setAgcTargetDbFs(value)
        fun setAgcEnabled(value: Boolean) = engine.setAgcEnabled(value)
```

- [ ] **Step 6: Build + test, then commit**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: compiles, tests green.

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(agc): MumbleManager StateFlows + persistence + engine delegation for AGC"
```

---

### Task 5: Settings UI (target slider + on/off) and DumbleApp wiring

**Goal:** A "Transmit loudness" slider and an "Automatic gain control" toggle in `SettingsScreen`, wired through `DumbleApp` to `MumbleManager`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] `SettingsScreen` takes `agcEnabled: Boolean`, `onAgcEnabledChange: (Boolean) -> Unit`, `agcTargetDbFs: Float`, `onAgcTargetChange: (Float) -> Unit`.
- [ ] A `Switch` toggles AGC; a `Slider` (range −30..−9 dBFS, `enabled = agcEnabled`) sets the target, showing the current dB value.
- [ ] `DumbleApp` collects both StateFlows and passes them + callbacks (`MumbleManager.setAgcEnabled` / `setAgcTargetDbFs`); the `@Preview` still compiles.
- [ ] `assembleDebug` builds.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Add the imports to `SettingsScreen.kt`** (with the other material3 imports):

```kotlin
import androidx.compose.material3.Switch
```

- [ ] **Step 2: Extend the `SettingsScreen` signature** — add the four params after `onVadThresholdChange`:

```kotlin
    vadThreshold: Float,
    onVadThresholdChange: (Float) -> Unit,
    agcEnabled: Boolean,
    onAgcEnabledChange: (Boolean) -> Unit,
    agcTargetDbFs: Float,
    onAgcTargetChange: (Float) -> Unit,
) {
```

- [ ] **Step 3: Add the AGC controls** — inside the inner `Column(modifier = Modifier.fillMaxWidth().padding(16.dp))`, after the sensitivity `Text`/`Slider`/`Text` block (after line ~93), insert:

```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Automatic gain control", modifier = Modifier.weight(1f))
                    Switch(checked = agcEnabled, onCheckedChange = onAgcEnabledChange)
                }
                Text("Transmit loudness: %.0f dBFS".format(agcTargetDbFs))
                Slider(
                    value = agcTargetDbFs,
                    onValueChange = onAgcTargetChange,
                    valueRange = -30f..-9f,
                    enabled = agcEnabled,
                )
                Text("Higher = louder transmit. Normalizes your level so peers hear you consistently.")
```

- [ ] **Step 4: Update the `@Preview`** — pass the new args:

```kotlin
        SettingsScreen(onBack = {}, onLaunchEchoTest = {}, onLaunchVadDebug = {},
            transmitMode = TransmitMode.VOICE_ACTIVATED, onTransmitModeChange = {},
            vadThreshold = 0.5f, onVadThresholdChange = {},
            agcEnabled = true, onAgcEnabledChange = {},
            agcTargetDbFs = -18f, onAgcTargetChange = {})
```

- [ ] **Step 5: Wire `DumbleApp.kt`** — collect the StateFlows (after `transmitMode`, line ~38):

```kotlin
    val agcEnabled by MumbleManager.agcEnabled.collectAsStateWithLifecycle()
    val agcTargetDbFs by MumbleManager.agcTargetDbFs.collectAsStateWithLifecycle()
```

And pass them into the `SettingsScreen(...)` call (after `onVadThresholdChange`):

```kotlin
                vadThreshold = vadThreshold,
                onVadThresholdChange = { MumbleManager.setVadThreshold(it) },
                agcEnabled = agcEnabled,
                onAgcEnabledChange = { MumbleManager.setAgcEnabled(it) },
                agcTargetDbFs = agcTargetDbFs,
                onAgcTargetChange = { MumbleManager.setAgcTargetDbFs(it) },
            )
```

- [ ] **Step 6: Build, then commit**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(agc): Settings target slider + on/off toggle, wired via DumbleApp"
```

---

### Task 6: Eval-harness AGC scoreboard

**Goal:** An automated regression guard proving the makeup gain converges corpus clips toward the target, shrinks cross-clip loudness spread vs no-AGC, and never clips.

**Files:**
- Modify: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluator.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/AgcEvaluationTest.kt` (new)

**Acceptance Criteria:**
- [ ] `VadEvaluator.evaluate(clip, gain: GainControl? = null)` runs the pipeline with the given gain (null → current no-gain behavior, so the existing `VadEvaluationTest` is unchanged).
- [ ] With AGC on (target −18), every corpus clip's output `speechLoudnessDbFs` moves toward −18 vs its no-AGC value, and `clipping == 0`.
- [ ] Cross-clip loudness **spread** (max − min) is strictly smaller with AGC than without.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AgcEvaluationTest" --tests "*VadEvaluationTest"` → pass.

**Steps:**

- [ ] **Step 1: Add an optional gain to `VadEvaluator.evaluate`** — change its signature and the processor construction:

```kotlin
    /** Full DSP evaluation: fresh RNNoise + gate per clip, optional makeup gain. */
    fun evaluate(clip: Clip, gain: GainControl? = null): Metrics {
        val suppressor = RnnoiseSuppressor()
        try {
            val proc = TransmitProcessor(
                suppressor, suppressor, TransmitGate(),
                gain ?: GainControl(enabled = false))
```

(The rest of `evaluate` is unchanged; because the gain mutates `cap` in place before the loudness/clipping sum, `speechLoudnessDbFs` and `clipping` are measured post-gain when a gain is supplied.)

- [ ] **Step 2: Write `AgcEvaluationTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.GainControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGC scoreboard: the makeup gain must pull the real-speech corpus toward the target loudness and
 * tighten cross-clip consistency, without ever clipping. Relative assertions (no pinned dB bars) so
 * this stays robust to convergence-lag and future constant tuning.
 */
class AgcEvaluationTest {
    private val target = -18f

    @Test fun agcConvergesTightensSpreadAndNeverClips() {
        val clips = CorpusBuilder.build()

        val noAgc = clips.map { VadEvaluator.evaluate(it).speechLoudnessDbFs }
        val withAgc = clips.map {
            VadEvaluator.evaluate(it, GainControl(targetDbFs = target, enabled = true))
        }

        // Never clips.
        for ((c, m) in clips.zip(withAgc))
            assertEquals("${c.name} must not clip under AGC", 0, m.clipping)

        // Each clip moves toward target vs its no-AGC loudness.
        for (i in clips.indices) {
            val before = kotlin.math.abs(noAgc[i] - target)
            val after = kotlin.math.abs(withAgc[i].speechLoudnessDbFs - target)
            assertTrue("${clips[i].name} loudness moves toward target " +
                "($before → $after dB from target)", after <= before + 1e-6)
        }

        // Cross-clip spread shrinks.
        val spreadBefore = noAgc.max() - noAgc.min()
        val spreadAfter = withAgc.maxOf { it.speechLoudnessDbFs } - withAgc.minOf { it.speechLoudnessDbFs }
        assertTrue("AGC tightens cross-clip spread ($spreadBefore → $spreadAfter dB)",
            spreadAfter < spreadBefore)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AgcEvaluationTest" --tests "*VadEvaluationTest"`
Expected: both pass (the AGC scoreboard green; the existing VAD eval unaffected because it calls `evaluate(clip)` with no gain).

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluator.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/AgcEvaluationTest.kt
git commit -m "test(agc): eval-harness AGC scoreboard (converge + tighten spread + no clip)"
```

---

## Manual acceptance (post-merge, on-device — not a subagent task)

The automated gate is Task 6. Final tuning is by ear, per the design: build to the Pixel 7a (`./gradlew :app:installDebug`), join a room with a live Mumble client, and use the new **AGC on/off** toggle to A/B against no-AGC and against the other client's level. Nudge the **Transmit loudness** slider until you match; confirm no pumping/breathing on your voice. Adjust `GainControl`'s default constants if the slider lands somewhere consistent.

---

## Self-Review

**Spec coverage:** GainControl behavior + both-modes + bidirectional + limiter (Tasks 1–3); freeze-during-non-speech (Task 1 test + Task 2 wiring); target −18 default + tunable (Tasks 1, 4); settings slider + on/off (Tasks 4, 5); eval scoreboard (Task 6); on-device A/B (manual acceptance). No spec requirement is unter-tasked.

**Placeholder scan:** none — every code step carries full code; every verify carries the exact command.

**Type consistency:** `GainControl(targetDbFs, enabled, …)`, `gain.process(pcm, off, n, speechProb)`, `gain.enabled`, `setAgcTargetDbFs`/`setAgcEnabled`, StateFlow names `agcTargetDbFs`/`agcEnabled`, prefs keys `agc_target_dbfs`/`agc_enabled`, engine params `initialAgcEnabled`/`initialAgcTargetDbFs` — used identically across Tasks 1–6.
