# Transmit-mode selector (PTT ↔ voice activation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-selectable transmit mode — voice activation (today's RNNoise gate) or push-to-talk (on-screen hold button) — persisted and applied live to the active call.

**Architecture:** A `TransmitMode` enum is honored inside `AudioVoiceEngine.nextOutgoingFrame()`: voice-activation runs the existing denoise→VAD→gate path; push-to-talk denoises but bypasses the gate, transmitting only while held, with the proven silent-terminator discipline on release and on mode switch. `MumbleManager` owns the mode as a persisted `StateFlow` mirroring `vadThreshold` exactly, and forwards it (plus a transient PTT-held bit) to the engine. The UI adds a selector in `SettingsScreen` and a hold-to-talk button that replaces Mute in `ActiveCallScreen`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Android `AudioRecord`/`AudioTrack`, RNNoise via `libdumble.so`, JUnit4 for JVM unit tests.

**User decisions (already made):**
- "I want a selector for voice activation in settings to go between push-to-talk and rnnoise based auto activation" — selector is the feature.
- "On-screen hold button" — PTT trigger; hardware/BT keys explicitly out of scope.
- "for now replace the mute button in PTT mode" — in PTT mode the hold button replaces Mute; switching into PTT clears self-mute.

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitMode.kt` (new) | The mode enum | 1 |
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt` | Add `denoise()` (denoise-only, no VAD/gate) | 1 |
| `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt` | Test `denoise()` | 1 |
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` | Mode-aware transmit decision | 2 |
| `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineTransmitModeTest.kt` (new) | Engine PTT behavior tests | 2 |
| `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt` | Persisted mode StateFlow + PTT-held forward + wiring | 3 |
| `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt` | Mode selector | 4 |
| `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt` | Hold-to-talk button, Mute replaced in PTT | 5 |
| `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt` | Collect mode, wire both screens | 6 |

**Dependency graph:** T1 → {T2, T4, T5}; T2 → T3; {T3, T4, T5} → T6. After T1, tasks **2, 4, 5 touch disjoint files and may run in parallel**. T3 needs T2's engine setters; T6 is the glue and runs last.

> **Execution note (revised during execution):** Tasks 4, 5, and 6 were **merged into a single UI task** (`SettingsScreen.kt` + `ActiveCallScreen.kt` + `DumbleApp.kt` in one commit). Kotlin compiles the whole module, so adding a required param to a screen breaks `DumbleApp`'s existing call site until T6 rewires it — the three changes cannot compile independently and must land together. The per-file code blocks in Tasks 4/5/6 below are still the source of truth; they are just applied as one task.

**Environment:** Every gradle command needs `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` first.

---

### Task 1: Voice core — TransmitMode enum + TransmitProcessor.denoise()

**Goal:** Add the `TransmitMode` enum and a denoise-only path on `TransmitProcessor` (runs the RNNoise suppressor per 10 ms sub-frame, no VAD, no gate) for the PTT held-audio path.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitMode.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt`

**Acceptance Criteria:**
- [ ] `TransmitMode` enum exists with `VOICE_ACTIVATED` and `PUSH_TO_TALK` values.
- [ ] `TransmitProcessor.denoise(capturePcm)` calls the suppressor once per 10 ms sub-frame (`FRAMES_PER_PACKET` times) and does NOT touch the gate.
- [ ] A test proves `denoise()` invokes the suppressor at offsets `0` and `FRAME_SAMPLES_10MS`, and leaves the gate closed (`gate` never opened).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.TransmitProcessorTest"` → BUILD SUCCESSFUL, all tests pass.

**Steps:**

- [ ] **Step 1: Create the enum**

Create `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitMode.kt`:

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * How the client decides to transmit.
 *  - [VOICE_ACTIVATED]: the RNNoise transmit gate opens/closes on voice activity (the default).
 *  - [PUSH_TO_TALK]: transmit only while the user holds the on-screen button; the gate is bypassed
 *    (audio is still denoised) so onsets are never clipped and quiet speech still goes through.
 */
enum class TransmitMode { VOICE_ACTIVATED, PUSH_TO_TALK }
```

- [ ] **Step 2: Write the failing test**

Open `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt` and add these tests inside the existing test class (keep existing imports; add `import org.junit.Assert.assertEquals` and `import org.junit.Assert.assertFalse` if not already present):

```kotlin
    private class OffsetRecordingSuppressor : NoiseSuppressor {
        val offsets = mutableListOf<Int>()
        override fun process(pcm: ShortArray, off: Int, n: Int) { offsets.add(off) }
        override fun close() {}
    }

    @Test fun denoiseRunsSuppressorPerSubframeWithoutGating() {
        val sup = OffsetRecordingSuppressor()
        val gate = TransmitGate()
        val proc = TransmitProcessor(sup, EnergyVadDetector(), gate)

        proc.denoise(ShortArray(CAPTURE_SAMPLES))

        assertEquals("one suppressor call per 10 ms sub-frame",
            listOf(0, FRAME_SAMPLES_10MS), sup.offsets)
        // denoise must not run VAD or the gate — the gate stays closed (no send decision made).
        assertFalse("gate must remain closed after denoise-only", gate.update(FloatArray(FRAMES_PER_PACKET)).send)
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.TransmitProcessorTest"`
Expected: FAIL — `denoise` is unresolved.

- [ ] **Step 4: Add `denoise()` to TransmitProcessor**

In `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt`, add this method right after `process()` (before `fun reset()`):

```kotlin
    /**
     * Denoise [capturePcm] (CAPTURE_SAMPLES) in place per 10 ms sub-frame, WITHOUT running VAD or
     * the gate. Used by the push-to-talk path: the mic is still cleaned, but the transmit decision
     * is the held button, not voice activity.
     */
    fun denoise(capturePcm: ShortArray) {
        for (i in 0 until FRAMES_PER_PACKET) {
            suppressor.process(capturePcm, i * FRAME_SAMPLES_10MS, FRAME_SAMPLES_10MS)
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.TransmitProcessorTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitMode.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt
git commit -m "feat(voice): TransmitMode enum + TransmitProcessor.denoise() for PTT path"
```

---

### Task 2: Engine — mode-aware transmit decision

**Goal:** Make `AudioVoiceEngine.nextOutgoingFrame()` honor `TransmitMode`: voice-activation unchanged; push-to-talk denoises and transmits only while `pttHeld`, bypassing the gate, emitting exactly one silent terminator on release and on a live mode switch.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineTransmitModeTest.kt` (new)

**Acceptance Criteria:**
- [ ] New constructor param `initialTransmitMode: TransmitMode = TransmitMode.VOICE_ACTIVATED`; setters `setTransmitMode(mode)` and `setPttHeld(value)`.
- [ ] PTT + not held → `nextOutgoingFrame()` returns `null` (no frames).
- [ ] PTT + held → returns one non-terminator frame per capture.
- [ ] PTT held→released edge → returns exactly one terminator frame (`length > 0`), then `null`.
- [ ] Switching mode while mid-transmit → returns exactly one terminator frame to close the open talkspurt.
- [ ] All existing `AudioVoiceEngineFrameNumberTest` tests still pass (voice-activation path unchanged).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.AudioVoiceEngine*"` → BUILD SUCCESSFUL, all tests pass.

**Steps:**

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineTransmitModeTest.kt`:

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineTransmitModeTest {

    /** ±amp square wave — same shape the frame-number test proves opens the VA gate. */
    private class SquareAudioIn(private val amp: Int = 8000) : AudioIn {
        override fun read(out: ShortArray, n: Int): Int {
            for (i in 0 until n) out[i] = if (i % 2 == 0) amp.toShort() else (-amp).toShort()
            return n
        }
        override fun close() {}
    }

    private fun engine(): AudioVoiceEngine =
        AudioVoiceEngine(FakeOpusCodec(), { SquareAudioIn() }, { FakeAudioOut() }).also { it.start() }

    @Test fun pttNotHeldTransmitsNothing() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK)
        repeat(5) { assertNull("PTT idle must not transmit", e.nextOutgoingFrame(0)) }
        e.stop()
    }

    @Test fun pttHeldTransmitsOneFramePerCapture() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK); e.setPttHeld(true)
        repeat(3) {
            val f = e.nextOutgoingFrame(0)
            assertTrue("held → a real, non-terminator frame", f != null && !f.isTerminator && f.length > 0)
        }
        e.stop()
    }

    @Test fun pttReleaseEmitsExactlyOneRealTerminatorThenSilence() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK)
        e.setPttHeld(true); repeat(3) { e.nextOutgoingFrame(0) }
        e.setPttHeld(false)

        val closing = e.nextOutgoingFrame(0)
        assertTrue("release → one terminator", closing != null && closing.isTerminator)
        assertTrue("terminator is a real (non-empty) frame", closing!!.length > 0)
        repeat(3) { assertNull("silence after the terminator", e.nextOutgoingFrame(0)) }
        e.stop()
    }

    @Test fun switchingToPttMidTalkspurtClosesWithOneTerminator() {
        val e = engine()                       // starts VOICE_ACTIVATED; loud square wave opens gate
        val speech = e.nextOutgoingFrame(0)
        assertTrue("VA is transmitting speech", speech != null && !speech.isTerminator)

        e.setTransmitMode(TransmitMode.PUSH_TO_TALK)   // switch mid-talkspurt, button not held
        val closing = e.nextOutgoingFrame(0)
        assertTrue("mode switch closes the open talkspurt", closing != null && closing.isTerminator)
        assertNull("then PTT idle is silent", e.nextOutgoingFrame(0))
        e.stop()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.AudioVoiceEngineTransmitModeTest"`
Expected: FAIL — `setTransmitMode` / `setPttHeld` unresolved.

- [ ] **Step 3: Add mode state + setters to the engine**

In `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`, add `initialTransmitMode` as the last constructor parameter (right after `gateOpenLevel: Float = 0.60f,`):

```kotlin
    gateOpenLevel: Float = 0.60f,
    initialTransmitMode: TransmitMode = TransmitMode.VOICE_ACTIVATED,
) : VoiceEngine {
```

Then, next to the other `@Volatile` fields (near `muted`/`running`/`wasMuted`), add:

```kotlin
    @Volatile private var transmitMode = initialTransmitMode
    @Volatile private var pttHeld = false
    @Volatile private var pendingClose = false   // set on a live mode change to flush an open talkspurt
    private var pttWasSending = false            // PTT release edge tracker
    private var sending = false                  // last emitted frame was a live (non-terminator) frame
```

Add the setters next to `setMuted` / `setVadThreshold`:

```kotlin
    /** Switch transmit mode live. A change flushes any open talkspurt on the next frame. */
    fun setTransmitMode(mode: TransmitMode) {
        if (mode == transmitMode) return
        transmitMode = mode
        pendingClose = true
    }

    /** Push-to-talk button state (only meaningful in [TransmitMode.PUSH_TO_TALK]). */
    fun setPttHeld(value: Boolean) { pttHeld = value }
```

- [ ] **Step 4: Rewrite `nextOutgoingFrame` + add two helpers**

Replace the whole `nextOutgoingFrame` body (from `override fun nextOutgoingFrame` through its closing brace) with:

```kotlin
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val rec = recorder ?: return null
        rec.read(capturePcm, CAPTURE_SAMPLES)             // capture clock — runs even while muted
        val fn = frameNumber
        frameNumber += FRAMES_PER_PACKET                 // wall-clock: advance every capture

        // A live transmit-mode change flushes any open talkspurt with one real terminator, so the
        // far end decodes/flushes instead of waiting on a stream that never resumes in the new mode.
        if (pendingClose) {
            pendingClose = false
            gate.reset(); pttWasSending = false; wasMuted = false
            if (sending) return terminatorFrame(fn)
        }

        if (muted) {
            gate.reset()                                 // so unmute starts closed
            if (!wasMuted) {                             // one real (silent) terminator on mute
                wasMuted = true
                return terminatorFrame(fn)
            }
            return null
        }
        wasMuted = false

        return when (transmitMode) {
            TransmitMode.VOICE_ACTIVATED -> {
                val d = processor.process(capturePcm)     // denoise (in place) → vad → gate
                if (!d.send) { sending = false; return null }
                encodeAndCount(fn, d.terminator)          // speech / hangover / closing frame
            }
            TransmitMode.PUSH_TO_TALK -> {
                gate.reset()                              // keep the VA gate closed under PTT
                when {
                    pttHeld -> {
                        processor.denoise(capturePcm)     // clean the mic, but do NOT gate
                        pttWasSending = true
                        encodeAndCount(fn, terminator = false)
                    }
                    pttWasSending -> {                    // release edge → one real terminator
                        pttWasSending = false
                        terminatorFrame(fn)
                    }
                    else -> { sending = false; null }
                }
            }
        }
    }

    /** A real (silent, non-empty) terminator frame. Mumble drops empty-payload packets before
     *  reading the terminator flag, so the closing frame must carry real (silent) Opus bytes. */
    private fun terminatorFrame(fn: Long): VoiceFrame {
        sending = false
        java.util.Arrays.fill(capturePcm, 0)
        val opus = encoder.encode(capturePcm, CAPTURE_SAMPLES)
        return VoiceFrame(opus, opus.size, fn, isTerminator = true)
    }

    /** Encode the current capture, update uplink stats, and mark send state. */
    private fun encodeAndCount(fn: Long, terminator: Boolean): VoiceFrame {
        sending = !terminator
        val opus = encoder.encode(capturePcm, CAPTURE_SAMPLES)
        uplinkBytes += opus.size
        if (++uplinkFrames >= 250) {
            val avgBytes = uplinkBytes.toDouble() / uplinkFrames
            android.util.Log.d("AudioVoiceEngine", "uplink avg=%.1f B/frame ~%.1f kbps".format(avgBytes, avgBytes * 0.4))
            uplinkBytes = 0; uplinkFrames = 0
        }
        sent++
        _stats.update { it.copy(sent = sent) }
        return VoiceFrame(opus, opus.size, fn, isTerminator = terminator)
    }
```

Note: this preserves the voice-activation path exactly (the same denoise→VAD→gate and the same encode/stats), factored into `encodeAndCount`. The mute terminator now routes through `terminatorFrame` (same silent, non-empty frame as before; still no `sent++`), so `AudioVoiceEngineFrameNumberTest.mutedReturnsNullButStillReads` still holds.

- [ ] **Step 5: Run the full engine suite to verify pass + no regression**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.AudioVoiceEngine*"`
Expected: PASS — both `AudioVoiceEngineTransmitModeTest` and `AudioVoiceEngineFrameNumberTest` green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineTransmitModeTest.kt
git commit -m "feat(voice): push-to-talk transmit mode in AudioVoiceEngine (gate bypassed while held)"
```

---

### Task 3: MumbleManager — persisted mode StateFlow + PTT-held forward + wiring

**Goal:** Add `transmitMode` as a persisted `StateFlow` on `MumbleManager` (mirroring `vadThreshold`), a transient `setPttHeld`, construct the engine with the loaded mode, and clear self-mute when switching to PTT.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`

**Acceptance Criteria:**
- [ ] `MumbleManager.transmitMode: StateFlow<TransmitMode>` exists, default `VOICE_ACTIVATED`, loaded from `"dumble_audio"` pref key `"transmit_mode"` in `init()`.
- [ ] `setTransmitMode(mode)` persists the mode, forwards to the active engine, and calls `setMuted(false)` when the mode is `PUSH_TO_TALK`.
- [ ] `setPttHeld(held)` forwards to the active engine (not persisted).
- [ ] The engine is constructed with `initialTransmitMode = _transmitMode.value`.
- [ ] `./gradlew :app:compileDebugKotlin` succeeds.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Import the enum**

At the top of `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`, add to the imports:

```kotlin
import me.danielstiner.dumble.mumble.voice.TransmitMode
```

- [ ] **Step 2: Add the StateFlow fields**

Immediately after the `vadThreshold` field declaration:

```kotlin
    private val _vadThreshold = MutableStateFlow(DEFAULT_VAD_THRESHOLD)
    /** RNNoise VAD open threshold (0..1), persisted and applied live to the active call. */
    val vadThreshold: StateFlow<Float> = _vadThreshold.asStateFlow()
```

add:

```kotlin
    private val _transmitMode = MutableStateFlow(TransmitMode.VOICE_ACTIVATED)
    /** Voice-activation vs push-to-talk, persisted and applied live to the active call. */
    val transmitMode: StateFlow<TransmitMode> = _transmitMode.asStateFlow()
```

- [ ] **Step 3: Add the setters**

Right after the existing `setVadThreshold` function (the one that persists `"vad_threshold"`), add:

```kotlin
    @Synchronized fun setTransmitMode(mode: TransmitMode) {
        _transmitMode.value = mode
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putString("transmit_mode", mode.name)?.apply()
        // In PTT the hold button is the sole transmit control, so clear any self-mute (this also
        // broadcasts selfMute=false so other clients stop showing the mute icon).
        if (mode == TransmitMode.PUSH_TO_TALK) setMuted(false)
        active?.setTransmitMode(mode)
    }

    @Synchronized fun setPttHeld(held: Boolean) { active?.setPttHeld(held) }
```

(`setMuted` is `@Synchronized` on the same singleton; JVM monitors are reentrant, so calling it from within `setTransmitMode` is safe.)

- [ ] **Step 4: Load the mode in `init()`**

In `init(context)`, replace the single `_vadThreshold` load line:

```kotlin
        _vadThreshold.value = app.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            .getFloat("vad_threshold", DEFAULT_VAD_THRESHOLD)
```

with:

```kotlin
        val audioPrefs = app.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
        _vadThreshold.value = audioPrefs.getFloat("vad_threshold", DEFAULT_VAD_THRESHOLD)
        val modeName = audioPrefs.getString("transmit_mode", null)
        _transmitMode.value = TransmitMode.entries.firstOrNull { it.name == modeName }
            ?: TransmitMode.VOICE_ACTIVATED
```

- [ ] **Step 5: Construct the engine with the loaded mode**

In `ActiveSession`, update the engine construction:

```kotlin
        private val engine = AudioVoiceEngine(
            codec, suppressor = rnnoise, vad = rnnoise, gateOpenLevel = _vadThreshold.value)
```

to:

```kotlin
        private val engine = AudioVoiceEngine(
            codec, suppressor = rnnoise, vad = rnnoise, gateOpenLevel = _vadThreshold.value,
            initialTransmitMode = _transmitMode.value)
```

- [ ] **Step 6: Add the ActiveSession delegates**

Next to the existing `fun setVadThreshold(value: Float) = engine.setVadThreshold(value)`:

```kotlin
        fun setTransmitMode(mode: TransmitMode) = engine.setTransmitMode(mode)
        fun setPttHeld(held: Boolean) = engine.setPttHeld(held)
```

- [ ] **Step 7: Verify compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(voice): persist transmit mode + forward PTT-held on MumbleManager"
```

---

### Task 4: Settings UI — mode selector

**Goal:** Add a "Transmit mode" radio selector (Voice activity | Push to talk) to `SettingsScreen` above the sensitivity slider; disable + annotate the slider in PTT mode.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`

**Acceptance Criteria:**
- [ ] `SettingsScreen` takes new params `transmitMode: TransmitMode` and `onTransmitModeChange: (TransmitMode) -> Unit`.
- [ ] A radio group lets the user pick Voice activity or Push to talk; picking one calls `onTransmitModeChange`.
- [ ] The sensitivity slider is `enabled` only in voice-activation mode and shows an "Applies in Voice activity mode" note in PTT mode.
- [ ] `./gradlew :app:compileDebugKotlin` succeeds and the `@Preview` compiles.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Add imports**

Add to `SettingsScreen.kt` imports:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import me.danielstiner.dumble.mumble.voice.TransmitMode
```

- [ ] **Step 2: Add the params**

Change the `SettingsScreen` signature to add the two new params (place them before `vadThreshold`):

```kotlin
fun SettingsScreen(
    onBack: () -> Unit,
    onLaunchEchoTest: () -> Unit,
    onLaunchVadDebug: () -> Unit,
    transmitMode: TransmitMode,
    onTransmitModeChange: (TransmitMode) -> Unit,
    vadThreshold: Float,
    onVadThresholdChange: (Float) -> Unit,
) {
```

- [ ] **Step 3: Add the selector + gate the slider**

Replace the inner `Column` that currently holds the "Voice activity" block:

```kotlin
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Voice activity")
                Text("Sensitivity threshold: %.2f".format(vadThreshold))
                Slider(
                    value = vadThreshold,
                    onValueChange = onVadThresholdChange,
                    valueRange = 0.3f..0.95f,
                )
                Text("Higher = transmits only on clearer speech. Applies live to the active call.")
            }
```

with:

```kotlin
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Transmit mode")
                Column(modifier = Modifier.selectableGroup()) {
                    TransmitMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = transmitMode == mode,
                                    onClick = { onTransmitModeChange(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = transmitMode == mode, onClick = null)
                            Text(
                                when (mode) {
                                    TransmitMode.VOICE_ACTIVATED -> "Voice activity"
                                    TransmitMode.PUSH_TO_TALK -> "Push to talk"
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                val vaMode = transmitMode == TransmitMode.VOICE_ACTIVATED
                Text("Sensitivity threshold: %.2f".format(vadThreshold))
                Slider(
                    value = vadThreshold,
                    onValueChange = onVadThresholdChange,
                    valueRange = 0.3f..0.95f,
                    enabled = vaMode,
                )
                Text(
                    if (vaMode) "Higher = transmits only on clearer speech. Applies live to the active call."
                    else "Applies in Voice activity mode.",
                )
            }
```

- [ ] **Step 4: Update the `@Preview`**

Change the preview call to pass the new params:

```kotlin
        SettingsScreen(onBack = {}, onLaunchEchoTest = {}, onLaunchVadDebug = {},
            transmitMode = TransmitMode.VOICE_ACTIVATED, onTransmitModeChange = {},
            vadThreshold = 0.5f, onVadThresholdChange = {})
```

- [ ] **Step 5: Verify compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt
git commit -m "feat(ui): transmit-mode selector in Settings"
```

---

### Task 5: Call UI — hold-to-talk button replaces Mute in PTT

**Goal:** In `ActiveCallScreen`, render a press-and-hold "Hold to talk" button when the mode is `PUSH_TO_TALK` (replacing the Mute chip); voice-activation mode keeps the Mute chip. Release fires reliably even on gesture cancel.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`

**Acceptance Criteria:**
- [ ] `ActiveCallScreen` takes new params `transmitMode: TransmitMode`, `onPttPress: () -> Unit`, `onPttRelease: () -> Unit`.
- [ ] In `VOICE_ACTIVATED` mode the Mute chip shows; in `PUSH_TO_TALK` mode it is absent and a hold-to-talk button is shown instead.
- [ ] Press calls `onPttPress`; release/cancel calls `onPttRelease` (via `detectTapGestures` + `tryAwaitRelease`).
- [ ] `./gradlew :app:compileDebugKotlin` succeeds and the `@Preview` compiles.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Add imports**

Add to `ActiveCallScreen.kt` imports:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import me.danielstiner.dumble.mumble.voice.TransmitMode
```

(`MaterialTheme`, `Box`, `Modifier`, `Alignment`, `Text`, `dp` may already be imported — do not duplicate.)

- [ ] **Step 2: Add the params**

Update the `ActiveCallScreen` signature (add the three params after `muted`):

```kotlin
fun ActiveCallScreen(
    statusText: String,
    statsText: String,
    muted: Boolean,
    transmitMode: TransmitMode,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    speaker: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
```

- [ ] **Step 3: Make the control row mode-aware + add the PTT button**

Replace the existing controls `Row` (the one with the Mute and Speaker `FilterChip`s):

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = muted,
                    onClick = onToggleMute,
                    label = { Text(if (muted) "Unmute" else "Mute") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = speaker,
                    onClick = onToggleSpeaker,
                    label = { Text("Speaker") },
                    modifier = Modifier.weight(1f),
                )
            }
```

with:

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (transmitMode == TransmitMode.VOICE_ACTIVATED) {
                    FilterChip(
                        selected = muted,
                        onClick = onToggleMute,
                        label = { Text(if (muted) "Unmute" else "Mute") },
                        modifier = Modifier.weight(1f),
                    )
                }
                FilterChip(
                    selected = speaker,
                    onClick = onToggleSpeaker,
                    label = { Text("Speaker") },
                    modifier = Modifier.weight(1f),
                )
            }
            if (transmitMode == TransmitMode.PUSH_TO_TALK) {
                PushToTalkButton(onPress = onPttPress, onRelease = onPttRelease)
            }
```

- [ ] **Step 4: Add the `PushToTalkButton` composable**

Add this private composable below `ActiveCallScreen` (above the `@Preview`):

```kotlin
@Composable
private fun PushToTalkButton(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (pressed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; onPress()
                    tryAwaitRelease()          // suspends until release OR gesture cancel
                    pressed = false; onRelease()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (pressed) "Release to stop" else "Hold to talk",
            color = if (pressed) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
```

- [ ] **Step 5: Update the `@Preview`**

Update `ActiveCallScreenPreview` to pass the new params:

```kotlin
        ActiveCallScreen(
            statusText = "In Call",
            statsText = "state=Synchronized mode=UDP\nudpRtt=11.5ms jit=1.4ms",
            muted = false,
            transmitMode = TransmitMode.VOICE_ACTIVATED,
            onPttPress = {},
            onPttRelease = {},
            speaker = false,
            onToggleMute = {},
            onToggleSpeaker = {},
            onHangUp = {},
            onOpenSettings = {},
        )
```

- [ ] **Step 6: Verify compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt
git commit -m "feat(ui): hold-to-talk button replaces Mute in PTT mode"
```

---

### Task 6: DumbleApp — collect mode, wire both screens

**Goal:** Collect `MumbleManager.transmitMode` in `DumbleApp` and pass the new params/callbacks into `ActiveCallScreen` and `SettingsScreen`, completing the end-to-end wiring.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] `DumbleApp` collects `MumbleManager.transmitMode` via `collectAsStateWithLifecycle()`.
- [ ] `ActiveCallScreen` is called with `transmitMode`, `onPttPress = { MumbleManager.setPttHeld(true) }`, `onPttRelease = { MumbleManager.setPttHeld(false) }`.
- [ ] `SettingsScreen` is called with `transmitMode` and `onTransmitModeChange = { MumbleManager.setTransmitMode(it) }`.
- [ ] Full app builds and the whole unit-test suite is green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, all tests pass.

**Steps:**

- [ ] **Step 1: Collect the mode**

After the existing `val vadThreshold by MumbleManager.vadThreshold.collectAsStateWithLifecycle()` line, add:

```kotlin
    val transmitMode by MumbleManager.transmitMode.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Wire `ActiveCallScreen`**

Update the `ActiveCallScreen(...)` call to add the three params (keep the existing ones):

```kotlin
            ActiveCallScreen(
                statusText = statusText, statsText = statsText,
                muted = muted, speaker = speaker,
                transmitMode = transmitMode,
                onPttPress = { MumbleManager.setPttHeld(true) },
                onPttRelease = { MumbleManager.setPttHeld(false) },
                onToggleMute = { MumbleManager.setMuted(!muted) },
                onToggleSpeaker = { CallManager.setSpeaker(!speaker) },
                onHangUp = onHangUp,
                onOpenSettings = { showSettings = true },
            )
```

- [ ] **Step 3: Wire `SettingsScreen`**

Update the `SettingsScreen(...)` call:

```kotlin
            SettingsScreen(
                onBack = { showSettings = false },
                onLaunchEchoTest = onLaunchEchoTest,
                onLaunchVadDebug = onLaunchVadDebug,
                transmitMode = transmitMode,
                onTransmitModeChange = { MumbleManager.setTransmitMode(it) },
                vadThreshold = vadThreshold,
                onVadThresholdChange = { MumbleManager.setVadThreshold(it) },
            )
```

- [ ] **Step 4: Verify build + full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): wire transmit-mode selector + PTT button end-to-end"
```

---

## Manual verification (recommended, not gated)

The PTT button and self-mute broadcast can't be exercised by JVM unit tests. After Task 6, on a device/emulator: connect to a server, open Settings, switch to **Push to talk** — confirm the Mute chip disappears in the call screen and a "Hold to talk" button appears; hold it and confirm the far end hears you only while held, with no clipped onset; release and confirm transmission stops cleanly; switch back to **Voice activity** and confirm the Mute chip returns and the gate transmits on speech. Also confirm the mode survives an app restart (persisted).

---

## Self-Review

**Spec coverage:**
- Mode enum + default → Task 1 / Task 3. ✓
- Engine PTT logic (gate bypass, denoise retained, terminator on release, wall-clock frame numbers) → Task 2. ✓
- MumbleManager mirror of `vadThreshold` + persistence + clear-self-mute-on-PTT + engine construction → Task 3. ✓
- Settings selector + slider annotation → Task 4. ✓
- Call UI hold button replacing Mute → Task 5. ✓
- DumbleApp wiring → Task 6. ✓
- Mode-switch clean close (spec §6) → Task 2 (`pendingClose`) + test. ✓
- Tests mirror `AudioVoiceEngineFrameNumberTest` harness (spec §7) → Task 2. ✓
- Scoped-out hardware keys / latch / haptics → not in any task. ✓

**Type consistency:** `TransmitMode.{VOICE_ACTIVATED, PUSH_TO_TALK}`, `setTransmitMode(TransmitMode)`, `setPttHeld(Boolean)`, `initialTransmitMode` param, and `transmitMode: StateFlow<TransmitMode>` are used identically across Tasks 1–6. Pref key `"transmit_mode"` and store `"dumble_audio"` match `vadThreshold`'s pattern.

**Placeholder scan:** none — every code step is complete.
