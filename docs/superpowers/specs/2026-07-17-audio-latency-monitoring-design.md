# Audio Latency Monitoring — Design

**Goal:** Add live capture (mic→PCM) and playout (PCM→speaker) latency measurement to the developer **Audio diagnostics** screen, filling the two audio-path gaps alongside the network RTT and jitter-buffer depth already shown.

**Architecture:** Two `getTimestamp`-based estimators live inside the existing `AndroidAudioIn`/`AndroidAudioOut` classes (they own the `AudioRecord`/`AudioTrack` and see every frame). The pure latency math is extracted into a JVM-testable unit; a new `LatencyStats` StateFlow mirrors the existing `netStats`/`voiceStats` pattern and renders as a new **Latency** section in `AudioDiagnosticsScreen`.

**Tech Stack:** Kotlin, `android.media.AudioRecord`/`AudioTrack` `getTimestamp(AudioTimestamp)`, Compose (diagnostics screen), StateFlow.

**User decisions (already made):**
- Purpose: **developer diagnostic** (accuracy/detail over polish; lives in the existing Audio diagnostics screen). Not user-facing.
- Measurement: **hardware timestamps** (`getTimestamp`), EMA-smoothed, with graceful fallback when unavailable.
- Placement: extend the existing **Audio diagnostics** screen; network RTT + `Buffer: N ms` stay where they are.
- Scope: **no** summed end-to-end number in v1 (all components are visible on one screen — YAGNI).

---

## Context — what already exists

The **Audio diagnostics** screen (`AudioDiagnosticsScreen.kt`) already renders:
- **Network**: `NetStats.mode` / `tcpRttMs` / `udpRttMs` / `udpJitterMs` (from ping/pong).
- **Voice**: `VoiceStats.sent`/`received`/`lost`/`concealed`/**`bufferMs`** (jitter-buffer depth)/`activeSpeakers`.

So the full receive chain's *network* and *jitter-buffer* latency contributors are already visible. The only gap is the two **audio-hardware** latencies:
- **Capture latency** — time between a frame arriving at the ADC and the app reading it (`AndroidAudioIn` / `AudioRecord`, `VOICE_COMMUNICATION`, 48 kHz mono PCM16, blocking reads).
- **Playout latency** — time between the app writing a frame and it being presented at the output (`AndroidAudioOut` / `AudioTrack`, `USAGE_VOICE_COMMUNICATION`, MODE_STREAM, blocking writes).

The audio path itself is **not** modified by this feature.

## Verified API facts (fable, 2026-07-17, against AOSP sources)

These are load-bearing and were fact-checked against the AOSP Javadoc (`AudioTimestamp.java`, `AudioRecord.java`, `AudioTrack.java`) + `api-versions.xml`:

1. `AudioTrack.getTimestamp(AudioTimestamp)` returns **`boolean`**; on `true`, `framePosition` is a frame recently presented (or committed to be presented) at the output and `nanoTime` is its presentation time. `nanoTime` is **`TIMEBASE_MONOTONIC`** — by API contract "same units and timebase as `System.nanoTime()`", so directly comparable.
2. **`AudioTrack.framePosition` is the low-order 32 bits, in wrapping frame units** (like `getPlaybackHeadPosition()`), *despite the field being a `long`*. It wraps every 2³²/48000 s ≈ **24.85 h**. It MUST be unwrapped/compared modulo 2³² against our 64-bit `framesWritten` counter.
3. `AudioRecord.getTimestamp(AudioTimestamp, int timebase)` returns **`int`** (`AudioRecord.SUCCESS == 0`, else `ERROR_INVALID_OPERATION`), added in **API 24** (fine at minSdk 34). With `timebase = AudioTimestamp.TIMEBASE_MONOTONIC`, `nanoTime` is the capture time of `framePosition` "at the earliest point available in the capture pipeline". **`AudioRecord.framePosition` uses all 64 bits — no wrap.**
4. Both timestamps are **best-effort**: "cannot account for any delay unknown to the implementation" — so the reported latency is **pipeline latency, not acoustic latency**, and may under-report downstream/Bluetooth-sink delay. Label it as such; do not claim mouth-to-ear accuracy.
5. `getTimestamp` may return **unavailable as a steady state**, not just during warmup — some routes never provide timestamps. Treat "no timestamp" as: keep the last EMA value, mark stale; if never available, report `—`.
6. `AudioTrack.getLatency()` is **`@hide`/`@UnsupportedAppUsage`** — NOT public API at target 34. Do not use or reference it.
7. Successive short-term timestamp reports are **noise** (docs recommend polling every 10 s–1 min once stable). We sample at **~1 Hz** and **EMA-smooth** (0.9/0.1, matching `SyntheticVoiceSource`/`TransportSelector`).
8. `read()`/`write()` may return **short** — accumulate their actual return values into the frame counters. `AudioRecord`'s frame count **resets to 0 on `startRecording()` after `stop()`** — counters must reset with the stream (n/a here: streams are recreated per call, so counters start at 0 with the object).

## Components

### 1. `LatencyMath` — pure, JVM-testable

Top-level object with two pure functions (no Android deps). Both **clamp to ≥ 0** and return `Double` milliseconds; extrapolation overshoot or a stalled position (underrun) can drive the raw value slightly negative → clamp.

```kotlin
object LatencyMath {
    /**
     * Playout latency: in-flight audio between app and output = framesWritten − framesPresentedNow.
     * AudioTrack.framePosition is a low-order 32-bit WRAPPING value, so the difference is computed
     * modulo 2^32 and interpreted as the small signed in-flight count (true in-flight ≪ 2^31 frames).
     */
    fun outputLatencyMs(
        framesWritten: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long, rate: Int,
    ): Double {
        val extrapolatedPresented = tsFramePosition + Math.round((nowNanos - tsNanoTime) * rate / 1e9)
        val diff32 = ((framesWritten - extrapolatedPresented) and 0xFFFFFFFFL)   // wrap-safe
        val inFlight = if (diff32 >= 0x80000000L) diff32 - 0x100000000L else diff32  // signed
        return (maxOf(inFlight, 0L).toDouble() / rate) * 1000.0
    }

    /**
     * Capture latency: now − capture-time-of-newest-read-frame. AudioRecord.framePosition is full
     * 64-bit (no wrap), so plain arithmetic is safe.
     */
    fun inputLatencyMs(
        framesRead: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long, rate: Int,
    ): Double {
        val captureTimeNanos = tsNanoTime + (framesRead - tsFramePosition) * 1_000_000_000L / rate
        return maxOf(nowNanos - captureTimeNanos, 0L).toDouble() / 1e6
    }
}
```

### 2. `LatencyEma` — pure smoothing + staleness

A tiny stateful holder (no Android deps, JVM-testable): folds each raw sample into an EMA and tracks the last-updated wall time so the consumer can decide staleness. Returns `Double.NaN` until the first sample.

```kotlin
class LatencyEma(private val alpha: Double = 0.1) {
    var valueMs: Double = Double.NaN; private set
    fun update(sampleMs: Double) {
        valueMs = if (valueMs.isNaN()) sampleMs else valueMs * (1 - alpha) + sampleMs * alpha
    }
}
```

(EMA-only state; "stale vs never-available" is expressed by the value staying `NaN` until the first successful timestamp, and by the ~1 Hz sampler simply not calling `update()` when `getTimestamp` fails — the last EMA persists.)

### 3. `AudioIn` / `AudioOut` interface — default no-op latency

Add one method with a default so only the Android impls implement it (synthetic/test doubles inherit the default):

```kotlin
interface AudioIn { /* … existing … */ fun latencyMs(): Double = Double.NaN }
interface AudioOut { /* … existing … */ fun latencyMs(): Double = Double.NaN }
```

### 4. `AndroidAudioIn` / `AndroidAudioOut` — wiring

Each gains: a 64-bit frame counter (accumulate the actual `read`/`write` return), a `LatencyEma`, and a `~1 Hz` sampler inside the existing `read()`/`write()` loop:

- On each call, add the returned frame count to the counter.
- If `nowNanos − lastSampleNanos ≥ 1e9`: call `getTimestamp`; on success feed `LatencyMath.{input,output}LatencyMs(...)` → `ema.update(...)`; update `lastSampleNanos` regardless (so a failing route doesn't hot-loop the call).
- `latencyMs()` returns `ema.valueMs` (may be `NaN`).

`AndroidAudioIn` uses `AudioRecord.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)` (== `SUCCESS`); `AndroidAudioOut` uses `AudioTrack.getTimestamp(ts)` (== `true`). `nowNanos = System.nanoTime()`.

**Threading:** the counter/EMA are mutated only on the audio (`read`/`write`) thread, but `latencyMs()` is read from the stats-emit thread. Publish the smoothed value through an `@Volatile var latencyMs: Double` that the audio thread writes and `latencyMs()` reads — a single volatile double, no lock needed (last-value-wins is fine for a diagnostic).

### 5. `LatencyStats` + flow

```kotlin
data class LatencyStats(
    val captureMs: Double = Double.NaN,   // NaN = unavailable
    val playoutMs: Double = Double.NaN,
)
```

`AudioVoiceEngine` folds `audioIn.latencyMs()` / `audioOut.latencyMs()` into a `LatencyStats` on its existing periodic stats emit (same cadence as `VoiceStats`). `MumbleManager` exposes `latencyStats: StateFlow<LatencyStats>` (mirrors `netStats`/`voiceStats`); `DumbleApp` collects it and passes it into `AudioDiagnosticsScreen`.

### 6. `AudioDiagnosticsScreen` — new Latency section

Rendered right after **Network** (so the reader sees capture → jitter buffer → playout → network on one screen). `NaN` → `—`.

```
Latency
  Capture:  12.3 ms
  Playout:  28.7 ms
Playout is best-effort pipeline latency (excludes some downstream/Bluetooth delay).
```

## Data flow

```
AudioRecord.getTimestamp ─┐                          ┌─ AudioTrack.getTimestamp
   (AndroidAudioIn, 1 Hz) │                          │  (AndroidAudioOut, 1 Hz)
   framesRead ────────────┴─ LatencyMath.inputMs ─┐  └─ LatencyMath.outputMs ─┐
                                                    ├─ LatencyEma (per side) ──┤
                                                    ▼                          ▼
                                   audioIn.latencyMs()        audioOut.latencyMs()
                                                    └────────► AudioVoiceEngine
                                                               builds LatencyStats
                                                                      ▼
                                             MumbleManager.latencyStats: StateFlow
                                                                      ▼
                                        DumbleApp ► AudioDiagnosticsScreen "Latency"
```

## Testing

JVM unit tests (no Android):
- `LatencyMath.outputLatencyMs`: known frames + synthetic timestamp → expected ms; **32-bit wrap** case (framePosition near/after 2³² boundary while framesWritten is large) yields correct small in-flight value; extrapolation overshoot / stalled position clamps to 0.
- `LatencyMath.inputLatencyMs`: capture-to-read delay for typical (HAL ahead of framesRead) and boundary cases; clamps to 0.
- `LatencyEma`: first sample seeds the value; convergence toward a constant; `NaN` until first update; a gap in updates holds the last value (staleness contract).

The Android `getTimestamp` wiring is verified **on-device** — the Latency section itself is the readout (join a call; confirm plausible, stable capture/playout ms; confirm `—` on a route that reports no timestamp).

## Non-goals (v1)

- No summed "end-to-end / mouth-to-ear" number — the components are all on one screen; a naive sum would falsely imply acoustic accuracy the best-effort timestamps don't provide.
- No changes to the audio capture/playout path, buffer sizes, or the jitter buffer.
- Not user-facing; no call-screen surface.
- No `getLatency()` (hidden API) or Oboe migration (separate TODO).
