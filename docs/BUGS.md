# Known Bugs / TODO

Running list of bugs found during on-device testing of the audio pipeline. Deferred
*features* are tracked as native tasks (#40, #52, #53, #54); this file is for observed
**defects** — reproducible or not.

## Open

### 🔴 Received audio drops as "late" — talkspurt/silence handling (→ feature task #56)
- **Symptom:** remote voice is mostly silence with occasional bursts. On-device counters
  prove the pipeline delivers voice **intact**: `udpAudioRx == voiceRx` (1:1),
  `decryptFail=0`, tiny `lost` — yet the new `lateDrops` counter is **~30 % of `voiceRx` and
  climbing** (e.g. `voiceRx=9868, lateDrops=2935`). So it's not network, decrypt, demux, or
  buffer *size* (the 100 ms fixed prebuffer barely moved `lateDrops`).
- **Root cause:** Mumble `frame_number` is a frames-*transmitted* sequence that **pauses
  during a VAD peer's silence**. Our playout cursor advances at wall-clock rate during pauses
  (PLC-on-underrun), so resumed talkspurts land *behind* the cursor →
  `JitterBuffer` rejects them as "late." Natural speech's sub-200 ms pauses → constant drops.
  This also subsumes the earlier "retire churn" note (both stem from wrong silence handling)
  and the earlier "~45 s cutout" framing (packets do arrive; they're dropped as late).
- **Fix = its own feature (task #56):** (1) re-anchor the cursor at talkspurt boundaries —
  wire the incoming `is_terminator` through the seam; don't advance the cursor at wall-clock
  through silence; (2) adaptive playout delay (NetEq/Speex-style) instead of fixed 100 ms.
  Full design: `docs/superpowers/adaptive-jitter-buffer-design-notes.md`.
- **Note:** the shipped branch keeps a fixed 100 ms prebuffer + diagnostic counters; calls
  will drop audio with VAD peers on jittery paths until #56 lands. Transport is UDP
  (`forceTcp` mitigation reverted, `e7920bf`).

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

## Fixed (this on-device testing pass)
- Hang-up native crash — Opus encoder freed before the send thread was joined (use-after-free) — `ae378a0`
- Mute button state desynced across calls (stale singleton vs. fresh engine) — `a03a873`
- Only one of two simultaneous speakers audible — mixer hard-sum clipping collapse; fixed with an Int-accumulate + soft-knee limiter — `49b9846`
- Other Mumble clients didn't show self-mute — now broadcast `UserState{self_mute}` on toggle — `5107a2c`
- `libdumbleopus.so` 16 KB page alignment (Play compliance) — `-Wl,-z,max-page-size=16384`; all LOAD segments 0x4000 on all ABIs — `1567505`

## Follow-up features / tasks
- **#56** adaptive jitter buffer + talkspurt/silence handling (fixes the 🔴 above) — design notes: `docs/superpowers/adaptive-jitter-buffer-design-notes.md`
- **#55** notification: show server label & channel (fallback to hostname)
- **#54** Bluetooth headset not selected as the initial call audio route
- **#53** evaluate 32 kbps CVBR encoder default
- **#40** voice-activity detection (transmit mode)
- Cleanup: remove the per-5 s debugging logs (Ping tick, mic/track state, mix peaks, uplink kbps) when #56 lands
