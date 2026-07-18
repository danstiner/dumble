# Audio Latency Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add live capture (mic→PCM) and playout (PCM→speaker) latency to the developer **Audio diagnostics** screen, measured via `AudioRecord`/`AudioTrack` `getTimestamp`, EMA-smoothed.

**Architecture:** Pure JVM-testable latency arithmetic (`LatencyMath`) + smoothing (`LatencyEma`) feed two `getTimestamp` samplers inside the existing `AndroidAudioIn`/`AndroidAudioOut` classes. A new `LatencyStats` StateFlow is emitted from the engine's 20 ms playback-loop tick, mirrored on `MumbleManager` like `netStats`/`voiceStats`, and rendered as a new **Latency** section on `AudioDiagnosticsScreen`. The audio path itself is unchanged.

**Tech Stack:** Kotlin, `android.media.AudioRecord`/`AudioTrack` `getTimestamp(AudioTimestamp)`, kotlinx StateFlow, Compose, JUnit4.

**User decisions (already made):**
- Purpose: **developer diagnostic** (accuracy over polish; lives in the existing Audio diagnostics screen; not user-facing).
- Measurement: **hardware timestamps** (`getTimestamp`), EMA-smoothed, graceful fallback when unavailable.
- Placement: extend the existing **Audio diagnostics** screen; network RTT + `Buffer: N ms` stay as-is.
- Scope: **no** summed end-to-end number in v1.

**Spec:** `docs/superpowers/specs/2026-07-17-audio-latency-monitoring-design.md` (getTimestamp semantics + latency math fable-verified against AOSP; see its "Verified API facts" section — do not re-derive).

**Load-bearing facts (from the spec's verified section):**
- `AudioTrack.getTimestamp(ts)` returns **`boolean`**; `AudioTrack.framePosition` is **low-order 32-bit wrapping** (must be unwrapped mod 2³² against the 64-bit `framesWritten`).
- `AudioRecord.getTimestamp(ts, timebase)` returns **`int`** (`AudioRecord.SUCCESS == 0`); `framePosition` is **full 64-bit** (no wrap).
- `nanoTime` is `TIMEBASE_MONOTONIC` → comparable to `System.nanoTime()`. Latency is **best-effort pipeline latency, not acoustic**.
- Audio is **mono** (`CHANNEL_*_MONO`), so 1 short == 1 frame — `read`/`write` return values (shorts) are frame counts.
- `SAMPLE_RATE` is a file-level constant already used in `AndroidAudioIn`/`AndroidAudioOut` (48000).

---

## File Structure

| File | Task | Responsibility |
|------|------|----------------|
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyMath.kt` | T1 | Pure: `outputLatencyMs` (32-bit wrap) + `inputLatencyMs`, clamp ≥ 0 |
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyEma.kt` | T1 | Pure: `@Volatile` single-value EMA smoother |
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyStats.kt` | T1 | Pure data class `LatencyStats(captureMs, playoutMs)` |
| `app/src/test/java/me/danielstiner/dumble/mumble/voice/LatencyMathTest.kt` | T1 | JVM tests (basic, extrapolation, wrap, clamp) |
| `app/src/test/java/me/danielstiner/dumble/mumble/voice/LatencyEmaTest.kt` | T1 | JVM tests (seed, converge, NaN, hold) |
| `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` | T2, T3 | `latencyMs()` default on `AudioIn`/`AudioOut`; sampler wiring in `AndroidAudioIn`/`AndroidAudioOut`; `_latency` flow + playback-tick emit |
| `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt` | T3 | `latencyStats` StateFlow + collector + `shutdown()` reset |
| `app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt` | T4 | New **Latency** section |
| `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt` | T4 | Collect `latencyStats`, pass to the screen |

**Task order (sequential deps):** T1 → T2 → T3 → T4. All gradle commands are prefixed with `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Never stage `.idea/gradle.xml`. Commit trailers: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz`.

**On-device verification is NOT a task** — Dan batches on-device testing. Stop after the final whole-plan review and report; the Latency section is the on-device readout (plausible/stable capture+playout ms; `—` on a no-timestamp route).

---

### Task 1: Pure latency primitives (LatencyMath + LatencyEma + LatencyStats)

**Goal:** Create the three pure, Android-free types (`LatencyMath`, `LatencyEma`, `LatencyStats`) with JVM unit tests for the two with logic.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyMath.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyEma.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyStats.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/LatencyMathTest.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/LatencyEmaTest.kt`

**Acceptance Criteria:**
- [ ] `LatencyMath.outputLatencyMs` computes in-flight frames with modulo-2³² wrap handling and clamps ≥ 0.
- [ ] `LatencyMath.inputLatencyMs` computes capture-to-read delay (full 64-bit) and clamps ≥ 0.
- [ ] `LatencyEma.valueMs` is `@Volatile`, `NaN` until first `update`, then EMA-smoothed.
- [ ] `LatencyStats` is a data class with `captureMs`/`playoutMs` defaulting to `Double.NaN`.
- [ ] Both test files pass on the JVM.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.LatencyMathTest" --tests "me.danielstiner.dumble.mumble.voice.LatencyEmaTest"` → all cases PASS.

**Steps:**

- [ ] **Step 1: Write `LatencyMathTest.kt` (failing — types don't exist yet)**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyMathTest {
    private val rate = 48000
    private val t0 = 1_000_000_000L // arbitrary base nanoTime

    @Test fun output_basic_noExtrapolation() {
        // 2400 frames written but not yet presented, timestamp taken "now" → 2400/48000 s = 50 ms.
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = 4800, tsFramePosition = 2400, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(50.0, ms, 0.01)
    }

    @Test fun output_extrapolatesPresentedFramesForward() {
        // 10 ms after the timestamp, 480 more frames have been presented → in-flight 4800-2880=1920 → 40 ms.
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = 4800, tsFramePosition = 2400, tsNanoTime = t0, nowNanos = t0 + 10_000_000, rate = rate)
        assertEquals(40.0, ms, 0.01)
    }

    @Test fun output_handles32BitWrap() {
        // framesWritten is past the 2^32 boundary; framePosition has wrapped back near 0.
        // (2^32 + 2400) - 0 = mod 2^32 → 2400 frames in-flight → 50 ms.
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = (1L shl 32) + 2400, tsFramePosition = 0, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(50.0, ms, 0.01)
    }

    @Test fun output_negativeInFlightClampsToZero() {
        // presented slightly ahead of written (overshoot) → small negative → clamp 0.
        val ms = LatencyMath.outputLatencyMs(
            framesWritten = 2400, tsFramePosition = 2500, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(0.0, ms, 0.0)
    }

    @Test fun input_basic() {
        // Newest read frame == HAL framePosition, captured 5 ms ago → 5 ms.
        val ms = LatencyMath.inputLatencyMs(
            framesRead = 4800, tsFramePosition = 4800, tsNanoTime = t0, nowNanos = t0 + 5_000_000, rate = rate)
        assertEquals(5.0, ms, 0.01)
    }

    @Test fun input_halAheadOfRead() {
        // HAL has captured 200 frames beyond what we've read → newest read frame captured earlier.
        // captureTime = t0 + (4800-5000)*1e9/48000 = t0 - 4_166_666 ns; now = t0 → ~4.17 ms.
        val ms = LatencyMath.inputLatencyMs(
            framesRead = 4800, tsFramePosition = 5000, tsNanoTime = t0, nowNanos = t0, rate = rate)
        assertEquals(4.1667, ms, 0.01)
    }

    @Test fun input_negativeClampsToZero() {
        val ms = LatencyMath.inputLatencyMs(
            framesRead = 4800, tsFramePosition = 4800, tsNanoTime = t0 + 5_000_000, nowNanos = t0, rate = rate)
        assertEquals(0.0, ms, 0.0)
    }
}
```

- [ ] **Step 2: Run to confirm it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.LatencyMathTest"` → FAIL (unresolved reference `LatencyMath`).

- [ ] **Step 3: Create `LatencyMath.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Pure latency arithmetic for AudioTrack/AudioRecord getTimestamp readings. No Android deps →
 * JVM-testable. Both functions clamp to >= 0 (extrapolation overshoot or a stalled position can push
 * the raw value slightly negative).
 *
 * getTimestamp semantics (fable-verified against AOSP, see the design doc): timestamp.nanoTime is
 * TIMEBASE_MONOTONIC, directly comparable to System.nanoTime(); the result is best-effort *pipeline*
 * latency, not acoustic mouth-to-ear latency.
 */
object LatencyMath {
    /**
     * Playout latency ms: in-flight audio between app and output = framesWritten - framesPresentedNow.
     * AudioTrack.framePosition is the LOW-ORDER 32 BITS in wrapping frame units (despite the long
     * field), so the difference is taken modulo 2^32 and reinterpreted as the small signed in-flight
     * count (true in-flight << 2^31 frames).
     */
    fun outputLatencyMs(
        framesWritten: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long, rate: Int,
    ): Double {
        val presentedNow = tsFramePosition + Math.round((nowNanos - tsNanoTime) * rate / 1e9)
        val diff32 = (framesWritten - presentedNow) and 0xFFFFFFFFL
        val inFlight = if (diff32 >= 0x80000000L) diff32 - 0x100000000L else diff32
        return maxOf(inFlight, 0L).toDouble() / rate * 1000.0
    }

    /**
     * Capture latency ms: now - capture-time-of-newest-read-frame. AudioRecord.framePosition uses all
     * 64 bits (no wrap), so plain arithmetic is safe.
     */
    fun inputLatencyMs(
        framesRead: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long, rate: Int,
    ): Double {
        val captureTimeNanos = tsNanoTime + (framesRead - tsFramePosition) * 1_000_000_000L / rate
        return maxOf(nowNanos - captureTimeNanos, 0L).toDouble() / 1e6
    }
}
```

- [ ] **Step 4: Run to confirm `LatencyMathTest` passes** — same command as Step 2 → PASS (7 tests).

- [ ] **Step 5: Write `LatencyEmaTest.kt` (failing)**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyEmaTest {
    @Test fun nanUntilFirstSample() {
        assertTrue(LatencyEma().valueMs.isNaN())
    }

    @Test fun firstSampleSeedsValue() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(10.0)
        assertEquals(10.0, ema.valueMs, 0.0)
    }

    @Test fun convergesTowardConstant() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(10.0)
        repeat(200) { ema.update(20.0) }
        assertEquals(20.0, ema.valueMs, 0.01)
    }

    @Test fun gapHoldsLastValue() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(12.0)
        // No further update() calls (e.g. getTimestamp failing) → value persists.
        assertEquals(12.0, ema.valueMs, 0.0)
    }

    @Test fun smoothsSecondSample() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(10.0)
        ema.update(20.0) // 10*0.9 + 20*0.1 = 11.0
        assertEquals(11.0, ema.valueMs, 0.0)
    }
}
```

- [ ] **Step 6: Run to confirm it fails** — `... --tests "me.danielstiner.dumble.mumble.voice.LatencyEmaTest"` → FAIL (unresolved `LatencyEma`).

- [ ] **Step 7: Create `LatencyEma.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Single-value EMA smoother for a latency reading. Pure (no Android deps) → JVM-testable.
 * [valueMs] is written on the audio (read/write) thread and read on the playout/stats thread, so it
 * is @Volatile — a single volatile double is safe per JMM 17.7. NaN until the first sample; v1 does
 * not mark stale — a route that stops reporting timestamps simply holds the last value.
 */
class LatencyEma(private val alpha: Double = 0.1) {
    @Volatile
    var valueMs: Double = Double.NaN
        private set

    fun update(sampleMs: Double) {
        valueMs = if (valueMs.isNaN()) sampleMs else valueMs * (1 - alpha) + sampleMs * alpha
    }
}
```

- [ ] **Step 8: Create `LatencyStats.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/** Capture (mic→PCM) and playout (PCM→speaker) latency in ms. NaN = unavailable (rendered as "—"). */
data class LatencyStats(
    val captureMs: Double = Double.NaN,
    val playoutMs: Double = Double.NaN,
)
```

- [ ] **Step 9: Run both test classes → PASS** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.LatencyMathTest" --tests "me.danielstiner.dumble.mumble.voice.LatencyEmaTest"` → PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyMath.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyEma.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/LatencyStats.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/LatencyMathTest.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/LatencyEmaTest.kt
git commit -m "feat(voice): pure latency primitives (LatencyMath/LatencyEma/LatencyStats)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 2: getTimestamp sampler wiring in AndroidAudioIn/AndroidAudioOut

**Goal:** Add a `latencyMs()` default to the `AudioIn`/`AudioOut` interfaces and wire a ~1 Hz `getTimestamp` sampler (frame counter + freshness guard + `LatencyEma`) into `AndroidAudioIn`/`AndroidAudioOut`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` (interfaces at lines 15-16; `AndroidAudioIn` at ~383-409; `AndroidAudioOut` at ~412-432)

**Acceptance Criteria:**
- [ ] `AudioIn` and `AudioOut` each declare `fun latencyMs(): Double = Double.NaN` (default; test fakes inherit it, `SyntheticVoiceSource` unaffected).
- [ ] `AndroidAudioIn` accumulates `framesRead`, samples `AudioRecord.getTimestamp(ts, TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS` at ~1 Hz with a `< 2 s` freshness guard, feeds `LatencyMath.inputLatencyMs` → `ema`, and returns `ema.valueMs` from `latencyMs()`.
- [ ] `AndroidAudioOut` accumulates `framesWritten` (only on `w >= 0`), samples `AudioTrack.getTimestamp(ts)` (boolean) at ~1 Hz with the same freshness guard, feeds `LatencyMath.outputLatencyMs` → `ema`, and returns `ema.valueMs`.
- [ ] The sampler sits in the wrapper `read()`/`write()` (once per call), NOT inside the internal short-read retry loop.
- [ ] `assembleDebug` succeeds and the full unit suite stays green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass; then `grep -n "latencyMs" app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` shows the two interface defaults + two overrides.

**Steps:**

- [ ] **Step 1: Add the interface defaults.** Replace lines 15-16:

```kotlin
interface AudioIn { fun read(out: ShortArray, n: Int): Int; fun close(); fun captureInfo(): CaptureInfo? = null; fun latencyMs(): Double = Double.NaN }
interface AudioOut { fun write(pcm: ShortArray, n: Int); fun close(); fun latencyMs(): Double = Double.NaN }
```

- [ ] **Step 2: Wire `AndroidAudioIn`.** In `class AndroidAudioIn : AudioIn` add the sampler fields and rewrite `read()` (keep the existing `minBuf`/`record`/`platformEffects`/`captureInfo`/`close` as-is):

```kotlin
    private var framesRead = 0L
    private var lastSampleNanos = 0L
    private val ema = LatencyEma()
    private val ts = android.media.AudioTimestamp()
    override fun latencyMs(): Double = ema.valueMs

    override fun read(out: ShortArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = record.read(out, off, n - off, AudioRecord.READ_BLOCKING)
            if (r <= 0) {
                android.util.Log.w("AudioVoiceEngine", "AudioRecord.read=$r state=${record.recordingState} off=$off")
                break
            }
            off += r
        }
        framesRead += off // mono: 1 short == 1 frame
        val now = System.nanoTime()
        if (now - lastSampleNanos >= 1_000_000_000L) {
            lastSampleNanos = now
            if (record.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                && now - ts.nanoTime < 2_000_000_000L // skip a stale (stalled) timestamp
            ) {
                ema.update(LatencyMath.inputLatencyMs(framesRead, ts.framePosition, ts.nanoTime, now, SAMPLE_RATE))
            }
        }
        return off
    }
```

- [ ] **Step 3: Wire `AndroidAudioOut`.** In `class AndroidAudioOut : AudioOut` add the sampler fields and rewrite `write()` (keep `minBuf`/`track`/`close` as-is):

```kotlin
    private var framesWritten = 0L
    private var lastSampleNanos = 0L
    private val ema = LatencyEma()
    private val ts = android.media.AudioTimestamp()
    override fun latencyMs(): Double = ema.valueMs

    override fun write(pcm: ShortArray, n: Int) {
        val w = track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
        if (w < 0) android.util.Log.w("AudioVoiceEngine", "AudioTrack.write err=$w playState=${track.playState}")
        else framesWritten += w // mono: 1 short == 1 frame
        val now = System.nanoTime()
        if (now - lastSampleNanos >= 1_000_000_000L) {
            lastSampleNanos = now
            if (track.getTimestamp(ts) && now - ts.nanoTime < 2_000_000_000L) {
                ema.update(LatencyMath.outputLatencyMs(framesWritten, ts.framePosition, ts.nanoTime, now, SAMPLE_RATE))
            }
        }
    }
```

- [ ] **Step 4: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt
git commit -m "feat(voice): sample capture/playout latency via getTimestamp

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 3: LatencyStats flow — engine emit + MumbleManager mirror + shutdown reset

**Goal:** Emit `LatencyStats` from the engine's 20 ms playback-loop tick, mirror it onto `MumbleManager.latencyStats`, and reset it in `ActiveSession.shutdown()`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` (flow decl near lines 35-39; playback-loop tick at ~357)
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt` (flow decls near lines 64-65; collector near line 370; `shutdown()` near line 458)

**Acceptance Criteria:**
- [ ] `AudioVoiceEngine` exposes `val latency: StateFlow<LatencyStats>`, updated each playback-loop tick from `recorder?.latencyMs()` / `out.latencyMs()`.
- [ ] `MumbleManager` exposes `val latencyStats: StateFlow<LatencyStats>`, fed by a `sessionScope.launch { engine.latency.collect { _latencyStats.value = it } }` in `ActiveSession.start()`.
- [ ] `ActiveSession.shutdown()` resets `_latencyStats.value = LatencyStats()`.
- [ ] `assembleDebug` + full unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass; `grep -n "latencyStats\|engine.latency\|_latency" app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` shows the flow, the collector, and the shutdown reset.

**Steps:**

- [ ] **Step 1: Add the engine flow.** In `AudioVoiceEngine.kt`, after the `_diagnostics`/`diagnostics` pair (line 39), insert:

```kotlin
    private val _latency = MutableStateFlow(LatencyStats())
    val latency: StateFlow<LatencyStats> = _latency.asStateFlow()
```

- [ ] **Step 2: Emit from the playback tick.** In `playbackLoop()`, immediately after the existing `_stats.update { it.copy(received = received.get(), activeSpeakers = active) }` (line 357), add:

```kotlin
            // Latency HUD: recorder read on this (playout) thread crosses from the send thread — safe
            // via LatencyEma's @Volatile. StateFlow conflation + data-class equality throttle emissions
            // to the EMA's real change rate (~1 Hz) despite the 20 ms poll.
            _latency.value = LatencyStats(
                captureMs = recorder?.latencyMs() ?: Double.NaN,
                playoutMs = out.latencyMs(),
            )
```

(`out` is the `val out = track!!` bound at the top of `playbackLoop()`; `recorder` is the nullable `AudioIn?` field.)

- [ ] **Step 3: Add the MumbleManager flow.** In `MumbleManager.kt`, after the `_voiceStats`/`voiceStats` pair (lines 64-65), insert:

```kotlin
    private val _latencyStats = MutableStateFlow(LatencyStats())
    val latencyStats: StateFlow<LatencyStats> = _latencyStats.asStateFlow()
```

Add the import (with the other `me.danielstiner.dumble.mumble.voice.*` imports):

```kotlin
import me.danielstiner.dumble.mumble.voice.LatencyStats
```

- [ ] **Step 4: Collect it.** In `ActiveSession.start()`, immediately after `sessionScope.launch { engine.stats.collect { _voiceStats.value = it } }` (line 370), add:

```kotlin
            sessionScope.launch { engine.latency.collect { _latencyStats.value = it } }
```

- [ ] **Step 5: Reset on shutdown.** In `ActiveSession.shutdown()`, immediately after `_audioDiagnostics.value = AudioDiagnostics()` (line 458), add:

```kotlin
            _latencyStats.value = LatencyStats()
```

- [ ] **Step 6: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(voice): expose LatencyStats flow (engine tick + MumbleManager)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 4: Render the Latency section on the diagnostics screen

**Goal:** Add a **Latency** section to `AudioDiagnosticsScreen` (Capture / Playout, `NaN` → `—`) and thread `MumbleManager.latencyStats` through `DumbleApp`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt` (collectors near lines 44-53; `AudioDiagnosticsScreen(...)` call near line 102)

**Acceptance Criteria:**
- [ ] `AudioDiagnosticsScreen` takes a `latency: LatencyStats` param and renders a **Latency** section after **Network** with `Capture:`/`Playout:` lines, `NaN` → `—`, and the best-effort footnote.
- [ ] `DumbleApp` collects `MumbleManager.latencyStats` and passes it as `latency = latency`.
- [ ] `assembleDebug` succeeds; full unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

**Steps:**

- [ ] **Step 1: Add the section to `AudioDiagnosticsScreen.kt`.** Add the import:

```kotlin
import me.danielstiner.dumble.mumble.voice.LatencyStats
```

Change the signature to accept `latency`:

```kotlin
fun AudioDiagnosticsScreen(
    diagnostics: AudioDiagnostics, net: NetStats, voice: VoiceStats, latency: LatencyStats, onBack: () -> Unit,
) {
```

Add a `lat` formatter next to the existing `db`/`rtt` helpers:

```kotlin
    fun lat(v: Double) = if (v.isFinite()) "%.1f ms".format(v) else "—"
```

Insert the Latency block immediately after the Network block's trailing `Text("")` (i.e. after `Text("  UDP jitter: %.2f ms".format(net.udpJitterMs))` and its following `Text("")`), before `Text("Voice")`:

```kotlin
            Text("Latency")
            Text("  Capture:  " + lat(latency.captureMs))
            Text("  Playout:  " + lat(latency.playoutMs))
            Text("  (best-effort pipeline latency — excludes delay the HAL doesn't report, e.g. Bluetooth; not acoustic)")
            Text("")
```

- [ ] **Step 2: Thread the flow through `DumbleApp.kt`.** With the other `collectAsStateWithLifecycle()` calls (near line 53), add:

```kotlin
    val latency by CallManager.let { MumbleManager.latencyStats }.collectAsStateWithLifecycle()
```

(Or simply `val latency by MumbleManager.latencyStats.collectAsStateWithLifecycle()` — `MumbleManager` is the singleton already used for `voiceStats`/`netStats` in this file.)

Update the `AudioDiagnosticsScreen(...)` call (line ~102) to pass it:

```kotlin
            AudioDiagnosticsScreen(diagnostics = audioDiagnostics, net = netStats, voice = voiceStats,
                latency = latency, onBack = { showDiagnostics = false })
```

- [ ] **Step 3: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): show capture/playout latency on the diagnostics screen

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

## Self-Review

**Spec coverage:** LatencyMath/LatencyEma/LatencyStats (T1) ✓; getTimestamp wiring + interface default (T2) ✓; engine tick emit + MumbleManager mirror + shutdown reset (T3) ✓; diagnostics Latency section + DumbleApp threading (T4) ✓; best-effort labeling (T4 footnote) ✓; freshness guard + 1 Hz + EMA (T2) ✓; per-call counter reset — natural (engine recreates `AndroidAudioIn`/`AndroidAudioOut` in `start()`, so counters start at 0) ✓. Non-goals (no summed number, no dead-field population, no Oboe) respected ✓.

**Type consistency:** `latencyMs(): Double`, `LatencyEma.valueMs`, `LatencyMath.{inputLatencyMs,outputLatencyMs}(framesX, tsFramePosition, tsNanoTime, nowNanos, rate)`, `LatencyStats(captureMs, playoutMs)`, `latency`/`_latency` (engine), `latencyStats`/`_latencyStats` (MumbleManager) — consistent across T1-T4.

**Placeholders:** none — every code step shows complete code.
