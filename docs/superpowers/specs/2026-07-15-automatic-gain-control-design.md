# Automatic Gain Control (transmit path) — Design

**Date:** 2026-07-15
**Status:** Approved (brainstorm), pending implementation plan
**Feature:** Fix the "too quiet" transmit level — **diagnosis-led**. Measure *why* the uplink is quiet
(what the platform is already doing, and where the level is lost) before adding gain, then apply the
minimal fix: a **makeup / recovery gain** after RNNoise that complements the platform's own AGC rather
than competing with it.

Sub-project **2 of 4** in the audio-quality effort. Depends on sub-project 4 (the `TransmitProcessor`
extraction is where the gain lands, and the eval harness is the measurement tool). Related: spec 1
(activation modes) and spec 4 (eval) share the same `TransmitProcessor`.

---

## Goal

The client transmits too quietly. Rather than assume and bolt on a second AGC, **measure the chain**,
locate the loss, and apply the smallest fix that hits a consistent target loudness — validated by
spec 4's loudness/consistency/clipping metrics.

## Why "another AGC" is the wrong framing (challenge to the assumption)

The `VOICE_COMMUNICATION` capture source **already enables a platform AGC** (plus AEC + NS) in the
HAL. Stacking a second closed-loop AGC on top would make the two chase each other and pump. So this
spec does **not** add a competing AGC. The real chain is:

```
mic → [platform HAL: AEC, NS, AGC] → AudioRecord → RNNoise (our code) → gate → Opus
```

The platform AGC normalizes the mic signal at the **front**; then **RNNoise attenuates downstream**
with nothing to recover it. Fable-verified (RNNoise `6cbfd53`): its per-band gains are sigmoid-bounded
**[0,1]** — output band energy never exceeds input, there is **no makeup-gain / normalization stage
anywhere**. And the loss is **SNR-dependent**: the platform AGC normalized the *noisy* signal to its
target; RNNoise strips the noise; the remaining speech now sits **below** target. Add a conservative
(telephony-style, device-variable) platform-AGC target, and the encoded signal is quiet — while the
receiver's `AudioMixer` *assumes* "each remote stream is already AGC-normalized to near full-scale."

So the justified fix is a **makeup/recovery gain after RNNoise**, not a competing AGC. But that is a
hypothesis until measured — hence Phase 0.

**Verified caveat that shapes the design:** RNNoise **bypasses very quiet frames at unity gain**
(total band energy < 0.04 → skips the gain block), so it does *not* attenuate quiet noise. A recovery
gain must therefore **freeze during non-speech** or it will ramp up that unattenuated quiet noise.

---

## Phase 0 — Diagnosis (measure before building)

Two complementary measurements; **neither adds user-facing behavior.**

1. **Platform effect state (on-device):** log `AutomaticGainControl.isAvailable()`/`getEnabled()`, and
   the same for `AcousticEchoCanceler` and `NoiseSuppressor`, on the live `AudioRecord` session — so we
   know what the HAL is actually doing on the test device (it's device-variable; it may be weak or off).
2. **Stage-by-stage loudness:**
   - **On-device (live):** RMS dBFS of the captured PCM **post-platform-AGC / pre-RNNoise**, and
     **post-RNNoise**, logged periodically — shows the real drop across RNNoise on this device.
   - **Harness (spec 4):** feed known-level corpus clips through RNNoise in isolation to quantify the
     attenuation RNNoise imposes vs input SNR (the corpus has no platform AGC — it isolates RNNoise).

**The measurement picks the fix:**
- **RNNoise is the drop** → a post-RNNoise **makeup/recovery gain** (below). *Most likely.*
- **Platform AGC is weak/off** → the `disablePlatformAgc` toggle + our gain as the sole controller.
- **Uplink is fine; it's actually downlink** → the `AudioMixer` full-scale assumption is the culprit —
  a **different fix** (out of scope here; flagged for its own item).

Phase 0 sets the concrete **target loudness** (what the receiver/mixer expects and what headroom
exists), replacing the placeholder below.

---

## The fix — makeup / recovery gain

A `GainControl` class (pure Kotlin), applied in `TransmitProcessor` **after** `suppressor.process`
(denoise) and **before** Opus encode (the "AGC last" order). It scales PCM only; the gate still decides
*when* to transmit from the RNNoise probability, so gain changes *how loud* we sound, never *when* we
open.

Per 10 ms sub-frame, in place:
- Track the speech level; adapt a smoothed linear `gain` toward `targetRms / level`, clamped to
  `[minGain, maxGain]`, with **asymmetric smoothing** (ramp up slowly, pull down faster) so it doesn't
  pump or overshoot.
- **Freeze adaptation when not speech** (RNNoise prob ≤ `adaptSpeechThreshold`) — required by the
  silence-bypass caveat: no gain ramp on unattenuated quiet noise.
- **Peak limiter** after gain (soft-knee, mirroring `AudioMixer`'s tanh knee) so makeup gain on a loud
  burst can't clip.
- It is a *recovery* gain (compensating a known, roughly-SNR-dependent downstream loss), not a second
  closed-loop AGC racing the platform's — that's why it complements rather than fights the platform AGC.

Live-tunable constants (defaults + `var`, dialed in against spec 4 — **no user-facing setting in v1**):
`targetRmsDbFS` (start ~-18, confirmed by Phase 0), `maxGainDb` (~+30), `minGainDb`, `attackMs`/
`releaseMs`, `limiterThreshDbFS` (~-1), `adaptSpeechThreshold`.

---

## Platform-AGC runtime toggle

A config flag `disablePlatformAgc` (a runtime/eval axis, **not** a user setting): when set, attach
`android.media.audiofx.AutomaticGainControl` to the capture session and `setEnabled(false)` — keeping
**AEC + NS** — so our recovery gain can be the sole gain controller. **Switchable; default chosen by
spec 4's metrics** (per the brainstorm decision). Graceful fallback: if `isAvailable()` is false or the
call throws, log and continue (our gain still applies; it just stacks). Plumbed
`MumbleManager → AudioVoiceEngine → AndroidAudioIn`.

---

## Components / files
- **New:** `mumble/voice/GainControl.kt` (pure Kotlin → runs in spec 4's harness + JVM unit tests).
- **Modify:** `TransmitProcessor` (add the gain stage after denoise, gated by prob); `AudioVoiceEngine`
  / `AndroidAudioIn` (Phase-0 stage-RMS logging + platform-effect state log + the `disablePlatformAgc`
  flag and audiofx handling); `MumbleManager` (plumb the flag).

## Testing
- **Unit (JVM):** gain converges to target on a steady tone; **freezes during silence** (no ramp on a
  noise-only input); limiter holds peaks below the threshold on a loud transient; gain stays within
  `[min,max]`; deterministic.
- **Spec 4 harness (the AGC scoreboard):** on corpus clips of **varying input level**, output loudness
  converges to target and **cross-clip consistency improves vs no-AGC**, with **zero clipping**. Also
  records the Phase-0 RNNoise-attenuation numbers.
- **On-device (user acceptance gate):** peers hear a consistent, adequately loud level with **no
  pumping/breathing**; quiet and loud talkers both land near target; compare `disablePlatformAgc`
  on/off using the logged levels.

## Scope / deferred
- The full `UNPROCESSED`-capture + WebRTC **AEC3 → NS → AGC2** overhaul (taking over echo cancellation)
  is a **separate future project**, not this fix.
- A **user-facing AGC on/off + strength setting** — deferred; defaults + tunable constants first.
- **Downlink loudness** (the `AudioMixer` full-scale assumption) — a distinct fix; Phase 0 will say
  whether it's implicated, but it is not built here.

## Interaction with other sub-projects
- **Spec 4 (eval):** provides the `TransmitProcessor` seam this gain lands in and the metrics that tune
  it and validate Phase 0. **Build spec 4 first.**
- **Spec 1 (activation modes):** shares `TransmitProcessor`; independent of the gain stage.
- The verified RNNoise attenuation + silence-bypass findings are also recorded in the spec-4 context;
  the load-bearing claim is now fable-verified against RNNoise `6cbfd53`.
