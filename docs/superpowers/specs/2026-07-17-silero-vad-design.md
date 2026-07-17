# Silero v6 VAD Engine — Design

**Date:** 2026-07-17
**Status:** Approved (fable-verified; pending user review before planning)
**Feature:** Add Silero v6 as a selectable voice-activity-detection engine alongside the
existing Energy and RNNoise VADs, behind the existing `VadDetector` seam.

## Goal

Ship Silero VAD v6 as a first-class, user-selectable, persisted voice-detection engine, running
on-device via ONNX Runtime and comparable against RNNoise through the existing eval harness so the
default is chosen from measured numbers.

## User decisions (already made)

- **Scope:** Full production engine — persisted VAD-source selector in Settings, polished now, with
  intent that Silero could become the default.
- **Version:** Silero **v6** (latest v6.2.1, 2026-02-24). Same input contract as v5 (fixed 512-sample
  @16 kHz windows), better accuracy, and a reduced-If ("ifless") ONNX export for mobile.
- **Runtime:** ONNX Runtime Android (full) — `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
  (AAR) for the app + `com.microsoft.onnxruntime:onnxruntime:1.27.0` (JVM jar) for host eval tests.
- **Default engine:** Decide after eval numbers — build it selectable, run the eval comparison as part
  of this work, pick the default from measured metrics before merging.
- **VAD input:** Silero runs on the **raw mic** (pre-denoise) in production, because Silero is trained
  noise-robust and denoise-before-VAD clips consonants and pushes the signal out of distribution
  (Silero maintainer guidance, disc #614/#172; fable DSP analysis 2026-07-17). **The eval harness
  compares raw vs denoised Silero input** so the coherence hypothesis (gate on what's transmitted) is
  tested on numbers; a production runtime knob is added only if denoised measurably wins.
- **RNNoise default:** flip to **OFF** — with Silero-on-raw, RNNoise is purely a TX-quality stage
  (doesn't touch detection), and the `VOICE_COMMUNICATION` capture path already denoises on most
  devices, so defaulting it off avoids double-denoising. RNNoise stays a user toggle. **Follow-up:**
  auto-enable RNNoise when the platform NS probe reports *no* denoiser present (deferred; see below).
- **Switching:** Live-switch mid-call — hot-swap the detector without reconnecting, matching the app's
  existing live-tunable controls.
- **Onset latency:** add a **configurable lookahead delay** (K, user-tunable, **default low/off**) so the
  onset-clip-vs-latency tradeoff is in the user's hands — the jitter-safe fixed-delay-queue form (fable
  2026-07-17), NOT a pre-roll burst. The 200 ms hangover is a separate *tail* knob, unchanged.

## Background: the current VAD seam

`VadDetector { fun level(pcm: ShortArray, off: Int, n: Int): Float }` (0..1 speech level per 10 ms
sub-frame, send-thread only) is a **separate interface** from `NoiseSuppressor`. Today production wires
one object as both — `AudioVoiceEngine(suppressor = rnnoise, vad = rnnoise)` — where
`RnnoiseSuppressor.level()` returns the VAD probability RNNoise computed as a denoise byproduct.

`TransmitProcessor` drives it per 10 ms sub-frame (480 samples @48 kHz):

```
suppressor.process(capturePcm, off, 480)      // denoise in place
val prob = vad.level(capturePcm, off, 480)     // → gate + makeup gain
```

The audio path is 48 kHz mono PCM16 end to end (Opus + RNNoise both want 48 kHz). Silero slots in as
`vad = silero` while `suppressor = rnnoise` stays for (now optional) denoising. The only engine/processor
changes are (a) feeding the VAD the **raw** sub-frame and (b) a lifecycle `reset()` for the stateful
detector — both minimal, detailed below.

## Platform audio pipeline (already in place — verified)

fable's Android-side checklist is largely already satisfied, and it's why RNNoise-default-off is safe:

- **AEC loopback reference correct:** playback (`AndroidAudioOut`) uses `USAGE_VOICE_COMMUNICATION`,
  capture uses the `VOICE_COMMUNICATION` source, and `CallManager` sets `MODE_IN_COMMUNICATION` + audio
  focus on the voice-comm path — so hardware AEC has the reference it needs (not the media path).
- **Per-device effect probe:** `PlatformAudioEffects` attaches AEC/NS/AGC by session ID and logs
  `isAvailable()`/`getEnabled()` (read-only), surfaced in the diagnostics screen. This is the same NS
  probe the RNNoise auto-enable follow-up would consume — with the standing caveat that `getEnabled()`
  is a self-report, "not guaranteed... stage RMS is the ground truth."
- **AGC creep diagnostic:** platform AGC rides the noise floor up during silence; if Silero's
  probability creeps *between* utterances, that's the AGC, not a threshold bug. The eval/diagnostics
  plan logs per-frame Silero probability across conditions (quiet / fan / keyboard / speakerphone
  double-talk) to distinguish these.

## Silero v6 technical contract (fable-verified against the actual model binaries)

Verified by running the models under ORT 1.27.0 (bit-exact equivalence + benchmarking), plus upstream
`src/silero_vad/utils_vad.py`:

- **Model files (all bit-exact identical outputs):** `silero_vad_op18_ifless.onnx` (2.85 MB, reduced-If
  — 1 If node vs 25 nested, mobile-recommended), `silero_vad_16k_op15.onnx` (1.29 MB, 16k-only),
  standard `silero_vad.onnx` (2.33 MB, 25 Ifs). **License: MIT.**
- **Bundle choice:** ship the smallest, `silero_vad_16k_op15.onnx` (~1.3 MB, 16k-only) — fable verified
  it produces bit-exact outputs. Because a 16k-only export may drop the `sr` input, the detector
  validates the model's actual input names via `getInputInfo()` at load and builds the input map
  accordingly (`input`/`state` always; `sr` only if the model declares it), backstopped by the golden
  self-test. Fallback if it won't load cleanly on ORT Android: the fully-characterized
  `silero_vad_op18_ifless.onnx` (2.85 MB; `input`/`state`/`sr`, reduced-If). All three are numerically
  equivalent (still `[1,576]` = 64 context ⊕ 512 window).
- **Fixed window:** exactly **512 samples @16 kHz** (32 ms) per inference. No internal buffering — the
  caller buffers and windows.
- **Stateful streaming:** carries a recurrent `state` `float32 [2,1,128]` **plus** a 64-sample context
  (last 64 samples of the previous chunk). Reset = zero **both** (missing the context leaks ~4 ms of
  pre-discontinuity audio). Reset only on discontinuities, not per chunk.
- **ONNX I/O:** inputs `input` `float32 [1,576]` (= 64 context ⊕ 512 chunk), `state` `float32 [2,1,128]`,
  `sr` `int64` scalar = 16000. Outputs `output` (prob `[1,1]`) + `stateN` (`[2,1,128]`). Carry:
  `state = stateN`; `context = input[..., -64:]`.
- **⚠ Silent-failure trap:** the graph declares the sequence dim as **dynamic**, so ORT input metadata
  reports it as `-1`/'sequence' — `getInputInfo()` **cannot discover the 576 width**, and feeding
  `[1,512]` **runs without error but returns collapsed probabilities** (~0.001 where truth is ~0.97).
  So: validate input *names/dtypes* via `getInputInfo()`, **hardcode width = 64 + 512 = 576** from the
  upstream contract, and run a **golden-vector startup self-test** (known 576-wide input → assert prob
  in expected range) to catch a wrong width.
- **Cost:** ~0.09 ms/inference measured (intra-op = 1); < 1 ms on a modern Android CPU (~260k params).

## Architecture

A self-contained **`SileroVadDetector : VadDetector`** owns everything Silero-specific: the ORT session,
a 48 k→16 k decimator, a 512-sample ring buffer, the recurrent state/context, the probability hold, and
`reset()`. It honors the existing `level()` contract and returns 0..1 into the existing `TransmitGate`.
`TransmitProcessor`/`AudioVoiceEngine` gain only the raw-input snapshot, the `reset()` seam, and a live
detector-swap.

Rejected: resampling the whole capture path to 16 kHz (invasive; 48 kHz needed for Opus/RNNoise);
inference on a separate thread (unnecessary at <1 ms; breaks the single-thread `level()` contract).

## Components

### 1. `VadDetector.reset()` — seam addition

Add `fun reset() {}` (default no-op). `EnergyVadDetector`/`RnnoiseSuppressor` ignore it.
`SileroVadDetector` overrides it to zero its ring buffer, decimator history, `state`, **and** `context`.
`AudioVoiceEngine` calls `vad.reset()` at `start()`, on unmute (mute→unmute edge), and on transmit-mode
change — the discontinuity points (which already reset the gate).

### 2. Raw-input snapshot in `TransmitProcessor`

The VAD sees the raw mic, not the denoised buffer. In both `process()` and `denoise()` per-sub-frame
loops, snapshot the raw sub-frame *before* `suppressor.process()`:

```
System.arraycopy(capturePcm, off, rawFrame, 0, FRAME_SAMPLES_10MS)  // raw snapshot
suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)             // denoise in place (if enabled)
val prob = vad.level(rawFrame, 0, FRAME_SAMPLES_10MS)               // VAD on RAW
gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)            // gain on denoised
```

`rawFrame` is a reused `ShortArray(FRAME_SAMPLES_10MS)` (send-thread only). We still transmit the
denoised `capturePcm` — detect on raw, send cleaned. Safe for the other engines: `RnnoiseSuppressor.level()`
ignores its buffer arg (prob already from `process()`), so RNNoise-as-VAD is unchanged; `EnergyVadDetector`
shifts denoised→raw (not in the eval regression, and raw is arguably more consistent for it too).

The `denoise()` (PTT) path reads `vad.level()` only when gain is enabled; Silero's stateful `level()`
is thus un-advanced during PTT — harmless (gate closed in PTT) and cleaned by the `reset()` on the
PTT→VA mode change.

### 3. `Decimator` — 48 kHz → 16 kHz

Integer 3:1 decimation (48000 / 16000 = 3 exactly) via a **polyphase anti-alias FIR**: a windowed-sinc
low-pass, **~7 kHz passband edge**, **24–36 taps → >60 dB stopband**, then take every 3rd sample.
480 samples in → 160 out per call. Stateful (FIR tap history across calls); cleared on `reset()`.
Kotlin on the send-thread side-chain — per-sample cost negligible next to inference.

**Do not stride-decimate, and do not settle for boxcar averaging.** Stride decimation applies zero
anti-aliasing (all 8–24 kHz folds into baseband at full amplitude); length-3 averaging is marginally
better (nulls at 16/32 kHz but only −3.5 dB at the 8 kHz fold edge). The failure mode this guards
against is **broadband HF noise — keyboard clicks, mic hiss, fan harmonics — aliasing into the speech
band and inflating false positives**, negating the noise robustness we adopt v6 for. (Speech's own HF
aliasing down is benign — the model tolerates it; Silero's JIT itself stride-decimates. It's the
*noise* aliasing we must not hand it.) See fable DSP analysis 2026-07-17. Deferred alternative if the
Kotlin FIR proves insufficient: libspeexdsp's resampler (used by desktop Mumble) via CMake/JNI.

### 4. `SileroVadDetector` — the detector

Per `level(pcm, off, 480)`:

1. **Decimate** 480 → 160 samples @16 kHz; append to the ring buffer.
2. **Window + infer:** while ≥ 512 samples buffered, consume a 512-sample chunk, prepend the carried
   64-sample context → `input [1,576]`, run ORT with current `state`; read prob + new `state`; set
   context = chunk's last 64 samples. (~1 inference per 3.2 sub-frames.)
3. **Hold:** return the most recent probability; between inferences `level()` returns the last value,
   so the gate gets a value every 10 ms.
4. Convert `Short`→`Float` normalized to [-1, 1] (÷32768) for the model input.

Added latency: up to ~32 ms worst-case (window fill; ~16 ms avg) + <1 ms inference + the model's own
onset lag — acceptable given the gate's hangover smoothing.

`reset()` zeros ring buffer, decimator, `state`, `context`, held probability. `close()` releases the
ORT session/env.

### 5. ORT session + model asset

- Bundle the model under `app/src/main/assets/silero_vad_16k_op15.onnx` (~1.3 MB; fallback
  `silero_vad_op18_ifless.onnx`). The detector reads the model's declared input names via `getInputInfo()`
  and builds the ORT input map accordingly (omit `sr` if the 16k-only export doesn't declare it).
- Load off the send thread: read asset bytes, `OrtEnvironment.getEnvironment().createSession(bytes, opts)`
  with `setIntraOpNumThreads(1)` (tiny model; extra threads add jitter). **`SessionOptions` must be
  configured before `createSession` — later changes have no effect.**
- **RT-thread resource discipline** (fable): `OrtSession.Result` is `AutoCloseable` over native memory
  — **copy `stateN` out before closing the `Result`** (or use the `pinnedOutputs` overload); **reuse
  direct-buffer `OnnxTensor`s** rather than allocating per call. Single session + single send-thread is
  thread-safe.
- **Startup golden self-test:** run a known 576-wide input through the session and assert the output
  probability is in the expected range, catching the silent wrong-width failure and a bad asset.

### 6. `MumbleManager` wiring + live-switch

- Persist the engine in `dumble_audio` prefs as `vad_engine` (`"energy" | "rnnoise" | "silero"`), read
  at init. Default `"rnnoise"` until the eval comparison picks the winner.
- **Flip `rnnoise_enabled` default to `false`** (both the `_rnnoiseEnabled` initial value and the
  persisted `getBoolean("rnnoise_enabled", false)` default).
- Expose `MumbleManager.setVadEngine(engine)` + `vadEngine: StateFlow<String>` mirroring the existing
  `rnnoiseEnabled`/`vadThreshold` pattern; persist on change.
- `AudioVoiceEngine.setVadDetector(vad)`: build + model-load the new detector off the send thread, then
  publish it to `TransmitProcessor` (its `vad` becomes a `@Volatile var`); the send thread's next
  `level()` reads the new one; the old detector's `close()` runs after the swap.
- `suppressor = rnnoise` stays always → the RNNoise denoise toggle remains independent of VAD choice.

### 7. Settings UI

Add a **"Voice detection engine"** selector (Energy / RNNoise / Silero) to `SettingsScreen`, following
existing control patterns. Keep the RNNoise denoise toggle (now default off) and the VAD-threshold
slider. Add a **lookahead-delay** slider (~0–100 ms, default 0) for onset recovery (§10).

### 8. `VadDebugActivity`

Extend `vadNames` from `arrayOf("Energy", "RNNoise")` to include `"Silero"` and add the Silero branch to
the live probability display (the `// Silero to be added` scaffold anticipates this).

### 9. Eval-harness integration (the "decide after eval" payoff)

- Add `testImplementation("com.microsoft.onnxruntime:onnxruntime:1.27.0")` (desktop JVM).
- Parametrize `VadEvaluator.evaluate` over a detector factory (default keeps `RnnoiseSuppressor`). Add a
  `SileroVadDetector` path loading the model from `app/src/main/assets/…onnx` (resolvable from the test
  working dir). Corpus is already 48 kHz; Silero resamples internally.
- **Compare three configs:** RNNoise, Silero-on-raw, Silero-on-denoised — emit a comparative
  `metrics.md` (coverage / onset / mid-dropout / false-openings) in `build/reports/vad-eval`. This
  picks the default engine, the Silero threshold, and settles raw-vs-denoised. Keep the regression
  *asserts* on RNNoise only; Silero is reported, not asserted, until its threshold is chosen.

### 10. Lookahead delay queue + gate lookahead (onset recovery)

Engine-agnostic TX-path addition to recover clipped talkspurt onsets (Silero's ~32 ms window + the
model's threshold-cross lag clips ~30–70 ms of each talkspurt — the first plosive/syllable). **This is
the fixed-lookahead-delay form (jitter-safe), NOT a pre-roll burst** — replaying past frames at
gate-open makes them arrive late for their sequence position, so the receiver's jitter buffer stalls or
drops the talkspurt (fable 2026-07-17).

Mechanism (in `AudioVoiceEngine.computeOutgoing`):
- Each tick, process the live capture immediately (denoise/gain/VAD as today) and compute its live gate
  `open` boolean, but **don't emit** — push {processed PCM, frameNumber, open} into a **K-capture delay
  ring**.
- Emit the ring's **oldest** capture, ORing the `open` booleans across the ring window: when the gate
  first opens on the newest capture, the buffered pre-onset captures transmit too — the onset goes out
  intact, in-order, with contiguous (K-shifted) frame numbers. The existing hangover handles the tail.
- **K in milliseconds, quantized to the 20 ms capture granularity** (ring depth = K / 20 ms captures),
  exposed as a setting (~0–100 ms). **Default 0 = identity** (ring depth 0 → exactly today's behavior;
  a regression test pins this).
- **Latency:** a constant K ms added mouth-to-ear, only when K > 0 (default 0 adds nothing; fable's
  suggested 40–60 ms fits a ~150 ms interactivity budget).

Edge cases (all preserve the wall-clock `frame_number` invariant — a constant K-shift is fine): mute,
transmit-mode change, and stop must **flush** buffered captures, then emit the terminator and clear the
ring; the emitted `frameNumber` is the delayed capture's (contiguous). At K = 0 none of this engages.

This component is engine-agnostic (helps RNNoise onset too) and is built/tested as its own unit so K=0
is a provable no-op.

## Threshold & gate

The gate already has two-threshold hysteresis (open `vadThreshold` / close 0.35) + 200 ms hangover,
matching fable's guidance (~0.5–0.6 open / lower release / 300–700 ms hangover — our hangover is a touch
short). Keep a **single shared `vadThreshold`**; land good per-engine values (Silero likely wants a
different close level / longer hangover) from the eval. **Per-engine persisted threshold/hangover** is a
noted follow-up, not v1.

## Data flow (Silero selected, VOICE_ACTIVATED, RNNoise off by default)

```
mic 48k ─► capturePcm ─┬─► rawFrame snapshot ─► SileroVadDetector.level()
                       │                          ├─ decimate 48k→16k (÷3, anti-aliased)
                       │                          ├─ ring buffer → 512-sample windows
                       │                          ├─ ORT infer (state+context) → prob
                       │                          └─ hold prob
                       └─► RNNoise.process() (denoise in place, if enabled) ─► gain ─► Opus ─► send
                                                     prob ─► TransmitGate ─► send/terminator decision
```

## Error handling

- **Model load / self-test failure** (missing asset, ORT init error, golden-vector out of range): log,
  do not swap; keep the current engine. On startup with persisted `"silero"` that fails, fall back to
  RNNoise/Energy and surface it (log + optional snackbar); selecting Silero when it can't load reverts
  the setting.
- **ORT inference exception** in `level()`: never crash the send thread — catch, return the last held
  prob, log rate-limited; repeated failures auto-fall-back via `setVadDetector`.
- `close()` idempotent; safe after a failed load.

## Threading / real-time

`level()` on the send thread; inference <1 ms per 32 ms window. Model load + detector construction off
the send thread. `TransmitProcessor.vad` is a `@Volatile var` published after the new detector is fully
built; the old detector closes after the swap so no in-flight `level()` touches a released session.
ORT `Result`/tensor discipline as in §5.

Our send thread reads `AudioRecord` with **blocking reads** — not an Oboe/AAudio real-time callback — so
running the 0.09 ms inference inline is safe. If/when we migrate to Oboe/AAudio callback capture
(separate TODO), move Silero to a worker thread fed by a lock-free queue with the gate state passed back
as an atomic (fable) — never inference inside the audio callback.

## Build / dependency changes

- Via `libs.versions.toml`: `implementation` `onnxruntime-android:1.27.0`; `testImplementation`
  `onnxruntime:1.27.0`. (Verify versions against `repo1.maven.org` metadata — search.maven.org's index
  is stale, showing 1.22.0.)
- New asset `app/src/main/assets/silero_vad_16k_op15.onnx` (~1.3 MB; fallback ~2.85 MB reduced-If).
- **APK size:** +ORT native libs (~7 MB / arm64, more across ABIs) + model. Mitigated by per-ABI App
  Bundle splits; reduced-ORT build is a deferred size follow-up.
- **16 KB page size (Android 15+):** app already passes `-Wl,-z,max-page-size=16384` for its own lib;
  verify the prebuilt ORT 1.27 `.so` is 16 KB-aligned on a Pixel with 16 KB pages (ORT ≥1.20 supports
  this — verify on-device).

## Testing

- **Decimator:** >8 kHz tone → strong attenuation; speech-band tone → passthrough + correct 3:1 count;
  continuity across calls.
- **Ring buffer / windowing / hold:** assert inference cadence (~every 3.2 calls), that `level()` holds
  the last prob between inferences, exact 512-sample consumption.
- **`reset()`:** state, context, buffer, decimator all re-zeroed (post-reset output == fresh detector).
- **ORT golden test** (host JVM, desktop jar, real asset): known 576-wide input → prob in expected
  range; silence → low, speech → higher; output `[1,1]`, `state` `[2,1,128]`; determinism.
- **Wrong-width guard test:** feeding `[1,512]` produces collapsed prob → the golden self-test catches
  it (regression guard for the silent-failure trap).
- **Eval comparison:** RNNoise vs Silero-raw vs Silero-denoised → comparative `metrics.md`. RNNoise
  asserts stay as the regression guard.
- **`setVadDetector` swap:** swapping detectors on a running `TransmitProcessor` routes later `level()`
  to the new detector and closes the old.
- **Lookahead delay (§10):** K=0 emits byte-identical to the no-delay path (identity regression); K>0
  transmits the buffered pre-onset captures when the gate opens (onset recovered) with contiguous frame
  numbers; mute / mode-change / stop flush the ring + emit the terminator correctly.

## Open items / follow-ups (out of scope for v1)

- Flip default to Silero if eval + on-device justify it.
- **Auto-enable RNNoise when the platform NS probe reports no denoiser present** (the conditional your
  double-denoising idea pointed at, in the safe "enable when absent" direction; deferred because it
  rests on the `getEnabled()` self-report our own code flags as unreliable — needs on-device
  corroboration, e.g. stage RMS).
- Per-engine persisted threshold/hangover (vs the single shared `vadThreshold`).
- APK-size optimization via reduced ORT build (+ `.ort` model).
- Onset pre-roll / `speech_pad_ms`-style tuning (existing deferred VAD tasks) revisited with Silero.

## Risks

- **Silent wrong-width failure** — mitigated by hardcoded 576 + golden startup self-test + the
  wrong-width regression test (do NOT rely on ORT metadata; the width is dynamic).
- **ORT native lib / 16 KB page alignment** on newest Android — verify on-device early.
- **Resampler quality** — a too-cheap FIR aliases noise into speech-like energy; the decimator test
  guards this and the eval harness would show degraded false-openings.
- **RNNoise-default-off perceptual impact** — some devices have weak platform NS; the auto-enable
  follow-up + the user toggle cover this, but watch on-device TX quality reports.
