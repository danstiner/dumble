# Known Bugs / TODO

Running list of bugs found during on-device testing of the audio pipeline. Deferred
*features* are tracked as native tasks (#40, #52, #53, #54); this file is for observed
**defects** — reproducible or not.

## Open

### 🟠 Bluetooth headset not selected as the initial call audio route (task #54)
- **Symptom:** joining a call with a Bluetooth headset already connected plays audio out
  the phone **speaker**, not the headset.
- **Status:** to investigate (2026-07-13).
- **Likely cause:** we rely on the framework/Telecom to auto-route and only *respond* to
  Speaker/Earpiece toggles — we never proactively select an initial endpoint; possibly an
  `AudioTrack`-created-before-route ordering issue (track starts at `onCryptReady`).
- **Next step (task #54):** log `onAvailableCallEndpointsChanged` / `onCallEndpointChanged`
  to see whether BT is offered/active at call start; if not, `requestCallEndpointChange` to
  the preferred endpoint (BT > wired) at start, or fix the ordering.

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
- **#40** voice-activity detection (transmit mode)
- Cleanup: remove the per-5 s debugging logs (Ping tick, mic/track state, mix peaks, uplink kbps) now that #56 part A is verified
