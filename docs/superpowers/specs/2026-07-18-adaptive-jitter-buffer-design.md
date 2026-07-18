# Adaptive Jitter Buffer (#56 Part B) — Design

**Goal:** Replace the static ~100 ms playout prebuffer with a **per-speaker adaptive prebuffer target** that starts at the floor and grows only as much as each speaker's measured network jitter requires — cutting playout latency on good paths without reintroducing the late-drop starvation the fixed 100 ms was guarding against.

**Architecture:** A new pure, JVM-testable `DownlinkJitterEstimator` (one per `SpeakerStream`, fed every arriving packet including late-dropped ones) tracks per-speaker relative arrival delay and produces a `@Volatile` target depth. `SpeakerStream`'s anchor gate reads that target instead of a fixed constant; its existing conceal-and-hold underrun path (`plcHold`) provides Mumble-style mid-spurt growth up to the target/MAX; shrink happens only at the next talkspurt anchor. A small diagnostics readout surfaces the target + measured jitter so the win is verifiable on-device (using the latency HUD shipped in the prior feature).

**Tech Stack:** Kotlin, existing `SpeakerStream`/`JitterBuffer`/`AudioVoiceEngine` in `mumble/voice`, kotlinx StateFlow, Compose (diagnostics), JUnit4.

**User decisions (already made):**
- Scope: **adaptive-at-anchor only** — no mid-spurt *shrink*, no WSOLA. (Mid-spurt *grow* via concealment is in.)
- Posture: aggressive — cold-start anchors on the first packet, **floor 10 ms**, grow on measured jitter.
- **Per-speaker** estimator (matches Mumble), not pooled.
- Prebuffer target is a real sample/time value (10 ms is meaningful because 10 ms-frame peers exist).

**Prior art:** `docs/superpowers/adaptive-jitter-buffer-design-notes.md` (the #56 notes). Part A (talkspurt/silence handling) already landed — `lateDrops ≈ 0` on-device; **this Part B is a latency play, not a drop fix.**

---

## Context — what exists

- **`SpeakerStream`** (`mumble/voice/SpeakerStream.kt`): per-speaker playout. Anchors the playout `cursor` to the first buffered packet once `buffer.bufferedSamples() >= prebufferSamples` (static `FRAME_SAMPLES_20MS * 5` = ~100 ms). Re-anchors from scratch at every talkspurt boundary (`resetToIdle` on terminator / `maxHoldTicks`=10 held-underrun ticks / big forward gap). `plcHold()` conceals and **holds the cursor** on live underrun (this is the grow primitive). `offer(timestampSamples, opus, spanSamples, isTerminator)`.
- **`JitterBuffer`** (`mumble/voice/JitterBuffer.kt`): sample-domain reorder buffer, `highWaterSamples` = 28800 (~600 ms) cap. `offer(p, playoutCursor)` returns `LATE` when `ts < cursor`. Tracks `bufferedSpans` in real samples.
- **`AudioVoiceEngine.onIncomingFrame(opusData, offset, length, frameNumber, senderSession, arrivalNanos, isTerminator)`** (`mumble/voice/AudioVoiceEngine.kt:304`): computes `span = codec.packetSamples(...)` (real per-packet duration via `opus_packet_get_nb_samples` — 10/20/40/60 ms peers all handled), `tsSamples = frameNumber * FRAME_SAMPLES_10MS` (480-sample granularity), `speakers.computeIfAbsent(senderSession) { SpeakerStream(codec) }`, then `stream.offer(...)`. **`arrivalNanos` is already plumbed here but not passed into `offer` — that is the estimator hook.**

## Verified facts (fable, 2026-07-18, against Speex/Mumble/WebRTC source)

Load-bearing claims, fact-checked against `speexdsp/libspeexdsp/jitter.c`, Mumble `AudioOutputSpeech.cpp`/`Settings.h`, WebRTC `neteq/delay_manager.cc`:

1. **Mumble grows mid-spurt via ungated Opus-PLC insertion on underrun** (`jitter_buffer_get` → `JITTER_BUFFER_INSERTION` → `opus_decode(nullptr)` PLC frame, 10 ms step). It **shrinks** mid-spurt only when the just-decoded frame is in the **bottom ~1 % of the `fPowerMin`/`fPowerMax` dynamic-range envelope** (near-silence). → Our plan mirrors this: `plcHold` *is* the ungated PLC grow; "shrink only at the next talkspurt anchor" is the analog of the quiet-gated shrink.
2. **Neither Speex nor Mumble caps max delay** — Speex has no `SET_MAX_LATENCY` ctl; growth is bounded only by a ±682 ms internal timing clamp. NetEq caps target at `min(maximum_delay_ms, 75 % of buffer)`. → Our MAX must do real work Mumble delegates to its cost function.
3. **280 ms is too low.** Wi-Fi power-save (DTIM 100–300 ms) and cellular bursts routinely add 150–300 ms; 280 ms leaves no headroom above one stall. → **target `MAX` = 400 ms** (under 75 % of the 600 ms high-water = 450 ms; G.114 puts ~400 ms one-way at the edge of acceptable). The 600 ms high-water stays the larger backstop so packets keep queueing during a stall.
4. **Late-dropped packets MUST feed the estimator** — Speex feeds late packets to `update_timings`; excluding them is survivorship bias that blocks growth exactly when the buffer is too small.
5. **Relative-delay metric is offset-cancelling, not drift-cancelling** — subtracting a window-local minimum bounds oscillator-skew error to ≤~1.5 ms/30 s; an all-time minimum would accumulate skew. Use **window-local minima**.
6. **Units:** differencing a nanotime arrival against a sample-domain timestamp is dimensionally invalid — convert to one unit. All timestamps are multiples of 480 samples, and 480 samples = 10 ms exactly, so `rtpNanos = (rtpSamples / 480) * 10_000_000` is exact.
7. **Estimator statistic:** p95 over peak-hold buckets is sound (mirrors NetEq's arrival-history + quantile), but 500 ms buckets make it a *robust max*; **200 ms buckets** (~40 over an 8 s window) make p95 a real quantile that ignores a one-off spike (top ~2 of 40 excluded) while covering recurring bursts. (NetEq's own forget factor is `kIatFactor`=0.9993, quantile `kLimitProbability`=0.95.)

## Components

### 1. `DownlinkJitterEstimator` — new, pure, per-speaker, JVM-testable

No Android deps. One instance per `SpeakerStream`. Fed on the **receive thread** for every arriving voice packet (including late-dropped); exposes a `@Volatile` target read on the **playback thread**.

**Per-packet input:** `rtpSamples: Long` (the packet's `tsSamples`), `arrivalNanos: Long`.

**Metric (nanoseconds, integer-exact):**
- `rtpNanos = (rtpSamples / 480L) * 10_000_000L` (exact; all ts are multiples of 480).
- `d = arrivalNanos - rtpNanos` (arrival-minus-expected; carries a fixed offset + slow skew).
- `relDelay = d - windowMinD` where `windowMinD` = minimum `d` over the live window (window-local baseline → offset/skew-cancelling, always ≥ 0).

**Peak-hold buckets:** a ring of **40 buckets × 200 ms = 8 s window**. Bucket boundaries are keyed off `arrivalNanos` (bucket index = `arrivalNanos / 200ms`). Each live bucket stores `minD` and `maxD` seen in its 200 ms slot. Buckets older than the window (index gap ≥ 40) are recycled/zeroed as time advances — no wall-clock timer needed; advancement is driven by arriving packets.

**Target computation (on each fed packet, cheap O(40)):**
- `windowMinD = min(bucket.minD over live buckets)`.
- `bucketPeaks = [bucket.maxD - windowMinD for each live bucket]` (each ≥ 0).
- `targetNs = clamp(percentile95(bucketPeaks), FLOOR_NS, MAX_NS)`. No additive margin — peak-hold-per-bucket already lifts the value above the packet-level p95, and that *is* the burst headroom (see Estimator rationale).
- Publish `@Volatile targetSamples = targetNs * 48 / 1_000_000` (ns → samples; 1 ms = 48 samples).

`percentile95` of N bucket peaks = the value at index `ceil(0.95·N) - 1` of the ascending-sorted peaks (N=40 → index 37, i.e. 3rd-largest → a single spike bucket is excluded). Cold start (no buckets yet) → `targetSamples` = the floor.

**Constants:**
| Name | Value | Note |
|---|---|---|
| `FLOOR_NS` | 10 ms | 480 samples; real for 10 ms-frame peers |
| `MAX_NS` | 400 ms | adaptive **target** cap — the only playout-delay ceiling; under 75 % of the 600 ms high-water |
| `BUCKET_NS` | 200 ms | peak-hold slot |
| `WINDOW_BUCKETS` | 40 | 8 s window |
| `PERCENTILE` | 0.95 | of bucket peaks |

`targetNs` clamps to `[FLOOR_NS, MAX_NS]`. **There are exactly two ceilings in the whole design:** this adaptive **playout-delay target** (`MAX_NS` = 400 ms) and the unchanged **`JitterBuffer` high-water** (600 ms) — the high-water is deliberately larger so packets keep queueing during a stall even when the target is saturated. No separate hold ceiling (the mid-spurt grow in §2 deepens toward the same `MAX_NS`), and no `+margin` fudge (peak-hold *is* the headroom).

**Estimator rationale (vs NetEq / Speex).** NetEq peak-holds relative delay into buckets, feeds them into an exponentially-forgetting histogram (100 bins, decayed every packet), and takes the 0.95 quantile of that distribution. We keep the peak-hold buckets but take **p95 directly over the last 40 bucket peaks** — dropping NetEq's forgetting-histogram layer entirely (nothing to decay per packet). Bucket size is the accuracy knob: at 500 ms (~16 buckets) p95 barely filters and tracks the single worst window (a robust max → over-buffers on one-off spikes); at **200 ms (~40 buckets) p95 excludes the top ~2 buckets**, so an isolated spike is ignored while a recurring burst (several buckets) still drives growth — a lower, more responsive target. The peak-hold (worst-per-bucket) covers short bursts a raw packet-level p95 would miss, which is why no separate safety margin is needed. Speex's cost-function optimizer is deliberately *not* adopted — it's coupled to a per-quiet-frame WSOLA micro-adjust we don't do.

**Thread-safety:** all mutation on the receive thread (single writer); the only cross-thread read is `targetSamples` (`@Volatile`, single Int) — identical to the latency-EMA pattern already in the codebase.

### 2. `SpeakerStream` — adaptive anchor + Mumble-style mid-spurt grow

- Constructor: replace `prebufferSamples: Int` with an owned `DownlinkJitterEstimator` (default real one; injectable for tests) and read `estimator.targetSamples` at the anchor gate. The gate becomes:
  `if (buffer.bufferedSamples() < estimator.targetSamples && buffer.terminatorTimestamp == null) return false`.
  Floor = 10 ms (480), so on a clean path the first packet (≥ 480 samples) anchors immediately; on a jittery path the gate waits for the grown target. Quantization to whole packets is automatic (packets arrive in ≥480-sample spans).
- `offer(timestampSamples, opus, spanSamples, isTerminator, arrivalNanos)` — add `arrivalNanos`; feed `estimator.onPacket(timestampSamples, arrivalNanos)` at the top of `offer` for **every** packet (so late-dropped ones still feed growth), then delegate to `buffer.offer(...)` as today.
- **Mid-spurt grow (the valve):** Part A's underrun path is unchanged — `plcHold()` conceals + holds the cursor, `resetToIdle` after `maxHoldTicks` (200 ms). The *new* piece covers the one case fable flagged that at-anchor growth misses: a long continuous spurt whose jitter rises but **never fully underruns** (some packets on time, some `LATE`) — with no underrun there's no re-anchor, so the grown target never gets applied and late-drops persist to the talkspurt end. Fix: on **sustained late-drops without underrun** (≥ 3 `LATE` in 200 ms), deepen by concealing **one** PLC frame while **holding the cursor** for that tick (the same `plcHold` primitive), so the next real packets land further ahead of the cursor. Deepen only until buffered depth reaches the current adaptive target (`MAX_NS`-capped). **This is exactly Mumble's grow path** — PLC insert + pointer hold. No new ceiling.
- **Shrink** is unchanged from Part A: it happens for free at the next talkspurt anchor (re-anchor to the now-lower target). This is the analog of Mumble's quiet-gated shrink — **the only way our mid-spurt behavior differs from Mumble** is that Mumble can *also* shrink mid-spurt during silence, and we don't. No mid-spurt shrink, no WSOLA.

### 3. `AudioVoiceEngine` — plumb `arrivalNanos` into `offer`

`onIncomingFrame` already has `arrivalNanos`; pass it into the new `stream.offer(tsSamples, copy, span, terminator, arrivalNanos)`. No other change (the `LateDiag`/`lateDropCount` block stays; late drops now also feed the estimator via `offer`).

### 4. Diagnostics readout

Surface the adaptive state so the win is verifiable on-device with the latency HUD. Add to `AudioVoiceEngine` a small aggregate (e.g. the **max `targetSamples` across active speakers**, in ms, plus the max p95 relDelay) exposed as a StateFlow, mirrored on `MumbleManager`, rendered in the **Audio diagnostics** screen under a "Jitter" line: `Target: N ms`, `p95 jitter: N ms`. This lets a dev watch the target fall toward 10 ms on a clean network and grow on a jittery one, and read the playout-latency drop directly from the existing Latency section.

## Data flow

```
onIncomingFrame(arrivalNanos, frameNumber→tsSamples, opus) [receive thread]
        │
        ▼
SpeakerStream.offer(ts, opus, span, term, arrivalNanos)
        ├─► DownlinkJitterEstimator.onPacket(ts, arrivalNanos)   (every pkt, incl. LATE)
        │        └─ relDelay → 200ms peak-hold buckets → p95 → @Volatile targetSamples
        └─► JitterBuffer.offer(...)  (LATE if ts < cursor)
                                   ▲
                                   │ targetSamples (read on playback thread)
SpeakerStream.fillTick() [playback thread]
   anchor gate: bufferedSamples() >= estimator.targetSamples ?
   underrun: plcHold (conceal + hold cursor) up to target/MAX  ← mid-spurt grow
        │
        ▼  (aggregate max target/jitter)
AudioVoiceEngine flow → MumbleManager → AudioDiagnosticsScreen "Jitter"
```

## Testing

- **`DownlinkJitterEstimator`** (pure, JVM): synthetic `(tsSamples, arrivalNanos)` traces —
  - steady low-jitter arrivals → target converges to the floor (10 ms).
  - injected 250 ms stall-and-burst → target grows to cover it (≥ burst − margin), then decays back toward floor once the bursty buckets age out of the 8 s window.
  - a single one-off spike bucket → target unchanged (p95 excludes the top bucket).
  - clamp: pathological jitter → target caps at MAX; empty/cold → floor.
  - window-local minima: a slow monotonic arrival drift does NOT inflate the target (baseline re-bases).
- **`SpeakerStream`**: anchor waits for the (mocked) estimator target; a long continuous underrun holds/deepens up to MAX rather than the fixed 200 ms; re-anchor uses the current target. (Reuse Part A's `SpeakerStream` test harness with an injected estimator/target supplier.)
- On-device verification (Dan's batch): the "Jitter" readout drops toward 10 ms on a clean network and the Latency section's Playout number falls correspondingly; on a jittery path the target grows and `lateDrops` stays ≈ 0.

## Non-goals

- **No mid-spurt shrink, no WSOLA** — shrink is at-anchor only (Mumble's quiet-gated shrink analog). If a long-monologue-on-improving-network case proves to hold latency too long, revisit shrink as a follow-up.
- **No pooled/global estimator** — per-speaker, matching Mumble.
- **No 10 ms output framing** — that's the separate TODO ("why playback is 20 ms not 10 ms"); this feature only lowers the *receive* prebuffer and already benefits 10 ms-frame senders.
- **No change to `JitterBuffer.highWaterSamples`** (600 ms) — it stays the larger backstop above the 400 ms adaptive target cap.
