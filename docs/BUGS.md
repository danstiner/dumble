# Known Bugs / TODO

Running list of bugs found during on-device testing of the audio pipeline. Deferred
*features* are tracked as native tasks (#40, #52, #53, #54); this file is for observed
**defects** — reproducible or not.

## Open

### 🔴 Intermittent ~30 s call-audio silence (both directions)
- **Symptom:** after ~30 s in a call, both transmit and receive audio go silent, but the
  app's sent/received counters keep incrementing — so the network + software loops are
  alive; the Android audio *session* is being silenced.
- **Status:** not yet reliably reproducible (first seen 2026-07-13).
- **Leading hypothesis:** audio-focus loss or an audio-mode reset silences the
  `AudioRecord` (mic returns zeros) and/or `AudioTrack` (output dropped) while our loops
  keep running.
- **Diagnostics live in the build (commit `e4be3d0`):** logs `mic recordingState=` /
  `spk playState=` every ~5 s and on any read/write error, plus `AUDIOFOCUS change=` and
  `requestAudioFocus result=` in `CallManager`. When it recurs, capture:
  ```
  adb logcat -s AudioVoiceEngine:* CallManager:*
  ```
  and check whether `recordingState` leaves RECORDING(3), `playState` leaves PLAYING(3),
  or an `AUDIOFOCUS change=` fires around the failure window.
- **Next step:** reproduce + attach logcat → fix root cause → remove the noisy per-5 s
  diagnostic logs afterward.

## Fixed (this on-device testing pass)
- Hang-up native crash — Opus encoder freed before the send thread was joined (use-after-free) — `ae378a0`
- Mute button state desynced across calls (stale singleton vs. fresh engine) — `a03a873`
- Only one of two simultaneous speakers audible — mixer hard-sum clipping collapse; fixed with an Int-accumulate + soft-knee limiter — `49b9846`
- Other Mumble clients didn't show self-mute — now broadcast `UserState{self_mute}` on toggle — `5107a2c`
- `libdumbleopus.so` 16 KB page alignment (Play compliance) — `-Wl,-z,max-page-size=16384`; all LOAD segments 0x4000 on all ABIs — `1567505`

## Deferred features (tracked as tasks, not defects)
- **#40** voice-activity detection (transmit mode)
- **#53** evaluate 32 kbps CVBR encoder default
- **#54** Bluetooth headset not selected as the initial call audio route
