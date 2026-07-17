# Known Bugs / TODO

Running list of bugs found during on-device testing of the audio pipeline. Deferred
*features* are tracked as native tasks (#40, #52, #53, #54); this file is for observed
**defects** — reproducible or not.

## Open

- Check if we can integrate with Pixel phone's clear calling feature
- Evaluate move to OBOE and native low-latency audio capture: https://developer.android.com/games/sdk/oboe/low-latency-audio
- Add latency monitoring (measure average audio input/audio output/network latency, surface in settings page or similar)
- move all non-essential settings under an advanced section in settings. Currently only transmit mode looks essential. That includes the debug pages for echo/VAD tuning/audio diagnostics, those should all be under advanced
- advanced option to use silero v5 for VAD, maybe with tunable preroll ranging from 10-100ms. once we have it working well maybe we'll replace the current VAD
- Show when a remote client is deafened
- Show a more obvious glowing ring around the speaker
- Use Ben's algorithm for automatic user icon color based on hash of their name
- Add chat feature
- More similar color scheme to phone app, white buttons with light gray background for the bottom bar
- Make noise suppression a group button select (native, if supported, and then RNNoise or none)

### UI / settings polish
- **Audio diagnostics: make it work standalone** — open a local capture session while the screen is on-screen (like the VAD Gate Tuner / Echo Test tools), so platform effects + stage levels show *without* joining a server call. Today the screen needs a live call (the effect probe + stage RMS come from the in-call AudioRecord).
- **Settings screen: grouping / dividers** — add section headers or dividers between groups of controls (transmit mode / sensitivity / AGC / debug tools) so they read as distinct sections.
- **Transmit-mode selector as M3 connected button group** — DONE (SingleChoiceSegmentedButtonRow), pending on-device visual check; revisit if the specifically-Expressive `ButtonGroup`/`ToggleButton` variant is wanted.
- **Call controls: adopt M3 `toggleableShapes()`** — the Mute/Deafen/Speaker toggle shape morph
  (pill↔squircle) is a manual *instant* swap today (`controlPillShape`/`controlActiveShape` in
  `ActiveCallScreen.kt`). When `IconButtonDefaults.toggleableShapes()` reaches a **stable** Compose BOM
  (not in `2026.06.01`; alpha-only as of 2026-07-17), pass `shapes = IconButtonDefaults.toggleableShapes()`
  to the three `FilledIconToggleButton`s for the native *animated* morph and drop the manual constants.

### Deferred native tasks (consolidated here for single-place tracking)
- Reduce Silero label speech_pad_ms (30→10-20ms) for onset latency (was task #33)
- Add noise-only false-activation clip (real MUSAN noise) to the VAD eval corpus (was task #34)
- Pre-roll buffer to recover clipped onset — revisit when tuning Silero (was task #35)
- PTT accessibility: TalkBack-operable transmit (hold gesture unusable via screen reader) + `mergeDescendants` polish (was task #42)

### 🟠 Bluetooth headset not selected as the initial call audio route (task #54)
- **Symptom (original report):** joining a call with a Bluetooth headset already connected plays
  received audio out the phone **speaker**, not the headset.
- **Status: CONFIRMED on-device (2026-07-17).** Dan joined a call with a BT headset connected —
  output played through the phone **speaker**, not the headset. The framework does NOT route playout to
  BT at call start, so the measure-first proactive-routing follow-up below is now warranted — BUT see the
  route-picker bug directly below: the CallEndpoint *selection* path itself may be broken on-device,
  which is a prerequisite for any proactive routing.
- **Root cause (code-verified):** `CallManager` never proactively selects an endpoint —
  `onAvailableCallEndpointsChanged` only stores the list; the only `requestCallEndpointChange` is in
  `setSpeaker()`, which toggles TYPE_SPEAKER↔TYPE_EARPIECE with zero BT/wired awareness.
- **Measurement instrument (shipped):** the redesigned call screen's **Speaker** control shows the
  active audio device + route icon (`CallManager.activeEndpoint` → `AudioRoute.icon` + the framework
  endpoint name). Read it with a BT headset connected at call start to decide whether proactive routing
  is needed.
- **Measure-first follow-up (only if confirmed):** if the indicator reads Speaker/Earpiece with BT
  connected at call start, add proactive routing — on the first available-endpoints after the call is
  active, auto-select the preferred endpoint by priority **BT > wired > earpiece** (speaker only on
  explicit request), and make `setSpeaker(false)` return to the best non-speaker route rather than
  always earpiece. Brainstormed 2026-07-16; priority **BT > wired** chosen (rarely co-occur). Don't
  build it until the indicator confirms the framework isn't already routing correctly.

### 🔴 Route picker doesn't switch the output device — selecting Earpiece was a no-op (2026-07-17, on-device)
- **Symptom:** in the in-call route picker, tapping **Earpiece** did not switch output to the earpiece;
  audio kept playing on the previous device.
- **Likely shared root with #54:** both point at `CallManager`'s CallEndpoint selection path.
  `selectRoute(type)` picks the first endpoint of `type` from `_endpoints` and calls
  `Connection.requestCallEndpointChange(ep, executor, receiver)` where the `OutcomeReceiver`'s
  `onResult`/`onError` are **empty** — so any failure is a silent no-op.
- **Investigate:** (1) is a `TYPE_EARPIECE` endpoint actually present in `_endpoints` (else `firstOrNull`
  → null → nothing happens)? (2) does `requestCallEndpointChange` invoke `onError` (log the
  `CallEndpointException`)? (3) log the available endpoint types the framework reports. If endpoint
  selection is broken across the board, the #54 proactive-routing fix cannot work until this does —
  fix this first.

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

## Fixed
- **🟡 `lateDrops` over-counted duplicates (diagnostic) — FIXED (`latedrops-dup-count`):**
  `JitterBuffer.offer` now returns an `OfferResult` (`QUEUED`/`LATE`/`DUPLICATE`/`EMPTY`) instead
  of a bare Boolean, and `AudioVoiceEngine` increments `lateDropCount` only on a genuine `LATE`
  (audio behind the playout cursor) — not on duplicate/reordered retransmits (`DUPLICATE`) or
  tag-only terminators (`EMPTY`). Diagnostic metric only; no runtime behavior change.
- **Call-screen redesign (merged to main 2026-07-16):** replaced the minimal in-call screen with a
  Material 3 screen — server/channel header + connection timer, live channel→user tree (speaking rings,
  self/server mute + deafened + recording badges, YOU tag, self floated, DFS hierarchy + depth indent),
  and a Mute/Deafen/Speaker/Disconnect control bar (Mute→Hold-to-Talk in PTT). Added **self-deafen**
  (hot-mic-safe, Mumble-faithful), a prominent full-screen **Connecting** state, and localized route
  labels. Fable-compared to the desktop/iOS clients (deferred items under "Mumble fidelity — deferred").
  Net/voice debug stats moved into the **Audio diagnostics** screen. Spec:
  `docs/superpowers/specs/2026-07-15-mumble-call-screen-redesign-design.md`; plan:
  `docs/superpowers/plans/2026-07-16-call-screen-redesign.md`.
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
  **Correction (source re-verified 2026-07-17):** upstream `AudioInput::encodeAudioFrame` runs
  `iFrameCounter++` as its FIRST statement — *before* the `!bIsSpeech && !bPreviousVoice` early
  `return` — so Mumble's `frame_number` **advances at wall-clock rate through silence** and resets
  to 0 only after 500 silent frames (~5 s). `docs/mumble-protocol.md` is already correct.
  **Mechanism re-derivation (2026-07-17, fable-verified):** the "paused sender" story (in the #56
  entry / talkspurt spec / adaptive-jitter notes) is wrong — a wall-clock sender + wall-clock cursor
  stay in lockstep. My follow-up guess ("prebuffer-cushion consumption") was **refuted by fable**:
  wall-clock PLC *preserves* the cushion (playout delay `D = t_wall − cursor` is constant through
  PLC), so a silence resume is late iff per-packet excess delay `j > C` — independent of gap length,
  and `j ≈ 0` for a fresh resume → never late; any transient cushion-less state is capped at ≤10
  drops by retire+re-prebuffer. **Actual driver: stall-and-burst (delay-spike) delivery** — mid-
  talkspurt audio queued during a *network* stall (Wi-Fi power-save/DTIM, BT-coex, UDP cutout,
  TCP-tunnel HoL) arrives as a burst; the pre-fix cursor marched through the stall, so everything in
  the burst older than the cushion is rejected (`drops ≈ (S−C)/20 ms`, recurring → ~15–35 %). Fits
  all evidence (1:1 arrival, mostly-silence-with-bursts, prebuffer-enlargement barely helped,
  fix→0). **Analysis-verified, not yet observed live** — decisive on-device test: at each LATE reject
  log `latenessMs`, inter-arrival gap, and ts-contiguity across the gap (stall-burst = contiguous-ts
  runs ≤10 with a descending-20ms lateness staircase; refuted silence-resume = isolated lates whose
  ts jumps by the gap). Docs corrected: `adaptive-jitter-buffer-design-notes.md` → "Mechanism
  (fable-verified 2026-07-17)" + talkspurt-spec banner. `docs/mumble-protocol.md` was already correct.
- **🔴 Received audio drops as "late" — talkspurt/silence handling (#56 part A)** — symptom:
  resumed talkspurts landed behind the playout cursor and `JitterBuffer` rejected them as "late"
  (`lateDrops` ~30 % of `voiceRx` and climbing). Fixed by holding the cursor on live underrun,
  resetting in place at talkspurt boundaries, wiring `is_terminator` through the seam, and clearing
  the stale terminator tag on contiguous/reordered talkspurts. On-device verified (audio continuous,
  `lateDrops` ≈ 0). **Root-cause NOTE (corrected 2026-07-17, fable-verified):** the original "sender
  pauses `frame_number`" explanation is wrong — a desktop-Mumble sender advances it at wall-clock
  (source-verified), so silence resumes are essentially never late. Actual driver is stall-and-burst
  network delivery (mid-talkspurt audio arriving in a burst after a network stall, past the
  wall-clock-marched cursor) — see the mechanism-re-derivation flag under #40 above and
  `adaptive-jitter-buffer-design-notes.md`. **Part B (adaptive playout delay) remains deferred.**
  Branch `talkspurt-silence-handling`; design
  `docs/superpowers/specs/2026-07-13-talkspurt-silence-handling-design.md`.
- Hang-up native crash — Opus encoder freed before the send thread was joined (use-after-free) — `ae378a0`
- Mute button state desynced across calls (stale singleton vs. fresh engine) — `a03a873`
- Only one of two simultaneous speakers audible — mixer hard-sum clipping collapse; fixed with an Int-accumulate + soft-knee limiter — `49b9846`
- Other Mumble clients didn't show self-mute — now broadcast `UserState{self_mute}` on toggle — `5107a2c`
- `libdumbleopus.so` 16 KB page alignment (Play compliance) — `-Wl,-z,max-page-size=16384`; all LOAD segments 0x4000 on all ABIs — `1567505`

## Follow-up features / tasks
- **#56 part B** adaptive jitter buffer + adaptive playout delay (part A / talkspurt-silence handling has landed) — design notes: `docs/superpowers/adaptive-jitter-buffer-design-notes.md`
  - **Lower the prebuffer floor 100 ms → ~10 ms** to match Mumble desktop's low-latency default (its
    Speex jitterbuffer is adaptive; 10 ms is the floor, not a static target). Today
    `SpeakerStream.prebufferSamples` is a **static** 100 ms (`FRAME_SAMPLES_20MS*5`) and
    `JitterBuffer.highWaterSamples` a static 600 ms cap — the buffer does **not** grow dynamically.
    The 100 ms was deliberately raised (`4031812`) to stop late-drop starvation on jittery paths, so
    this must land *with* adaptive sizing: start ~10–20 ms and grow toward the 600 ms cap on measured
    jitter / `lateDrops`; a naive static 100→10 would regress that fix.
- **#55** notification: show server label & channel (fallback to hostname)
- **#54** Bluetooth headset not selected as the initial call audio route
- **#53** evaluate 32 kbps CVBR encoder default
- **#40 voice-activity detection — LANDED.** RNNoise-denoised uplink gated by RNNoise's own VAD
  probability (default 0.5, in-app tunable + persisted via Settings → Voice activity). Wall-clock
  `frame_number` + real terminators (see Fixed). Remaining VAD follow-ups: expand the Voice
  Activity settings (RNNoise on/off, hangover, VAD-source select), and evaluate **Silero** as a
  third VAD behind the swappable `VadDetector` seam (the VAD Gate Tuner is the eval bench). Design:
  `docs/superpowers/specs/2026-07-14-voice-activity-detection-design.md`.
- **Connecting phase feels long — add an overall ~10 s deadline. DONE** (`connecting-timeout`):
  `SessionStateMachine.armConnectTimeout(scope, 10 s)` is armed before the blocking TCP connect +
  TLS handshake, so one deadline now spans handshake→auth→ServerSync. On timeout it calls
  `fail(TIMEOUT)`, which closes the channel (unblocking both the stalled handshake and the reader
  loop) with first-failure-wins; reaching Synchronized earlier completes the watchdog immediately.
  Unit-tested with virtual time. **On-device check pending:** point at an unreachable/black-hole
  host (accepts TCP then stalls) and confirm it fails in ~10 s instead of hanging on "Connecting…".
- Cleanup: remove the per-5 s debugging logs — **DONE** (`cleanup/debug-logs`): mic/track state +
  uplink kbps removed (with their dead counters); "mix peaks" was already gone. **Ping tick kept**
  deliberately — it is the only surface for remoteGood/mode/decryptFail/udpAudioRx and is still the
  instrument for the pending UDP→TCP transport-recovery verification (`470c129`); remove it after
  that verification lands.
- Cleanup: remove the temporary **`LateDiag`** log (`AudioVoiceEngine`, gated on LATE rejects) once
  the #56 stall-and-burst mechanism is confirmed on-device. On-device: watch logcat tag `LateDiag`
  during a jittery call — a **stall-burst** shows contiguous-ts runs (`contiguous=true`) after an
  `arrivalGap`, lateness descending ~20 ms per packet; the refuted **silence-resume** would show
  isolated `contiguous=false` lates whose ts jumps by the gap.
