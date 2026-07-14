# Voice-Activity Detection (transmit) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate the uplink so Dumble transmits only during speech, producing Mumble talkspurts terminated by `is_terminator`, instead of the current always-on-when-unmuted transmit.

**Architecture:** A small swappable seam in the `voice` package — `VadDetector` (energy/level → 0..1), `NoiseSuppressor` (in-place denoise), and a `TransmitGate` state machine (hysteresis + 10 ms-tick hangover) — driven from `AudioVoiceEngine.nextOutgoingFrame()`. Phase 1 ships a pure-Kotlin energy detector with an adaptive noise floor and `NoiseSuppressor.None`. Phase 2 adds an RNNoise JNI suppressor that denoises each 480-sample half in place, feeding both the detector and the Opus encoder. Send packetization is expressed via a single `FRAMES_PER_PACKET` parameter (default 2 = 20 ms); all gate timing is counted in 10 ms ticks so it is packet-size-invariant.

**Tech Stack:** Kotlin, Android `AudioRecord` (48 kHz mono PCM16), libopus via existing JNI (`libdumbleopus.so`, CMake FetchContent, 16 KB page alignment), xiph RNNoise (Phase 2), JUnit4 pure-JVM tests with `FakeOpusCodec`.

**User decisions (already made):**
- "voice activity detection" for the transmit path (#40).
- "Two-phase in one spec": Phase 1 energy VAD, Phase 2 RNNoise.
- Phase 2 = "RNNoise denoise + energy VAD (Mumble-faithful)" — RNNoise denoises; detection stays energy-based on the denoised signal; RNNoise's own VAD output is unused.
- "20 ms packets + 10 ms sub-processing" — keep 20 ms read + single 20 ms Opus encode; VAD/RNNoise run on two 10 ms halves.
- "VAD replaces continuous, no toggle." Mute still hard-suppresses.
- Adaptive noise floor (fixed-threshold fallback), RNNoise denoises the uplink too.
- "be flexible, we might want to add a slider in the future to allow 10/20/60 etc ms packet sizes" → packet size is a single `FRAMES_PER_PACKET` parameter; no slider/settings built now (YAGNI).
- AGC is not in scope (a future VAD-gated listener-loudness feature).

Design spec: `docs/superpowers/specs/2026-07-14-voice-activity-detection-design.md`.

---

## File Structure

**New files (Phase 1):**
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/VadDetector.kt` — `VadDetector` interface + `EnergyVadDetector` (adaptive noise floor).
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/NoiseSuppressor.kt` — `NoiseSuppressor` interface + `None`.
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitGate.kt` — hysteresis + hangover state machine.
- `app/src/test/java/me/danielstiner/dumble/mumble/voice/VadDetectorTest.kt`
- `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitGateTest.kt`

**New files (Phase 2):**
- `app/src/main/cpp/rnnoise_jni.c` — JNI wrapper for RNNoise.
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/NativeRnnoise.kt` — JNI bindings.
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/RnnoiseSuppressor.kt` — `NoiseSuppressor` backed by RNNoise.

**Modified files:**
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioConstants.kt` — add `FRAMES_PER_PACKET`, `CAPTURE_SAMPLES`.
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` — VAD in `nextOutgoingFrame`; `suppressor` ctor param; `suppressor.close()` in `stop()`.
- `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt` — feed speech/silence via a scripted fake; add VAD gating tests.
- `app/src/main/cpp/CMakeLists.txt` — vendor + build RNNoise (Phase 2).
- `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt:119` — pass `RnnoiseSuppressor()` (Phase 2).

---

## Task 1: VAD detector + noise-suppressor seam

**Goal:** Add the `VadDetector` and `NoiseSuppressor` interfaces plus `EnergyVadDetector` (adaptive noise floor) and `NoiseSuppressor.None`, unit-tested in pure JVM.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VadDetector.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/NoiseSuppressor.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/VadDetectorTest.kt`

**Acceptance Criteria:**
- [ ] `EnergyVadDetector.level` returns ~1.0 for a burst well above the adapted floor and ~0.0 for steady background once the floor has adapted.
- [ ] The floor does not inflate during a sustained high-amplitude run (a burst after sustained speech still reads high, i.e. the floor stayed low).
- [ ] `NoiseSuppressor.None.process` leaves the buffer byte-identical.
- [ ] All new tests pass.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.VadDetectorTest"` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Write `NoiseSuppressor.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/** In-place noise suppression on a sub-frame of PCM16. One instance per capture stream (stateful). */
interface NoiseSuppressor {
    /** Denoise n samples starting at pcm[off] in place. */
    fun process(pcm: ShortArray, off: Int, n: Int)
    fun close()

    /** No-op suppressor (Phase 1 default). */
    object None : NoiseSuppressor {
        override fun process(pcm: ShortArray, off: Int, n: Int) {}
        override fun close() {}
    }
}
```

- [ ] **Step 2: Write `VadDetector.kt` with `EnergyVadDetector`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** Per-10ms-sub-frame speech level in 0f..1f. Stateful (adaptive). Single-thread (send thread). */
interface VadDetector {
    fun level(pcm: ShortArray, off: Int, n: Int): Float
}

/**
 * Energy VAD with an adaptive noise floor. The returned level is how far the sub-frame's
 * RMS sits above a slowly-tracked background floor, mapped over [marginDb]. The floor updates
 * only when the sub-frame is NOT speech-like (its level is within the margin), so sustained
 * speech never inflates it — this reproduces Mumble's "don't adapt during speech" without any
 * gate-state coupling. This is the fixed-threshold fallback's adaptive sibling; see the spec.
 */
class EnergyVadDetector(
    private val marginDb: Float = 15f,   // dB above floor that maps to level 1.0
    private val riseCoef: Float = 0.02f, // slow: floor creeps up toward louder background
    private val fallCoef: Float = 0.3f,  // fast: floor drops toward quieter background
    private val minDb: Float = -96f,
    initialFloorDb: Float = -60f,
) : VadDetector {
    private var floorDb = initialFloorDb

    override fun level(pcm: ShortArray, off: Int, n: Int): Float {
        val db = rmsDb(pcm, off, n)
        val above = db - floorDb
        if (above < marginDb) {                                // quiet / non-speech → track floor
            val coef = if (db < floorDb) fallCoef else riseCoef
            floorDb += coef * (db - floorDb)
        }
        return (above / marginDb).coerceIn(0f, 1f)
    }

    private fun rmsDb(pcm: ShortArray, off: Int, n: Int): Float {
        var sumSq = 0.0
        for (i in off until off + n) { val s = pcm[i].toDouble(); sumSq += s * s }
        val rms = sqrt(sumSq / n)
        if (rms < 1.0) return minDb                            // ~digital silence
        return (20.0 * log10(rms / 32768.0)).toFloat().coerceAtLeast(minDb)
    }
}
```

- [ ] **Step 3: Write `VadDetectorTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadDetectorTest {
    private fun frame(amp: Int, n: Int = FRAME_SAMPLES_10MS): ShortArray =
        ShortArray(n) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }  // ±amp square wave

    @Test fun burstAboveAdaptedFloorReadsHigh() {
        val d = EnergyVadDetector()
        val quiet = frame(100)
        repeat(200) { d.level(quiet, 0, FRAME_SAMPLES_10MS) }        // let floor adapt to ~ -50 dB
        assertEquals(0f, d.level(quiet, 0, FRAME_SAMPLES_10MS), 0.05f) // background ~0
        assertTrue("loud burst reads high", d.level(frame(8000), 0, FRAME_SAMPLES_10MS) > 0.9f)
    }

    @Test fun floorDoesNotInflateDuringSustainedSpeech() {
        val d = EnergyVadDetector()
        val loud = frame(8000)
        repeat(500) { assertTrue(d.level(loud, 0, FRAME_SAMPLES_10MS) > 0.9f) } // stays high the whole time
    }

    @Test fun silenceReadsZero() {
        val d = EnergyVadDetector()
        val silent = ShortArray(FRAME_SAMPLES_10MS)
        repeat(50) { d.level(silent, 0, FRAME_SAMPLES_10MS) }
        assertEquals(0f, d.level(silent, 0, FRAME_SAMPLES_10MS), 0.001f)
    }

    @Test fun noneSuppressorIsIdentity() {
        val pcm = ShortArray(FRAME_SAMPLES_10MS) { (it % 7 - 3).toShort() }
        val copy = pcm.copyOf()
        NoiseSuppressor.None.process(pcm, 0, FRAME_SAMPLES_10MS)
        assertTrue(pcm.contentEquals(copy))
    }
}
```

- [ ] **Step 4: Run tests**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.VadDetectorTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/VadDetector.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/NoiseSuppressor.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/VadDetectorTest.kt
git commit -m "feat(voice): energy VAD detector + noise-suppressor seam (#40 phase 1)"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/VadDetector.kt", "app/src/main/java/me/danielstiner/dumble/mumble/voice/NoiseSuppressor.kt", "app/src/test/java/me/danielstiner/dumble/mumble/voice/VadDetectorTest.kt"], "verifyCommand": "export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\" && ./gradlew testDebugUnitTest --tests \"me.danielstiner.dumble.mumble.voice.VadDetectorTest\"", "acceptanceCriteria": ["EnergyVadDetector.level ~1.0 for a burst above the adapted floor, ~0.0 for steady background", "floor does not inflate during sustained high amplitude", "NoiseSuppressor.None is identity", "new tests pass"], "modelTier": "mechanical"}
```

---

## Task 2: TransmitGate state machine

**Goal:** Add `TransmitGate` — two-threshold hysteresis plus a 10 ms-tick hangover — turning per-sub-frame levels into one per-capture `Decision(send, terminator)`, unit-tested.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitGate.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitGateTest.kt`

**Acceptance Criteria:**
- [ ] Opens on the first sub-frame above `openLevel`; a single voiced sub-frame in a capture yields `send=true`.
- [ ] Stays open through sub-hangover silence; closes only after `maxHoldTicks` consecutive silent sub-frames.
- [ ] Emits exactly one `terminator=true` on the close transition, then `send=false, terminator=false` while idle.
- [ ] Re-opens on a later burst; `reset()` returns it to closed/idle.
- [ ] All new tests pass.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.TransmitGateTest"` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Write `TransmitGate.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Turns per-10ms-sub-frame speech levels into one per-capture transmit decision.
 *
 * Two-threshold hysteresis (open at [openLevel], stay open above [closeLevel]) plus a
 * time-based hangover counted in 10 ms sub-frame ticks ([maxHoldTicks]) — so the hold
 * DURATION is invariant to packet size (mirrors Mumble's iHoldFrames, which counts 10 ms
 * frames). A single voiced sub-frame in a capture keeps the whole capture transmitting; the
 * silent half rides along as lead-in/tail.
 *
 * send/terminator are mutually exclusive: while transmitting, send=true; on the first capture
 * after transmission fully stops, terminator=true (emit one empty terminator); then idle.
 */
class TransmitGate(
    private val openLevel: Float = 0.60f,
    private val closeLevel: Float = 0.35f,
    private val maxHoldTicks: Int = 20,   // 20 x 10 ms = 200 ms hangover
) {
    data class Decision(val send: Boolean, val terminator: Boolean)

    private var transmitting = false
    private var holdTicks = 0

    /** @param levels one entry per 10 ms sub-frame of the capture (size == FRAMES_PER_PACKET). */
    fun update(levels: FloatArray): Decision {
        val wasTransmitting = transmitting
        for (lvl in levels) {
            val raw = if (transmitting) lvl > closeLevel else lvl > openLevel
            if (raw) {
                holdTicks = 0
                transmitting = true
            } else if (transmitting) {
                if (++holdTicks >= maxHoldTicks) transmitting = false
            }
        }
        return when {
            transmitting -> Decision(send = true, terminator = false)
            wasTransmitting -> Decision(send = false, terminator = true)  // just closed
            else -> Decision(send = false, terminator = false)
        }
    }

    fun reset() { transmitting = false; holdTicks = 0 }
}
```

- [ ] **Step 2: Write `TransmitGateTest.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmitGateTest {
    private val speech = floatArrayOf(1f, 1f)
    private val silence = floatArrayOf(0f, 0f)

    @Test fun opensOnSpeechAndSends() {
        val g = TransmitGate()
        val d = g.update(speech)
        assertTrue(d.send); assertFalse(d.terminator)
    }

    @Test fun onsetInSecondHalfSendsWholeCapture() {
        val g = TransmitGate()
        val d = g.update(floatArrayOf(0f, 1f))   // silent first 10 ms, speech second 10 ms
        assertTrue("capture with one voiced sub-frame is sent", d.send)
    }

    @Test fun holdsThroughShortSilenceThenTerminatesOnce() {
        val g = TransmitGate(maxHoldTicks = 20)
        g.update(speech)                                  // open
        // 9 silent captures = 18 ticks < 20 → still transmitting (hangover)
        repeat(9) { assertTrue(g.update(silence).send) }
        // 10th silent capture: ticks reach 20 → closes this capture → terminator
        val closing = g.update(silence)
        assertFalse(closing.send); assertTrue(closing.terminator)
        // subsequent silence → idle, no repeat terminator
        val idle = g.update(silence)
        assertFalse(idle.send); assertFalse(idle.terminator)
    }

    @Test fun singleQuietSubframeDoesNotFlutterClosed() {
        val g = TransmitGate(maxHoldTicks = 20)
        g.update(speech)
        assertTrue("one quiet sub-frame among speech keeps sending",
            g.update(floatArrayOf(0f, 1f)).send)
    }

    @Test fun reopensAfterTerminator() {
        val g = TransmitGate(maxHoldTicks = 2)
        g.update(speech)
        g.update(silence)                                 // ticks=2 → closes → terminator
        assertTrue("re-opens on next speech", g.update(speech).send)
    }

    @Test fun resetReturnsToClosed() {
        val g = TransmitGate()
        g.update(speech)
        g.reset()
        // after reset, a silent capture must not emit a terminator (was not transmitting)
        val d = g.update(silence)
        assertFalse(d.send); assertFalse(d.terminator)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.TransmitGateTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitGate.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitGateTest.kt
git commit -m "feat(voice): TransmitGate hysteresis + 10ms-tick hangover (#40 phase 1)"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitGate.kt", "app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitGateTest.kt"], "verifyCommand": "export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\" && ./gradlew testDebugUnitTest --tests \"me.danielstiner.dumble.mumble.voice.TransmitGateTest\"", "acceptanceCriteria": ["opens on first sub-frame above openLevel; single voiced sub-frame sends the capture", "holds through sub-hangover silence, closes after maxHoldTicks", "emits exactly one terminator on close then idle", "reopens on later burst; reset returns to closed", "new tests pass"], "modelTier": "mechanical"}
```

---

## Task 3: Integrate VAD into AudioVoiceEngine (replace continuous transmit)

**Goal:** Wire the detector/suppressor/gate into `nextOutgoingFrame` so silence stops transmission and produces one terminator, parameterize the send path with `FRAMES_PER_PACKET`, and keep mute precedence — with JVM tests driving speech/silence through the real Phase-1 stack.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioConstants.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt`

**Acceptance Criteria:**
- [ ] A silent mic produces `null` (no frames) — continuous transmit is gone.
- [ ] Sustained speech produces frames whose `frameNumber` advances by `FRAMES_PER_PACKET` (2) and freezes across silence.
- [ ] A speech→silence transition emits exactly one empty `isTerminator` frame carrying the frozen `frameNumber`, then `null`.
- [ ] Mute still emits one terminator then `null`, and unmute starts with a closed gate.
- [ ] Full unit-test suite is green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Add send-path constants to `AudioConstants.kt`**

Append after `MAX_FRAME_SAMPLES`:

```kotlin
/** 10 ms sub-frames per outgoing packet. Single knob for a future 10/20/40/60 ms packet-size
 *  slider (see spec); all gate timing is in 10 ms ticks so it stays packet-size-invariant. */
const val FRAMES_PER_PACKET = 2
/** Samples captured & encoded per outgoing packet (FRAMES_PER_PACKET x 10 ms). 960 at N=2. */
const val CAPTURE_SAMPLES = FRAMES_PER_PACKET * FRAME_SAMPLES_10MS
```

- [ ] **Step 2: Add the failing test first — silence gates, speech sends, terminator on transition**

Replace the body of `AudioVoiceEngineFrameNumberTest.kt` with (updates the two existing tests to feed speech, and adds VAD tests + a scripted fake):

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineFrameNumberTest {

    /** Fills each read with a scripted per-capture amplitude (±amp square wave). */
    private class ScriptedAudioIn(private val amps: List<Int>) : AudioIn {
        var reads = 0
        override fun read(out: ShortArray, n: Int): Int {
            val amp = amps.getOrElse(reads) { amps.last() }; reads++
            for (i in 0 until n) out[i] = if (i % 2 == 0) amp.toShort() else (-amp).toShort()
            return n
        }
        override fun close() {}
    }

    private fun engine(input: AudioIn): AudioVoiceEngine =
        AudioVoiceEngine(FakeOpusCodec(), { input }, { FakeAudioOut() }).also { it.start() }

    @Test fun frameNumberAdvancesByTwoWhileSpeaking() {
        val e = engine(ScriptedAudioIn(List(4) { 8000 }))   // loud → gate open
        val f1 = e.nextOutgoingFrame(0)!!
        val f2 = e.nextOutgoingFrame(0)!!
        assertEquals(0L, f1.frameNumber)
        assertEquals(2L, f2.frameNumber)
        assertFalse(f1.isTerminator)
        e.stop()
    }

    @Test fun silentMicProducesNoFrames() {
        val e = engine(ScriptedAudioIn(List(5) { 0 }))      // silence → gate stays closed
        repeat(5) { assertNull("silence must not transmit", e.nextOutgoingFrame(0)) }
        e.stop()
    }

    @Test fun speechThenSilenceEmitsOneTerminatorAndFreezesFrameNumber() {
        // 3 loud captures, then silence long enough to expire the 200 ms (20-tick) hangover.
        val amps = List(3) { 8000 } + List(20) { 0 }
        val e = engine(ScriptedAudioIn(amps))

        var lastSent = -1L
        var terminators = 0
        var terminatorFrameNumber = -1L
        var sawNullAfterTerminator = false
        repeat(amps.size) {
            val f = e.nextOutgoingFrame(0)
            when {
                f == null -> if (terminators > 0) sawNullAfterTerminator = true
                f.isTerminator -> { terminators++; terminatorFrameNumber = f.frameNumber }
                else -> lastSent = f.frameNumber
            }
        }
        assertTrue("at least the 3 speech frames were sent", lastSent >= 4L)
        assertEquals("exactly one terminator", 1, terminators)
        assertEquals("terminator freezes frameNumber at the next value after last sent",
            lastSent + 2L, terminatorFrameNumber)
        assertTrue("idle nulls follow the terminator", sawNullAfterTerminator)
        e.stop()
    }

    @Test fun mutedReturnsNullButStillReads() {
        val fakeIn = ScriptedAudioIn(List(3) { 8000 })
        val e = AudioVoiceEngine(FakeOpusCodec(), { fakeIn }, { FakeAudioOut() })
        e.start(); e.setMuted(true)
        val first = e.nextOutgoingFrame(0)
        assertTrue("mute emits one terminator", first != null && first.isTerminator)
        assertNull("subsequent muted → null", e.nextOutgoingFrame(0))
        assertTrue("mic still drained while muted", fakeIn.reads >= 1)
        e.stop()
    }
}

class FakeAudioOut : AudioOut {
    override fun write(pcm: ShortArray, n: Int) {}
    override fun close() {}
}
```

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.AudioVoiceEngineFrameNumberTest"`
Expected: FAIL to compile / assertions fail (engine still transmits continuously; `CAPTURE_SAMPLES`/gating not wired).

> Note: the old `FakeAudioIn` class is removed; `ScriptedAudioIn` replaces it. If any other test references `FakeAudioIn`, update it to `ScriptedAudioIn(listOf(8000))`. `FakeAudioOut` is retained here.

- [ ] **Step 3: Wire the seam into `AudioVoiceEngine`**

In `AudioVoiceEngine.kt`, add the `suppressor` constructor parameter (4th, defaulted so existing positional calls compile):

```kotlin
class AudioVoiceEngine(
    private val codec: OpusCodec,
    private val recorderFactory: () -> AudioIn = { AndroidAudioIn() },
    private val trackFactory: () -> AudioOut = { AndroidAudioOut() },
    private val suppressor: NoiseSuppressor = NoiseSuppressor.None,
) : VoiceEngine {
```

Add fields near `capturePcm` (replace the `capturePcm` line):

```kotlin
    private val vad: VadDetector = EnergyVadDetector()
    private val gate = TransmitGate()
    private val capturePcm = ShortArray(CAPTURE_SAMPLES)
    private val subLevels = FloatArray(FRAMES_PER_PACKET)
```

Replace `nextOutgoingFrame` (lines 60–81) with:

```kotlin
    /** Send thread. Drains the mic; gates transmission on voice activity. */
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val rec = recorder ?: return null
        rec.read(capturePcm, CAPTURE_SAMPLES)             // capture clock — runs even while muted
        if (muted) {
            gate.reset()                                 // so unmute starts closed
            if (!wasMuted) { wasMuted = true; return VoiceFrame(ByteArray(0), 0, frameNumber, isTerminator = true) }
            return null
        }
        wasMuted = false

        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)   // None = no-op (Phase 1)
            subLevels[i] = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
        }
        val d = gate.update(subLevels)

        if (d.terminator) {
            return VoiceFrame(ByteArray(0), 0, frameNumber, isTerminator = true)  // freeze frameNumber
        }
        if (!d.send) return null

        val opus = encoder.encode(capturePcm, CAPTURE_SAMPLES)
        uplinkBytes += opus.size
        if (++uplinkFrames >= 250) {
            val avgBytes = uplinkBytes.toDouble() / uplinkFrames
            android.util.Log.d("AudioVoiceEngine", "uplink avg=%.1f B/frame ~%.1f kbps".format(avgBytes, avgBytes * 0.4))
            uplinkBytes = 0; uplinkFrames = 0
        }
        val fn = frameNumber
        frameNumber += FRAMES_PER_PACKET
        sent++
        _stats.update { it.copy(sent = sent) }
        return VoiceFrame(opus, opus.size, fn)
    }
```

In `stop()`, after `encoder.close()`, add:

```kotlin
        suppressor.close()
```

- [ ] **Step 4: Run the focused test, then the whole suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.AudioVoiceEngineFrameNumberTest"`
Expected: PASS.

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` — whole suite green (receive-side tests unaffected).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioConstants.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt
git commit -m "feat(voice): gate uplink on voice activity, replace continuous transmit (#40 phase 1)"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioConstants.kt", "app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt", "app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt"], "verifyCommand": "export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\" && ./gradlew testDebugUnitTest", "acceptanceCriteria": ["silent mic produces null (no continuous transmit)", "speech advances frameNumber by FRAMES_PER_PACKET and freezes across silence", "speech->silence emits exactly one empty terminator with frozen frameNumber then null", "mute still emits one terminator then null; unmute starts closed", "full unit suite green"], "modelTier": "standard"}
```

---

## Task 4: Phase 1 on-device verification gate

**Goal:** On a real device, confirm the energy VAD transmits during speech, stops during silence (uplink kbps → ~0), clips no word onsets, and that a second Mumble client hears clean talkspurts.

> **USER-ORDERED GATE — NON-SKIPPABLE.** This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

**Files:** none (manual device verification).

**Acceptance Criteria:**
- [ ] While speaking, the `AudioVoiceEngine` "uplink ... kbps" logcat line shows non-zero throughput and the `sent` stat increases.
- [ ] During a few seconds of silence, no packets are sent (uplink log stops / kbps → ~0), and speaking again resumes within ~200 ms with no audible onset clipping.
- [ ] A second Mumble client on the same channel hears complete words with no mid-word chopping.
- [ ] If the adaptive floor misbehaves in the test room (chatters open/closed on background noise), the user notes it so Task tuning (thresholds, or the fixed-threshold fallback) can follow.

**Verify:** Manual. Build & install: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`; join a channel with a second client; watch `adb logcat -s AudioVoiceEngine` while speaking and staying silent.

**Steps:**

- [ ] **Step 1:** Build and install on a physical device: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`.
- [ ] **Step 2:** Join a Mumble channel with a second client (desktop or another phone).
- [ ] **Step 3:** `adb logcat -s AudioVoiceEngine`. Speak → confirm uplink kbps logs and the peer hears you. Go silent for ~5 s → confirm uplink stops. Speak again → confirm fast, unclipped resume.
- [ ] **Step 4:** Report to the coordinator: PASS, or the specific misbehavior (chatter, clipping, stuck-open) so a tuning follow-up can be scheduled.

```json:metadata
{"files": [], "verifyCommand": "manual: ./gradlew installDebug then adb logcat -s AudioVoiceEngine while speaking/silent with a second client on-channel", "acceptanceCriteria": ["uplink kbps logs non-zero and sent stat increases while speaking", "silence sends no packets (uplink -> ~0); resume within ~200ms with no onset clipping", "a second client hears complete words, no mid-word chopping", "any adaptive-floor misbehavior is reported for tuning"], "userGate": true, "tags": ["user-gate"], "modelTier": "standard"}
```

---

## Task 5: RNNoise native build + JNI + Kotlin bindings

**Goal:** Vendor and build xiph RNNoise into `libdumbleopus.so` (keeping 16 KB page alignment), expose `rnnoise_create` / `rnnoise_process_frame` / `rnnoise_destroy` over JNI, and add `NativeRnnoise` bindings that run without crashing.

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/rnnoise_jni.c`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/NativeRnnoise.kt`

**Acceptance Criteria:**
- [ ] `./gradlew :app:assembleDebug` builds `libdumbleopus.so` for all ABIs with RNNoise linked in.
- [ ] All `LOAD` segments remain 16 KB-aligned (the existing `-Wl,-z,max-page-size=16384` still applies to the combined lib).
- [ ] `NativeRnnoise.createState()` returns non-zero, `processFrame` runs on a 480-sample buffer without crashing, `destroyState` frees it (validated by the smoke in Task 6 / on-device).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.

> **HIGH-RISK TASK.** RNNoise upstream builds with autotools, not CMake, and recent versions fetch the trained model (`rnnoise_data.c`) via `download_model.sh` at configure time. The steps below use the same `FetchContent` mechanism as opus and compile RNNoise's `src/*.c` directly, plus a model-fetch step. If the model source is absent after fetch, run the download script (Step 2b) or pin a commit/fork that commits the model. If a cheap model stalls here, escalate to a more capable model rather than thrashing the build.

**Steps:**

- [ ] **Step 1: Add RNNoise to `CMakeLists.txt`**

Replace the file with:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(dumbleopus C)

include(FetchContent)

FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG        v1.5.2
)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opus)

# RNNoise has no upstream CMake; fetch source and compile src/*.c directly.
FetchContent_Declare(
    rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG        master   # Step 3: pin to the resolved commit after first green build
)
FetchContent_MakeAvailable(rnnoise)

# The trained model (rnnoise_data.c/.h) must exist before globbing (see Step 2b).
file(GLOB RNNOISE_SRC ${rnnoise_SOURCE_DIR}/src/*.c)

add_library(dumbleopus SHARED opus_jni.c rnnoise_jni.c ${RNNOISE_SRC})
target_include_directories(dumbleopus PRIVATE ${rnnoise_SOURCE_DIR}/include)
target_link_libraries(dumbleopus opus)
target_link_options(dumbleopus PRIVATE "-Wl,-z,max-page-size=16384")
```

- [ ] **Step 2a: First build attempt**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`

- [ ] **Step 2b: If the build fails on missing `rnnoise_data` symbols**, the model source was not present. Fetch it into the populated source tree, then rebuild:

```bash
RNN_DIR=$(find app/.cxx ~/.gradle -type d -path '*rnnoise-src' 2>/dev/null | head -1)
# The model script lives at the RNNoise source root; run it to generate src/rnnoise_data.c(.h):
( cd "$RNN_DIR" && ./download_model.sh )   # requires bash + curl/wget
```

If `download_model.sh` is unavailable in the pinned revision, change `GIT_TAG` to a commit that commits `src/rnnoise_data.c`, or vendor that file under `app/src/main/cpp/rnnoise/` and add it to the `add_library` list. Re-run Step 2a.

- [ ] **Step 3: Pin the RNNoise revision**

After a green build, capture the exact commit and replace `GIT_TAG master`:

```bash
git -C "$RNN_DIR" rev-parse HEAD    # copy this hash into CMakeLists.txt GIT_TAG
```

- [ ] **Step 4: Write `rnnoise_jni.c`**

```c
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include "rnnoise.h"

#define RNN_FRAME 480   /* RNNoise fixed frame: 480 samples @ 48 kHz = 10 ms */

JNIEXPORT jlong JNICALL
Java_me_danielstiner_dumble_mumble_voice_NativeRnnoise_createState(JNIEnv *env, jobject thiz) {
    return (jlong)(intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_me_danielstiner_dumble_mumble_voice_NativeRnnoise_destroyState(JNIEnv *env, jobject thiz, jlong st) {
    if (st) rnnoise_destroy((DenoiseState *)(intptr_t) st);
}

/* Denoise 480 samples in place at pcm[off..off+480). RNNoise works on float samples in the
 * int16 range (NOT normalized to [-1,1]). */
JNIEXPORT void JNICALL
Java_me_danielstiner_dumble_mumble_voice_NativeRnnoise_processFrame(
        JNIEnv *env, jobject thiz, jlong st, jshortArray arr, jint off) {
    if (!st) return;
    jshort *pcm = (*env)->GetShortArrayElements(env, arr, NULL);
    float buf[RNN_FRAME];
    for (int i = 0; i < RNN_FRAME; i++) buf[i] = (float) pcm[off + i];
    rnnoise_process_frame((DenoiseState *)(intptr_t) st, buf, buf);
    for (int i = 0; i < RNN_FRAME; i++) {
        float v = buf[i];
        if (v > 32767.0f) v = 32767.0f;
        else if (v < -32768.0f) v = -32768.0f;
        pcm[off + i] = (jshort) lrintf(v);
    }
    (*env)->ReleaseShortArrayElements(env, arr, pcm, 0);
}
```

- [ ] **Step 5: Write `NativeRnnoise.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/** Thin JNI binding to RNNoise (see app/src/main/cpp/rnnoise_jni.c). State is an opaque pointer. */
object NativeRnnoise {
    init { System.loadLibrary("dumbleopus") }   // RNNoise is linked into the same shared lib

    external fun createState(): Long
    external fun destroyState(state: Long)
    /** Denoise 480 samples in place starting at pcm[offset]. */
    external fun processFrame(state: Long, pcm: ShortArray, offset: Int)
}
```

- [ ] **Step 6: Rebuild and confirm**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/cpp/CMakeLists.txt app/src/main/cpp/rnnoise_jni.c \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/NativeRnnoise.kt
git commit -m "feat(voice): vendor RNNoise into libdumbleopus + JNI bindings (#40 phase 2)"
```

```json:metadata
{"files": ["app/src/main/cpp/CMakeLists.txt", "app/src/main/cpp/rnnoise_jni.c", "app/src/main/java/me/danielstiner/dumble/mumble/voice/NativeRnnoise.kt"], "verifyCommand": "export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\" && ./gradlew :app:assembleDebug", "acceptanceCriteria": ["assembleDebug builds libdumbleopus.so for all ABIs with RNNoise linked", "LOAD segments remain 16KB-aligned", "NativeRnnoise.createState non-zero, processFrame runs on 480 samples without crashing, destroyState frees"], "modelTier": "standard"}
```

---

## Task 6: RnnoiseSuppressor + wire into the pipeline

**Goal:** Add `RnnoiseSuppressor` (persistent `DenoiseState`, denoise each 480-sample half in place feeding both the detector and the encoder) and make production construct it, with a JVM test proving the engine calls the suppressor twice per capture at the right offsets.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/RnnoiseSuppressor.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt:119`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt`

**Acceptance Criteria:**
- [ ] `RnnoiseSuppressor` holds one `DenoiseState` for its lifetime and frees it on `close()`.
- [ ] `process` requires 480-sample frames and denoises in place via `NativeRnnoise`.
- [ ] The engine calls `suppressor.process` exactly `FRAMES_PER_PACKET` times per capture, at offsets 0 and 480, and the (denoised) buffer is what the encoder receives.
- [ ] `MumbleManager` constructs the engine with `RnnoiseSuppressor()`.
- [ ] Unit suite green (RNNoise itself is exercised on-device in Task 7).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Write `RnnoiseSuppressor.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * RNNoise noise suppression. One persistent DenoiseState per capture stream, fed consecutive
 * 480-sample frames in order (never reset between frames) to preserve RNNoise's internal
 * pitch/overlap continuity. Denoises in place; the cleaned audio feeds both the VAD and the
 * Opus encoder (Mumble-faithful: denoise -> detect/encode).
 */
class RnnoiseSuppressor : NoiseSuppressor {
    private val state = NativeRnnoise.createState().also {
        require(it != 0L) { "rnnoise_create failed" }
    }

    override fun process(pcm: ShortArray, off: Int, n: Int) {
        require(n == FRAME_SAMPLES_10MS) { "RNNoise requires 480-sample frames, got $n" }
        NativeRnnoise.processFrame(state, pcm, off)
    }

    override fun close() { NativeRnnoise.destroyState(state) }
}
```

- [ ] **Step 2: Add a wiring test (failing first)**

Append to `AudioVoiceEngineFrameNumberTest.kt` (inside the test class):

```kotlin
    private class RecordingSuppressor : NoiseSuppressor {
        val calls = mutableListOf<Int>()   // offsets seen
        override fun process(pcm: ShortArray, off: Int, n: Int) { calls.add(off) }
        override fun close() {}
    }

    @Test fun engineDenoisesEachHalfInPlacePerCapture() {
        val sup = RecordingSuppressor()
        val e = AudioVoiceEngine(
            FakeOpusCodec(), { ScriptedAudioIn(List(2) { 8000 }) }, { FakeAudioOut() }, sup,
        )
        e.start()
        e.nextOutgoingFrame(0)                       // one capture
        assertEquals("two 10 ms sub-frames per capture", listOf(0, FRAME_SAMPLES_10MS), sup.calls)
        e.stop()
    }
```

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.AudioVoiceEngineFrameNumberTest"`
Expected: PASS (the seam from Task 3 already calls `suppressor.process` per half — this test locks the offsets in). If it fails, the Task 3 loop is wrong; fix the loop.

- [ ] **Step 3: Make production use RNNoise**

In `MumbleManager.kt` line 119, change:

```kotlin
        private val engine = AudioVoiceEngine(codec)
```

to:

```kotlin
        private val engine = AudioVoiceEngine(codec, suppressor = RnnoiseSuppressor())
```

- [ ] **Step 4: Run the suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/RnnoiseSuppressor.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt
git commit -m "feat(voice): RnnoiseSuppressor denoises uplink halves feeding VAD+encoder (#40 phase 2)"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/RnnoiseSuppressor.kt", "app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt", "app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt"], "verifyCommand": "export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\" && ./gradlew testDebugUnitTest", "acceptanceCriteria": ["RnnoiseSuppressor holds one DenoiseState, frees on close", "process requires 480 samples and denoises in place via NativeRnnoise", "engine calls process twice per capture at offsets 0 and 480, denoised buffer feeds the encoder", "MumbleManager constructs engine with RnnoiseSuppressor()", "unit suite green"], "modelTier": "standard"}
```

---

## Task 7: Phase 2 on-device verification gate

**Goal:** On a real device, confirm RNNoise runs without native crashes or 16 KB-alignment regressions, audibly reduces background noise on the uplink, and improves VAD robustness in a noisy room versus Phase 1.

> **USER-ORDERED GATE — NON-SKIPPABLE.** This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

**Files:** none (manual device verification).

**Acceptance Criteria:**
- [ ] The app runs on device with RNNoise active (no native crash in logcat; `installDebug` succeeds — Play 16 KB compliance already enforced by the link flag).
- [ ] A second client hears audibly less background noise (e.g. fan/keyboard) on the uplink than with Phase 1.
- [ ] In a noisy room, the VAD opens/closes on speech more reliably than Phase 1 (fewer false opens on steady noise).
- [ ] No new audible artifacts (musical noise, dropouts) that make speech worse.

**Verify:** Manual. `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`; join a channel with a second client; test with background noise present; watch `adb logcat` for native crashes.

**Steps:**

- [ ] **Step 1:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug` on a physical device.
- [ ] **Step 2:** Join a channel with a second client; introduce steady background noise (fan, typing).
- [ ] **Step 3:** Confirm the peer hears reduced noise, speech stays clean, and the VAD gates reliably. Watch `adb logcat` for RNNoise/native crashes.
- [ ] **Step 4:** Report PASS or specific regressions (crash, artifacts, worse gating) to the coordinator.

```json:metadata
{"files": [], "verifyCommand": "manual: ./gradlew installDebug then test with a second client and background noise; adb logcat for native crashes", "acceptanceCriteria": ["app runs on device with RNNoise active, no native crash, installDebug succeeds (16KB compliant)", "second client hears audibly less background noise than Phase 1", "VAD gates more reliably in a noisy room (fewer false opens)", "no new audible artifacts that worsen speech"], "userGate": true, "tags": ["user-gate"], "modelTier": "standard"}
```

---

## Dependencies

- Task 3 blockedBy Task 1, Task 2.
- Task 4 blockedBy Task 3.
- Task 5 blockedBy Task 4 (don't start Phase 2 until Phase 1 is verified on device).
- Task 6 blockedBy Task 3, Task 5.
- Task 7 blockedBy Task 6.

Tasks 1 and 2 are independent (disjoint files) and may run in parallel.
