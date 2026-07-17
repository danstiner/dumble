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

**Leading hypothesis — prebuffer-cushion consumption (load-bearing; NOT yet fable/empirically
confirmed, do not finalize the spec on it).** Playout normally runs ~100 ms behind the live edge
(the prebuffer), so jittered/late packets still land in the cursor's future. On a gap longer than
that cushion the buffer drains; pre-fix, live underrun kept advancing the cursor at wall-clock
(`plcStep`) **without re-prebuffering** — a sub-200 ms gap never hits the retire/re-anchor path
(verified in the pre-fix `fillTick`) — so the stream then runs *cushion-less*, cursor pinned to the
live edge. A resumed packet was sent one network-latency `L` ago, so it arrives with `ts ≈ liveEdge
− L < cursor` → rejected late; rejects keep the buffer empty, the cursor keeps tracking the live
edge, and the cushion never rebuilds until the ~200 ms retire re-anchors + re-prebuffers. That
self-sustaining cushion-less state — not a paused counter — is the candidate driver of `lateDrops ≈
30 %`. The fix (hold cursor on live underrun) keeps the cursor at the end of received audio =
cushion restored; resumed packets then satisfy `ts ≥ cursor` and decode. Matches lateDrops ≈ 0.

**Why this is still a hypothesis:** a back-of-envelope model with typical `L < cushion` does not
obviously yield a *steady* 30 %, so the exact trigger (gap-length distribution vs cushion, PLC 20 ms
quantization, jitter tail, or sender/receiver clock drift) is unresolved from static analysis. Decide
it empirically before rewriting the spec's mechanism: instrument an on-device run to log `resumeTs`
vs `cursor` per gap, or build a sim harness feeding a wall-clock-sender + latency model through the
pre-/post-fix `SpeakerStream`.

**This is not primarily a buffer-size problem** — enlarging the prebuffer to 100 ms barely
moved `lateDrops`. The fix is **re-anchoring the cursor at talkspurt boundaries** and **not
advancing the cursor at wall-clock through inter-talkspurt silence**.

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
