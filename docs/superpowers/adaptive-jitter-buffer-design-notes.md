# Adaptive Jitter Buffer + Talkspurt/Silence Handling — Design Notes

Basis for the future feature (task #56). Captured 2026-07-13 from on-device debugging +
research into Mumble and WebRTC. **Not yet implemented** — the shipped code uses a fixed
100 ms prebuffer, which does not fix the primary bug below.

## The primary bug: talkspurt/silence-boundary late-drops

On-device counters (build with `lateDrops`): `voiceRx` and `udpAudioRx` match 1:1,
`decryptFail=0`, `lost` tiny — every voice packet arrives and decodes intact — yet
**`lateDrops` ≈ 30 % of received voice and climbing**, and the user hears mostly silence
with occasional bursts.

**Mechanism (corrected 2026-07-17 — supersedes the original "paused sender" account).**

Verified against upstream Mumble source and this repo's code:
- **The sender does NOT pause `frame_number`.** `AudioInput::encodeAudioFrame` runs
  `iFrameCounter++` as its first statement, *before* the `!bIsSpeech && !bPreviousVoice` early
  `return`, so a desktop-Mumble VAD sender's `frame_number` **advances at wall-clock through
  silence** (resets only after ~500 silent frames ≈ 5 s). The #56 investigation (2026-07-13)
  predated our own VAD sender (#40, 2026-07-14), so the test peer was a *stock desktop Mumble*
  client — a wall-clock sender — not our then-frozen counter.
- **Pre-fix the receiver cursor advanced at wall-clock during silence** (`plcStep` did
  `cursor += 960` on live underrun), and the playback loop is hard-paced at real-time (it always
  writes a full 20 ms, so `AudioTrack` never drains → no buffer-refill burst over-advances it).

So the original "both sides paused → `ts == cursor`" story is **wrong**: with a wall-clock sender
the cursor and the sender advance in lockstep, which cannot by itself produce late drops. Renaming
"pauses"→"advances" would just make that account self-contradictory.

**Mechanism (fable-verified 2026-07-17): stall-and-burst (delay-spike) delivery — NOT cushion
consumption, NOT a paused sender.**

An intermediate guess — "prebuffer-cushion consumption" — was **refuted** by fable. Wall-clock PLC
*preserves* the cushion, it does not consume it: through both decode and pre-fix `plcStep` the cursor
advances 960 samples per DAC-paced 20 ms tick = 48000 samples/s — exactly the sender's `iFrameCounter`
rate — so the playout delay `D = t_wall − cursor` is **constant**, and the cursor stays a fixed cushion
`C` (~80–100 ms) *behind* the live edge for any silence-gap length. It is never "pinned to the live
edge"; an empty buffer ≠ zero timing margin. The arithmetic: a packet captured at time τ with excess
delay `j` over the anchor-baseline latency is late iff **`j > C`** — a per-packet condition with *no
gap-length term*. A silence resume is captured fresh (`j ≈ 0`) and clears the cursor by `C`, so it is
essentially never late, for any gap. And even a hypothetical cushion-less state self-limits: after
`consecutivePlc == 10` (~200 ms) the stream retires, is reaped within one tick, and the next packet
builds a fresh stream (sentinel cursor 0) that re-prebuffers — capping any episode at ≤10 drops.
Neither can yield a steady, climbing 30 %. (It also contradicts the observation below that enlarging
the prebuffer barely moved `lateDrops` — raising `C` would have fixed a cushion-margin bug.)

**What actually happened:** delayed-burst delivery of **mid-talkspurt** audio during *network* stalls
— Wi-Fi power-save/DTIM clumping, BT-coexistence, the observed UDP cutout, TCP-tunnel head-of-line —
not the VAD silence structure. Packets captured *during* a stall are in flight and arrive as a burst
when the link releases. Pre-fix the cursor marched at wall-clock through the stall (underrun →
`plcStep`), so at release `cursor = t_release − L − C` and every burst packet with `ts < cursor` — all
but the newest `C` worth — is rejected late. Per stall of length `S`: `drops ≈ (S − C)/20 ms`, capped
at 10 by the retire path; with clumped delivery at ~1–2 Hz and `S ≈ 200–350 ms` (textbook power-save/
coex behavior) the steady rate is ~15–35 %. This fits every observation the silence story could not:
packets all *arrive* 1:1 (`voiceRx == udpAudioRx`, `decryptFail = 0`, tiny `lost` — delay, not loss);
"mostly silence with bursts"; the prebuffer 40→100 ms barely moved it (`drops ∝ S − C`, `ΔC ≪ S`); and
`lateDrops ≈ 0` after the fix.

**Why the fix works (real reason):** holding the cursor on live underrun keeps it at the end of
received audio through the stall, so the delayed burst arrives at `ts ≥ cursor` and queues (600 ms
high-water) or, after 10 held ticks, `resetToIdle` un-anchors and accepts everything. The
talkspurt/silence framing was incidental. (Fable also flagged a secondary pre-fix latent bug the
restructure removed: a packet arriving in the one tick after `consecutivePlc` hit 10 with a
*non-empty* buffer could wedge `fillTick` forever — a permanent per-speaker mute with *zero* further
`lateDrops` — which also contributed to the "mostly silence" symptom.)

**Decisive on-device confirmation (final step — the mechanism is analysis-verified, not yet observed
live):** at each LATE reject log `latenessMs = (cursor − ts)/48`, the inter-arrival gap for that
speaker, and whether `ts` is *contiguous* with the pre-gap ts sequence (`arrivalNanos` is already
plumbed and unused). Stall-burst signature: consecutive-ts runs of up to 10 lates right after an
arrival gap, `ts` contiguous across the gap, lateness descending ~20 ms per packet (the `S − C`
staircase). Refuted silence-resume signature: isolated single lates whose `ts` *jumps* by the gap
length. The ts-contiguity bit discriminates the two per event.

**This is not a buffer-size problem** — enlarging the prebuffer 40→100 ms barely moved `lateDrops`
(consistent with `drops ∝ S − C`). The cure is **holding the cursor through underruns** so delayed
real audio still lands at/after it, plus boundary reset/retire hygiene — not a larger static buffer.

### Fix direction for the silence half
- **Wire the incoming `is_terminator` flag through the seam.** `VoiceTransport.onPlaintext`
  parses `Audio.is_terminator` but `VoiceEngine.onIncomingFrame` has no param for it, so the
  engine only infers a terminator from an empty payload. Add it (additive param or a parallel
  signal) so `SpeakerStream` knows a talkspurt ended.
- On a terminator (or a real sustained underrun), mark the stream idle and **re-anchor on the
  next packet** with a fresh prebuffer — never compare a new-talkspurt packet against the old
  cursor.
- Do **not** advance the cursor at wall-clock during silence; play silence in place and let the
  next talkspurt define the new anchor.

## Reference 1 — Mumble (libspeexdsp `JitterBuffer`)

Mumble creates one `jitter_buffer_*` per speaking user (`AudioOutputSpeech.cpp`), keyed by
`timestamp = iFrameSize * frameNumber` (same frame-number scheme we use). Per packet it records
a signed "slack" sample (`timestamp - arrival - margin`) into 3 rotating sorted windows
(`MAX_TIMINGS=40`, `max_late_rate=4 %` → ~10 s window). `compute_opt_delay()` minimizes
`cost(delay) = -delay + late_factor·(#late-at-delay)` over the 40 worst samples, with
`late_factor` auto-tuned from the recent jitter spread and a hysteresis penalty to avoid
flip-flop. Result: **STRETCH** (insert concealment, grow margin) when `opt<0`, **SKIP**
(fast-forward `pointer_timestamp`, shrink margin) when `opt>0`.

**Artifact avoidance:** the actual grow/shrink is only executed (a) on a genuine miss
(concealment already playing) or (b) after a decoded frame whose energy is in the **bottom 1 %
of the observed dynamic range** (`fPowerMin/fPowerMax` asymmetric envelope tracker) — i.e.
adjustments land in near-silence. **Mumble uses no WSOLA** — its shrink is a raw frame-skip
gated on that quiet test.

## Reference 2 — WebRTC NetEq

- **Arrival stat** (`PacketArrivalHistory`): relative delay
  `(arrival_i - arrival_min) - (rtp_i - rtp_min)` vs. the fastest packet in a 2000 ms window —
  buffer-state-independent, drift-cancelling, never negative.
- **Target delay** (`UnderrunOptimizer`): peak-hold the max relative delay per 500 ms bucket,
  feed into a forgetting-factor histogram (steady-state 0.983, fast initial ramp), take the
  **95th percentile**; a parallel `ReorderOptimizer` minimizes `delay + 100·loss%`. Cold start
  `kStartDelayMs=80`. Clamp target ≤ 75 % of physical buffer capacity.
- **Enact** (`DecisionLogic` + `time_stretch`): compare a smoothed buffer level to the target;
  `Accelerate`/`PreemptiveExpand` (minimal WSOLA: 4 kHz autocorr, 2.5–15 ms pitch lag, cross-fade
  one pitch period in/out if correlation > 0.9, else decline) grow/shrink by one pitch period,
  rate-limited to ~1 op / 50 ms; `Expand` = PLC; `Merge` cross-fades concealment back into
  resumed audio. Glitch-free because edits happen at points of maximal self-similarity.

## Concrete Kotlin design (fable's recommendation)

Structures: `JitterBuffer` (sample/time-domain, `timestampSamples = frame_number*480`),
`SpeakerStream` (playout cursor, lazy Opus decoder, decoded-PCM FIFO, re-anchor + retire),
`AudioVoiceEngine` (owns speakers, `onIncomingFrame(... arrivalNanos)` — `arrivalNanos` is
currently unused; it's the hook).

1. **`DownlinkJitterEstimator`** (new, owned by `AudioVoiceEngine`, pooled across speakers since
   all share one downlink path): per-speaker RTP/arrival baseline (rebased on a new fastest
   packet or after a 2 s gap); relative delay per packet; 500 ms peak-hold buckets over an 8 s
   window (16 buckets); **target = clamp(p95(bucket peaks) + 20 ms, 40 ms, 280 ms)**; cold start
   100 ms. **Feed every arriving packet, including late-dropped ones** (a late drop is the
   strongest evidence to grow).
2. **Grow at talkspurt anchor** — `SpeakerStream` takes `targetPrebufferSamples: () -> Int`
   instead of a fixed value; at `cursor<0` anchor, wait for the current adaptive target. Zero
   glitch risk (nothing playing), no rate limit needed.
3. **Grow mid-spurt** — existing PLC-on-gap already does this (NetEq `Expand`); nothing new.
4. **Shrink mid-spurt** — drop exactly one queued packet, ≤ once / 2 s, only when the last frame
   is in the bottom 1 % of dynamic range (Mumble's energy gate), and only when buffered depth
   exceeds target by a 40 ms deadband.
5. **WSOLA not required initially** (Mumble ships without it). If shrink proves audible, add
   NetEq's minimal pitch-synchronous cross-fade (~100 lines on the decoded PCM) as an alternative
   to the frame-skip.

Constants: bucket 500 ms · window 16×500 ms=8 s · percentile 0.95 · safety +20 ms · MIN 40 ms ·
MAX 280 ms (≤ 75 % of the 600 ms high-water) · cold start 100 ms · shrink deadband 40 ms /
cooldown 2 s · baseline staleness 2 s.

**Order of work:** fix the silence/talkspurt-boundary handling FIRST (it's the dominant bug),
then layer the adaptive delay on top.
