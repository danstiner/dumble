# Call-Screen Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the minimal in-call screen with the imported "Mumble Call" design — a themed screen with a live channel/user tree (speaking indicators, mute badges), a Mute/Deafen/Speaker/Leave control bar, and a self-deafen capability.

**Architecture:** New engine signals (`speakingSessions`, `selfTransmitting`) flow through `MumbleManager` alongside the existing model; a pure `CallScreenState` mapper assembles them plus local mute/deafen/route into ordered channel + user view-models; a rewritten `ActiveCallScreen` renders them with **standard Material 3 Expressive components** styled from `MaterialTheme.colorScheme` (so it follows the app theme, light or dark). Self-deafen is hot-mic-safe (never silently opens a live mic).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 / Expressive, Compose BOM 2026.06.00), `material-icons-extended`, StateFlow, JUnit4 (JVM unit tests, host-native RNNoise available).

**User decisions (already made):**
- **Do NOT match the mock pixel-perfect.** Prefer standard **Material 3 Expressive** components, design language, and paradigms — "simple and good looking." `docs/design/Mumble-Call.dc.html` is **layout/content reference**, not a pixel spec.
- Style everything from `MaterialTheme.colorScheme` (no hardcoded palette); the screen **follows the app theme** (light/dark) rather than being locked light-only.
- Content the mock informs: server/channel header with a connection timer; a scrollable **channel → user tree**; per-user **speaking** indicator + **self-mute (neutral) / server-mute (error)** badges + **YOU** tag; a **Mute / Deafen / Speaker / Leave** control bar (Leave inline, not a separate pill).
- **Speaker reflects the active route** (BT / wired / earpiece / speaker) via `CallManager.activeEndpoint` + `AudioRoute.label`, folding in the already-shipped route indicator.
- **Omit** BOT tag, AFK/away moon, and per-channel icons (no Mumble protocol backing) — one generic channel glyph, flat (non-nested) list.
- **Float self** to the top of its own channel; tag YOU.
- Add the `material-icons-extended` dependency.
- PTT already shipped (spec 1): in Push-to-Talk mode the **Mute** slot becomes **Hold-to-Talk**.
- **Do NOT merge at the end** — Dan batches on-device testing.

**Spec:** `docs/superpowers/specs/2026-07-15-mumble-call-screen-redesign-design.md`. **Design source:** `docs/design/Mumble-Call.dc.html`.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | add `material-icons-extended` | 1 |
| `.../voice/SpeakingHold.kt`, `.../voice/TransmitHold.kt` (new) | pure release-hold helpers for the speaking/transmitting indicators | 2 |
| `.../voice/AudioVoiceEngine.kt` | `speakingSessions`/`selfTransmitting` StateFlows; `deafened` playout muting; `setDeafened` | 3 |
| `.../protocol/SessionStateMachine.kt` | `sendSelfDeaf(deaf, mute)` | 4 |
| `.../MumbleManager.kt`, `.../voice/DeafenLogic.kt` (new) | mirror speaking flows; `_deafened` StateFlow; hot-mic-safe `setDeafened`; reset on shutdown | 5 |
| `.../telecom/AudioRoute.kt` | route→icon mapping (for the Speaker control) | 6 |
| `.../ui/CallScreenState.kt` (new) | pure state assembly (channels/users VMs) — no Compose deps | 7 |
| `.../ui/ActiveCallScreen.kt` | full rewrite with M3 components: header + channel tree + control bar | 8 |
| `.../ui/DumbleApp.kt` | collect model + speaking flows + deafen/route; assemble state; `connectedSince` watcher; wire callbacks | 9 |

**Dependencies:** 2→3; (3,4)→5; (1,6,7)→8; (5,7,8)→9. Tasks 1, 2, 4, 6, 7 have no cross-dependencies and may run in parallel (disjoint files).

**Environment:** every gradle command needs `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` first.

---

### Task 1: Add material-icons-extended dependency

**Goal:** Make the extended Material icon set (mic/mic_off/headphones/headset_off/call_end/tune/headset_mic + route icons) available to Compose.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Acceptance Criteria:**
- [ ] `libs.versions.toml` declares `androidx-compose-material-icons-extended` (no explicit version — governed by the Compose BOM)
- [ ] `app/build.gradle.kts` adds `implementation(libs.androidx.compose.material.icons.extended)`
- [ ] `Icons.Filled.Headphones` and `Icons.Filled.CallEnd` resolve (a debug build compiles)

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Declare the library** in `gradle/libs.versions.toml`, directly after the `androidx-compose-material-icons-core` line:

```toml
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
```

- [ ] **Step 2: Add the dependency** in `app/build.gradle.kts`, directly after the `implementation(libs.androidx.compose.material.icons.core)` line:

```kotlin
    implementation(libs.androidx.compose.material.icons.extended)
```

- [ ] **Step 3: Verify** it resolves. Run:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add material-icons-extended for the call-screen redesign"
```

---

### Task 2: Pure release-hold helpers

**Goal:** Two pure, single-threaded, JVM-testable helpers that add a ~200 ms release hold to the speaking/transmitting indicators so they don't strobe at the 20 ms tick cadence.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakingHold.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitHold.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakingHoldTest.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitHoldTest.kt`

**Acceptance Criteria:**
- [ ] `SpeakingHold.tick(produced)` keeps a session in the returned set for `SPEAKING_HOLD_TICKS` ticks after its last produced frame, then drops it
- [ ] `SpeakingHold.drop(session)` removes a session immediately
- [ ] `TransmitHold.update(sending)` returns true while `sending` and for `TRANSMIT_HOLD_TICKS` calls after the last true, then false
- [ ] Both are deterministic, no Android imports

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*SpeakingHoldTest" --tests "*TransmitHoldTest"` → PASS

**Steps:**

- [ ] **Step 1: Write `SpeakingHoldTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Semantics: a session is present on the tick it is produced, then held for `holdTicks` further
// silent ticks, then dropped. So holdTicks=2 → present on the producing tick + 2 held ticks.
class SpeakingHoldTest {
    @Test fun heldForHoldTicksThenDropped() {
        val h = SpeakingHold(holdTicks = 2)
        assertTrue(h.tick(setOf(7)).contains(7))     // produced (appearance)
        assertTrue(h.tick(emptySet()).contains(7))   // hold 1
        assertTrue(h.tick(emptySet()).contains(7))   // hold 2
        assertFalse("dropped after holdTicks holds", h.tick(emptySet()).contains(7))
    }

    @Test fun refreshExtendsTheHold() {
        val h = SpeakingHold(holdTicks = 2)
        h.tick(setOf(1))                              // produced
        h.tick(setOf(1))                              // refreshed -> hold restarts
        assertTrue(h.tick(emptySet()).contains(1))   // hold 1 after refresh
        assertTrue(h.tick(emptySet()).contains(1))   // hold 2 after refresh
        assertFalse(h.tick(emptySet()).contains(1))  // dropped
    }

    @Test fun dropRemovesImmediately() {
        val h = SpeakingHold(holdTicks = 5)
        h.tick(setOf(4))
        h.drop(4)
        assertFalse(h.tick(emptySet()).contains(4))
    }

    @Test fun refreshedSessionOutlivesAnUnrefreshedOne() {
        val h = SpeakingHold(holdTicks = 2)
        h.tick(setOf(1, 2))                          // both produced
        h.tick(setOf(1))                             // 1 refreshed; 2 keeps aging
        h.tick(emptySet())                           // both still held
        val c4 = h.tick(emptySet())
        assertTrue("refreshed 1 still held", c4.contains(1))
        assertFalse("unrefreshed 2 already dropped", c4.contains(2))
    }

    @Test fun clearEmptiesTheSet() {
        val h = SpeakingHold(holdTicks = 5)
        h.tick(setOf(1, 2))
        h.clear()
        assertTrue(h.tick(emptySet()).isEmpty())
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (unresolved `SpeakingHold`).
Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*SpeakingHoldTest"`

- [ ] **Step 3: Write `SpeakingHold.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Release-hold for the per-speaker "speaking" indicator. Fed the set of sessions that produced audio
 * on each playback tick; keeps a session in the "speaking" set for [holdTicks] ticks after its last
 * produced frame so the indicator doesn't strobe on the ~20 ms tick cadence. Pure, single-threaded
 * (playback thread only); JVM-unit-testable.
 */
class SpeakingHold(private val holdTicks: Int = SPEAKING_HOLD_TICKS) {
    private val remaining = HashMap<Int, Int>()

    /**
     * Advance one tick with the sessions that produced audio this tick; return the held set. A
     * produced session is present this tick and for [holdTicks] further silent ticks, then dropped.
     */
    fun tick(produced: Set<Int>): Set<Int> {
        for (s in produced) remaining[s] = holdTicks   // (re)arm produced sessions to the full hold
        val present = HashSet(remaining.keys)          // present THIS tick (incl. just-armed)
        val it = remaining.entries.iterator()          // then age the holds for the next tick
        while (it.hasNext()) {
            val e = it.next()
            if (e.value <= 0) it.remove() else e.setValue(e.value - 1)
        }
        return present
    }

    /** Forget a session immediately (its stream retired). */
    fun drop(session: Int) { remaining.remove(session) }

    fun clear() { remaining.clear() }

    companion object { const val SPEAKING_HOLD_TICKS = 10 }   // ~200 ms at 20 ms/tick
}
```

- [ ] **Step 4: Write `TransmitHoldTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmitHoldTest {
    @Test fun trueWhileSendingThenReleasesAfterHold() {
        val h = TransmitHold(holdTicks = 2)
        assertFalse("not held before any send", h.update(false))
        assertTrue(h.update(true))    // sending
        assertTrue(h.update(false))   // hold 1
        assertTrue(h.update(false))   // hold 2
        assertFalse("released after holdTicks holds", h.update(false))
    }

    @Test fun sendingRefreshesTheHold() {
        val h = TransmitHold(holdTicks = 2)
        h.update(true); h.update(false)  // sending, hold 1
        assertTrue(h.update(true))       // sending again -> refresh
        assertTrue(h.update(false))      // hold 1 after refresh
        assertTrue(h.update(false))      // hold 2 after refresh
        assertFalse(h.update(false))     // released
    }

    @Test fun clearResetsToNotHeld() {
        val h = TransmitHold(holdTicks = 5)
        h.update(true)
        h.clear()
        assertFalse(h.update(false))
    }
}
```

- [ ] **Step 5: Write `TransmitHold.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Release-hold for the local "you are transmitting" indicator. Fed whether a real (non-terminator)
 * frame was sent on each capture; stays true for [holdTicks] captures after the last real send so the
 * indicator doesn't flicker between talkspurt frames. Pure, single-threaded (send thread only).
 */
class TransmitHold(private val holdTicks: Int = TRANSMIT_HOLD_TICKS) {
    private var remaining = -1   // <0 = not held

    /**
     * Advance one capture; [sending] = a real (non-terminator) frame went out this capture. Returns
     * true on a sending capture and for [holdTicks] further captures after the last send (consistent
     * with [SpeakingHold]).
     */
    fun update(sending: Boolean): Boolean {
        if (sending) remaining = holdTicks
        if (remaining < 0) return false
        remaining--          // present this capture, then age (holdTicks further after a send)
        return true
    }

    fun clear() { remaining = -1 }

    companion object { const val TRANSMIT_HOLD_TICKS = 10 }   // ~200 ms at 20 ms/capture
}
```

- [ ] **Step 6: Run — expect PASS.** Then commit:

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakingHold.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitHold.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakingHoldTest.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitHoldTest.kt
git commit -m "feat(voice): pure release-hold helpers for speaking/transmitting indicators"
```

---

### Task 3: Engine live-speaking signals + deafen

**Goal:** `AudioVoiceEngine` exposes `speakingSessions: StateFlow<Set<Int>>` and `selfTransmitting: StateFlow<Boolean>`, mutes playout when deafened (still draining streams), and offers `setDeafened`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineSpeakingTest.kt`

**Acceptance Criteria:**
- [ ] `selfTransmitting` becomes true while real frames are sent and releases to false `TRANSMIT_HOLD_TICKS` captures after sending stops (unit-tested via `nextOutgoingFrame`)
- [ ] `setDeafened(true)` sets the engine's deafened flag (unit-tested via `isDeafened`); `playbackLoop` zeroes the mix when deafened but still calls `fillTick` on every stream
- [ ] `speakingSessions` is emitted only on set-change, uses `SpeakingHold`, and drops a session's hold when its stream retires (logic covered by `SpeakingHoldTest`; wiring compiles)
- [ ] `stop()` resets `speakingSessions`/`selfTransmitting`/`deafened` and clears both holds

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioVoiceEngineSpeakingTest" --tests "*AudioVoiceEngine*"` → PASS

**Steps:**

- [ ] **Step 1: Add state + helpers.** In `AudioVoiceEngine.kt`, after the `lateDrops` property (around line 65), add:

```kotlin
    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    /** Sessions currently producing playout audio (with a ~200 ms release hold). */
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()
    private val _selfTransmitting = MutableStateFlow(false)
    /** True while our uplink is sending real (non-terminator) frames (with a ~200 ms release hold). */
    val selfTransmitting: StateFlow<Boolean> = _selfTransmitting.asStateFlow()

    private val speakingHold = SpeakingHold()   // playback thread only
    private val transmitHold = TransmitHold()   // send thread only
    @Volatile private var deafened = false

    /** Live-toggle self-deafen: mutes playout (still draining streams to keep jitter buffers sane). */
    fun setDeafened(value: Boolean) { deafened = value }
    internal val isDeafened get() = deafened   // test seam
```

- [ ] **Step 2: Track self-transmitting.** Wrap `nextOutgoingFrame` so every capture updates the hold. Rename the current `override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? { ... }` body to a private `computeOutgoing()`, and add the wrapper. Replace the method signature line:

```kotlin
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
```

with:

```kotlin
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val frame = computeOutgoing()
        val realSend = frame != null && !frame.isTerminator
        val transmitting = transmitHold.update(realSend)
        if (transmitting != _selfTransmitting.value) _selfTransmitting.value = transmitting
        return frame
    }

    private fun computeOutgoing(): VoiceFrame? {
```

(The rest of the original method body — `val rec = recorder ?: return null` through the `when (mode) { ... }` — is unchanged; it now lives in `computeOutgoing()`.)

- [ ] **Step 3: Track speaking sessions + deafen in `playbackLoop`.** Replace the `playbackLoop()` body with:

```kotlin
    private fun playbackLoop() {
        val out = track!!
        val mix = ShortArray(FRAME_SAMPLES_20MS)
        val acc = IntArray(FRAME_SAMPLES_20MS)
        val speakerOut = ShortArray(FRAME_SAMPLES_20MS)
        val producedSessions = HashSet<Int>()
        while (running) {
            java.util.Arrays.fill(acc, 0)
            producedSessions.clear()
            var active = 0
            val it = speakers.entries.iterator()
            while (it.hasNext()) {
                val (session, stream) = it.next()
                val produced = stream.fillTick(speakerOut)
                if (produced) {
                    AudioMixer.accumulate(acc, speakerOut, FRAME_SAMPLES_20MS)
                    producedSessions.add(session)
                    active++
                }
                if (stream.retired) { stream.close(); it.remove(); speakingHold.drop(session) }
            }
            AudioMixer.finalizeMix(acc, mix, FRAME_SAMPLES_20MS)
            if (deafened) java.util.Arrays.fill(mix, 0)   // mute playout, streams already drained above
            out.write(mix, FRAME_SAMPLES_20MS)            // ALWAYS write 20 ms (silence when idle/deafened)
            val speaking = speakingHold.tick(producedSessions)
            if (speaking != _speakingSessions.value) _speakingSessions.value = speaking
            _stats.update { it.copy(received = received.get(), activeSpeakers = active) }
        }
    }
```

(This drops the per-50-tick mix-peak debug log, which was diagnostic-only and is superseded by the speaking indicator.)

- [ ] **Step 4: Reset on stop.** In `stop()`, after `speakers.clear()`, add:

```kotlin
        _speakingSessions.value = emptySet()
        _selfTransmitting.value = false
        speakingHold.clear()
        transmitHold.clear()
        deafened = false
```

- [ ] **Step 5: Write `AudioVoiceEngineSpeakingTest.kt`** (harness mirrors `AudioVoiceEngineDiagnosticsTest`):

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineSpeakingTest {
    private class SteadyAudioIn(private val amp: Int) : AudioIn {
        override fun read(out: ShortArray, n: Int): Int {
            for (i in 0 until n) out[i] = (if (i % 2 == 0) amp else -amp).toShort(); return n
        }
        override fun close() {}
    }
    private class AlwaysSpeechVad : VadDetector {
        override fun level(pcm: ShortArray, off: Int, n: Int): Float = 1.0f
    }

    @Test fun selfTransmittingTracksRealSendsWithReleaseHold() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(6000) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = AlwaysSpeechVad(), gateOpenLevel = 0.5f,
        ).also { it.start() }
        repeat(5) { engine.nextOutgoingFrame(0) }
        assertTrue("transmitting while sending", engine.selfTransmitting.value)
        engine.setMuted(true)                                          // -> one terminator, then silence
        repeat(TransmitHold.TRANSMIT_HOLD_TICKS + 3) { engine.nextOutgoingFrame(0) }
        assertFalse("released after hold once sending stops", engine.selfTransmitting.value)
        engine.stop()
    }

    @Test fun setDeafenedTogglesEngineFlag() {
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { SteadyAudioIn(500) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = EnergyVadDetector(),
        ).also { it.start() }
        assertFalse(engine.isDeafened)
        engine.setDeafened(true)
        assertTrue(engine.isDeafened)
        engine.stop()
        assertFalse("reset on stop", engine.isDeafened)
    }
}
```

- [ ] **Step 6: Run the verify command — expect PASS. Commit:**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineSpeakingTest.kt
git commit -m "feat(voice): speakingSessions/selfTransmitting signals + deafen playout muting"
```

**Note (expected, not a bug):** `SpeakerStream.produced` is true during PLC concealment and false during the ~100 ms prebuffer, so the local speaking ring onsets ~100 ms late and rides through concealed gaps — matches the spec. `playbackLoop`/deafen playout are threaded and verified on-device.

---

### Task 4: SessionStateMachine.sendSelfDeaf

**Goal:** Broadcast self-deaf (and the resulting self-mute) in a single `UserState`, mirroring `sendSelfMute`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt`

**Acceptance Criteria:**
- [ ] `sendSelfDeaf(deaf, mute)` sends one `UserState` with both `self_deaf` and `self_mute` set
- [ ] Existing `sendSelfMute` unchanged; compiles

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Add `sendSelfDeaf`** directly after `sendSelfMute` (around line 155):

```kotlin
    /** Broadcasts self-deaf plus the resulting self-mute in one UserState (server infers session). */
    fun sendSelfDeaf(deaf: Boolean, mute: Boolean) {
        channel.send(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSelfDeaf(deaf).setSelfMute(mute).build())
    }
```

- [ ] **Step 2: Verify + commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt
git commit -m "feat(protocol): sendSelfDeaf broadcasts self_deaf + self_mute"
```

---

### Task 5: MumbleManager deafen + speaking-flow mirror

**Goal:** Mirror the engine's speaking flows, expose `deafened` with a hot-mic-safe `setDeafened` (auto-unmute only if the deafen set the mute), and reset everything on shutdown.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/DeafenLogic.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/DeafenLogicTest.kt`

**Acceptance Criteria:**
- [ ] `DeafenLogic.onSetDeafened` is hot-mic-safe: `manual-mute → deafen → un-deafen` leaves the mic muted; `unmuted → deafen → un-deafen` returns to unmuted
- [ ] `MumbleManager` exposes `speakingSessions`, `selfTransmitting`, `deafened` StateFlows mirrored from the engine
- [ ] `setDeafened` mutes playout, sets `_muted` directly (no double UserState), and sends one `UserState{self_deaf, self_mute}` via `sendSelfDeaf`
- [ ] shutdown resets the new flows

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*DeafenLogicTest" && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` → PASS + BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Write `DeafenLogicTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeafenLogicTest {
    @Test fun unmutedDeafenThenUndeafenReturnsToUnmuted() {
        val d = DeafenLogic.onSetDeafened(deafen = true, curMuted = false, curDeafenSetMute = false)
        assertTrue(d.muted); assertTrue(d.deafenSetMute)
        val u = DeafenLogic.onSetDeafened(deafen = false, curMuted = d.muted, curDeafenSetMute = d.deafenSetMute)
        assertFalse("auto-unmutes because deafen set the mute", u.muted)
        assertFalse(u.deafenSetMute)
    }

    @Test fun manualMuteSurvivesDeafenUndeafen() {
        val d = DeafenLogic.onSetDeafened(deafen = true, curMuted = true, curDeafenSetMute = false)
        assertTrue(d.muted); assertFalse("deafen did not set the mute", d.deafenSetMute)
        val u = DeafenLogic.onSetDeafened(deafen = false, curMuted = d.muted, curDeafenSetMute = d.deafenSetMute)
        assertTrue("manual mute must survive un-deafen (no hot mic)", u.muted)
    }
}
```

- [ ] **Step 2: Write `DeafenLogic.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Hot-mic-safe deafen ↔ mute coupling (mirrors Mumble's bAutoUnmute). Deafen forces mute; un-deafen
 * auto-unmutes ONLY if the deafen was what set the mute — a pre-existing manual mute must survive a
 * `mute → deafen → un-deafen` sequence so the mic never silently reopens.
 */
object DeafenLogic {
    data class Result(val muted: Boolean, val deafenSetMute: Boolean)

    fun onSetDeafened(deafen: Boolean, curMuted: Boolean, curDeafenSetMute: Boolean): Result =
        if (deafen) {
            Result(muted = true, deafenSetMute = !curMuted)          // "we set it" only if it wasn't already muted
        } else {
            Result(muted = if (curDeafenSetMute) false else curMuted, deafenSetMute = false)
        }
}
```

- [ ] **Step 3: Add the state + flows in `MumbleManager.kt`.** After the `_agcEnabled`/`rnnoiseEnabled` block (around line 74), add:

```kotlin
    private val _deafened = MutableStateFlow(false)
    /** Self-deafen (mutes playout + implies self-mute), broadcast to peers. */
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()
    private var deafenSetMute = false
    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    /** Sessions currently producing playout audio (live during a call). */
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()
    private val _selfTransmitting = MutableStateFlow(false)
    /** True while our uplink is transmitting (live during a call). */
    val selfTransmitting: StateFlow<Boolean> = _selfTransmitting.asStateFlow()
```

- [ ] **Step 4: Add `setDeafened`** after `setMuted` (around line 83):

```kotlin
    @Synchronized fun setDeafened(value: Boolean) {
        val r = DeafenLogic.onSetDeafened(value, _muted.value, deafenSetMute)
        deafenSetMute = r.deafenSetMute
        _deafened.value = value
        _muted.value = r.muted                 // set directly — one combined UserState below, no double-send
        active?.setDeafened(value)
        active?.setMuted(r.muted)
        active?.sendSelfDeaf(value, r.muted)
    }
```

- [ ] **Step 5: Mirror the engine flows.** In `ActiveSession.start()`, after the `engine.stats.collect` line (around line 242), add:

```kotlin
            sessionScope.launch { engine.speakingSessions.collect { _speakingSessions.value = it } }
            sessionScope.launch { engine.selfTransmitting.collect { _selfTransmitting.value = it } }
```

- [ ] **Step 6: Add the `ActiveSession` delegate** after `sendSelfMute` (around line 306):

```kotlin
        fun setDeafened(value: Boolean) = engine.setDeafened(value)
        fun sendSelfDeaf(deaf: Boolean, mute: Boolean) = sm.sendSelfDeaf(deaf, mute)
```

- [ ] **Step 7: Reset on shutdown.** In `ActiveSession.shutdown()`, after `_audioDiagnostics.value = AudioDiagnostics()`, add:

```kotlin
            _speakingSessions.value = emptySet()
            _selfTransmitting.value = false
            _deafened.value = false
            deafenSetMute = false
```

- [ ] **Step 8: Run the verify command — expect PASS + BUILD SUCCESSFUL. Commit:**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/DeafenLogic.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/DeafenLogicTest.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(voice): hot-mic-safe self-deafen + speaking-flow mirror in MumbleManager"
```

---

### Task 6: Route→icon mapping

**Goal:** An endpoint-type→icon-key mapping so the Speaker control can show the right route glyph. (No design tokens — the screen uses `MaterialTheme.colorScheme` directly.)

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/AudioRoute.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/telecom/AudioRouteTest.kt` (extend)

**Acceptance Criteria:**
- [ ] `AudioRoute.RouteIcon` enum maps each `CallEndpoint.TYPE_*` to a stable key (BLUETOOTH/WIRED/EARPIECE/SPEAKER/UNKNOWN); `AudioRoute.icon(type)` returns it (pure, JVM-testable)

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioRouteTest"` → PASS

**Steps:**

- [ ] **Step 1: Add the route→icon mapping** to `AudioRoute.kt`. Append inside the `AudioRoute` object:

```kotlin
    /** Stable route-icon key for the Speaker control (mapped to a Material icon in the UI). */
    enum class RouteIcon { BLUETOOTH, WIRED, EARPIECE, SPEAKER, UNKNOWN }

    fun icon(type: Int): RouteIcon = when (type) {
        CallEndpoint.TYPE_BLUETOOTH -> RouteIcon.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> RouteIcon.WIRED
        CallEndpoint.TYPE_EARPIECE -> RouteIcon.EARPIECE
        CallEndpoint.TYPE_SPEAKER -> RouteIcon.SPEAKER
        else -> RouteIcon.UNKNOWN
    }
```

- [ ] **Step 2: Extend `AudioRouteTest.kt`** with:

```kotlin
    @Test fun iconMapsEachType() {
        assertEquals(AudioRoute.RouteIcon.BLUETOOTH, AudioRoute.icon(CallEndpoint.TYPE_BLUETOOTH))
        assertEquals(AudioRoute.RouteIcon.WIRED, AudioRoute.icon(CallEndpoint.TYPE_WIRED_HEADSET))
        assertEquals(AudioRoute.RouteIcon.EARPIECE, AudioRoute.icon(CallEndpoint.TYPE_EARPIECE))
        assertEquals(AudioRoute.RouteIcon.SPEAKER, AudioRoute.icon(CallEndpoint.TYPE_SPEAKER))
        assertEquals(AudioRoute.RouteIcon.UNKNOWN, AudioRoute.icon(999))
    }
```

- [ ] **Step 3: Verify + commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/telecom/AudioRoute.kt \
        app/src/test/java/me/danielstiner/dumble/telecom/AudioRouteTest.kt
git commit -m "feat(telecom): route->icon mapping for the Speaker control"
```

---

### Task 7: CallScreenState pure mapper

**Goal:** A pure, JVM-testable mapper that assembles `ServerModel` + speaking flows + local mute/deafen into ordered channels with ordered user view-models, honoring the reconciliation decisions.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/CallScreenState.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/ui/CallScreenStateTest.kt`

**Acceptance Criteria:**
- [ ] Users are grouped by channel; channels ordered by `position`; only channels with ≥1 user (plus your own) are shown; flat (no nesting)
- [ ] Your row is tagged `isYou` and floated to the top of your channel; server name resolves to the root channel (`parentId == null`) name, else the config-host fallback
- [ ] `isYou` uses `sessionId`; the self row prefers local `muted`/`selfTransmitting` over the echoed model; `serverMute = mute || suppress`; `selfMute` is the neutral badge
- [ ] `speaking(you) = selfTransmitting && !muted`; `speaking(other) = session in speakingSessions`
- [ ] No Compose imports (pure data + logic; the UI derives avatar color from `UserVm.session`)

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*CallScreenStateTest"` → PASS

**Steps:**

- [ ] **Step 1: Write `CallScreenStateTest.kt`**

```kotlin
package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.model.MumbleChannel
import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.model.ServerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreenStateTest {
    private val model = ServerModel(
        channels = mapOf(
            0 to MumbleChannel(id = 0, parentId = null, name = "Root", position = 0),
            1 to MumbleChannel(id = 1, parentId = 0, name = "Gaming", position = 1),
            2 to MumbleChannel(id = 2, parentId = 0, name = "Empty", position = 2),
        ),
        users = mapOf(
            10 to MumbleUser(session = 10, name = "me", channelId = 1),
            11 to MumbleUser(session = 11, name = "alice", channelId = 1),
            12 to MumbleUser(session = 12, name = "bob", channelId = 0, mute = true),
        ),
        sessionId = 10,
    )

    private fun build(muted: Boolean = false, deaf: Boolean = false,
                      speaking: Set<Int> = emptySet(), selfTx: Boolean = false) =
        buildCallScreenState(model, speaking, selfTx, muted, deaf, configHostFallback = "host")

    @Test fun serverNameFromRootChannel() {
        assertEquals("Root", build().serverName)
        assertEquals("host", buildCallScreenState(ServerModel(), emptySet(), false, false, false, "host").serverName)
    }

    @Test fun emptyChannelsHiddenExceptYours() {
        val names = build().channels.map { it.name }
        assertTrue(names.contains("Gaming")); assertTrue(names.contains("Root"))
        assertFalse("empty non-self channel hidden", names.contains("Empty"))
    }

    @Test fun selfFloatedToTopAndTaggedYou() {
        val gaming = build().channels.first { it.name == "Gaming" }
        assertEquals("me", gaming.users.first().name)
        assertTrue(gaming.users.first().isYou)
        assertTrue("your channel is active", gaming.isActive)
    }

    @Test fun serverMuteVsSelfMuteMapping() {
        val bob = build().channels.first { it.name == "Root" }.users.first { it.name == "bob" }
        assertTrue("mute -> server (red)", bob.serverMute); assertFalse(bob.selfMute)
    }

    @Test fun selfRowPrefersLocalMuteAndTransmit() {
        val meMuted = build(muted = true).channels.first { it.name == "Gaming" }.users.first()
        assertTrue(meMuted.selfMute); assertFalse(meMuted.speaking)
        val meTx = build(selfTx = true).channels.first { it.name == "Gaming" }.users.first()
        assertTrue("local transmit drives own speaking", meTx.speaking)
    }

    @Test fun otherSpeakingFromSet() {
        val alice = build(speaking = setOf(11)).channels.first { it.name == "Gaming" }.users.first { it.name == "alice" }
        assertTrue(alice.speaking)
    }
}
```

- [ ] **Step 2: Write `CallScreenState.kt`**

```kotlin
package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.model.ServerModel

data class UserVm(
    val session: Int,           // the UI derives a deterministic avatar color from this
    val initial: String,
    val name: String,
    val isYou: Boolean,
    val speaking: Boolean,
    val selfMute: Boolean,      // neutral badge
    val serverMute: Boolean,    // error badge (server mute or suppress)
)

data class ChannelVm(
    val id: Int,
    val name: String,
    val isActive: Boolean,      // contains you
    val users: List<UserVm>,
)

data class CallScreenState(
    val serverName: String,
    val channels: List<ChannelVm>,
)

/**
 * Pure assembly of the call screen. Groups users by channel (ordered by position), hides empty
 * channels except your own, floats your row to the top of your channel, and prefers LOCAL
 * mute/transmit for your own row (the server round-trips your UserState with a lag).
 */
fun buildCallScreenState(
    model: ServerModel,
    speakingSessions: Set<Int>,
    selfTransmitting: Boolean,
    localMuted: Boolean,
    localDeafened: Boolean,
    configHostFallback: String,
): CallScreenState {
    val myId = model.sessionId
    val rootName = model.channels.values.firstOrNull { it.parentId == null }?.name
    val serverName = rootName?.takeIf { it.isNotBlank() } ?: configHostFallback

    val usersByChannel = model.users.values.groupBy { it.channelId }
    val myChannelId = myId?.let { model.users[it]?.channelId }

    fun userVm(session: Int): UserVm {
        val u = model.users.getValue(session)
        val you = session == myId
        val selfMute = if (you) localMuted else u.selfMute
        val serverMute = u.mute || u.suppress
        val speaking = if (you) selfTransmitting && !localMuted else session in speakingSessions
        return UserVm(
            session = session,
            initial = u.name.trim().firstOrNull()?.uppercase() ?: "?",
            name = u.name,
            isYou = you,
            speaking = speaking && !(you && localDeafened),   // deafened self isn't "speaking"
            selfMute = selfMute,
            serverMute = serverMute,
        )
    }

    val channels = model.channels.values
        .sortedWith(compareBy({ it.position }, { it.id }))
        .mapNotNull { ch ->
            val members = usersByChannel[ch.id].orEmpty().map { it.session }
            val isActive = ch.id == myChannelId
            if (members.isEmpty() && !isActive) return@mapNotNull null
            // float self to the top of its own channel; others keep name order for stability
            val ordered = if (isActive && myId != null && myId in members) {
                listOf(myId) + members.filter { it != myId }.sortedBy { model.users.getValue(it).name.lowercase() }
            } else {
                members.sortedBy { model.users.getValue(it).name.lowercase() }
            }
            ChannelVm(id = ch.id, name = ch.name, isActive = isActive, users = ordered.map(::userVm))
        }

    return CallScreenState(serverName = serverName, channels = channels)
}
```

- [ ] **Step 3: Run the verify command — expect PASS. Commit:**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/CallScreenState.kt \
        app/src/test/java/me/danielstiner/dumble/ui/CallScreenStateTest.kt
git commit -m "feat(ui): pure CallScreenState mapper (channel/user view-models)"
```

---

### Task 8: ActiveCallScreen redesign (Material 3 Expressive)

**Goal:** Rewrite `ActiveCallScreen` with standard M3 components styled from `MaterialTheme.colorScheme` — a `Scaffold` with a two-line `TopAppBar` (server name + connection status, settings action), a `LazyColumn` channel→user tree (`ListItem` rows with avatar, speaking indicator, mute `Badge`, YOU `Badge`), and a bottom control bar (Mute / Deafen / Speaker / Leave; Mute→Hold-to-Talk in PTT mode). Simple, good-looking, theme-adaptive (light/dark). The mock is layout/content reference only.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`

**Acceptance Criteria:**
- [ ] Uses standard M3 components (`Scaffold`, `TopAppBar`, `LazyColumn`, `ListItem`, `Surface`, `Badge`, `Icon`) and `MaterialTheme.colorScheme`/`typography` throughout — no hardcoded palette; follows the app theme
- [ ] Header: server name (titleLarge) + connection status line; a settings action (`Icons.Filled.Tune`) → `onOpenSettings`
- [ ] Channel header (generic glyph + uppercase name, `primary` when active) then its user rows; user row: avatar (deterministic color from `session`) with a speaking ring (`primary` border) when speaking, a mute `Badge` (`error` for server-mute, neutral for self-mute, `Icons.Filled.MicOff`), name + `YOU` badge, and a speaking `Icons.Filled.GraphicEq` trailing icon when speaking
- [ ] Control bar: Mute / Deafen / Speaker / Leave — toggle controls use `secondaryContainer` idle / `primary` active (`errorContainer` when the active state is "muted"/"deafened"); Speaker shows the route icon + `routeLabel`; Leave uses `error`; **Mute becomes Hold-to-Talk (press-hold) in PTT mode**
- [ ] `@Preview` (wrapped in `DumbleTheme`) renders; `assembleDebug` builds

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Rewrite `ActiveCallScreen.kt`.** Replace the entire file. This is complete M3 code; the mock (`docs/design/Mumble-Call.dc.html`) is reference for *content/layout*, not pixels. Exact M3 API names (e.g. `IconButtonDefaults`, experimental opt-ins) shift across BOMs — `assembleDebug` is the oracle; adjust names as the compiler directs.

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.telecom.AudioRoute
import me.danielstiner.dumble.ui.theme.DumbleTheme

// Deterministic avatar palette — indexed by session; reads acceptably on light and dark.
private val avatarPalette = listOf(
    Color(0xFF5A6BF0), Color(0xFF4CAF50), Color(0xFFC64AA6), Color(0xFFC6971F),
    Color(0xFF17A79A), Color(0xFF7E8AA0), Color(0xFF7E57C2), Color(0xFFC0603C),
)
private fun avatarColor(session: Int): Color = avatarPalette[Math.floorMod(session, avatarPalette.size)]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    state: CallScreenState,
    connectedText: String,                 // "Connected · 12:34" or "Connecting…"
    muted: Boolean,
    deafened: Boolean,
    speaker: Boolean,
    routeIcon: AudioRoute.RouteIcon,
    routeLabel: String,                    // "Speaker" / "Bluetooth" / device name / …
    transmitMode: TransmitMode,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onHangUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Box(Modifier.padding(start = 12.dp).size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.HeadsetMic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp))
                    }
                },
                title = {
                    Column {
                        Text(state.serverName, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(connectedText, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Tune, "Settings") } },
            )
        },
        bottomBar = {
            ControlBar(muted, deafened, speaker, routeIcon, routeLabel, transmitMode,
                onToggleMute, onToggleDeafen, onToggleSpeaker, onPttPress, onPttRelease, onHangUp)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            state.channels.forEach { ch ->
                item(key = "ch-${ch.id}") { ChannelHeader(ch) }
                items(ch.users, key = { "u-${it.session}" }) { u -> UserRow(u) }
            }
        }
    }
}

@Composable
private fun ChannelHeader(ch: ChannelVm) {
    val color = if (ch.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Tag, null, modifier = Modifier.size(18.dp), tint = color)
        Text(ch.name.uppercase(), style = MaterialTheme.typography.labelLarge, color = color,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun UserRow(u: UserVm) {
    ListItem(
        leadingContent = { Avatar(u) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (u.isYou) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary) { Text("YOU") }
                }
            }
        },
        trailingContent = if (u.speaking) {
            { Icon(Icons.Filled.GraphicEq, "speaking", tint = MaterialTheme.colorScheme.primary) }
        } else null,
    )
}

@Composable
private fun Avatar(u: UserVm) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        val ring = if (u.speaking) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
        Box(Modifier.size(40.dp).then(ring).clip(CircleShape).background(avatarColor(u.session)),
            contentAlignment = Alignment.Center) {
            Text(u.initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        if (u.selfMute || u.serverMute) {
            Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape)
                .background(if (u.serverMute) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MicOff, "muted", modifier = Modifier.size(11.dp),
                    tint = if (u.serverMute) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ControlBar(
    muted: Boolean, deafened: Boolean, speaker: Boolean,
    routeIcon: AudioRoute.RouteIcon, routeLabel: String, transmitMode: TransmitMode,
    onToggleMute: () -> Unit, onToggleDeafen: () -> Unit, onToggleSpeaker: () -> Unit,
    onPttPress: () -> Unit, onPttRelease: () -> Unit, onHangUp: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            if (transmitMode == TransmitMode.PUSH_TO_TALK) {
                // Deafen implies self-mute; disable Talk while deafened (same hot-mic guard as Mute).
                HoldToTalkControl(enabled = !deafened, onPress = onPttPress, onRelease = onPttRelease)
            } else {
                // Deafen forces mute; disable Mute while deafened so a stray unmute can't reopen a hot mic.
                ToggleControl(checked = muted, onClick = onToggleMute,
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (muted) "Unmute" else "Mute", danger = true, enabled = !deafened)
            }
            ToggleControl(checked = deafened, onClick = onToggleDeafen,
                icon = if (deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headphones,
                label = if (deafened) "Undeafen" else "Deafen", danger = true)
            ToggleControl(checked = speaker, onClick = onToggleSpeaker,
                icon = routeIconVector(routeIcon), label = routeLabel, danger = false)
            LeaveControl(onHangUp)
        }
    }
}

/** A round-rect control tile + caption. `danger` = its "on" state means muted/deafened (error tint). */
@Composable
private fun ToggleControl(
    checked: Boolean, onClick: () -> Unit, icon: ImageVector, label: String, danger: Boolean, enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val container = when { danger && checked -> cs.errorContainer; checked -> cs.primary; else -> cs.secondaryContainer }
    val content = when { danger && checked -> cs.onErrorContainer; checked -> cs.onPrimary; else -> cs.onSecondaryContainer }
    ControlColumn(label) {
        Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
            color = container, contentColor = content, modifier = Modifier.size(60.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, modifier = Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun LeaveControl(onHangUp: () -> Unit) {
    ControlColumn("Leave") {
        Surface(onClick = onHangUp, shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size(width = 72.dp, height = 60.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.CallEnd, "Leave", modifier = Modifier.size(28.dp)) }
        }
    }
}

@Composable
private fun HoldToTalkControl(enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    DisposableEffect(Unit) { onDispose { currentRelease() } }   // release if it leaves composition while held
    val cs = MaterialTheme.colorScheme
    val container = when { !enabled -> cs.onSurface.copy(alpha = 0.12f); pressed -> cs.primary; else -> cs.secondaryContainer }
    val content = when { !enabled -> cs.onSurface.copy(alpha = 0.38f); pressed -> cs.onPrimary; else -> cs.onSecondaryContainer }
    // Raw pointerInput drives press/hold, so the button semantics (role + label, disabled state) must
    // be set explicitly — a Surface with no onClick isn't exposed as an actionable button to TalkBack.
    val gesture = if (enabled) Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            pressed = true; currentPress(); tryAwaitRelease(); pressed = false; currentRelease()
        })
    } else Modifier
    ControlColumn(if (pressed) "Release" else "Talk") {
        Surface(shape = RoundedCornerShape(20.dp), color = container, contentColor = content,
            modifier = Modifier.size(60.dp).then(gesture).semantics {
                role = Role.Button; contentDescription = "Push to talk"; if (!enabled) disabled()
            }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, null, modifier = Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun ControlColumn(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 76.dp))
    }
}

private fun routeIconVector(icon: AudioRoute.RouteIcon): ImageVector = when (icon) {
    AudioRoute.RouteIcon.BLUETOOTH -> Icons.Filled.BluetoothAudio
    AudioRoute.RouteIcon.WIRED -> Icons.Filled.Headset
    AudioRoute.RouteIcon.EARPIECE -> Icons.Filled.PhoneInTalk
    AudioRoute.RouteIcon.SPEAKER, AudioRoute.RouteIcon.UNKNOWN -> Icons.Filled.VolumeUp
}

@Preview
@Composable
private fun ActiveCallScreenPreview() {
    DumbleTheme {
        val state = CallScreenState(
            serverName = "Acoustic HQ",
            channels = listOf(
                ChannelVm(1, "General", isActive = true, users = listOf(
                    UserVm(10, "C", "citelao", isYou = true, speaking = true, selfMute = false, serverMute = false),
                    UserVm(11, "A", "AdamTReineke", isYou = false, speaking = false, selfMute = false, serverMute = false),
                )),
                ChannelVm(2, "Gaming", isActive = false, users = listOf(
                    UserVm(12, "H", "hayden", isYou = false, speaking = true, selfMute = false, serverMute = false),
                    UserVm(13, "G", "gun", isYou = false, speaking = false, selfMute = true, serverMute = false),
                )),
            ),
        )
        ActiveCallScreen(state, "Connected · 24:32", muted = false, deafened = false, speaker = false,
            routeIcon = AudioRoute.RouteIcon.BLUETOOTH, routeLabel = "Bluetooth",
            transmitMode = TransmitMode.VOICE_ACTIVATED,
            onToggleMute = {}, onToggleDeafen = {}, onToggleSpeaker = {},
            onPttPress = {}, onPttRelease = {}, onHangUp = {}, onOpenSettings = {})
    }
}
```

- [ ] **Step 2: Resolve M3/icon names against the compiler.** `Surface(onClick=…)`, `ListItem`, `Badge`, `TopAppBar` are M3; some need `@OptIn(ExperimentalMaterial3Api::class)` (already on `ActiveCallScreen`). For any extended icon that doesn't resolve, pick the nearest available (e.g. `Tag`→`Forum`/`Groups`; `PhoneInTalk`→`Hearing`/`PhoneInTalk`; `GraphicEq` should exist). `assembleDebug` is the oracle.

- [ ] **Step 3: Verify.** Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. Fix any unresolved name until green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt
git commit -m "feat(ui): redesign the active call screen with M3 (channel tree + control bar)"
```

---

### Task 9: DumbleApp wiring

**Goal:** Collect the model + speaking flows + deafen/route, assemble `CallScreenState`, track `connectedSince`, format the timer, and pass everything to `ActiveCallScreen`; wire deafen/route callbacks.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] Collects `MumbleManager.model.state`, `speakingSessions`, `selfTransmitting`, `deafened`, `activeEndpoint`; assembles `CallScreenState` via `buildCallScreenState`
- [ ] Captures `connectedSince` on the transition into `Synchronized` and clears it otherwise; shows "Connected · mm:ss" (or "Connecting…") with a live 1 s tick
- [ ] Passes route icon/label from `activeEndpoint` (fallback "Speaker" when null); wires `onToggleDeafen`/`onToggleSpeaker`/PTT/mute/hangup/settings
- [ ] `assembleDebug` builds; existing behavior (settings/diagnostics nav) unchanged

**Verify:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Collect the new flows.** In `DumbleApp`, alongside the existing `collectAsStateWithLifecycle()` calls, add:

```kotlin
    val serverModel by MumbleManager.model.state.collectAsStateWithLifecycle()
    val speakingSessions by MumbleManager.speakingSessions.collectAsStateWithLifecycle()
    val selfTransmitting by MumbleManager.selfTransmitting.collectAsStateWithLifecycle()
    val deafened by MumbleManager.deafened.collectAsStateWithLifecycle()
```

(`activeEndpoint` and the derived `activeRouteLabel` are already collected from Task on the route indicator.)

- [ ] **Step 2: Track connected-since + a 1 s clock.** Add, after the `inCall` computation:

```kotlin
    var connectedSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state) {
        connectedSince = if (state is ConnectionState.Synchronized) {
            connectedSince ?: System.currentTimeMillis()
        } else null
    }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (connectedSince != null) { nowMillis = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val connectedText = connectedSince?.let { "Connected · " + formatElapsed(nowMillis - it) } ?: "Connecting…"
```

- [ ] **Step 3: Add the `formatElapsed` helper** at the bottom of the file (top-level):

```kotlin
private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
```

- [ ] **Step 4: Replace the `ActiveCallScreen(...)` call** (in the `inCall && !showSettings` branch) with:

```kotlin
            val callState = buildCallScreenState(
                serverModel, speakingSessions, selfTransmitting, muted, deafened,
                configHostFallback = form.host,
            )
            val routeIcon = activeEndpoint?.let { AudioRoute.icon(it.endpointType) } ?: AudioRoute.RouteIcon.SPEAKER
            ActiveCallScreen(
                state = callState,
                connectedText = connectedText,
                muted = muted, deafened = deafened, speaker = speaker,
                routeIcon = routeIcon, routeLabel = activeRouteLabel ?: "Speaker",
                transmitMode = transmitMode,
                onToggleMute = { MumbleManager.setMuted(!muted) },
                onToggleDeafen = { MumbleManager.setDeafened(!deafened) },
                onToggleSpeaker = { CallManager.setSpeaker(!speaker) },
                onPttPress = { MumbleManager.setPttHeld(true) },
                onPttRelease = { MumbleManager.setPttHeld(false) },
                onHangUp = onHangUp,
                onOpenSettings = { showSettings = true },
            )
```

- [ ] **Step 5: Add imports** for `buildCallScreenState`/`ActiveCallScreen` (same package — no import needed), `AudioRoute` (already imported from the route-indicator task), and `LaunchedEffect`/`mutableStateOf`/`remember` (already present). Run the verify command; fix until BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): wire the redesigned call screen (model + speaking flows + deafen/route)"
```

---

## After all tasks

Run the full suite + build once more:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:assembleDebug` → all green.

**Do NOT merge.** Report back so Dan can batch the on-device test (channel tree renders; speaking ring/eq animate on the real talker and on yourself while transmitting; self-mute vs server-mute badges correct; Deafen silences playout + shows you deafened to peers; Speaker reflects the route; PTT Hold-to-Talk gates transmission; cross-check a second client sees your speaking indicator).

## On-device acceptance (spec)

Join a multi-user channel and confirm membership + speaking rings; verify Deafen, Speaker route, and PTT Hold-to-Talk; cross-check the speaking indicator on a second client (validates the verified terminator path end-to-end).
