# Talkspurt / Silence Handling — Design Spec (feature #56, part A)

**Status:** approved design, fable-reviewed. Basis for the implementation plan.
**Date:** 2026-07-13
**Scope:** part **A** only — make received voice continuous across a VAD peer's silences by
fixing the playout cursor's behavior. Keeps the existing **fixed ~100 ms prebuffer**. The
**adaptive** playout delay (part B, NetEq/Speex-style) is a *separate later cycle* and is
explicitly out of scope here (see `docs/superpowers/adaptive-jitter-buffer-design-notes.md`).

---

## 1. Problem (root cause, confirmed from code by fable)

Symptom: remote voice is mostly silence with occasional bursts. On-device counters prove
packets arrive and decode intact (`udpAudioRx == voiceRx` 1:1, `decryptFail=0`, tiny loss) —
but `AudioVoiceEngine.lateDropCount` is ~30 % of received voice and climbing.

Mechanism (traced through the real code):

- Mumble `frame_number` is a **frames-transmitted** sequence in 10 ms units, **not** a
  wall-clock timestamp. A VAD sender (desktop Mumble) **pauses `frame_number` during silence**
  and resumes where it left off. Timestamp = `frame_number × 480` samples
  (`AudioVoiceEngine.kt:93`, `FRAME_SAMPLES_10MS = 480`).
- `SpeakerStream.plcStep()` (`SpeakerStream.kt:69-74`) advances the playout cursor
  `cursor += 960` **per empty tick at wall-clock rate**, including through silence.
- `JitterBuffer.offer` (`JitterBuffer.kt:30`) rejects any packet with
  `timestampSamples < playoutCursor` as "late."
- So during a pause the cursor runs ahead: after *k* empty ticks `cursor = L + 960 + k·960`,
  while the resumed talkspurt arrives at `ts = L + 960 < cursor` → rejected. Because rejects
  keep the buffer empty, PLC keeps advancing the cursor every tick and the two **never
  converge**; escape only happens at `consecutivePlc == 10` (~200 ms,
  `SpeakerStream.kt:39-41`) when the stream retires and is recreated. Natural speech is full of
  sub-200 ms transmission gaps → near-continuous late-drops. That is the observed pattern.

Two refinements fable added that this design must absorb:

1. **The terminator is never recognized today for desktop peers.** `VoiceTransport.kt` parses
   `Audio.is_terminator` but drops it; `AudioVoiceEngine.onIncomingFrame` re-derives
   `isTerminator = (length == 0)` (`AudioVoiceEngine.kt:86`). Upstream Mumble flags the
   terminator on the final **audio-carrying** packet, so the empty-payload inference misses it.
   Only our own client's mute path (empty frame, `AudioVoiceEngine.kt:65`) is seen as a
   terminator today.
2. **"Sub-200 ms pauses"** means sub-200 ms *transmission gaps* (speech pause minus the
   sender's VAD hold). Gaps > 200 ms already recover today via the retire path — badly (churn +
   re-prebuffer), but they recover.

---

## 2. The fix, in one invariant

> **The playout cursor never advances past the end of received audio (last packet's
> `timestamp + span`).**

The bug is entirely that `plcStep` violates this during silence. Everything below enforces it.

### 2.1 Three cursor behaviors (in `SpeakerStream.fillTick`, playback thread)

| Situation | Condition | Action | Cursor |
|---|---|---|---|
| **Due** | `next == cursor` (`else` branch) | `decodeNext()` — decode the queued packet | `= ts + n` (snaps onto sender timeline) |
| **Measured hole** | `next > cursor` (a real future packet is queued) | `plcAdvance()` — conceal one frame to fill packet loss | `+= 960` (bounded by the queued `next`; self-correcting on the next decode) |
| **Live underrun** | `next == null` (nothing queued) | `plcHold()` — conceal one frame for output continuity | **held (unchanged)** ← the fix |

The distinction that makes this safe: the *measured-hole* branch advances **toward a real
received timestamp** and self-corrects (`decodeNext` sets `cursor = ts + n`). Only the
*live-underrun* branch would advance into unknown territory — so it must hold. Silence onset
**always** presents as `next == null` (an empty buffer), never as a measured hole, so it is
never misclassified.

**Effect:** when the peer resumes after a pause, its packet arrives with `ts == cursor` (both
sides paused). `JitterBuffer.offer`'s late check is strict (`ts < playoutCursor`), so **equal
is accepted**, and the `else`/due branch decodes it seamlessly. Sub-threshold pauses become
**zero-loss, zero-reset, zero-re-prebuffer**. No latency accumulates: `plcHold` only runs once
the ~100 ms prebuffer cushion is already drained, and it pushes one frame per tick at the same
rate the FIFO drains, so buffer depth stays put; any concealment injected during a hold is
trimmed by `fifo.clear()` at the next boundary reset (bounded ≤ ~200 ms, self-healing).

### 2.2 Boundary reset (secondary — cleanup, no longer correctness-critical)

A talkspurt boundary is reached when, on a **live underrun**, *either*:

- **`isPastTerminator()`** — a terminator was recognized for this talkspurt and the cursor has
  reached it (immediate reset), **or**
- **`consecutivePlc >= maxHoldTicks`** — we have held through ~200 ms of sustained silence with
  no terminator (the always-on fallback for missing/late/unrecognized terminators).

On a boundary → **reset in place** on the playback thread:

```
cursor = -1
fifo.clear()
consecutivePlc = 0
buffer.clearTerminator()      // clears the stale terminator tag — see §3.2
```

The decoder is **kept** (not destroyed). On the next arriving packet the existing `cursor < 0`
anchor path re-anchors to that packet's timestamp and refills the prebuffer; because the cursor
is un-anchored, `offer` passes `playoutCursor = 0`, so the resumed talkspurt is queued, not
late-dropped.

Because hold-on-underrun already prevents the late-drops, the reset's *timing* is no longer
correctness-critical — it only trims injected concealment and re-arms the prebuffer/terminator
logic for the next talkspurt.

### 2.3 Long-idle retire (decoupled from boundary reset)

Retire (free the decoder, remove the stream) is now **decoupled** from the talkspurt boundary.
A boundary reset keeps the stream and decoder alive. A stream retires only after a **long idle**
— un-anchored (`cursor < 0`) with an empty buffer for `retireIdleTicks` (~10 s). This removes
the per-pause retire churn while still reclaiming decoders for speakers who have gone quiet.

---

## 3. Component changes

### 3.1 Seam — `VoiceEngine.onIncomingFrame` gains `isTerminator`

`app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceEngine.kt`

```kotlin
fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                    frameNumber: Long, senderSession: Int, arrivalNanos: Long,
                    isTerminator: Boolean)
```

Additive param. **Every** `VoiceEngine` implementer and every caller must be updated (compiler
enforces it): `AudioVoiceEngine`, `VoiceTransport` (the caller), and any test doubles /
loopback engines. Update all call sites in the same task.

`VoiceTransport` already parses `audio.isTerminator`; it now **passes it through** instead of
dropping it.

`AudioVoiceEngine.onIncomingFrame` (`AudioVoiceEngine.kt:84-96`):

```kotlin
val terminator = isTerminator || length == 0     // keep the empty-payload inference
...
val queued = stream.offer(frameNumber * FRAME_SAMPLES_10MS, copy, span, terminator)
if (!queued && !terminator) lateDropCount++
```

Keeping `|| length == 0` preserves our own client's empty-frame mute terminator path.

### 3.2 `JitterBuffer` — monotonic terminator tag + `clearTerminator()`

`app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterBuffer.kt`

- **Monotonic set** in `offer` (line 28) — ignore a reordered/older terminator so it can't
  retroactively re-tag a boundary already passed:

  ```kotlin
  if (p.isTerminator) {
      val t = terminatorTimestamp
      if (t == null || p.timestampSamples >= t) terminatorTimestamp = p.timestampSamples
  }
  ```

- **New method** (synchronized):

  ```kotlin
  @Synchronized fun clearTerminator() { terminatorTimestamp = null }
  ```

  Called by `SpeakerStream` on every boundary reset (playback thread). This is the fix for the
  latent bug fable flagged: today the tag is **never cleared**, so after the first talkspurt
  (a) the anchor prebuffer bypass `terminatorTimestamp == null` (`SpeakerStream.kt:35`) is
  defeated forever — every later talkspurt anchors with no prebuffer — and (b) `cursor >= staleT`
  stays true, so the first transient underrun of a *new* talkspurt spuriously retires it.
  Reset-in-place would un-mask both; clearing the tag closes them.

The rest of `JitterBuffer` is unchanged (the strict `<` late check stays; it is correct for
intra-talkspurt reordering once the cursor stops over-advancing).

### 3.3 `SpeakerStream` — hold-on-underrun, boundary reset, long-idle retire

`app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt`

Constructor params (rename `maxConsecutivePlc` → the two intents):

```kotlin
prebufferSamples: Int = FRAME_SAMPLES_20MS * 5,     // ~100 ms (unchanged)
reanchorGapSamples: Long = SAMPLE_RATE.toLong(),    // 1 s forward jump → boundary (unchanged)
maxHoldTicks: Int = 10,                             // ~200 ms held underrun → boundary reset
retireIdleTicks: Int = 500,                         // ~10 s un-anchored+empty → retire
```

New field: `private var idleTicks = 0`.

`fillTick` structure (playback thread):

```kotlin
fun fillTick(out: ShortArray): Boolean {
    if (cursor < 0) {                                   // un-anchored
        val first = buffer.peekFirstTimestamp()
        if (first == null) {                            // idle: nothing to play
            if (++idleTicks >= retireIdleTicks) retired = true
            return false                                // caller ignores `out` when !produced
        }
        idleTicks = 0
        if (buffer.bufferedSamples() < prebufferSamples &&
            buffer.terminatorTimestamp == null) {       // wait for prebuffer (or fast-start if terminated short)
            return false
        }
        cursor = first
        consecutivePlc = 0
    }
    while (fifo.size < FRAME_SAMPLES_20MS) {
        val next = buffer.peekFirstTimestamp()
        if (next == null) {                             // live underrun
            if (isPastTerminator() || consecutivePlc >= maxHoldTicks) { resetToIdle(); break }
            plcHold(); break                            // conceal, HOLD cursor
        }
        when {
            next > cursor + reanchorGapSamples -> { resetToIdle(); break }   // big jump → boundary
            next > cursor -> { consecutivePlc = 0; plcAdvance() }            // measured hole
            else -> decodeNext()                                             // due
        }
    }
    val produced = fifo.size > 0
    fifo.drainInto(out, FRAME_SAMPLES_20MS)
    return produced
}
```

Helpers:

```kotlin
private fun plcHold() {                 // live underrun — do NOT advance cursor
    consecutivePlc++
    val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
    fifo.push(decodeOut, n)
}
private fun plcAdvance() {              // measured hole — advance toward the queued packet
    val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
    fifo.push(decodeOut, n)
    cursor += FRAME_SAMPLES_20MS
}
private fun resetToIdle() {             // boundary reset — playback thread only
    cursor = -1
    fifo.clear()
    consecutivePlc = 0
    buffer.clearTerminator()
}
```

`decodeNext` is unchanged (already sets `cursor = p.timestampSamples + n` and
`consecutivePlc = 0`). `isPastTerminator()` is unchanged. The old `idleOrRetire()` and the
`consecutivePlc >= maxConsecutivePlc` retire block are replaced by the structure above.

Notes:
- `reanchorGapSamples` now routes through `resetToIdle()` so a big forward jump gets a fresh
  prebuffer instead of anchoring bare. `resetToIdle()` does **not** drop the queued far-future
  packet (it clears the FIFO and the terminator tag, not the buffer queue), so the next tick
  anchors to it.
- `consecutivePlc` now counts **only** held (live-underrun) ticks; `plcAdvance`/`decodeNext`
  reset it. This makes `maxHoldTicks` a "sustained silence" detector, not a packet-loss limiter
  (packet loss is bounded instead by `reanchorGapSamples`).

### 3.4 Decoder is kept across boundaries

Confirmed correct: `opus_decode` with a valid packet is well-defined from any decoder state
(state affects only a few ms of prediction/overlap). `NativeOpus` exposes no `OPUS_RESET_STATE`,
so "resetting" would mean destroy/recreate anyway — pointless. Keeping matches our own
Drumble↔Drumble path (the encoder is created once and never reset across mute cycles). No
change beyond *not* retiring on short pauses.

---

## 4. Threading invariants (must hold)

- `cursor` has a **single writer**: `fillTick` (playback thread). `offer` only **reads** it
  (`SpeakerStream.kt:26`); it stays `@Volatile`.
- `fifo` is **playback-thread-only** and not thread-safe. `resetToIdle()` touches `fifo` and
  `cursor`, so it **must run inside `fillTick`** on the playback thread. A terminator arriving
  on the receive thread only sets `JitterBuffer` state (via the synchronized `offer`) that the
  playback thread later observes — **never** call `resetToIdle` from the receive path.
- `JitterBuffer` methods stay `@Synchronized`; `clearTerminator()` is synchronized too.

---

## 5. Testing (JVM unit tests — no device)

`SpeakerStream` + `JitterBuffer` are pure JVM logic; tests use a deterministic fake `OpusCodec`
whose `decode` returns a known sample count and whose `packetSamples` returns a fixed span
(follow the existing fake in the current voice tests). Each test drives `offer(...)` then pumps
`fillTick(out)` and asserts on produced/latched samples and `lateDrops`.

Required cases:

1. **Hold-on-underrun resume (the core fix).** Anchor a talkspurt, feed N frames, skip M ticks
   (no offers) so the cushion drains and `plcHold` runs, then resume at the *continued*
   timestamp (`ts == cursor`). Assert the resumed frames decode and play; **no** frames rejected
   as late.
2. **VAD-peer silence.** Feed a talkspurt whose `frame_number` then **pauses** (gap with no
   advance) and resumes at the paused value. Assert continuity and `lateDrops == 0` (this is the
   exact production failure).
3. **Recognized terminator boundary.** Talkspurt ending with `isTerminator = true` (on an
   audio-carrying frame), then a *new* talkspurt. Assert: first fully plays; on the underrun
   after the terminator the stream resets; the second talkspurt **re-anchors with a fresh
   prebuffer** (its first packet does not play until `prebufferSamples` buffered).
4. **Stale-terminator regression guard.** After case 3's reset, assert the second talkspurt's
   anchor **honors the prebuffer gate** (tag was cleared — not bypassed) and does **not**
   spuriously retire on its first transient underrun.
5. **Sustained silence, no terminator (fallback).** Talkspurt, then sustained underrun for
   `> maxHoldTicks` with no terminator → reset; a later talkspurt re-anchors. Assert PLC does not
   run unbounded and the resume is not late-dropped.
6. **Short pause keeps the stream (no churn).** A sub-`maxHoldTicks` gap: assert `retired ==
   false` and `decoderCreated` stays `true` across the gap (decoder not recreated).
7. **Long-idle retire.** Un-anchored + empty for `retireIdleTicks` → `retired == true`.
8. **Measured-hole (packet loss) still works.** A 1–2 frame hole with a real future packet
   queued → `plcAdvance` fills it, then decodes; assert no regression and cursor tracks the
   sender timeline.
9. **Monotonic terminator.** An out-of-order older terminator does not overwrite a newer tag and
   does not cause a spurious reset.
10. **`JitterBuffer.clearTerminator()`** unit: tag set → cleared → `terminatorTimestamp == null`.

All existing voice unit tests must still pass (89 green today).

## 6. On-device verification (user gate)

Build, install, connect to the user's live Mumble server with a **desktop Mumble** peer (VAD
sender) also connected. Hold a real back-and-forth conversation for ≥ 60 s over **UDP**
transport, then capture the periodic stats from `adb logcat`.

Pass criteria:
- Remote speech is **continuous and intelligible** — not "mostly silence with occasional
  bursts."
- `lateDrops` stays **≈ 0** and does not climb (was ~30 % of `voiceRx` and climbing).
- `udpAudioRx == voiceRx` (1:1) and `decryptFail == 0` (unchanged — proves the fix is in
  playout, not transport).

## 7. Out of scope / deferred (agreed with fable)

- **Adaptive playout delay** (part B) — the whole point of keeping the fixed 100 ms prebuffer
  here. `docs/superpowers/adaptive-jitter-buffer-design-notes.md`.
- **10 ms-frame senders**: `plcAdvance` advancing a flat 960 can overshoot a 480-aligned gap by
  one tick (rare, non-fatal). Defer the `min(960, next - cursor)` refinement to part B.
- **`OPUS_RESET_STATE`** exposure in `NativeOpus` — not needed for correctness.
- **Splitting `lateDropCount`** from duplicate/high-water rejects — nice for cleaner validation;
  optional. (Today `lateDropCount` also counts dup/high-water; with the fix it should fall to
  near zero regardless.)
- **Cleanup**: remove the per-5 s debug logs (Ping tick, mic/track state, mix peaks, uplink
  kbps) when this lands — tracked in `docs/BUGS.md`.

## 8. References

- Root-cause + research notes: `docs/superpowers/adaptive-jitter-buffer-design-notes.md`
- Bug entry: `docs/BUGS.md` → "Received audio drops as 'late'"
- Protocol reference: `docs/mumble-protocol.md`
- Code: `voice/SpeakerStream.kt`, `voice/JitterBuffer.kt`, `voice/AudioVoiceEngine.kt`,
  `voice/VoiceEngine.kt`, `voice/VoiceTransport.kt`, `voice/OpusCodec.kt`, `voice/NativeOpus.kt`
