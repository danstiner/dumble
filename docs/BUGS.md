# Known Bugs / TODO

Running list of bugs found during on-device testing of the audio pipeline. Deferred
*features* are tracked as native tasks (#40, #52, #53, #54); this file is for observed
**defects** — reproducible or not.

## Open

### 🔴 ~45 s downlink UDP voice cutout (both directions go silent)
- **Symptom:** ~45 s into a call, voice dies both ways permanently while the app stays
  "connected." Counters: `pingLoop` alive; TCP + UDP pings round-trip (`good` +1/tick =
  the pong); server's `remoteGood` climbs ~+250/5 s (our uplink voice reaches the server);
  our `good` only rises from the pong (no downlink voice decrypts). Audio devices stay
  RECORDING/PLAYING. Verified NOT audio-focus, NOT buffer-reuse, NOT decrypt-of-pong, NOT
  receive-thread death, NOT tunnel-parse.
- **Root cause (narrowed):** downlink UDP *voice* stops while the UDP *pong* keeps arriving
  on the same mapping/crypt path. So it's either the server ceasing UDP voice to us, or the
  path dropping voice-sized/rate UDP while the sparse pong passes. Not simple NAT expiry
  (pong survives) and not a decrypt failure (pong decrypts).
- **User decision:** keep UDP — the `forceTcp` tunnel mitigation was tried and **reverted**
  (`e7920bf`); we want a UDP-preserving fix.
- **Diagnostic in the build (`e7920bf`):** per-tick `Ping` log now includes `udpAudioRx`
  (decrypted audio packets), `voiceRx` (audio reaching the engine), and `decryptFail`. Next
  repro **with active remote speech** + `adb logcat -s Ping:*` disambiguates: `udpAudioRx`
  climbs but `voiceRx` flat → dropped after decrypt (demux bug); `decryptFail` climbing →
  arriving-but-failing decrypt; both flat → not arriving (server/path).
- **Then fix, UDP-preserving:** target whichever the counters show; only add a *temporary*
  tunnel auto-recover (retry UDP) as a last resort if the path genuinely can't carry UDP.

### 🟠 SpeakerStream retire churn on every speech pause
- **Symptom:** a remote speaker's `SpeakerStream` is retired + recreated on every short
  pause (logs show "new speaker session=N" every 1–4 s), throwing away Opus inter-packet
  decoder state and re-incurring the ~40 ms prebuffer each time → choppy receive.
- **Cause:** `maxConsecutivePlc = 10` (~200 ms) both stops PLC *and* destroys the stream
  (`SpeakerStream.kt`).
- **Fix:** decouple "go idle / stop emitting PLC" from "destroy the stream" — keep the
  stream + decoder alive across short pauses (emit silence), retire only on an explicit
  `is_terminator` **or** a long idle (several seconds).

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

## Deferred features (tracked as tasks, not defects)
- **#40** voice-activity detection (transmit mode)
- **#53** evaluate 32 kbps CVBR encoder default
- **#54** Bluetooth headset not selected as the initial call audio route
