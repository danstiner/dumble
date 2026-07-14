# Voice-Activity Detection (transmit mode) — Design

**Feature:** #40 — voice-activity detection on the transmit path.

**Goal:** Stop transmitting when the local user is not speaking. Replace the current
continuous (always-on-when-unmuted) uplink with a speech-gated one that produces Mumble
talkspurts terminated by `is_terminator`, so peers hear clean speech, silence costs no
bandwidth, and the sender's output matches exactly what our receive path (feature #56
part A) was built to consume.

**Scope:** two phases in one spec.
- **Phase 1** — pure-Kotlin energy/amplitude VAD with an adaptive noise floor and a
  hysteresis + hangover transmit gate. This is Mumble's *default* VAD mode ("Amplitude").
- **Phase 2** — add RNNoise noise suppression (JNI) ahead of the same gate, denoising the
  uplink audio as well. This is what Mumble does when RNNoise is enabled: denoise → detect,
  with the VAD level still derived from the (denoised) signal, not RNNoise's own VAD head.

## User decisions (already made)

- Feature is "voice activity detection" for the **transmit** path (#40).
- **Two-phase in one spec**: Phase 1 energy VAD, Phase 2 RNNoise.
- Phase 2 detector = **"RNNoise denoise + energy VAD (Mumble-faithful)"** — RNNoise
  suppresses noise; detection stays energy-based on the denoised signal; RNNoise's own VAD
  probability is not used (matching Mumble).
- Packetization: **"20 ms packets + 10 ms sub-processing"** — keep the current 20 ms
  blocking read and single 20 ms Opus encode; split each capture into two 10 ms sub-frames
  for VAD (and RNNoise, which requires 480-sample frames).
- Transmit mode: **"VAD replaces continuous, no toggle."** When unmuted, VAD always gates
  transmission. No settings UI. Mute still hard-suppresses.
- Threshold approach: **adaptive noise floor** (level-relative / SNR-like detection), with a
  fixed-threshold constant as an on-device fallback.
- Phase 2 RNNoise **denoises the uplink audio too** (denoised buffer feeds both the detector
  and the Opus encoder), not only the detector.
- **AGC is not a prerequisite** and is not in this spec. Running AGC before VAD is circular
  (good AGC must be VAD-gated) and normalizes loudness, not SNR. A VAD-gated AGC for
  listener loudness is noted as a possible future item only.

## Architecture and seam

Three small units in `app/src/main/java/me/danielstiner/dumble/mumble/voice/`, driven from
the existing `AudioVoiceEngine.nextOutgoingFrame()` (voice-send-thread context):

- **`VadDetector`** — `fun level(pcm: ShortArray, off: Int, n: Int): Float`. Evaluated on
  each 10 ms / 480-sample sub-frame; returns a normalized speech level in `0f..1f`. Phase 1
  implementation: `EnergyVadDetector`.
- **`NoiseSuppressor`** — `fun process(pcm: ShortArray, off: Int, n: Int)`. Denoises a
  480-sample sub-frame in place. Phase 1 uses a no-op implementation
  (`NoiseSuppressor.None`); Phase 2 adds `RnnoiseSuppressor` (JNI).
- **`TransmitGate`** — the state machine. Given the two sub-frame levels of one 20 ms
  capture, returns `Decision(send: Boolean, terminator: Boolean)`.

The seam is deliberately packet-size-independent. Packet size is a single parameter —
`framesPerPacket`, N × 10 ms (Phase 1/2 ship N = 2 = 20 ms) — and the detector and gate always
operate in 10 ms units regardless of it: a capture is N × 480 samples processed as N
sub-frames, `frameNumber` advances by N per packet, and all gate timing is counted in 10 ms
ticks (not packets), so it is invariant to packet size. This is deliberate so a future
configurable packet-size slider (10/20/40/60 ms) is a localized change, not a rewrite (see
Deferred). We do **not** build any packet-size configuration now (YAGNI) — we only avoid
hardcoding 20 ms in a way that would force a rewrite later.

### Integration point

`AudioVoiceEngine.nextOutgoingFrame()` today reads a 20 ms / 960-sample frame, and — when
unmuted — encodes and sends it unconditionally, advancing `frameNumber += 2`. It already
models a talkspurt boundary for mute: on unmute→mute it emits one empty (`ByteArray(0)`)
`isTerminator` frame, then returns null, and `frameNumber` only advances for frames actually
sent. VAD generalizes that boundary from the manual mute flag to a per-frame speech decision.

New flow when unmuted:

1. `rec.read(capturePcm, FRAME_SAMPLES_20MS)` — unchanged (960 samples).
2. **Phase 2 only:** `suppressor.process(capturePcm, 0, 480)` and
   `suppressor.process(capturePcm, 480, 480)` — denoise each half in place. The denoised
   buffer is what gets encoded.
3. `val l0 = detector.level(capturePcm, 0, 480)`; `val l1 = detector.level(capturePcm, 480, 480)`.
4. `val d = gate.update(l0, l1)`.
5. If `d.send`: encode the full 960-sample `capturePcm`, `frameNumber += 2`, return the
   voice frame (unchanged encode path).
6. Else if `d.terminator`: return one empty `isTerminator` frame carrying the current
   `frameNumber` (do **not** advance it) — identical to the existing mute-terminator emission.
7. Else: return null. `frameNumber` stays frozen.

Mute takes precedence: when `muted`, the existing mute path runs and the gate is bypassed
and reset to a closed state so a later unmute starts clean.

No new send-side wire plumbing is required: `VoiceFrame.isTerminator` and the empty-payload
convention already exist and are used by the mute path, and the receive path already treats
`isTerminator || length == 0` as a talkspurt terminator.

## Phase 1 — energy VAD + transmit gate

### EnergyVadDetector (adaptive noise floor)

There is no settings UI, so a hardcoded absolute dB threshold would be fragile across
devices and microphones. `EnergyVadDetector` instead makes detection **level-relative**:

- Compute the sub-frame's short-term level in dBFS (RMS or peak; RMS preferred for
  stability), floored at a minimum (e.g. −96 dB) so digital silence maps to the bottom.
- Maintain an adaptive **noise-floor** estimate that tracks background level. It updates only
  while the gate is **closed** (so active speech never inflates the floor), rising slowly and
  falling faster, with a short time constant on the order of a few hundred ms to a couple of
  seconds.
- The returned speech level is how far the current frame sits above the noise floor, mapped
  into `0f..1f` over a fixed dB span (the "decision margin"). A frame at the floor → ~0; a
  frame a full margin above → ~1.

This makes the decision an SNR-like quantity rather than an absolute one, which is the
correct answer to varying input levels and to the platform AGC raising the background floor.

**Fallback:** if the adaptive floor proves to fight the platform AGC on-device, a fixed dB
threshold constant can replace the floor with no change to the gate. This is a tuning
fallback, not a redesign.

### TransmitGate state machine

Mirrors Mumble's proven amplitude-mode gate:

- **Two-threshold hysteresis.** Open when level > `openLevel`; stay open while level >
  `closeLevel`, with `closeLevel < openLevel`. Prevents flutter at the boundary. (Mumble's
  analogous defaults are `fVADmax`/`fVADmin`.)
- **Hangover (hold timer).** After the level drops below `closeLevel`, keep transmitting for a
  hold period of ~200 ms (matching Mumble's master `iVoiceHold` default of 20 × 10 ms) before
  closing. Counted in **10 ms sub-frame ticks, not packets** — the gate ticks its hold counter
  once per 10 ms sub-frame it consumes, so the hangover *duration* is invariant to packet size.
  This mirrors Mumble's `iHoldFrames` (a 10 ms-frame counter, which is exactly why Mumble's
  "audio per packet" setting never changes the hold time). Hangover frames carry real captured
  audio, so trailing consonants and short inter-word gaps are not chopped. This is a mechanism
  distinct from the two-threshold hysteresis above: hysteresis gives *level*-based stickiness
  (stay open while above `closeLevel`); the hold timer gives *time*-based stickiness (stay open
  for the hold window after the level test has already failed). Verified against Mumble source:
  the `iHoldFrames` block runs only when the per-frame speech test fails and never consults the
  thresholds.
- **Onset.** Open on the first 10 ms sub-frame above `openLevel`. Because detection runs per
  10 ms half, an onset in the second half of a 20 ms capture still sends the whole packet,
  giving ~10 ms of natural lead-in — so no explicit pre-roll/lookahead buffer is needed
  (avoids added latency and buffering).
- **Per-capture decision.** `update(l0, l1)` treats the capture as voiced if `max(l0, l1)`
  exceeds the active threshold — `openLevel` when the gate is closed, `closeLevel` when it is
  open. It returns `send = (open || withinHangover)`, and `terminator = true` exactly on the
  transition where hangover expires while previously transmitting.
- **Only one sub-frame voiced.** The wire packet is atomic (a single 20 ms Opus encode), so a
  half-packet cannot be sent; the two sub-verdicts collapse via the `max` rule above.
  Consequences: an onset in the *second* half (gate closed) opens the gate and sends the whole
  packet, with the silent first half as ~10 ms of natural lead-in (no onset clip). A silent
  *second* half at offset (gate open) is sent as the talkspurt tail. A lone quiet sub-frame
  inside voiced audio keeps `max` above `closeLevel`, so the gate does not flutter closed. The
  10 ms resolution therefore sharpens edge *timing*, not sub-packet transmission; the silent
  half that rides along at each edge is ≤10 ms and harmless (it compresses to near-nothing).

### frameNumber and terminator semantics

- `frameNumber` advances `+= 2` only for packets actually sent (speech or hangover), and
  **freezes during silence** — unchanged from today.
- On the speech→silence edge (hangover expiry), emit exactly one empty `isTerminator` frame
  (latched, like the existing `wasMuted` one-shot), then return null until speech resumes.
- On the next talkspurt, `frameNumber` continues monotonically from where it left off, with
  wall-clock time having advanced during the silence. This frozen-counter-across-silence is
  precisely the pattern the feature #56 part A receiver re-anchors on via the terminator.

We deliberately use an **empty** terminator frame rather than Mumble's zero-padded real final
frame (see appendix): it reuses the existing mute-terminator path unchanged, and the receive
path already treats `isTerminator || length == 0` as a boundary. Trailing audio is preserved
by the hangover (we keep sending real frames until it expires), so the empty terminator only
marks the already-decided end — no audio is lost by not padding it.

## Phase 2 — RNNoise noise suppression

Add RNNoise (xiph, BSD) as a vendored native library with a JNI wrapper, following the
existing `libdumbleopus.so` precedent (including the 16 KB max-page-size alignment already
solved for the Opus library).

- `RnnoiseSuppressor : NoiseSuppressor` denoises each 480-sample sub-frame **in place** at
  48 kHz — RNNoise's native rate, so no resampling.
- **Frame alignment is exact, not incidental.** At 48 kHz, 10 ms = 480 samples, and RNNoise's
  frame is fixed at 480 samples (48 kHz-only). So one 20 ms / 960-sample capture is exactly
  two RNNoise frames (`[0, 480)` then `[480, 960)`), one RNNoise frame per 10 ms sub-frame,
  with no remainder — hence no resampling and no bridging ring buffer. This 1:1 match is the
  reason the sub-processing granularity was chosen at 10 ms.
- **RNNoise is stateful.** Keep one persistent `DenoiseState` per capture stream and feed it
  consecutive frames in order (half 0, then half 1, capture after capture); never reset it
  between frames. This preserves RNNoise's internal pitch/overlap continuity across the two
  halves and across successive captures. Processing is block-aligned 480-in/480-out.
- The denoised buffer feeds **both** the `EnergyVadDetector` (cleaner detection, higher SNR)
  and the Opus encoder (cleaner uplink audio). This is the bonus Mumble gets from denoising
  before encode.
- RNNoise's own per-frame voice probability is **discarded** — the VAD level stays
  energy-based on the denoised signal, matching current Mumble.
- The `TransmitGate` and `EnergyVadDetector` are unchanged between phases. Phase 2 only swaps
  `NoiseSuppressor.None` for `RnnoiseSuppressor` in the pipeline wiring.

## Testing

- **Pure-JVM unit tests** (existing `FakeOpus`-based voice test infrastructure):
  - `EnergyVadDetector`: a burst above floor+margin returns a high level; steady background
    returns a low level once the floor has adapted; the floor does not inflate while the gate
    is held open (fed via the gate, or by asserting the update-only-when-closed rule).
  - `TransmitGate`: opens on onset; holds through sub-hangover silence; closes only after
    sustained (> hangover) silence; emits exactly one terminator on close; re-opens on the
    next burst; the two-sub-frame rule sends a packet when only the second 10 ms half is
    speech.
  - Integration through a fake `AudioIn`/`OpusCodec`: `frameNumber` advances only for sent
    packets and freezes across silence; the empty terminator carries the frozen
    `frameNumber`; mute still takes precedence and resets the gate.
- **Phase 2 / RNNoise** cannot run in pure JVM. `NoiseSuppressor` is an interface with an
  identity fake for JVM tests; the real JNI `RnnoiseSuppressor` is validated on-device.

## On-device verification (user gate)

Like feature #56, each phase ends with an on-device check by the user:

- **Phase 1:** speech gates cleanly with no audible onset clipping; sustained silence stops
  transmission (uplink kbps drops to ~0 between talkspurts, visible in the existing uplink
  log); remote peers hear clean, correctly-bounded talkspurts; the adaptive floor behaves in
  a real (noisy) room, or the fixed-threshold fallback is selected.
- **Phase 2:** RNNoise runs without native crashes or 16 KB-alignment/Play-compliance
  regressions; background noise is audibly reduced on the uplink; VAD robustness in noise
  improves over Phase 1.

## Risks

- **Platform AGC interaction.** `MediaRecorder.AudioSource.VOICE_COMMUNICATION` typically
  enables platform AGC/NS/AEC (device-dependent). AGC can raise quiet background toward its
  target and pump at onset — the classic hazard for amplitude VAD. Mitigations: the adaptive
  noise floor absorbs steady AGC-raised background; Phase 2 RNNoise raises SNR substantially;
  the fixed-threshold fallback exists if the adaptive floor fights the AGC. To be confirmed
  on-device.
- **RNNoise native build/compliance.** Another vendored C library plus 16 KB page alignment
  for Play compliance — the recipe already exists from `libdumbleopus.so`.
- **Codec conservatism.** The Opus encoder currently runs CBR; VAD reduces *when* we send but
  not per-frame size. No encoder change is in scope; DTX is intentionally not used (see
  Deferred).

## Deferred / not in scope

- **VAD-gated AGC** for consistent listener loudness — a downstream, VAD-gated feature, not a
  prerequisite. Possible future item.
- **Transmit-mode selector / push-to-talk** — VAD replaces continuous with no toggle; a
  Continuous/VAD/PTT selector and PTT input handling are out of scope.
- **Opus DTX** — keeps the stream flowing with tiny comfort-noise frames and produces no
  Mumble terminators, so it is redundant with explicit stop-transmitting VAD and mismatched
  with the receive path. Not used.
- **RNNoise's own VAD head**, and a **Speex/SNR probability** detector — considered and
  rejected in favor of energy detection on the denoised signal (Mumble-faithful).
- **Configurable packet size (10/20/40/60 ms slider)** — not built now: no settings/DataStore
  plumbing and no runtime switching (YAGNI). But the design is structured for it — packet size
  is the single `framesPerPacket` parameter (default 2 = 20 ms), the pipeline processes N × 10 ms
  sub-frames, `frameNumber += N`, and all gate timing is in 10 ms units, so it is
  packet-size-invariant. Adding a slider later is a localized change, not a rewrite. 20 ms stays
  the default on bandwidth/battery grounds (10 ms ≈ +29% uplink overhead).

## Appendix — Mumble source facts (fable-verified)

Verified against `mumble-voip/mumble` source (`src/mumble/AudioInput.cpp`/`.h`,
`AudioPreprocessor.cpp`, `Settings.h`, `src/MumbleProtocol.cpp`, `src/MumbleUDP.proto`),
supporting the decisions above:

- Internal frame size is 10 ms / 480 samples at 48 kHz (`iFrameSize = SAMPLE_RATE / 100`).
- A packet is a **single** Opus encode of `iAudioFrames × 10 ms` (default 2 → 20 ms), not
  bundled 10 ms Opus frames; `flushCheck` asserts one frame per packet. The old multi-frame
  bundling was CELT/Speex-era and is now dead.
- VAD is decided **per 10 ms frame** in `encodeAudioFrame`; the packet is assembled after.
- Default VAD source is **Amplitude** (`level = 1.0 + dPeakCleanMic/96`), the alternative
  being **SignalToNoise** (`level = fSpeechProb` from the Speex preprocessor). Two-threshold
  hysteresis (`fVADmin` 0.80 / `fVADmax` 0.98) plus a hold timer (200 ms on master).
- Amplitude mode **subtracts the AGC gain** back out (`dPeakCleanMic = dPeakSignal − AGCgain`)
  — evidence that AGC is compensated for, not used to level the signal for VAD.
- RNNoise is present but **off by default** (default noise suppression is Speex), runs at
  480 samples, and its **own VAD output is discarded** — speech probability always comes from
  the Speex preprocessor.
- The terminator is set on the packet containing the first non-speech frame after a talkspurt
  (zero-padded to full duration), i.e. on the speech→silence transition; on the wire it is
  `is_terminator` in the 1.5+ protobuf UDP format.

## Relation to this codebase

- Transmit path and integration point:
  `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
  (`nextOutgoingFrame`, the mute-terminator precedent).
- Frame/timestamp constants: `.../voice/AudioConstants.kt` (48 kHz, 960/480 samples).
- Receive-side talkspurt handling this feature feeds: `.../voice/SpeakerStream.kt`,
  `.../voice/JitterBuffer.kt` (feature #56 part A).
- Protocol background: `docs/mumble-protocol.md` (frame_number, talkspurts, is_terminator).
