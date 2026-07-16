# Known Bugs / TODO

Running list of bugs found during on-device testing of the audio pipeline. Deferred
*features* are tracked as native tasks (#40, #52, #53, #54); this file is for observed
**defects** — reproducible or not.

## Open

- Check if we can integrate with Pixel phone's clear calling feature
- Evaluate move to OBOE and native low-latency audio capture: https://developer.android.com/games/sdk/oboe/low-latency-audio
- Add latency monitoring (measure average audio input/audio output/network latency, surface in settings page or similar)
- option to use silero v5 for VAD, maybe with tunable preroll ranging from 10-100ms

### UI / settings polish
- **Audio diagnostics: make it work standalone** — open a local capture session while the screen is on-screen (like the VAD Gate Tuner / Echo Test tools), so platform effects + stage levels show *without* joining a server call. Today the screen needs a live call (the effect probe + stage RMS come from the in-call AudioRecord).
- **Settings screen: grouping / dividers** — add section headers or dividers between groups of controls (transmit mode / sensitivity / AGC / debug tools) so they read as distinct sections.
- **Transmit-mode selector as M3 connected button group** — DONE (SingleChoiceSegmentedButtonRow), pending on-device visual check; revisit if the specifically-Expressive `ButtonGroup`/`ToggleButton` variant is wanted.

### Deferred native tasks (consolidated here for single-place tracking)
- Reduce Silero label speech_pad_ms (30→10-20ms) for onset latency (was task #33)
- Add noise-only false-activation clip (real MUSAN noise) to the VAD eval corpus (was task #34)
- Pre-roll buffer to recover clipped onset — revisit when tuning Silero (was task #35)
- PTT accessibility: TalkBack-operable transmit (hold gesture unusable via screen reader) + `mergeDescendants` polish (was task #42)

### 🟠 Bluetooth headset not selected as the initial call audio route (task #54)
- **Symptom (original report):** joining a call with a Bluetooth headset already connected plays
  received audio out the phone **speaker**, not the headset.
- **Status:** route indicator shipped (2026-07-16) — awaiting on-device confirmation of the
  *playout-at-call-start* case (mic capture already works, see below).
- **Root cause (code-verified):** `CallManager` never proactively selects an endpoint —
  `onAvailableCallEndpointsChanged` only stores the list; the only `requestCallEndpointChange` is in
  `setSpeaker()`, which toggles TYPE_SPEAKER↔TYPE_EARPIECE with zero BT/wired awareness.
- **Shipped as a stopgap + measurement instrument:** a read-only **"Audio route: <device>"** indicator
  on the current call screen (`CallManager.activeEndpoint` StateFlow → `AudioRoute.label()` pure mapping
  → `ActiveCallScreen`). This is throwaway — it graduates into a first-class route control in the
  **call-screen redesign** (`docs/superpowers/specs/2026-07-15-mumble-call-screen-redesign-design.md`,
  which keeps Speaker as a control but must also show *which device*).
- **Measure-first follow-up (only if confirmed):** if the indicator reads Speaker/Earpiece with BT
  connected at call start, add proactive routing — on the first available-endpoints after the call is
  active, auto-select the preferred endpoint by priority **BT > wired > earpiece** (speaker only on
  explicit request), and make `setSpeaker(false)` return to the best non-speaker route rather than
  always earpiece. Brainstormed 2026-07-16; priority **BT > wired** chosen (rarely co-occur). Don't
  build it until the indicator confirms the framework isn't already routing correctly.

### Connecting bluetooth headset in middle of call does *not* take over audio like it should
- **Update (2026-07-16, on-device):** Dan tested connecting a BT headset mid-call — the **mic/capture
  DID take over** to the headset. So the `MODE_IN_COMMUNICATION` capture path auto-switches without our
  help. Remaining question: does *playout* also follow mid-call? Confirm with the new route indicator;
  if playout follows too, this item can close.

### 🟡 Short final talkspurt whose terminator is also lost can stall (minor, pre-existing)
- **Symptom:** a very short final talkspurt (fewer samples than the prebuffer) whose
  `is_terminator` frame is *also* lost never plays and the stream never retires until the
  speaker talks again (which self-heals it). No audible impact observed; self-recovers.
- **Cause:** `SpeakerStream.fillTick` zeroes `idleTicks` before the prebuffer gate, so an
  un-anchored stream stuck below the prebuffer with no terminator never accumulates toward
  the long-idle retire.
- **Note:** pre-existing; #56 part A actually *improves* the common case (a received
  terminator now bypasses the prebuffer gate, so short utterances play immediately).

### 🟡 `lateDropCount` also counts duplicate packets (diagnostic only)
- **Symptom:** the `lateDrops` stat over-counts — `AudioVoiceEngine` increments it whenever
  `JitterBuffer.offer` returns false for a non-terminator, which includes duplicate-timestamp
  packets, not just genuine late drops. Diagnostic metric only; no runtime effect.
- **Fix (optional):** distinguish late vs duplicate rejects in `JitterBuffer.offer`.

## Fixed
- **RNNoise denoise on/off toggle + default tweaks (2026-07-16):** a Settings switch turns off RNNoise
  *denoising* while keeping it running for VAD — when off, RNNoise runs on a scratch copy (advancing its
  `DenoiseState`, yielding the VAD prob) and the raw mic passes through untouched, so voice-activation is
  byte-identical. Fable-verified bit-identical against the pinned rnnoise (`6cbfd53`/v0.1.1:
  `rnnoise_process_frame` reads `in` once, never `out`; state + VAD are a pure function of input + prior
  state) and empirically confirmed by `RnnoiseSuppressorTest` (real host RNNoise, exact VAD-prob equality).
  Wired like the AGC toggle (`RnnoiseSuppressor.setDenoiseEnabled` → `engine.setRnnoiseEnabled` →
  `MumbleManager` StateFlow + `"rnnoise_enabled"` persistence → Settings switch). Also flipped the app
  defaults: VAD threshold 0.5→0.4, AGC target −18→−24 dBFS.
- **Audio-quality arc (merged to main):** AGC — a smoothed-loudness makeup gain after RNNoise (tunable −18 dBFS target + on/off; matches mainline Mumble's post-denoise AGC); the read-only **Audio diagnostics** screen (platform AEC/NS/AGC state via an OboeTester-style read-only probe + live raw/post-RNNoise/post-gain levels — this closes the former "check if AGC is enabled" open item); and the PTT ↔ voice-activation transmit-mode selector (now an M3 segmented button group).
- **🟠 Voice never recovered from TCP tunnel back to UDP** — `MumbleManager.pingLoop` gated UDP
  pings on `selector.mode == UDP`, so once voice fell back to the TCP tunnel no UDP left the client.
  `TransportSelector` returns to UDP only when BOTH `good` (we decrypt inbound pongs) and
  `remoteGood` (the server's count of our inbound pings, echoed in its TCP ping reply) advance —
  with no UDP traffic both froze at 0 and recovery never fired. Regression origin: `f28733e` added
  the ping-gate while tunneling-by-default; `e7920bf` reverted the default but left the gate. Fixed:
  send a UDP ping every tick whenever the socket exists (drop the mode check). Recovery contract
  locked by `TransportSelectorTest.tunnelRequiresBothDeltasToRecover`. **Caveat:** ping-based health
  can't distinguish a *voice-only* UDP failure (small pings pass, larger voice packets don't) — a
  pre-existing limitation of the delta policy, not introduced here. **On-device verification pending**
  (observe recovery after a real UDP stall). Commit `470c129`.
- **🔴 VAD-gated uplink inaudible to peers — talkspurt wire semantics (#40)** — root cause: our
  VAD sender (a) **froze `frame_number`** during silence and (b) emitted **empty-payload
  terminators**. A stock Mumble receiver schedules by *absolute* `frame_number` (so a frozen
  counter lands resumed talkspurts in the past of a still-alive jitter buffer → dropped as late)
  and **rejects empty-payload packets** before it ever reads `is_terminator`. Continuous transmit
  worked; gated did not. Fixed: `frame_number` advances at **wall-clock rate every capture**, and
  the VAD-close and mute terminators are **real (silent, non-empty) frames**. Source-verified
  (`AudioInput::encodeAudioFrame` `iFrameCounter`; `AudioOutput::addFrameToBuffer` /
  `decodeAudio_protobuf` empty-payload rejection). On-device verified (peers hear gated audio).
  Commit `84238e8`.
  **Correction:** the #56 entry below and `docs/mumble-protocol.md` say Mumble `frame_number`
  "pauses/freezes" during silence — that is wrong. It advances at wall-clock rate and only resets
  after ~5 s of continuous silence. (#56's receiver fix still holds — it re-anchors on gaps/terminators
  regardless — but the wording should be corrected during cleanup.)
- **🔴 Received audio drops as "late" — talkspurt/silence handling (#56 part A)** — root cause:
  Mumble `frame_number` pauses during a VAD peer's silence, but our playout cursor advanced at
  wall-clock rate (PLC-on-underrun), so resumed talkspurts landed behind the cursor and
  `JitterBuffer` rejected them as "late" (`lateDrops` ~30 % of `voiceRx` and climbing). Fixed by
  holding the cursor on live underrun, resetting in place at talkspurt boundaries, wiring
  `is_terminator` through the seam, and clearing the stale terminator tag on contiguous/reordered
  talkspurts. On-device verified (audio continuous, `lateDrops` ≈ 0). **Part B (adaptive playout
  delay) remains deferred.** Branch `talkspurt-silence-handling`; design
  `docs/superpowers/specs/2026-07-13-talkspurt-silence-handling-design.md`.
- Hang-up native crash — Opus encoder freed before the send thread was joined (use-after-free) — `ae378a0`
- Mute button state desynced across calls (stale singleton vs. fresh engine) — `a03a873`
- Only one of two simultaneous speakers audible — mixer hard-sum clipping collapse; fixed with an Int-accumulate + soft-knee limiter — `49b9846`
- Other Mumble clients didn't show self-mute — now broadcast `UserState{self_mute}` on toggle — `5107a2c`
- `libdumbleopus.so` 16 KB page alignment (Play compliance) — `-Wl,-z,max-page-size=16384`; all LOAD segments 0x4000 on all ABIs — `1567505`

## Follow-up features / tasks
- **#56 part B** adaptive jitter buffer + adaptive playout delay (part A / talkspurt-silence handling has landed) — design notes: `docs/superpowers/adaptive-jitter-buffer-design-notes.md`
- **#55** notification: show server label & channel (fallback to hostname)
- **#54** Bluetooth headset not selected as the initial call audio route
- **#53** evaluate 32 kbps CVBR encoder default
- **#40 voice-activity detection — LANDED.** RNNoise-denoised uplink gated by RNNoise's own VAD
  probability (default 0.5, in-app tunable + persisted via Settings → Voice activity). Wall-clock
  `frame_number` + real terminators (see Fixed). Remaining VAD follow-ups: expand the Voice
  Activity settings (RNNoise on/off, hangover, VAD-source select), and evaluate **Silero** as a
  third VAD behind the swappable `VadDetector` seam (the VAD Gate Tuner is the eval bench). Design:
  `docs/superpowers/specs/2026-07-14-voice-activity-detection-design.md`.
- Cleanup: remove the per-5 s debugging logs (Ping tick, mic/track state, mix peaks, uplink kbps) now that #56 part A is verified
