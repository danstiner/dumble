# Platform-Audio Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show, read-only, what the platform voice effects (AEC/NS/AGC) are doing on our capture session and the stage-by-stage loudness across our chain — in a Settings diagnostics screen + logs.

**Architecture:** A pure JVM-testable data layer (`AudioDiagnostics` + `rmsDbFs`), an Android-only `PlatformAudioEffects` probe (OboeTester's read-only half: `isAvailable()`→`create(sessionId)`→`getEnabled()`, never `setEnabled`, hold+release), stage metering in `AudioVoiceEngine` (raw + post-gain RMS; post-denoise **derived** as post-gain − gainDb; AGC gain + VAD prob) emitted as a `diagnostics: StateFlow`, mirrored by `MumbleManager`, and rendered by a read-only `AudioDiagnosticsScreen`.

**Tech Stack:** Kotlin, `android.media.audiofx`, Jetpack Compose, JUnit4, the existing engine fake harness.

**User decisions (already made):**
- "Look at what the oboetest app capture" → grounded on OboeTester's `StreamConfigurationView.setupEffects` (verified: `isAvailable()`→`create(sessionId)`→`getEnabled()`, then it toggles; we take the read-only half).
- Read-only ("Sounds good" to the read-only design) — never `setEnabled`; platform-AGC toggle deferred.
- Surface in a Settings diagnostics view + logs; live telemetry, not persisted.

**Spec:** `docs/superpowers/specs/2026-07-16-platform-audio-visibility-design.md`.

**Note:** `docs/BUGS.md → docs/TODO.md` is an unrelated stray uncommitted change in the tree — do NOT stage, commit, revert, or touch it. Stage only each task's own files.

---

## File Structure

- **`mumble/voice/AudioDiagnostics.kt`** (new) — pure data (`EffectState`, `CaptureInfo`, `AudioDiagnostics`) + `rmsDbFs` helper. No Android imports → JVM-unit-testable.
- **`mumble/voice/PlatformAudioEffects.kt`** (new) — Android-only `audiofx` probe. Not unit-tested.
- **`TransmitProcessor.kt`** — expose `lastVadProb`.
- **`AudioVoiceEngine.kt`** — `AudioIn.captureInfo()` seam; `AndroidAudioIn` runs the probe; stage metering; `diagnostics` StateFlow.
- **`MumbleManager.kt`** — `audioDiagnostics` StateFlow mirror (+ unprocessed-source support).
- **`ui/AudioDiagnosticsScreen.kt`** (new) + **`SettingsScreen.kt`** (ListItem) + **`DumbleApp.kt`** (nav) — read-only UI.

Each production edit is additive (defaulted interface method, new fields/flows), so tasks compile independently.

---

### Task 1: AudioDiagnostics — pure data + dBFS helper

**Goal:** JVM-testable diagnostics data types and an RMS→dBFS helper, idle-safe, with post-denoise/attenuation derived.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioDiagnostics.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioDiagnosticsTest.kt`

**Acceptance Criteria:**
- [ ] `rmsDbFs` returns ≈0 dBFS for full-scale, −6 dBFS for half-scale, and a −120 floor for silence (no `NaN`/`-Infinity`).
- [ ] `AudioDiagnostics.postDenoiseDbFs == postGainDbFs - agcGainDb` when finite.
- [ ] `AudioDiagnostics.rnnoiseAttenuationDb == rawDbFs - postDenoiseDbFs` when finite; `NaN` (not a crash) when idle defaults.
- [ ] Deterministic; no Android imports.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioDiagnosticsTest"` → pass.

**Steps:**

- [ ] **Step 1: Write `AudioDiagnostics.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** One platform voice effect's state. [enabled] null = available-but-unknown (probe threw). */
data class EffectState(val kind: String, val available: Boolean, val enabled: Boolean?)

/** Static capture-session info read once at capture start (Android probe fills it). */
data class CaptureInfo(val effects: List<EffectState>, val deviceModel: String)

/**
 * Read-only transmit-path diagnostics: platform effect state + stage loudness. Levels are dBFS RMS
 * (ref 32768, matching the eval harness). post-denoise is DERIVED from post-gain minus the makeup
 * gain (they differ only by the gain, except when the limiter is active — acceptable for a HUD).
 */
data class AudioDiagnostics(
    val effects: List<EffectState> = emptyList(),
    val deviceModel: String = "",
    val unprocessedSupported: String? = null,
    val connected: Boolean = false,
    val rawDbFs: Float = Float.NEGATIVE_INFINITY,
    val postGainDbFs: Float = Float.NEGATIVE_INFINITY,
    val agcGainDb: Float = 0f,
    val vadProb: Float = 0f,
) {
    /** Post-RNNoise (pre-gain) level, derived. */
    val postDenoiseDbFs: Float
        get() = if (postGainDbFs.isFinite()) postGainDbFs - agcGainDb else postGainDbFs

    /** How much RNNoise attenuated the platform-handed signal (raw − post-denoise). */
    val rnnoiseAttenuationDb: Float
        get() = if (rawDbFs.isFinite() && postDenoiseDbFs.isFinite()) rawDbFs - postDenoiseDbFs else Float.NaN
}

/** RMS of [n] samples at [pcm]+[off] as dBFS (ref 32768); floors at −120 for near-silence. */
fun rmsDbFs(pcm: ShortArray, off: Int, n: Int): Float {
    var sumSq = 0.0
    for (i in off until off + n) { val s = pcm[i].toDouble(); sumSq += s * s }
    val rms = sqrt(sumSq / n)
    if (rms < 1.0) return -120f
    return (20.0 * log10(rms / 32768.0)).toFloat()
}
```

- [ ] **Step 2: Write `AudioDiagnosticsTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticsTest {
    private fun square(amp: Int, n: Int = 960) = ShortArray(n) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }

    @Test fun rmsDbFsKnownLevels() {
        assertEquals(0.0, rmsDbFs(square(32767), 0, 960).toDouble(), 0.1)     // full scale ≈ 0
        assertEquals(-6.02, rmsDbFs(square(16384), 0, 960).toDouble(), 0.1)   // half scale
    }

    @Test fun rmsDbFsSilenceIsFlooredNotInfinite() {
        val db = rmsDbFs(ShortArray(960), 0, 960)
        assertEquals(-120f, db, 0f)
        assertTrue(db.isFinite())
    }

    @Test fun postDenoiseIsPostGainMinusGain() {
        val d = AudioDiagnostics(postGainDbFs = -18f, agcGainDb = 6f)
        assertEquals(-24f, d.postDenoiseDbFs, 1e-4f)
    }

    @Test fun rnnoiseAttenuationDerivation() {
        val d = AudioDiagnostics(rawDbFs = -12f, postGainDbFs = -18f, agcGainDb = 6f)
        assertEquals(12f, d.rnnoiseAttenuationDb, 1e-4f)   // -12 - (-24)
    }

    @Test fun idleDefaultsDoNotBlowUp() {
        val d = AudioDiagnostics()
        assertTrue(d.postDenoiseDbFs == Float.NEGATIVE_INFINITY)
        assertTrue(d.rnnoiseAttenuationDb.isNaN())
    }
}
```

- [ ] **Step 3: Run** `...--tests "*AudioDiagnosticsTest"` → 5 pass.
- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioDiagnostics.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioDiagnosticsTest.kt
git commit -m "feat(diag): AudioDiagnostics data + dBFS helper (JVM-tested)"
```

---

### Task 2: PlatformAudioEffects — read-only audiofx probe (Android)

**Goal:** Probe AEC/NS/AGC state on a capture session read-only (OboeTester's pattern minus the toggle), holding handles for the session and releasing on close.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/PlatformAudioEffects.kt`

**Acceptance Criteria:**
- [ ] For each of AEC/NS/AGC: `isAvailable()` (static) recorded; if available, `create(sessionId)` and read `getEnabled()` (via the Kotlin `.enabled` property). **No `setEnabled` anywhere in the file.**
- [ ] Handles held as fields; `close()` releases all (null-safe, exception-safe).
- [ ] `create`/`getEnabled` wrapped so any throw yields `enabled = null` (unknown), never a crash.
- [ ] Companion `deviceModel()` and `unprocessedSupported(AudioManager)`.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` → compiles (this file is Android-only; behavior verified on-device).

**Steps:**

- [ ] **Step 1: Write `PlatformAudioEffects.kt`** — and confirm `grep -n setEnabled` returns nothing in this file.

```kotlin
package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log

/**
 * Read-only probe of the platform voice effects on a capture session — the read-only half of
 * OboeTester's StreamConfigurationView.setupEffects: isAvailable() (static) -> create(sessionId) ->
 * getEnabled() (the platform DEFAULT state). We NEVER call setEnabled(), so the VOICE_COMMUNICATION
 * path is not perturbed; handles are held for the session and released in [close].
 *
 * Caveat: getEnabled() is the audiofx effect's self-report — it equals the HAL VOICE_COMMUNICATION
 * processing on most devices but is not guaranteed. The stage RMS is the ground truth.
 */
class PlatformAudioEffects(sessionId: Int) {
    private val aecAvail = AcousticEchoCanceler.isAvailable()
    private val agcAvail = AutomaticGainControl.isAvailable()
    private val nsAvail = NoiseSuppressor.isAvailable()

    private val aec: AudioEffect? = create("AEC") { if (aecAvail) AcousticEchoCanceler.create(sessionId) else null }
    private val agc: AudioEffect? = create("AGC") { if (agcAvail) AutomaticGainControl.create(sessionId) else null }
    private val ns: AudioEffect? = create("NS") { if (nsAvail) NoiseSuppressor.create(sessionId) else null }

    val states: List<EffectState> = listOf(
        EffectState("AEC", aecAvail, readEnabled(aec)),
        EffectState("AGC", agcAvail, readEnabled(agc)),
        EffectState("NS", nsAvail, readEnabled(ns)),
    )

    private inline fun create(kind: String, factory: () -> AudioEffect?): AudioEffect? =
        try { factory() } catch (t: Throwable) { Log.w(TAG, "create $kind failed", t); null }

    /** READ ONLY — getEnabled() via the .enabled property. Never setEnabled(). */
    private fun readEnabled(fx: AudioEffect?): Boolean? =
        if (fx == null) null else try { fx.enabled } catch (t: Throwable) { null }

    fun close() = listOf(aec, agc, ns).forEach { runCatching { it?.release() } }

    companion object {
        private const val TAG = "PlatformAudioEffects"
        fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        fun unprocessedSupported(am: AudioManager): String? =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
    }
}
```

- [ ] **Step 2: Verify + commit**
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
grep -n "setEnabled" app/src/main/java/me/danielstiner/dumble/mumble/voice/PlatformAudioEffects.kt   # must be empty
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/PlatformAudioEffects.kt
git commit -m "feat(diag): read-only PlatformAudioEffects probe (OboeTester pattern, no setEnabled)"
```

---

### Task 3: Stage metering + diagnostics StateFlow

**Goal:** `TransmitProcessor` exposes `lastVadProb`; `AudioIn` gains a `captureInfo()` seam; `AndroidAudioIn` runs the probe; `AudioVoiceEngine` measures raw + post-gain RMS periodically and emits `diagnostics: StateFlow<AudioDiagnostics>`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineDiagnosticsTest.kt`

**Acceptance Criteria:**
- [ ] `TransmitProcessor` exposes `lastVadProb` (updated from the per-sub-frame prob in `process()` and, when gain-enabled, in `denoise()`). Existing `TransmitProcessorTest` still passes.
- [ ] `AudioIn` gains `fun captureInfo(): CaptureInfo? = null` (default → existing fakes untouched). `AndroidAudioIn` constructs `PlatformAudioEffects(record.audioSessionId)`, returns `CaptureInfo(states, deviceModel)`, and releases it in `close()`.
- [ ] `AudioVoiceEngine.diagnostics: StateFlow<AudioDiagnostics>` is seeded from `recorder.captureInfo()` at `start()` and updated ~every 500 ms with `rawDbFs`, `postGainDbFs`, `agcGainDb` (20·log10 gain), `vadProb`, `connected=true`.
- [ ] New JVM test: driving the engine with a fake capture at a known level updates `diagnostics` with the expected `rawDbFs`/`postGainDbFs` sign and `connected=true`.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*TransmitProcessorTest" --tests "*AudioVoiceEngine*"` → pass.

**Steps:**

- [ ] **Step 1: `TransmitProcessor.kt` — expose `lastVadProb`.** Add the field and set it. In `process()`, after the loop set `lastVadProb = subLevels[FRAMES_PER_PACKET - 1]`. In `denoise()`, inside the `if (gain.enabled)` block, set `lastVadProb = prob` on the last sub-frame. Add near `subLevels`:

```kotlin
    /** RNNoise probability from the most recent processed sub-frame (diagnostics only). */
    var lastVadProb: Float = 0f
        private set
```
In `process()`, change the return to first record the prob:
```kotlin
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
            subLevels[i] = prob
            gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
        }
        lastVadProb = subLevels[FRAMES_PER_PACKET - 1]
        return gate.update(subLevels)
```
In `denoise()`, inside the enabled block:
```kotlin
            if (gain.enabled) {
                val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
                lastVadProb = prob
                gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
            }
```

- [ ] **Step 2: `AudioVoiceEngine.kt` — `AudioIn.captureInfo()` seam.** Extend the interface:
```kotlin
interface AudioIn { fun read(out: ShortArray, n: Int): Int; fun close(); fun captureInfo(): CaptureInfo? = null }
```

- [ ] **Step 3: `AudioVoiceEngine.kt` — diagnostics field + seed + periodic update.** Add fields near `_stats`:
```kotlin
    private val _diagnostics = MutableStateFlow(AudioDiagnostics())
    val diagnostics: StateFlow<AudioDiagnostics> = _diagnostics.asStateFlow()
    private var diagTick = 0
```
In `start()`, after `recorder = recorderFactory()`, seed:
```kotlin
        recorder?.captureInfo()?.let {
            _diagnostics.value = AudioDiagnostics(effects = it.effects, deviceModel = it.deviceModel, connected = true)
        }
```
In `nextOutgoingFrame`, right after `frameNumber += FRAMES_PER_PACKET`, sample raw on diag ticks:
```kotlin
        val diag = (++diagTick % DIAG_INTERVAL == 0)
        val rawDb = if (diag) rmsDbFs(capturePcm, 0, CAPTURE_SAMPLES) else 0f
```
Add a helper (near `terminatorFrame`):
```kotlin
    /** Sample post-gain level + gain + prob and push a diagnostics update. capturePcm is post-process. */
    private fun pushDiagnostics(rawDb: Float) {
        val postGainDb = rmsDbFs(capturePcm, 0, CAPTURE_SAMPLES)
        val gainDb = (20.0 * kotlin.math.log10(gainControl.gain.coerceAtLeast(1e-6f).toDouble())).toFloat()
        _diagnostics.update {
            it.copy(rawDbFs = rawDb, postGainDbFs = postGainDb, agcGainDb = gainDb,
                    vadProb = processor.lastVadProb, connected = true)
        }
    }
```
Call it after the in-place processing in the two branches that process audio:
```kotlin
            TransmitMode.VOICE_ACTIVATED -> {
                val d = processor.process(capturePcm)
                if (diag) pushDiagnostics(rawDb)
                if (!d.send) { sending = false; return null }
                encodeAndCount(fn, d.terminator)
            }
            TransmitMode.PUSH_TO_TALK -> {
                gate.reset()
                if (pttHeld) {
                    processor.denoise(capturePcm)
                    if (diag) pushDiagnostics(rawDb)
                    encodeAndCount(fn, terminator = false)
                } else if (sending) { terminatorFrame(fn) }
                else null
            }
```
Add the constant near the class (or in the companion / top-level in the file):
```kotlin
private const val DIAG_INTERVAL = 25   // ~500 ms at 20 ms captures
```
(`AudioDiagnostics`, `CaptureInfo`, `rmsDbFs` are same-package — no import needed. `gainControl` is the field from the AGC work.)

- [ ] **Step 4: `AndroidAudioIn` — run the probe.** In `AndroidAudioIn`, after the `record` field, add:
```kotlin
    private val platformEffects = PlatformAudioEffects(record.audioSessionId)
    override fun captureInfo(): CaptureInfo =
        CaptureInfo(platformEffects.states, PlatformAudioEffects.deviceModel())
```
and in its `close()`, add `runCatching { platformEffects.close() }` before releasing the record.

- [ ] **Step 5: Write `AudioVoiceEngineDiagnosticsTest.kt`** — drive the engine with a steady fake capture; assert diagnostics populate.

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineDiagnosticsTest {
    private class SteadyAudioIn(private val amp: Int) : AudioIn {
        override fun read(out: ShortArray, n: Int): Int {
            for (i in 0 until n) out[i] = (if (i % 2 == 0) amp else -amp).toShort(); return n
        }
        override fun close() {}
        // captureInfo() default null — engine seeds nothing, still updates levels.
    }

    @Test fun diagnosticsPopulateWithRawAndPostGainLevels() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(6000) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = EnergyVadDetector(),
        ).also { it.start() }
        // Pump enough captures to cross a DIAG_INTERVAL boundary (25).
        repeat(30) { engine.nextOutgoingFrame(0) }
        val d = engine.diagnostics.value
        engine.stop()
        assertTrue("connected", d.connected)
        assertTrue("raw level measured (~ -14.7 dBFS for amp 6000)", d.rawDbFs in -20f..-10f)
        assertTrue("post-gain level measured", d.postGainDbFs.isFinite())
    }
}
```
(`FakeOpusCodec`/`FakeAudioOut` are the existing package-visible test fakes. AGC is enabled by default in the engine, so `postGainDbFs` reflects the makeup gain; the assertion only checks it's finite/measured, not an exact value.)

- [ ] **Step 6: Run + commit**
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*TransmitProcessorTest" --tests "*AudioVoiceEngine*"
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineDiagnosticsTest.kt
git commit -m "feat(diag): stage metering + diagnostics StateFlow (raw/post-gain/gain/prob) + capture-probe seam"
```

---

### Task 4: MumbleManager audioDiagnostics mirror

**Goal:** `MumbleManager` mirrors `engine.diagnostics` as `audioDiagnostics: StateFlow`, enriched once with the device's unprocessed-source support; reset on disconnect.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`

**Acceptance Criteria:**
- [ ] `audioDiagnostics: StateFlow<AudioDiagnostics>` (default `AudioDiagnostics()`), collected from `engine.diagnostics` in `ActiveSession.start()`, with `unprocessedSupported` merged in.
- [ ] `unprocessedSupported` queried once from `appContext`'s `AudioManager` via `PlatformAudioEffects.unprocessedSupported`.
- [ ] Reset to `AudioDiagnostics()` on `shutdown()`.
- [ ] Compiles; existing tests pass.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` → pass.

**Steps:**

- [ ] **Step 1: Add the StateFlow + lazy unprocessed value.** Near the other `_*` StateFlows:
```kotlin
    private val _audioDiagnostics = MutableStateFlow(me.danielstiner.dumble.mumble.voice.AudioDiagnostics())
    /** Read-only transmit-path diagnostics (platform effects + stage levels), live during a call. */
    val audioDiagnostics: StateFlow<me.danielstiner.dumble.mumble.voice.AudioDiagnostics> = _audioDiagnostics.asStateFlow()
    private val unprocessedSupport: String? by lazy {
        (appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
            ?.let { me.danielstiner.dumble.mumble.voice.PlatformAudioEffects.unprocessedSupported(it) }
    }
```
(The `voice.*` wildcard import already in `MumbleManager` covers these; the fully-qualified names above are safe either way.)

- [ ] **Step 2: Collect in `ActiveSession.start()`** — next to the existing `engine.stats.collect`:
```kotlin
            sessionScope.launch { engine.diagnostics.collect { _audioDiagnostics.value = it.copy(unprocessedSupported = unprocessedSupport) } }
```

- [ ] **Step 3: Reset in `ActiveSession.shutdown()`** — next to the other `_*.value = …` resets:
```kotlin
            _audioDiagnostics.value = me.danielstiner.dumble.mumble.voice.AudioDiagnostics()
```

- [ ] **Step 4: Build + test + commit**
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(diag): MumbleManager audioDiagnostics StateFlow mirror (+ unprocessed-source support)"
```

---

### Task 5: Audio-diagnostics Settings screen + nav

**Goal:** A read-only "Audio diagnostics" screen (opened from a Settings `ListItem`) that renders `MumbleManager.audioDiagnostics`.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] New `AudioDiagnosticsScreen(diagnostics, onBack)` shows: per-effect available + enabled (Y/N/unknown), unprocessed support, device model, the "self-report vs ground-truth" caveat, and live raw / post-denoise / post-gain dBFS, RNNoise attenuation, AGC gain (dB), VAD prob.
- [ ] `SettingsScreen` gains an "Audio diagnostics" `ListItem` calling a new `onOpenDiagnostics` param.
- [ ] `DumbleApp` collects `audioDiagnostics`, holds a `showDiagnostics` flag, renders the screen (back returns to Settings).
- [ ] `assembleDebug` builds.

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Create `AudioDiagnosticsScreen.kt`**

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.AudioDiagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticsScreen(diagnostics: AudioDiagnostics, onBack: () -> Unit) {
    fun db(v: Float) = if (v.isFinite()) "%.1f dBFS".format(v) else "—"
    Scaffold(topBar = {
        TopAppBar(title = { Text("Audio diagnostics") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Platform effects (device self-report)")
            diagnostics.effects.forEach { e ->
                val en = when (e.enabled) { true -> "ON"; false -> "off"; null -> if (e.available) "unknown" else "n/a" }
                Text("  ${e.kind}: available=${e.available}, default=$en")
            }
            Text("  Unprocessed source: ${diagnostics.unprocessedSupported ?: "—"}")
            Text("  Device: ${diagnostics.deviceModel.ifBlank { "—" }}")
            Text("")
            Text("Stage levels (ground truth)" + if (diagnostics.connected) "" else " — not connected")
            Text("  Raw capture:   ${db(diagnostics.rawDbFs)}")
            Text("  Post-RNNoise:  ${db(diagnostics.postDenoiseDbFs)}")
            Text("  Post-gain:     ${db(diagnostics.postGainDbFs)}")
            Text("  RNNoise atten: " + if (diagnostics.rnnoiseAttenuationDb.isNaN()) "—" else "%.1f dB".format(diagnostics.rnnoiseAttenuationDb))
            Text("  AGC gain:      %.1f dB".format(diagnostics.agcGainDb))
            Text("  VAD prob:      %.2f".format(diagnostics.vadProb))
            Text("")
            Text("Effect state is the audiofx self-report; on some devices it may differ from the actual HAL processing. The stage levels are measured.")
        }
    }
}
```

- [ ] **Step 2: `SettingsScreen.kt` — add the ListItem + param.** Add `onOpenDiagnostics: () -> Unit,` to the signature (after `onAgcTargetChange`). Add a `ListItem` after the existing "VAD Gate Tuner" one:
```kotlin
            ListItem(
                headlineContent = { Text("Audio diagnostics") },
                supportingContent = { Text("Platform effects + live stage levels (read-only)") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDiagnostics),
            )
```
Update the `@Preview` call to pass `onOpenDiagnostics = {}`.

- [ ] **Step 3: `DumbleApp.kt` — collect + nav.** Collect the flow (near the other `collectAsStateWithLifecycle`):
```kotlin
    val audioDiagnostics by MumbleManager.audioDiagnostics.collectAsStateWithLifecycle()
```
Add nav state near `showSettings`:
```kotlin
    var showDiagnostics by remember { mutableStateOf(false) }
```
Add a branch to render the screen (place it BEFORE the `showSettings ->` branch so it takes precedence, and back returns to Settings):
```kotlin
        showDiagnostics -> {
            BackHandler { showDiagnostics = false }
            AudioDiagnosticsScreen(diagnostics = audioDiagnostics, onBack = { showDiagnostics = false })
        }
```
Pass the callback into `SettingsScreen(...)`:
```kotlin
                onAgcTargetChange = { MumbleManager.setAgcTargetDbFs(it) },
                onOpenDiagnostics = { showDiagnostics = true },
            )
```

- [ ] **Step 4: Build + commit**
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
git add app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(diag): read-only Audio diagnostics screen + Settings entry + nav"
```

---

## Manual acceptance (on-device, batched with the AGC + PTT test)

Build to the 7a, open Settings → Audio diagnostics during a call: confirm AEC/NS/AGC show available + default state, and the raw → post-RNNoise drop + post-gain recovery track what you expect. Compare AGC on vs off (the AGC toggle) and watch the numbers move.

## Self-Review

**Spec coverage:** effect probe read-only (Task 2), stage metering incl. derived post-denoise (Tasks 1, 3), diagnostics StateFlow + mirror (Tasks 3, 4), read-only Settings screen (Task 5), unprocessed-support + device model (Tasks 2, 4), self-report caveat (Task 5 UI + Task 2 KDoc), no persistence, toggle deferred. Covered.

**Placeholder scan:** none — full code per step; exact verify commands.

**Type consistency:** `EffectState(kind, available, enabled?)`, `CaptureInfo(effects, deviceModel)`, `AudioDiagnostics(...)` + `postDenoiseDbFs`/`rnnoiseAttenuationDb`/`rmsDbFs`, `AudioIn.captureInfo()`, `TransmitProcessor.lastVadProb`, `AudioVoiceEngine.diagnostics`, `MumbleManager.audioDiagnostics`, `PlatformAudioEffects(sessionId).states`/`.close()`/`.deviceModel()`/`.unprocessedSupported()`, `SettingsScreen(... onOpenDiagnostics)` — consistent across tasks.
