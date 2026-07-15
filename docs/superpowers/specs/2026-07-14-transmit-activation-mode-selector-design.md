# Transmit Activation-Mode Selector (Push-to-Talk / Voice-Activity) — Design

**Date:** 2026-07-14
**Status:** Approved (brainstorm), pending implementation plan
**Feature:** Let the user choose how the transmit path is activated — **Voice activity** (RNNoise VAD,
the current behavior) or **Push-to-talk** (transmit only while a call-screen button is held).

This is sub-project **1 of 3** in the "improve audio quality" effort. The others get their own
specs: (2) automatic gain control on the transmit path, (3) import + implement the "Mumble Call"
Claude Design for the call screen. See **Interaction with other sub-projects** below — sub-project 3
redesigns the call screen this spec adds a button to.

---

## Goal

Add a persisted **activation mode** setting with two values, `VOICE_ACTIVITY` and `PUSH_TO_TALK`,
selectable in Settings and applied live to the active call. In push-to-talk, audio transmits only
while the user holds a "Hold to Talk" button on the in-call screen. Default stays `VOICE_ACTIVITY`
so existing behavior is unchanged for current users.

## Non-goals (out of scope)

- **Automatic gain control** — the "too quiet" problem. Its own spec (sub-project 2). RNNoise denoise
  strictly attenuates and the transmit path has no makeup gain today; AGC is deliberately separate.
- **Hardware-key / background PTT** (volume key, headset/Bluetooth media button). v1 is on-screen,
  app-foreground only. Deferred; noted in Future work.
- **A "continuous / open-mic" third mode.** Trivial to add later (feed the gate a constant `1f`) but
  the user asked for exactly two modes. Not built.

---

## Architecture

**Chosen approach: the activation mode selects *what level feeds the existing `TransmitGate`*.**

The transmit path today is: capture 20 ms → for each of two 10 ms sub-frames run
`NoiseSuppressor.process` (RNNoise denoise, in place) then `VadDetector.level` → `TransmitGate.update`
→ Opus encode → send (with wall-clock `frameNumber` and real terminators). See
`AudioVoiceEngine.nextOutgoingFrame`.

Push-to-talk is simply "the level is 1.0 while I hold the button." So instead of forking the send
path, we change only the *source* of the per-sub-frame level fed into the gate we already trust:

| Mode | `subLevels[i]` source |
|------|-----------------------|
| `VOICE_ACTIVITY` | `vad.level(pcm, off, n)` — RNNoise VAD probability, exactly as today |
| `PUSH_TO_TALK` | `if (pttPressed) 1f else 0f` |

Everything downstream is **unchanged**: two-threshold hysteresis, the 200 ms hangover
(`maxHoldTicks`), the real (non-empty) terminator on close, and wall-clock `frameNumber`. RNNoise
`process` still runs in **both** modes — denoise is valuable regardless of how the gate is driven;
only its VAD *probability* is ignored under PTT.

Two consequences fall out for free:
- **Hangover in PTT:** releasing the button drives the level to 0, and the gate holds for ~200 ms
  before closing with a proper terminator — so a slightly-early release doesn't clip the word tail.
- **Terminator correctness:** the close-frame terminator path is identical to VAD close, so peers
  retire the stream correctly (no empty-payload packet, which Mumble drops before reading the flag).

Rejected alternatives:
- **Pluggable `TransmitActivation` seam** (a VAD impl + a PTT impl behind an interface). Matches the
  codebase's swappable-seam taste, but it re-implements or has to re-share the hysteresis/hangover/
  terminator logic for identical behavior — more surface for no functional gain.
- **Separate PTT send path** that bypasses `TransmitGate`. Forks the send logic into two paths that
  both must get terminators and `frameNumber` right. More maintenance risk.

### Threading

All `TransmitGate` mutation stays on the **send thread**, matching how mute already works:
- `AudioVoiceEngine` exposes `setActivationMode(m)` and `setPttPressed(pressed)`, both writing only
  `@Volatile` fields from the caller (UI) thread.
- The send thread reads the volatile `activationMode` at the top of each capture and, when it differs
  from a send-thread-local `lastActivationMode`, calls `gate.reset()` itself (so a mode switch starts
  the gate closed) — never resetting the gate from the UI thread.
- `pttPressed` is a plain volatile read inside the sub-frame loop.

---

## Components / files

### New: `app/src/main/java/me/danielstiner/dumble/mumble/voice/ActivationMode.kt`
```kotlin
package me.danielstiner.dumble.mumble.voice

/** How the transmit path decides when to send. */
enum class ActivationMode { VOICE_ACTIVITY, PUSH_TO_TALK }
```

### Modify: `AudioVoiceEngine.kt`
- Constructor gains `activationMode: ActivationMode = ActivationMode.VOICE_ACTIVITY`.
- Fields: `@Volatile private var activationMode = <ctor arg>`, `@Volatile private var pttPressed = false`,
  and a send-thread-local `private var lastActivationMode = <ctor arg>`.
- `fun setActivationMode(m: ActivationMode) { activationMode = m }` and
  `fun setPttPressed(pressed: Boolean) { pttPressed = pressed }`.
- In `nextOutgoingFrame`, after the mute block and before the sub-frame loop:
  ```kotlin
  val mode = activationMode
  if (mode != lastActivationMode) { gate.reset(); lastActivationMode = mode }
  ```
- The sub-frame loop's level assignment becomes:
  ```kotlin
  suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)          // denoise — both modes
  subLevels[i] = when (mode) {
      ActivationMode.VOICE_ACTIVITY -> vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
      ActivationMode.PUSH_TO_TALK   -> if (pttPressed) 1f else 0f
  }
  ```
  (No other change: `gate.update(subLevels)`, encode, terminator, `frameNumber` all as-is.)

### Modify: `MumbleManager.kt` (mirrors the existing `vadThreshold` pattern exactly)
- `_activationMode = MutableStateFlow(ActivationMode.VOICE_ACTIVITY)` + `val activationMode: StateFlow<ActivationMode>`.
- `init(context)`: load from `SharedPreferences("dumble_audio")` key `"activation_mode"` (stored as
  `ActivationMode.name`); unknown/missing → `VOICE_ACTIVITY`.
- `@Synchronized fun setActivationMode(mode: ActivationMode)`: set flow, persist the `.name`, call
  `active?.setActivationMode(mode)`.
- `fun setPttPressed(pressed: Boolean) { active?.setPttPressed(pressed) }` — **not persisted**
  (transient, per-press). No-op when there is no active session.
- `ActiveSession`: build the engine with `activationMode = _activationMode.value`; add
  `fun setActivationMode(m) = engine.setActivationMode(m)` and
  `fun setPttPressed(p) = engine.setPttPressed(p)` (next to `setMuted`).

### Modify: `ui/SettingsScreen.kt`
- New params: `activationMode: ActivationMode`, `onActivationModeChange: (ActivationMode) -> Unit`.
- At the top of the Voice section, a two-option selector (Material 3
  `SingleChoiceSegmentedButtonRow` with segments **Voice activity** / **Push to talk**; if that
  experimental API isn't in the app's M3 version, fall back to a `Row` of two `FilterChip`s or a
  `RadioButton` pair — same behavior).
- The existing "Sensitivity threshold" `Text` + `Slider` render **only when
  `activationMode == VOICE_ACTIVITY`** (the VAD threshold is meaningless under PTT).
- Imports the `ActivationMode` enum from the voice package (UI already depends on `MumbleManager`).

### Modify: `ui/ActiveCallScreen.kt`
- New params: `activationMode: ActivationMode`, `onPttDown: () -> Unit`, `onPttUp: () -> Unit`.
- When `activationMode == PUSH_TO_TALK`, render a large **"Hold to Talk"** button that reports press
  and release. Use `Modifier.pointerInput(Unit) { detectTapGestures(onPress = { onPttDown(); tryAwaitRelease(); onPttUp() }) }`
  so release is delivered on both normal lift and gesture-cancel (finger dragged off). Reflect the
  held state visually (e.g. filled/tonal + "Transmitting…" label).
- In `VOICE_ACTIVITY` mode the screen is unchanged (no PTT button).
- Mute + Speaker chips stay in both modes; **mute remains a hard override** (the engine's mute block
  runs before the activation logic, so a held PTT while muted transmits nothing but the mute
  terminator).

### Modify: `ui/DumbleApp.kt`
- `val activationMode by MumbleManager.activationMode.collectAsStateWithLifecycle()`.
- Pass to `SettingsScreen`: `activationMode`, `onActivationModeChange = { MumbleManager.setActivationMode(it) }`.
- Pass to `ActiveCallScreen`: `activationMode`, `onPttDown = { MumbleManager.setPttPressed(true) }`,
  `onPttUp = { MumbleManager.setPttPressed(false) }`.

---

## Data flow

```
Settings selector ──setActivationMode──▶ MumbleManager (persist + StateFlow)
                                              │
                                              └─▶ ActiveSession.setActivationMode ─▶ engine (@Volatile)
Call screen Hold-to-Talk ─press/release─▶ MumbleManager.setPttPressed ─▶ engine.pttPressed (@Volatile)

send thread (nextOutgoingFrame, per 20 ms capture):
  read activationMode  ──(changed?)──▶ gate.reset()
  per 10 ms sub-frame: RNNoise denoise ; level = mode==VAD ? vad.level : (pttPressed ? 1 : 0)
  gate.update(levels) ─▶ encode / terminator / send   (unchanged)
```

## Edge cases

- **Switch mode mid-talkspurt:** gate resets on the next capture → starts closed; a VAD talkspurt in
  progress emits its normal close terminator on the capture where the reset lands (no stuck-open
  stream). Acceptable; mode changes mid-utterance are rare and user-initiated.
- **PTT held across a mute toggle:** mute wins (engine mute block precedes activation). On unmute
  while still held, transmission resumes on the next capture.
- **PTT released app-backgrounded:** `tryAwaitRelease` guarantees `onPttUp` fires on gesture cancel;
  additionally, leaving the call screen should force `setPttPressed(false)` (belt-and-suspenders) so
  a backgrounded press can't latch the mic open. (Implementer: release on the call screen's
  `onDispose`/lifecycle stop.)
- **No active session** when the setting changes: persisted only; applied when the next session builds
  the engine from `_activationMode.value`.

---

## Testing

**Unit (JVM, no Android)** — new `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEnginePttTest.kt`,
following the existing `AudioVoiceEngineFrameNumberTest` fakes (`ScriptedAudioIn`, `FakeOpusCodec`,
`FakeAudioOut`). PTT ignores mic level, so these feed **silent** PCM and toggle `pttPressed`:
- `pttHeldTransmitsSilentMic`: mode PTT, `pttPressed = true`, silent mic → `nextOutgoingFrame` non-null
  (proves the gate opens on the button, not on audio level).
- `pttNotHeldDropsLoudMic`: mode PTT, `pttPressed = false`, **loud** mic → null every capture
  (proves audio level is ignored under PTT).
- `pttReleaseSendsOneRealTerminatorThenSilence`: hold 3 captures, release, then ≥20 silent captures →
  exactly one terminator (`length > 0`) followed by nulls (mirrors the VAD terminator test).
- `modeSwitchResetsGate`: drive VAD open with loud audio, `setActivationMode(PUSH_TO_TALK)` with
  `pttPressed = false` → next capture null (gate reset to closed).
- `mutedWinsOverHeldPtt`: `setMuted(true)` + `pttPressed = true` → first frame is the mute
  terminator, subsequent captures null.

**On-device (user acceptance gate):** in Settings pick **Push to talk**; join a call; confirm peers
hear you **only while the button is held**, the word-tail flushes on release (no clip), and switching
back to **Voice activity** resumes RNNoise auto-activation. Confirm the sensitivity slider is hidden
under PTT and shown under VAD.

---

## Interaction with other sub-projects

**Sub-project 3 (import the "Mumble Call" Claude Design) redesigns the in-call screen** — the same
`ActiveCallScreen` this spec adds a Hold-to-Talk button to. To avoid building the PTT affordance
twice, the button's **visual placement here is provisional**: if the imported design lands first or
concurrently, integrate the Hold-to-Talk control into that layout instead of the current one. The
**engine + settings** parts of this spec (`ActivationMode`, `MumbleManager`, `AudioVoiceEngine`,
`SettingsScreen`) are independent of the call-screen redesign and can proceed regardless. The
implementation plan should keep the call-screen button as the last, thin task so it can be re-homed
cheaply.

## Future work

- Hardware-key / headset / Bluetooth PTT that works with the screen off (needs key-event interception
  and a foreground service).
- Optional "continuous / open-mic" mode (`level = 1f` always).
