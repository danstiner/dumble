# Platform-Audio Visibility (transmit-path diagnostics) — Design

**Date:** 2026-07-16
**Status:** Approved (brainstorm) — ready for plan
**Scope:** Follow-up to the AGC feature — the **on-device half of the deferred AGC Phase-0**
(`docs/TODO.md`: "Check if AGC is enabled on the audio path and log / surface it under settings").
Read-only diagnostics; no change to the transmit signal path.

---

## Goal

Show, on-device and read-only, **what the platform is doing to our capture** and **what our chain
does to the level** — so "the client is too quiet / is the platform AGC even on?" becomes an
observable fact instead of a guess. Two parts: (1) the platform effect state (AEC/NS/AGC) on the live
capture session, and (2) the stage-by-stage loudness across our chain. Surfaced in a Settings
diagnostics screen and logged.

## Why / grounding

The AGC eval harness measured RNNoise's attenuation on corpus clips (no platform AGC). This is the
missing on-device half: what the platform's `VOICE_COMMUNICATION` preprocessing actually is on a real
device, and the real level at each stage.

**Grounded in Google's OboeTester** (`apps/OboeTester/.../StreamConfigurationView.java`,
`setupEffects(sessionId)`): the reference app reads platform effect state by
`AutomaticGainControl.isAvailable()` (static, gates the UI) → `create(sessionId)` → `getEnabled()`
(the platform's **default** state for that session, shown as "(Y)/(N)"). The Android docs confirm:
*"call `getEnabled()` after creating … to check the default activation state on that session"* —
`create()` reflects the default, it does not force-enable. OboeTester (a test tool) then also
`setEnabled(checkbox)` to toggle; **we do the read-only half only** (never `setEnabled`).

## Design

### 1. Platform effect probe (Android-only)

Lives where the capture session id is available — `AndroidAudioIn` in `AudioVoiceEngine.kt` exposes
`record.audioSessionId`. A small helper `PlatformAudioEffects` reads, once per capture session:

For each of `AcousticEchoCanceler`, `AutomaticGainControl`, `NoiseSuppressor`:
- `isAvailable()` (static — never attaches). If false → `{available=false, enabled=null}`.
- If available: `create(sessionId)`; if non-null, read `getEnabled()` → `{available=true,
  enabled=<default>}`. **Hold the handle for the capture session; never call `setEnabled`. Release on
  stop.** This reads the platform default without perturbing the `VOICE_COMMUNICATION` path (the
  read-only half of OboeTester's pattern). Guard each `create`/`getEnabled` in try/catch → on any
  throw, report `{available=true, enabled=null}` (unknown) and continue.

Also captured once: `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` (does the
device offer a raw, effect-free path — i.e. is `VOICE_COMMUNICATION`'s processing a deliberate
choice?), and `Build.MANUFACTURER`/`Build.MODEL` (to correlate device-specific behavior).

**Honest caveat (documented in the UI + code):** `getEnabled()` reflects the *audiofx effect* state,
which equals the `VOICE_COMMUNICATION` HAL preprocessing on most devices but is not guaranteed on all
— so it is the platform's *self-report*, and the stage-RMS below is the *ground truth*.

### 2. Stage-level metering (transmit path)

In the send path, measure RMS (dBFS, ref 32768 — matching the eval harness) at three points, plus the
live gain and speech probability:
- **raw** — `capturePcm` immediately after `rec.read`, before processing (what the platform handed us).
- **post-RNNoise** — after `suppressor.process`, before the makeup gain. Measured in
  `TransmitProcessor` (which owns that seam) and exposed as a per-capture field the engine reads.
- **post-gain** — `capturePcm` after `TransmitProcessor.process`/`denoise` returns (what we encode).
- **agcGain** — `GainControl.gain` (already exposed); **vadProb** — RNNoise's last probability.

These populate an `AudioDiagnostics` value; the drop `raw → post-RNNoise` is RNNoise's real
attenuation on this device, and `post-RNNoise → post-gain` is our makeup. The dBFS math is a pure
function (JVM-unit-testable); metering runs only on live captures (negligible cost — one extra RMS
per 20 ms).

### 3. State flow + wiring (mirrors `VoiceStats`)

- `AudioDiagnostics` data class: `{ effects: List<EffectState(kind, available, enabled?)>,
  unprocessedSupported: String?, deviceModel: String, rawDbFs, postDenoiseDbFs, postGainDbFs,
  agcGain, vadProb }` (levels are the latest windowed values; `-∞`/`NaN`-safe when idle).
- `AudioVoiceEngine` exposes `diagnostics: StateFlow<AudioDiagnostics>`, updated periodically from the
  send/playback loop (same cadence as `_stats`), seeded with the effect probe at capture start.
- `MumbleManager` mirrors it: `_audioDiagnostics` → public `audioDiagnostics: StateFlow` (like
  `voiceStats`). Not persisted — it's live read-only telemetry.
- Periodic `Log.d` line (like the existing uplink/ping logs) with the effect states + stage levels.

### 4. Settings UI (read-only diagnostics screen)

A new **"Audio diagnostics"** entry in `SettingsScreen` (a `ListItem`, like the existing "Echo Test"
/ "VAD Gate Tuner"), opening a read-only screen that observes `MumbleManager.audioDiagnostics`:
- Platform effects: AEC / NS / AGC → `available` + `default enabled` (Y / N / unknown); unprocessed
  support; device model; the self-report caveat line.
- Live levels: raw / post-RNNoise / post-gain dBFS, AGC gain (dB), VAD prob — updating live during a
  call (shows placeholders when not connected).

`DumbleApp` collects `audioDiagnostics` and passes it in, plus an `onLaunchAudioDiagnostics` nav
callback wired like the existing debug-tool launchers.

## Components / files

- **New:** `mumble/voice/AudioDiagnostics.kt` — the **pure** data (`EffectState`, `AudioDiagnostics`)
  + the dBFS/RMS helper (no Android imports → JVM-unit-testable).
- **New:** `mumble/voice/PlatformAudioEffects.kt` — the **Android-only** probe (`audiofx` +
  `AudioManager`/`Build`) that returns `List<EffectState>` + device fields; not unit-tested.
- **Modify:** `AudioVoiceEngine.kt` (`AndroidAudioIn` exposes session id; seed probe; stage metering;
  `diagnostics` StateFlow); `TransmitProcessor.kt` (expose per-capture post-denoise RMS);
  `MumbleManager.kt` (`audioDiagnostics` StateFlow mirror); `SettingsScreen.kt` (+ ListItem entry);
  `DumbleApp.kt` (collect + nav); a new read-only `AudioDiagnosticsScreen` composable.

## Testing

- **JVM unit:** the dBFS/RMS helper and the `AudioDiagnostics` aggregation (pure) — level math correct,
  idle-safe (no NaN/-∞ blowups). The stage-metering ordering (raw before process, post-denoise before
  gain) is covered by asserting the three values on a known synthetic capture via the existing engine
  fake harness.
- **Android-only (not unit-tested):** the `audiofx` probe and Compose screen — verified on-device.
- **On-device acceptance:** on the 7a during a call, the screen shows AEC/NS/AGC state, and the
  raw→post-RNNoise drop + post-gain recovery match expectations; captured alongside the AGC + PTT
  batch test.

## Scope out / deferred

- **Platform-AGC toggle (A/B)** — letting the user `setEnabled(false)` on the platform AGC to compare
  against ours. Genuinely useful for on-device A/B, but it *changes* the capture path and is
  device-variable — deferred; v1 is strictly read-only.
- **Persisting / uploading diagnostics** — no; live read-only only.
- **UNPROCESSED capture path** — reporting support only; actually switching to it is the separate
  Oboe/low-latency item.

## Verified / grounded claims

- OboeTester reads platform effect state via `isAvailable()` → `create(sessionId)` → `getEnabled()`,
  then (as a test tool) `setEnabled(checkbox)` — verified in
  `google/oboe apps/OboeTester/.../StreamConfigurationView.java:261-266, 670-720`. We take the
  read-only half.
- `create()` reflects the session's default effect state (does not force-enable); `getEnabled()` after
  `create()` is the documented way to read it — Android `audiofx` docs.
- `AudioRecord.audioSessionId` provides the session id for the probe — Android API.
