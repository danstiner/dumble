# Transmit-mode selector (PTT ↔ voice activation) — Design

**Date:** 2026-07-15
**Status:** Approved (design), ready for plan
**Scope:** Sub-project 1 of 2 in the "audio quality" arc. Sub-project 2 is AGC (automatic
gain control), designed separately after this ships.

## Goal

Let the user choose, in Settings, how the client decides to transmit: **voice activation**
(today's RNNoise transmit gate — the default) or **push-to-talk** (transmit only while an
on-screen button is held). The choice is global and persisted.

## Why

The client is currently voice-activation-only. Push-to-talk is the expected fallback for noisy
environments, shared rooms, or when the user simply wants deterministic control over the mic. It
also gives us a clean transmit path (gate bypassed) that never clips onsets — useful as a baseline
while we later tune the VAD.

## Current state (what we're wiring into)

The seams already exist; this feature is mostly mirroring an established pattern.

- **`AudioVoiceEngine`** owns the transmit decision in `nextOutgoingFrame()`: read a 20 ms
  capture → (if muted, emit one terminator then silence) → `TransmitProcessor.process()`
  (denoise → VAD → gate) → encode & send. It already exposes `setMuted()` and `setVadThreshold()`,
  advances `frameNumber` at wall-clock rate, and emits **real (silent, non-empty) terminator
  frames** to close a talkspurt (Mumble drops empty-payload packets before reading the terminator
  flag).
- **`TransmitProcessor.process(capture)`** runs the RNNoise suppressor per 10 ms sub-frame, then
  VAD, then the gate, returning a `Decision(send, terminator)`.
- **`MumbleManager`** (singleton) holds audio settings as `StateFlow`s, persists them to the
  `"dumble_audio"` SharedPreferences, and forwards live changes to the active engine.
  `vadThreshold` is the exact template: `_vadThreshold` MutableStateFlow → public StateFlow →
  `setVadThreshold()` (coerce, persist key `"vad_threshold"`, forward `active?.setVadThreshold()`)
  → loaded in `init()`. `setMuted()` additionally broadcasts self-mute to the server via
  `sendSelfMute()`.
- **`DumbleApp`** collects those StateFlows and passes them into `SettingsScreen` (hosts the
  sensitivity slider) and `ActiveCallScreen` (hosts the Mute button).

No microphone-permission changes: PTT uses the same capture path already running for voice
activation.

## Design

### Mode model

```kotlin
enum class TransmitMode { VOICE_ACTIVATED, PUSH_TO_TALK }
```

Global, persisted, default `VOICE_ACTIVATED` (preserves today's behavior). Preserved across
connects (loaded from prefs; not reset in `connect()`).

### 1. Engine — the transmit decision

`AudioVoiceEngine` gains `@Volatile var transmitMode` and `@Volatile var pttHeld`, plus
`setTransmitMode()` / `setPttHeld()`. In `nextOutgoingFrame()` the mute check stays first
(unchanged: hard-off, already emits its own terminator), then branch on mode:

- **VOICE_ACTIVATED** → unchanged (`processor.process()` → gate).
- **PUSH_TO_TALK**:
  - *Not held:* reset the gate; on the held→released **edge**, emit exactly one silent
    terminator frame (reusing the proven mute-terminator code path) to close the talkspurt
    cleanly; return `null` while idle thereafter.
  - *Held:* denoise the capture (RNNoise still cleans it) via a new denoise-only path, then send
    every 20 ms frame with the **gate bypassed** — no onset clipping, and quiet/soft speech still
    transmits. `isTerminator = false` while held.

`frameNumber` keeps advancing at wall-clock rate (unchanged), so a resumed talkspurt lands in the
future of a live jitter buffer rather than being dropped as late.

A denoise-only path is added to `TransmitProcessor` (e.g. `denoise(capture)`): runs just the 10 ms
sub-frame suppressor loop, no VAD, no gate, mutating in place. This keeps the sub-frame chunking
logic DRY (the engine does not re-implement it).

### 2. MumbleManager — state + persistence (mirrors `vadThreshold`)

- `_transmitMode` MutableStateFlow → public `transmitMode: StateFlow<TransmitMode>`.
- `setTransmitMode(mode)`: set the flow, persist to `"dumble_audio"` key `"transmit_mode"`,
  forward `active?.setTransmitMode(mode)`. **Also clears self-mute** when switching to
  `PUSH_TO_TALK` (call the existing `setMuted(false)`), so a lingering self-mute can't block the
  hold button and other clients stop rendering the mute icon.
- `setPttHeld(Boolean)` → `active?.setPttHeld(value)` (transient; not persisted).
- Loaded in `init()` alongside `vad_threshold`.
- `ActiveSession` delegates `setTransmitMode` / `setPttHeld` to the engine (like
  `setVadThreshold` / `setMuted`).

### 3. Settings UI — the selector

`SettingsScreen` gets a mode selector ("Voice activity" | "Push to talk") above the existing
sensitivity slider, wired via new `transmitMode` + `onTransmitModeChange` params (mirrors the
`vadThreshold` params). In PTT mode the sensitivity slider no longer affects transmit, so it stays
visible but annotated ("Applies in Voice activity mode") — it still governs the VAD used when the
user switches back.

### 4. Call UI — the hold-to-talk button

`ActiveCallScreen` branches on mode:

- **VOICE_ACTIVATED** → unchanged (Mute button present).
- **PUSH_TO_TALK** → a large **hold-to-talk button replaces the Mute button**. Press →
  `setPttHeld(true)`, release → `setPttHeld(false)`, with a clear pressed-state visual. No separate
  Mute control in this mode.

Wired via a new `transmitMode` param plus `onPttPress` / `onPttRelease` callbacks; the existing
`muted` / `onToggleMute` remain but are only rendered in voice-activation mode.

### 5. Mute semantics

Mute is a **voice-activation-mode-only** control. In PTT mode there is no self-mute control:
the hold button is the sole transmit control, not-holding = locally not transmitting (no
self-mute broadcast on press/release). Switching **into** PTT clears any existing self-mute
(broadcasts `selfMute=false`); the engine's mute-first check therefore sees `muted == false`
throughout PTT and the hold state governs. This keeps the protocol-level self-mute state honest —
a PTT user is genuinely not self-muted, just not currently transmitting.

### 6. Edge cases

- **Mode switch mid-call:** switching to PTT closes any open talkspurt with a terminator + resets
  the gate, and clears self-mute; switching back to voice activation starts from a closed gate.
- **Existing mute path:** unchanged in voice-activation mode; in PTT mode `muted` is held `false`
  by the mode-switch clear, so the mute-first branch is inert.
- **`connect()`** already resets `_muted = false`; `transmitMode` is loaded from prefs and
  preserved across connects.

### 7. Testing

New JVM unit tests in `AudioVoiceEngineTransmitModeTest.kt`, mirroring the fake harness in the
existing `AudioVoiceEngineFrameNumberTest.kt` (`ScriptedAudioIn` for per-capture amplitudes,
`FakeAudioOut`, `FakeOpusCodec`, `RecordingSuppressor`):

- PTT + not held → `nextOutgoingFrame()` emits nothing (no frames, no terminator spam).
- PTT + held → emits one frame per capture, `isTerminator == false`.
- PTT held→released edge → emits exactly one terminator frame, then silence.
- Mute (in voice-activation) still behaves as today; the mode-switch-to-PTT clears mute.
- Mode switch during an open talkspurt closes it cleanly.

No eval-corpus work: the transmit gate is bypassed in PTT, so the coverage/onset metrics don't
apply to this mode.

## Scope out (YAGNI)

- Hardware / Bluetooth PTT keys (MediaSession + media-button receiver + device-specific quirks) —
  its own future feature.
- PTT latch / toggle mode (tap to start, tap to stop).
- Haptics on press/release.

## Files touched (expected)

- `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitMode.kt` (new enum)
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt` (denoise-only path)
- `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`
- `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`
- `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`
- `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`
- `app/src/test/java/.../voice/AudioVoiceEngineTransmitModeTest.kt` (new, mirrors
  `AudioVoiceEngineFrameNumberTest.kt`)
