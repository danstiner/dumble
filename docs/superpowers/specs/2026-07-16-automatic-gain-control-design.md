# Automatic Gain Control (transmit path) — Design (refined)

**Date:** 2026-07-16
**Status:** Approved (brainstorm, fable-verified) — ready for plan
**Supersedes:** `2026-07-15-automatic-gain-control-design.md` (diagnosis-led draft; Phase-0 gate and
`disablePlatformAgc` toggle dropped after Mumble-source research + the "conventional target + user
setting" decision).
**Scope:** Sub-project **2 of 2** in the audio-quality arc. Sub-project 1 (the PTT ↔ voice-activation
transmit-mode selector) is merged to `main`.

---

## Goal

The client transmits too quietly. Add a **device-independent makeup gain after RNNoise** that
normalizes transmit loudness toward a conventional target (**−18 dBFS RMS**, user-tunable), so our
users sit at roughly the same level as other Mumble clients in a room without per-user fiddling.

**Loudness convention (used everywhere — `GainControl` and the eval harness must agree):** dBFS RMS
referenced to full-scale amplitude 32768, matching `VadEvaluator.speechLoudnessDbFs`
(`20·log10(rms/32768)`). A full-scale square wave = 0 dBFS RMS; a full-scale sine = −3 dBFS RMS.

## Why the level is lost (the chain)

Production capture path (`MumbleManager` → `AudioVoiceEngine`):

```
mic → [platform HAL on VOICE_COMMUNICATION: AEC + AGC (+ NS)] → AudioRecord → RNNoise (denoise + VAD) → gate → Opus
```

`MediaRecorder.AudioSource.VOICE_COMMUNICATION` requests the platform voice DSP — Android's docs name
**echo cancellation and AGC "if available"** (device-variable); NS is customary in device voice tuning
but not doc-guaranteed. So the platform AGC normalizes the **noisy** mic at the front. RNNoise then
strips the residual noise and pulls the speech **below** that target, with nothing after it to recover
the level.

**Fable-verified (RNNoise `6cbfd53`):** RNNoise's per-band gains are sigmoid-bounded to **[0,1]** (its
`pitch_filter` renormalizes band energy back to the input, then applies gains ≤ 1) — it can only
attenuate, never make up level, and there is **no normalization stage** in it. It also **bypasses
frames whose total band energy < 0.04 at unity gain** (and holds `vad_prob = 0` on those frames), so
it does not attenuate quiet noise. A makeup gain must therefore **freeze during non-speech** or it
would ramp that unattenuated noise up — and a prob-threshold freeze triggers on exactly the frames
RNNoise passes at unity.

## Approach — device-independent makeup gain (and why not the alternatives)

- **A — makeup gain after RNNoise (chosen).** An adaptive gain toward a target RMS **self-calibrates
  per device**: it measures the actual post-RNNoise speech level and drives it to target regardless of
  what the platform chain did upstream. This is the only option that satisfies the hard requirement:
  **works on any Android phone.**
- **B — bypass RNNoise, trust the platform.** Rejected: relies on the platform NS/AGC being good,
  which is exactly what varies across cheap/old devices.
- **C — make RNNoise conditional on platform-effect state.** Rejected as premature complexity; A
  already works everywhere, so C is deferred.

### Precedent — this is what mainline Mumble does

Verified against Mumble `master/src/mumble/AudioInput.cpp` + `Settings.h` (read directly from source):

- **Processing order (`encodeAudioFrame`):** echo-cancel (~L904) → **RNNoise denoise** (~L910–924) →
  **Speex preprocessor incl. AGC** (L926). Mumble's gain lands **after** RNNoise. With RNNoise as the
  selected canceller, Speex's own denoise is off but **AGC stays on** — a clean *denoise → makeup
  gain*, no double-denoise.
- **AGC config (`resetAudioProcessor`, ~L758–765):** `setAGCTarget(30000)` — a Speex internal
  *loudness*-domain target (perceptually weighted, `LOUDNESS_EXP=5`), **not** a portable output RMS;
  `setAGCMaxGain(floor(20·log10(30000 / iMinLoudness)))` with default `iMinLoudness = 1000` →
  `floor(29.54)` = **+29 dB** ceiling; `setAGCDecrement(-60)` (gain may fall ≤ 60 dB/s).
- **Freeze-on-silence (~L1053–1080):** `setAGCIncrement(0)` when `!bIsSpeech && !bPreviousVoice`
  (no upward ramp when idle), else `setAGCIncrement(12)` (≤ 12 dB/s up while speaking). Mumble zeroes
  only the *increment* — its −60 dB/s **decrement still applies during silence**, so Mumble's freeze
  is **upward-only**.

Takeaways we adopt: gain **after** RNNoise, **freeze during non-speech**, **asymmetric** (up slow /
down fast), **~+30 dB** max gain. Nuance: our baseline **fully** freezes during non-speech, which is
*stricter than* Mumble's upward-only freeze (simpler; safe because RNNoise passes those frames at
unity anyway) — so we don't claim to mirror Mumble exactly here. What we do **not** copy: the `30000`
value (Speex loudness units, and Mumble desktop has no platform AGC in front of it); our target is set
in our own dBFS-RMS units below.

## The fix — `GainControl`

A new `GainControl.kt` (pure Kotlin → runs in JVM unit tests **and** the eval harness), applied per
10 ms sub-frame **in place** inside `TransmitProcessor`, **after** the RNNoise denoise and **before**
Opus encode. It scales PCM only; the **gate still decides *when* to transmit** from the RNNoise
probability (computed pre-gain), so the gain changes *how loud* we sound, never *when* we open.

**Applied in both transmit modes.** `TransmitProcessor.process()` (voice-activated) and `denoise()`
(push-to-talk) both get the gain stage — a quiet talker is quiet in PTT too. The per-sub-frame RNNoise
probability drives the freeze decision; it is read through `TransmitProcessor`'s existing `vad`
field (`vad.level(...)` returns `RnnoiseSuppressor.lastVadProb`), so `denoise()` calls `vad.level()`
after `suppressor.process()` the same way `process()` already does — no interface change to
`NoiseSuppressor`.

**Per 10 ms sub-frame:**
1. Compute the sub-frame RMS.
2. **If speech** (RNNoise prob ≥ `adaptSpeechThreshold`): `desired = targetRmsLinear / rms`, clamped
   to `[minGainLinear, maxGainLinear]` (all linear; `targetRmsLinear` is the linear form of the dBFS
   setting); move `gain` toward `desired` with **asymmetric rate limiting** — `increaseRate` ~12 dB/s
   when raising gain, `decreaseRate` ~60 dB/s when lowering it (mirroring Mumble) — so it neither
   pumps nor overshoots.
3. **If not speech:** **freeze** `gain` (no adaptation) — required by the RNNoise silence-bypass
   caveat.
4. Apply `gain` to the sub-frame samples, then a **soft-knee limiter** (tanh knee, mirroring
   `AudioMixer`) so makeup gain on a loud burst can never clip.

(Naming note: `increaseRate` / `decreaseRate` are used deliberately instead of "attack/release" —
compressor "attack" conventionally means gain *reduction*, the opposite direction, so those terms
would mislead.)

**Bidirectional.** The gain boosts quiet speech **and trims hot speech** toward the target (within
`[minGain, maxGain]`) — that two-way normalization is what actually levels a room, and it is what
Mumble's AGC does.

**Why it is safe to stack on the platform AGC** (the old spec's pumping worry): the platform AGC acts
on the **raw mic**; `GainControl` acts on the **post-RNNoise** signal — a **series cascade with no
path from our output back to the platform AGC's input, so it cannot form a feedback loop and cannot
oscillate.** Two honest caveats it does *not* eliminate: (a) two cascaded level controllers can still
**compound transient gain modulation** (breathing during convergence) with a double time-constant; and
(b) RNNoise between them is **not scale-invariant** (its log-energy features and the absolute
`E < 0.04` bypass shift its operating point as upstream gain changes). Both are damped by the slow
increase rate + non-speech freeze, and the **on-device A/B is the empirical check** (listen for
pumping/breathing).

## Target loudness

Default **`targetDbFs` = −18 dBFS RMS**. Rationale — two anchors that agree:

- **Receiver headroom (code fact).** `AudioMixer` sums streams and soft-limits with a knee at
  `THRESHOLD = 26214 = 0.8 × full scale` (20·log10(0.8) = **−1.94 dBFS**), unity below; its KDoc
  assumes each stream is "already AGC-normalized to near full-scale." Speech has a ~12–18 dB
  peak-to-RMS crest factor, so `target_RMS ≈ ceiling − crest ≈ −1.9 − 15 ≈ −17 dBFS` keeps a **single
  stream's** peaks under the knee. −18 is a safe midpoint. (The −1.9 knee bounds the **mixed sum** —
  double-talk with two −18 dBFS streams *will* engage the tanh knee, which is exactly what it's for;
  the derivation only guarantees a single stream sits in the unity region.)
- **Interop intent.** Matching a conventional loud-VoIP level (Mumble targets *hot* loudness with a
  limiter and a ~+30 dB max-gain cap) keeps us level with other clients in a room.

The exact value is **tuned by ear on-device** against a live Mumble client; the setting below makes
that iteration a slider, not a rebuild. Worked example: `dev-other-700` at −31.5 dBFS needs +13.5 dB
to reach −18 (within the +30 dB cap).

## Settings (user-facing, v1)

Mirrors the existing `vadThreshold` plumbing (StateFlow-mirror persistence → live-applied to the
engine → slider in Settings).

- **Transmit-loudness target** — slider. `MumbleManager._agcTargetDbFs` StateFlow (default −18f),
  `setAgcTargetDbFs()` coerces to **[−30, −9] dBFS**, persists to `"dumble_audio"` key
  `"agc_target_dbfs"`, forwards `active?.setAgcTargetDbFs()`; loaded in `init()`.
- **AGC on/off** — toggle. `MumbleManager._agcEnabled` StateFlow (default `true`), `setAgcEnabled()`
  persists key `"agc_enabled"`, forwards `active?.setAgcEnabled()`. When off, `GainControl` is a
  bit-exact passthrough. Present so on-device A/B (AGC vs no-AGC) is one tap, not a rebuild.
- `ActiveSession` delegates both to the engine (like `setVadThreshold`); `AudioVoiceEngine` exposes
  `setAgcTargetDbFs()` / `setAgcEnabled()` → `GainControl`; `SettingsScreen` gains the slider + toggle;
  `DumbleApp` collects both StateFlows and wires the callbacks.

## Tunable constants (`var` defaults, dialed against the eval harness + on-device)

`targetDbFs` (−18), `maxGainDb` (+30), `minGainDb` (−12), `increaseRateDbPerSec` (~12),
`decreaseRateDbPerSec` (~60), `adaptSpeechThreshold` (RNNoise prob, ~0.5), `limiterThreshDbFs` (~−1.9,
matching the mixer knee).

## Testing

- **JVM unit (`GainControlTest`):** converges to target on a steady tone; **freezes on noise-only
  input** (no gain ramp when prob < threshold); limiter holds peaks below `limiterThresh` on a loud
  transient; gain stays within `[minGain, maxGain]`; `enabled = false` is bit-exact passthrough;
  deterministic.
- **Eval harness (the AGC scoreboard):** on corpus clips of varying input level, output
  `speechLoudnessDbFs` converges toward −18 and **cross-clip consistency improves vs no-AGC**, with
  **zero clipping**. The harness already computes `speechLoudnessDbFs` and `clipping`, so this drops in.
- **On-device (acceptance):** A/B against a live Mumble client using the on/off toggle; nudge the
  target slider until we match; confirm **no pumping/breathing**; quiet and loud talkers both land near
  target.

## Files touched

- **New:** `mumble/voice/GainControl.kt`; `test/.../voice/GainControlTest.kt`.
- **Modify:** `TransmitProcessor` (gain stage in both `process()` and `denoise()`, prob via `vad`
  field); `AudioVoiceEngine` (construct + wire `GainControl`, `setAgcTargetDbFs`/`setAgcEnabled`);
  `MumbleManager` (two StateFlows + persistence + `ActiveSession` delegation); `SettingsScreen` (slider
  + toggle); `DumbleApp` (collect + wire); `VadEvaluator`/eval test (AGC-on scoreboard assertions).

## Scope out / deferred

- **`disablePlatformAgc`** toggle — dropped; the adaptive gain self-handles weak *or* strong platform
  AGC. Deferred, not built.
- **Conditional RNNoise (option C)** — deferred; A works everywhere.
- **Full `UNPROCESSED` + WebRTC AEC3/NS/AGC2 overhaul** — separate future project.
- **Downlink loudness** (the `AudioMixer` near-full-scale assumption) — a distinct fix; not built here.

## Verified claims (fable, 2026-07-16, against actual source)

1. **RNNoise attenuation-only + silence-bypass at unity, vad_prob=0 on bypass** — CONFIRMED (xiph
   rnnoise `6cbfd53`: sigmoid gains, energy-preserving `pitch_filter`, `E<0.04` bypass). Justifies the
   non-speech freeze.
2. **Mumble: order (RNNoise→AGC), target 30000, max gain floor(20·log10(30))=29 dB @ iMinLoudness=1000,
   decrement −60 dB/s, increment 0-when-idle / 12-when-speaking (upward-only freeze); 30000 is a Speex
   loudness-domain target, not portable RMS** — CONFIRMED (`AudioInput.cpp`, `Settings.h`,
   speexdsp `preprocess.c`).
3. **`AudioMixer` knee 0.8 FS ≈ −1.94 dBFS, near-full-scale assumption** — CONFIRMED (`AudioMixer.kt`).
4. **Crest-factor target derivation (−18 dBFS reasonable)** — CONFIRMED with the single-stream-vs-mix
   note above; keep the dBFS-RMS convention consistent with the eval harness.
5. **Cascaded platform-AGC + GainControl cannot oscillate** — CONFIRMED as "no feedback loop";
   qualified to acknowledge transient gain-modulation + RNNoise non-scale-invariance (wording folded in).
6. **`VOICE_COMMUNICATION` requests AEC/AGC "if available" (NS customary, not doc-guaranteed)** —
   CONFIRMED framing.
