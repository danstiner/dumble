# Opus encoder config (#53) — Evaluation & Decision

**Task:** #53 "evaluate 32 kbps CVBR encoder default." **Outcome:** the config is well-chosen — keep everything except **complexity 5 → 9**. FEC is confirmed a separate receiver-side feature (and a Drumble↔Drumble-only one). All findings fable-verified against the **vendored libopus v1.5.2** source the app builds (`app/src/main/cpp/CMakeLists.txt`), **measured** with `opus_demo` on ~5 min of 48 kHz mono speech (arm64), and grepped against **mainline Mumble master**.

## Current config
`app/src/main/cpp/opus_jni.c` `configureEncoder`: 48 kHz mono, `OPUS_APPLICATION_VOIP`, `OPUS_SET_BITRATE(32000)`, `OPUS_SET_COMPLEXITY(5)`, `OPUS_SET_VBR(1)` + `OPUS_SET_VBR_CONSTRAINT(1)` (CVBR), `OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)`, `OPUS_SET_INBAND_FEC(0)`, `OPUS_SET_DTX(0)`. Bitrate default from `LibOpusCodec(bitrate = 32_000, complexity = …)`.

## Decisions (per knob)

| Knob | Current | Decision | Rationale (verified) |
|---|---|---|---|
| Bitrate | 32 kbps | **Keep 32k** | Fullband hybrid at 32k; above the speech-quality plateau knee (~24–32k per Xiph recommended settings). Measured avg 31.0 kbps on speech. Mumble defaults to 40k **CBR** (`Settings.h iQuality=40000`, `AudioInput.cpp OPUS_SET_VBR(0)`) — roughly parity per delivered bit. 24k would save ~25% with less margin; not worth it. |
| VBR mode | CVBR | **Keep CVBR** | At 32k speech, CVBR and unconstrained VBR produced **bit-identical** bitstreams across all 14,330 measured frames — the constraint never binds. CVBR is also libopus's default. CBR only buys constant packet size (traffic-analysis property) at the cost of padding. |
| App / signal | VOIP + VOICE | **Keep** | Correct for a speech-only app. |
| **Complexity** | **5** | **→ 9** | **The one change.** Complexity ≥ 7 enables libopus's `run_analysis` (tonality/VAD that drives per-frame VBR allocation), lost entirely at 5, plus a coarser SILK search below 7. Measured cost 5→9 on arm64 release: ~+0.4% of one core (~0.18 ms/20 ms frame) — negligible next to RNNoise/mic. 9 = libopus + Mumble default. |
| FEC | off | **Keep off — separate feature (see below)** | |
| DTX | off | **Keep off** | Redundant with the app's VAD-gated transmit (encoder never sees sustained silence); would only touch hangover/terminator frames and muddy the "silent frame" terminator scheme. Mumble also off. |
| New ctls | — | **None** | `OPUS_SET_LSB_DEPTH` is a no-op with the int16 `opus_encode` API; phase-inversion is stereo-only (Drumble is mono). |

## FEC — why it's out of scope, and the recipe for later

`OPUS_SET_INBAND_FEC(1)` alone changes **nothing** — it's double-gated:
1. `decide_fec` returns 0 unless `OPUS_SET_PACKET_LOSS_PERC` is also > 0 (`opus_encoder.c`), which `configureEncoder` never sets.
2. Even with loss-perc set (burning ~6 kbps of the 32k budget on SILK LBRR), recovery requires the **receiver** to decode the *next* packet with `decode_fec=1`. Drumble's receiver is **PLC-only**: `OpusDecoder.decode` has no fec flag, `LibOpusDecoder.decode` hardcodes `fec=0`, and `SpeakerStream`'s gap paths (`plcHold`/`plcAdvance`/`plcDeepen`) all call `decode(null, …)` = pure PLC.

**Interop caveat (answers "would Mumble clients use it?"):** **mainline desktop Mumble also decodes with `decode_fec=0`** (`AudioOutputSpeech.cpp:348,404`) — it never requests FEC recovery. So Drumble sending FEC would give a desktop-Mumble peer **zero benefit** for the ~6 kbps cost. FEC is therefore a **Drumble↔Drumble-only** optimization (plus any rare third-party client that decode-with-FEC's) — which lowers its priority.

**Recipe if pursued (separate feature):**
- Encoder: `OPUS_SET_INBAND_FEC(1)` + `OPUS_SET_PACKET_LOSS_PERC(n)` (ideally fed from observed loss).
- Receiver: thread a `fec` flag through `OpusCodec.OpusDecoder.decode` → `NativeOpus.decode` (the JNI already accepts it); in `SpeakerStream.plcAdvance` (the "measured hole = lost packet with the next one queued" case), peek the queued next packet and `decode(next, fec = 1, frameSize = gap)` instead of `decode(null)`. `plcHold` (no next packet yet) stays PLC. Needs its own tests.

## Change made
- `OpusCodec.kt`: `complexity` default `5 → 9` (one line + rationale comment). No test changes — the existing encode path/tests are unaffected by the complexity value (encoder output stays a valid Opus stream).
- On-device (Dan's batch): confirm audio quality is unchanged-or-better and CPU/battery are unaffected during a call (expected — measured cost is ~0.4% of a core).
