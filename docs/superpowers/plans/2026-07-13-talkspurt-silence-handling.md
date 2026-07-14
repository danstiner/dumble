# Talkspurt / Silence Handling (feature #56 part A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make received voice continuous across a VAD peer's silences by stopping the playout cursor from free-running through silence, and wiring the real `is_terminator` flag through the seam.

**Architecture:** Enforce the invariant "the playout cursor never advances past received audio." On a live underrun `SpeakerStream` **holds** the cursor (conceals but does not advance), so a resumed talkspurt — whose `frame_number` paused during silence — arrives at `ts == cursor` and decodes instead of being late-dropped. Talkspurt boundaries (a recognized `is_terminator` or ~200 ms of sustained hold) reset the stream in place; retire is decoupled to a long idle. `JitterBuffer` gets a monotonic + clearable terminator tag. Fixed 100 ms prebuffer is kept; adaptive delay (part B) is out of scope.

**Tech Stack:** Kotlin, JUnit4 (pure-JVM voice unit tests via `FakeOpusCodec`), Android AGP unit-test task `./gradlew testDebugUnitTest`. No NDK/device work except the final on-device gate.

**User decisions (already made):**
- "Ship (A) first, then (B) separately" — this plan is part A (talkspurt/silence handling); adaptive playout delay is a separate later cycle.
- "approach 1 sounds good" — reset-on-boundary; refined by fable review to **hold-on-underrun** as the primary mechanism.
- Keep the fixed ~100 ms prebuffer (no adaptive delay here).
- Keep UDP for voice (no forced TCP tunnel).
- "Add a task to write tests simulating audio frame decoding … feed multiple frames including gaps and terminations and assert the muxed result is correct. Similar for multiple speakers" → Task 4.

Spec: `docs/superpowers/specs/2026-07-13-talkspurt-silence-handling-design.md`.

---

## File Structure

All under `app/src/main/java/me/danielstiner/dumble/mumble/voice/` (main) and `app/src/test/java/me/danielstiner/dumble/mumble/` (tests).

| File | Change | Task |
|---|---|---|
| `voice/JitterBuffer.kt` | Monotonic terminator tag; add `clearTerminator()` | 1 |
| `voice/JitterBufferTest.kt` | Tests for monotonic tag + clear | 1 |
| `voice/SpeakerStream.kt` | Hold-on-underrun, boundary reset, long-idle retire (rewrite `fillTick`, add `plcHold`/`plcAdvance`/`resetToIdle`, new ctor params/fields) | 2 |
| `voice/SpeakerStreamTest.kt` | Update 2 retire tests to new semantics; add hold/resume/boundary tests | 2 |
| `voice/VoiceEngine.kt` | Add `isTerminator` param to `onIncomingFrame` | 3 |
| `voice/VoiceTransport.kt` | Pass `audio.isTerminator` through `onPlaintext` | 3 |
| `voice/AudioVoiceEngine.kt` | `terminator = isTerminator || length == 0`; new override signature | 3 |
| `voice/SyntheticVoiceSource.kt` | New override signature (body unchanged) | 3 |
| `VoiceTransportTest.kt` | Update 2 test-double signatures; add pass-through test | 3 |
| `SyntheticVoiceSourceTest.kt` | Add trailing `false` to 4 `onIncomingFrame` calls | 3 |
| `voice/VoicePipelineMixTest.kt` | **New** — decode→mix tests (gaps, terminators, multi-speaker) | 4 |

**Constants (from `voice/AudioConstants.kt`, do not redefine):** `SAMPLE_RATE=48000`, `FRAME_SAMPLES_20MS=960`, `FRAME_SAMPLES_10MS=480`, `MAX_FRAME_SAMPLES=5760`.

**Test-fake behavior (from `voice/FakeOpusCodec.kt`, relied on by assertions):** a packet is a 4-byte big-endian sample count; `packetSamples` reads it; `FakeDecoder.decode` returns that many samples of `((i % 100) - 50)` for a real packet, or `plcFrameSamples` samples of `0` when `opus == null` (PLC). So a real 960-sample frame yields `out[0] == -50`; a PLC/hold frame yields `out[0] == 0`. Values stay well under the mixer's `THRESHOLD=26214`, so the mix is an exact integer sum.

---

### Task 1: JitterBuffer — monotonic + clearable terminator tag

**Goal:** Make the terminator tag ignore reordered/older terminators and add a `clearTerminator()` the stream can call on a boundary reset.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterBuffer.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterBufferTest.kt`

**Acceptance Criteria:**
- [ ] `offer` updates `terminatorTimestamp` only when the incoming terminator's timestamp is `>=` the current tag (a later terminator updates it; an older one does not).
- [ ] `clearTerminator()` sets `terminatorTimestamp` to `null`.
- [ ] All existing `JitterBufferTest` cases still pass.

**Verify:** `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.JitterBufferTest"` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Write the failing tests** — append to `JitterBufferTest.kt`:

```kotlin
    @Test fun terminatorTagIsMonotonic() {
        val b = JitterBuffer()
        b.offer(pkt(1920, span = 0, term = true), 0)      // tag = 1920
        b.offer(pkt(960, span = 0, term = true), 0)        // older terminator must NOT lower the tag
        assertEquals(1920L, b.terminatorTimestamp)
        b.offer(pkt(2880, span = 0, term = true), 0)       // newer → updates
        assertEquals(2880L, b.terminatorTimestamp)
    }

    @Test fun clearTerminatorResetsTag() {
        val b = JitterBuffer()
        b.offer(pkt(1920, span = 0, term = true), 0)
        assertEquals(1920L, b.terminatorTimestamp)
        b.clearTerminator()
        assertNull(b.terminatorTimestamp)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.JitterBufferTest"`
Expected: FAIL — `clearTerminator` unresolved; `terminatorTagIsMonotonic` fails (tag currently overwritten to 960).

- [ ] **Step 3: Implement** — in `JitterBuffer.kt`, replace the terminator line in `offer` and add the method:

```kotlin
    @Synchronized fun offer(p: Packet, playoutCursor: Long): Boolean {
        if (p.isTerminator) {
            val t = terminatorTimestamp
            if (t == null || p.timestampSamples >= t) terminatorTimestamp = p.timestampSamples
        }
        if (p.opus.isEmpty()) return false                       // terminator / empty → tag only
        if (p.timestampSamples < playoutCursor) return false      // late
        if (queue.containsKey(p.timestampSamples)) return false   // duplicate
        queue[p.timestampSamples] = p
        bufferedSpans += p.spanSamples
        while (bufferedSpans > highWaterSamples && queue.size > 1) {
            val dropped = queue.pollFirstEntry().value
            bufferedSpans -= dropped.spanSamples
        }
        return true
    }

    @Synchronized fun clearTerminator() { terminatorTimestamp = null }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.JitterBufferTest"`
Expected: PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterBuffer.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterBufferTest.kt
git commit -m "feat(voice): monotonic + clearable JitterBuffer terminator tag (#56)"
```

---

### Task 2: SpeakerStream — hold-on-underrun, boundary reset, long-idle retire

**Goal:** Stop the cursor free-running through silence: hold on live underrun, reset in place at talkspurt boundaries, and retire only after a long idle — so resumed talkspurts decode instead of being late-dropped.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt`

**Acceptance Criteria:**
- [ ] After a live underrun, `cursor` is unchanged (held); a resumed packet at the held timestamp is accepted by `offer` (returns `true`) and decodes.
- [ ] A recognized terminator (or `>= maxHoldTicks` sustained hold) triggers an in-place reset (`cursor = -1`, terminator tag cleared) — the stream is NOT immediately retired.
- [ ] After a boundary reset the next talkspurt re-anchors and honors the prebuffer gate (stale-tag bug is closed).
- [ ] A stream retires (`retired == true`) only after `retireIdleTicks` un-anchored + empty ticks.
- [ ] A short pause keeps the stream and its decoder (`decoderCreated` stays `true`, `retired == false`).
- [ ] Measured holes (a real future packet with a gap) are still concealed then decoded.

**Verify:** `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.SpeakerStreamTest"` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Rewrite `SpeakerStream.kt`** — replace the class body from the constructor through `idleOrRetire()` (keep `offer`, `ensureDecoder`, `decodeNext`, `isPastTerminator`, `close`, and `ShortArrayFifo` as they are, except where shown). Full new top-of-class:

```kotlin
class SpeakerStream(
    private val codec: OpusCodec,
    private val prebufferSamples: Int = FRAME_SAMPLES_20MS * 5,   // ~100 ms
    private val reanchorGapSamples: Long = SAMPLE_RATE.toLong(),  // 1 s forward jump → boundary
    private val maxHoldTicks: Int = 10,                           // ~200 ms held underrun → boundary reset
    private val retireIdleTicks: Int = 500,                       // ~10 s un-anchored + empty → retire
) {
    private val buffer = JitterBuffer()
    @Volatile private var cursor = -1L          // -1 = un-anchored; only fillTick writes it
    private var consecutivePlc = 0              // consecutive HELD (live-underrun) ticks
    private var idleTicks = 0                   // consecutive un-anchored + empty ticks
    private var decoder: OpusDecoder? = null    // playback-thread only
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    private val fifo = ShortArrayFifo(MAX_FRAME_SAMPLES * 4)

    val decoderCreated get() = decoder != null
    var retired = false; private set

    /** Receive thread. Only touches the synchronized JitterBuffer; a slightly stale cursor is safe. */
    fun offer(timestampSamples: Long, opus: ByteArray, spanSamples: Int, isTerminator: Boolean): Boolean {
        val cur = cursor
        return buffer.offer(JitterBuffer.Packet(timestampSamples, opus, spanSamples, isTerminator),
            if (cur < 0) 0 else cur)
    }

    /** Playback thread. Fills [out] (960 samples). Returns true if audio was produced. */
    fun fillTick(out: ShortArray): Boolean {
        if (cursor < 0) {                                       // un-anchored
            val first = buffer.peekFirstTimestamp()
            if (first == null) {                               // idle: nothing to play
                if (++idleTicks >= retireIdleTicks) retired = true
                return false                                   // caller ignores `out` when !produced
            }
            idleTicks = 0
            if (buffer.bufferedSamples() < prebufferSamples && buffer.terminatorTimestamp == null) return false
            cursor = first
            consecutivePlc = 0
        }
        while (fifo.size < FRAME_SAMPLES_20MS) {                // ensure >= one 20 ms frame
            val next = buffer.peekFirstTimestamp()
            if (next == null) {                                // live underrun
                if (isPastTerminator() || consecutivePlc >= maxHoldTicks) { resetToIdle(); break }
                plcHold(); break                               // conceal, HOLD cursor
            }
            when {
                next > cursor + reanchorGapSamples -> { resetToIdle(); break }  // big jump → boundary
                next > cursor -> { consecutivePlc = 0; plcAdvance() }           // measured hole
                else -> decodeNext()                                           // due
            }
        }
        val produced = fifo.size > 0
        fifo.drainInto(out, FRAME_SAMPLES_20MS)                 // pads with silence if < 960
        return produced
    }

    private fun ensureDecoder(): OpusDecoder = decoder ?: codec.newDecoder().also { decoder = it }

    private fun decodeNext() {
        val p = buffer.pollFirst() ?: return
        val n = ensureDecoder().decode(p.opus, 0, p.opus.size, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
        cursor = p.timestampSamples + n
        consecutivePlc = 0
    }

    private fun plcHold() {                 // live underrun — conceal, do NOT advance cursor
        consecutivePlc++
        val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
    }

    private fun plcAdvance() {              // measured hole — conceal AND advance toward the queued packet
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

    private fun isPastTerminator(): Boolean {
        val t = buffer.terminatorTimestamp ?: return false
        return cursor >= t
    }

    fun close() { decoder?.close(); decoder = null }
}
```

(The old `maxConsecutivePlc` field and `idleOrRetire()` method are removed; `ShortArrayFifo` at the bottom of the file is unchanged.)

- [ ] **Step 2: Update the two existing retire tests** in `SpeakerStreamTest.kt` to the new (decoupled) semantics. Replace `retiresAfterTerminatorDrains` and `retiresAfterExtendedDropoutWithoutTerminator` with:

```kotlin
    @Test fun terminatorBoundaryResetsNotRetires() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(960), 960, false)
        s.offer(960, ByteArray(0), 0, true)             // terminator at 960
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))      // plays ts 0..960
        assertFalse(s.fillTick(ShortArray(FRAME_SAMPLES_20MS))) // past terminator → reset (silence)
        assertFalse("boundary resets in place, does not retire", s.retired)
    }

    @Test fun retiresOnlyAfterLongIdle() {
        val s = SpeakerStream(codec, prebufferSamples = 0, maxHoldTicks = 3, retireIdleTicks = 3)
        s.offer(0, encoded(960), 960, false)            // one packet, NO terminator
        val out = ShortArray(FRAME_SAMPLES_20MS)
        // tick1 decode; ticks 2-4 hold; tick5 reset; then idle ticks accumulate to retire
        repeat(15) { s.fillTick(out) }
        assertTrue("retires after sustained silence then long idle", s.retired)
    }
```

- [ ] **Step 3: Add the new behavior tests** — append to `SpeakerStreamTest.kt`:

```kotlin
    @Test fun resumeAfterHoldIsNotLateDropped() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false)
        assertTrue(s.fillTick(out))                     // decode ts0 → cursor held at 960
        assertEquals(-50, out[0].toInt())               // real audio
        repeat(3) { s.fillTick(out) }                   // live underrun → plcHold ×3, cursor STAYS 960
        // peer resumes at the continued timestamp (frame_number paused during silence)
        assertTrue("resume at held cursor is accepted, not late", s.offer(960, encoded(960), 960, false))
        assertTrue(s.fillTick(out))
        assertEquals(-50, out[0].toInt())               // resumed talkspurt decodes (not lost)
    }

    @Test fun terminatorReAnchorsSecondTalkspurtWithPrebuffer() {
        val s = SpeakerStream(codec, prebufferSamples = FRAME_SAMPLES_20MS * 2)  // 1920
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false)
        s.offer(960, encoded(960), 960, true)           // terminated talkspurt 1 (buffered = 1920)
        repeat(4) { s.fillTick(out) }                   // drains both, resets on past-terminator underrun
        assertFalse(s.retired)
        // talkspurt 2: one packet is below prebuffer AND the tag was cleared → must wait
        s.offer(1920, encoded(960), 960, false)
        assertFalse("second talkspurt honors prebuffer (tag cleared)", s.fillTick(out))
        s.offer(2880, encoded(960), 960, false)         // buffered reaches 1920
        assertTrue(s.fillTick(out))                     // prebuffer met → plays
    }

    @Test fun shortPauseKeepsDecoderAndStream() {
        val s = SpeakerStream(codec, prebufferSamples = 0, maxHoldTicks = 10)
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false)
        s.fillTick(out)
        assertTrue(s.decoderCreated)
        repeat(3) { s.fillTick(out) }                   // short hold, well under maxHoldTicks
        assertFalse(s.retired)
        assertTrue(s.decoderCreated)
        assertTrue(s.offer(960, encoded(960), 960, false))
    }

    @Test fun measuredHoleIsConcealedThenDecoded() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false)            // ts 0
        s.offer(1920, encoded(960), 960, false)         // ts 1920 → ts 960 frame is lost (a real hole)
        s.fillTick(out)                                 // decode ts0 → cursor 960
        assertEquals(0, out[0].toInt())                 // wait — see note
        s.fillTick(out)                                 // measured hole: plcAdvance conceals, cursor → 1920
        assertTrue(s.fillTick(out))                     // ts 1920 now due → decodes (not late-dropped)
    }
```

Note on `measuredHoleIsConcealedThenDecoded`: the first `fillTick` decodes ts0, so `out[0]` is `-50`, not `0` — **remove that intermediate assertion** (it was a scratch line); assert only the final `fillTick` returns `true`. Corrected test body:

```kotlin
    @Test fun measuredHoleIsConcealedThenDecoded() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false)            // ts 0
        s.offer(1920, encoded(960), 960, false)         // ts 1920 → ts 960 frame lost (a real hole)
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())  // decode ts0 → cursor 960
        assertTrue(s.fillTick(out)); assertEquals(0, out[0].toInt())    // measured hole → PLC, cursor → 1920
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())  // ts 1920 due → decodes, not dropped
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.SpeakerStreamTest"`
Expected: PASS (all cases, including the unchanged `fortyMsPacketYieldsTwoTicks` and `lazyDecoderNotCreatedOnOffer`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt
git commit -m "fix(voice): hold cursor on underrun; reset at talkspurt boundary; long-idle retire (#56)"
```

---

### Task 3: Wire `is_terminator` through the seam

**Goal:** Deliver the real `Audio.is_terminator` flag to the engine (upstream sets it on the final audio-carrying packet, which the `length == 0` inference misses), keeping the empty-payload inference for our own mute path.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceEngine.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceTransport.kt:75-76`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt:84-96`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SyntheticVoiceSource.kt:72-74`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/VoiceTransportTest.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/SyntheticVoiceSourceTest.kt`

**Acceptance Criteria:**
- [ ] `VoiceEngine.onIncomingFrame` has a trailing `isTerminator: Boolean` param; all four implementers compile.
- [ ] `VoiceTransport.onPlaintext` passes `audio.isTerminator`.
- [ ] `AudioVoiceEngine` computes `terminator = isTerminator || length == 0` and passes it to `stream.offer`; `lateDropCount` increments only when not queued and not a terminator.
- [ ] A new `VoiceTransportTest` proves an incoming `Audio{is_terminator=true}` reaches the engine as `true`.

**Verify:** `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.VoiceTransportTest" --tests "me.danielstiner.dumble.mumble.SyntheticVoiceSourceTest" --tests "me.danielstiner.dumble.mumble.voice.*"` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Interface** — in `VoiceEngine.kt`, change the method to:

```kotlin
    /** Must not block; called from UDP receive thread or TCP reader (tunneled). */
    fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                        frameNumber: Long, senderSession: Int, arrivalNanos: Long,
                        isTerminator: Boolean)
```

- [ ] **Step 2: Caller** — in `VoiceTransport.kt`, the `UDP_TYPE_AUDIO` branch of `onPlaintext`:

```kotlin
                engine.onIncomingFrame(audio.opusData.toByteArray(), 0, audio.opusData.size(),
                    audio.frameNumber, audio.senderSession, arrivalNanos, audio.isTerminator)
```

- [ ] **Step 3: AudioVoiceEngine** — replace `onIncomingFrame` (`AudioVoiceEngine.kt:84-96`):

```kotlin
    /** Receive thread — must not block, must not allocate a decoder. */
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long,
                                 isTerminator: Boolean) {
        val terminator = isTerminator || length == 0     // keep the empty-payload inference (mute path)
        val copy = if (length == 0) ByteArray(0) else opusData.copyOfRange(offset, offset + length)
        val span = if (length == 0) 0 else codec.packetSamples(copy, 0, copy.size)
        val stream = speakers.computeIfAbsent(senderSession) {
            android.util.Log.d("AudioVoiceEngine", "new speaker session=$senderSession (total=${speakers.size + 1})")
            SpeakerStream(codec)
        }
        val queued = stream.offer(frameNumber * FRAME_SAMPLES_10MS, copy, span, terminator)
        if (!queued && !terminator) lateDropCount++
        received.incrementAndGet()
    }
```

- [ ] **Step 4: SyntheticVoiceSource** — update the override signature (`SyntheticVoiceSource.kt:72-74`), body unchanged:

```kotlin
    @Synchronized
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long,
                                 isTerminator: Boolean) {
        if (length < HEADER) return
```

- [ ] **Step 5: Update test doubles** — in `VoiceTransportTest.kt`, the `ScriptedEngine.onIncomingFrame` (line 26) becomes:

```kotlin
        override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                     frameNumber: Long, senderSession: Int, arrivalNanos: Long,
                                     isTerminator: Boolean) {
            incoming.add(Triple(frameNumber, senderSession, arrivalNanos))
        }
```

and the anonymous engine inside `terminatorFrameSetsIsTerminator` (line 97):

```kotlin
            override fun onIncomingFrame(o: ByteArray, off: Int, len: Int, fn: Long, s: Int, a: Long, term: Boolean) {}
```

- [ ] **Step 6: Update SyntheticVoiceSourceTest calls** — append `, false` to each of the four `src.onIncomingFrame(...)` calls (lines 26, 29, 31, 47). Example (line 26):

```kotlin
        src.onIncomingFrame(payload(1_000_000_000L), 0, 40, 0L, 1, 1_015_000_000L, false)
```

Apply the same trailing `, false` to the calls on lines 29, 31, and 47.

- [ ] **Step 7: Add the pass-through test** — append to `VoiceTransportTest.kt`:

```kotlin
    @Test fun passesIsTerminatorThroughOnIncoming() {
        var gotTerminator: Boolean? = null
        val engine = object : VoiceEngine {
            override fun start() {}
            override fun stop() {}
            override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? = null
            override fun onIncomingFrame(o: ByteArray, off: Int, len: Int, fn: Long, s: Int, a: Long, term: Boolean) {
                gotTerminator = term
            }
        }
        val vt = VoiceTransport(engine, { VoiceTransportMode.UDP }, { _, _ -> true }, { _, _ -> true })
        val audio = MumbleUdpProtos.Audio.newBuilder().setSenderSession(1).setFrameNumber(2L)
            .setIsTerminator(true)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(ByteArray(4) { 1 })).build()
        val buf = ByteArray(256)
        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, buf)
        vt.onPlaintext(buf, n, arrivalNanos = 0L)
        assertEquals(true, gotTerminator)
    }
```

- [ ] **Step 8: Run to verify pass**

Run: `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.VoiceTransportTest" --tests "me.danielstiner.dumble.mumble.SyntheticVoiceSourceTest"`
Expected: PASS, including the existing `terminatorFrameSetsIsTerminator` and `routesIncomingAudioAndPing`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceEngine.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceTransport.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/SyntheticVoiceSource.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/VoiceTransportTest.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/SyntheticVoiceSourceTest.kt
git commit -m "feat(voice): wire is_terminator through the onIncomingFrame seam (#56)"
```

---

### Task 4: Decode → mix pipeline tests (gaps, terminators, multi-speaker)

**Goal:** Prove the full decode→mux path is correct end to end: feed scripted frame timelines (contiguous, gap+resume, terminated, and multi-speaker) through `SpeakerStream` + `AudioMixer` exactly as `AudioVoiceEngine.playbackLoop` does, and assert the mixed PCM.

**Files:**
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/VoicePipelineMixTest.kt`

**Acceptance Criteria:**
- [ ] A single speaker's contiguous frames mux to exactly the decoded pattern.
- [ ] A gap muxes to silence during the hold, then the resumed talkspurt reappears (no loss).
- [ ] A terminated frame plays, then the boundary resets to silence.
- [ ] Two simultaneous speakers mux to the exact integer sum.
- [ ] With one speaker in a gap, the mux equals the other speaker's audio alone.

**Verify:** `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.VoicePipelineMixTest"` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Write the test file** — create `VoicePipelineMixTest.kt`:

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the decode → mix path the way AudioVoiceEngine.playbackLoop does: per tick, fillTick
 * every speaker and AudioMixer.accumulate the ones that produced, then finalizeMix. FakeDecoder
 * emits ((i%100)-50) for a real frame and 0 for PLC/hold, both under the mixer THRESHOLD, so the
 * mux is an exact integer sum.
 */
class VoicePipelineMixTest {
    private val codec = FakeOpusCodec()

    /** A 4-byte packet whose header encodes its sample count (matches FakeOpusCodec). */
    private fun frame(samples: Int = FRAME_SAMPLES_20MS): ByteArray {
        val b = ByteArray(4)
        b[0] = (samples ushr 24).toByte(); b[1] = (samples ushr 16).toByte()
        b[2] = (samples ushr 8).toByte();  b[3] = samples.toByte()
        return b
    }

    /** Mirror of AudioVoiceEngine.playbackLoop's per-tick mix. Returns the mixed 20 ms frame. */
    private fun mixTick(streams: List<SpeakerStream>): ShortArray {
        val acc = IntArray(FRAME_SAMPLES_20MS)
        val spk = ShortArray(FRAME_SAMPLES_20MS)
        for (s in streams) if (s.fillTick(spk)) AudioMixer.accumulate(acc, spk, FRAME_SAMPLES_20MS)
        val mix = ShortArray(FRAME_SAMPLES_20MS)
        AudioMixer.finalizeMix(acc, mix, FRAME_SAMPLES_20MS)
        return mix
    }

    private fun assertPattern(mix: ShortArray, gain: Int) {
        for (i in 0 until FRAME_SAMPLES_20MS) assertEquals(gain * ((i % 100) - 50), mix[i].toInt())
    }

    @Test fun singleSpeakerContiguousFramesMuxCorrectly() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, frame(), FRAME_SAMPLES_20MS, false)
        s.offer(960, frame(), FRAME_SAMPLES_20MS, false)
        assertPattern(mixTick(listOf(s)), gain = 1)
        assertPattern(mixTick(listOf(s)), gain = 1)
    }

    @Test fun gapMuxesToSilenceThenResumes() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, frame(), FRAME_SAMPLES_20MS, false)
        assertEquals(-50, mixTick(listOf(s))[0].toInt())     // real audio
        assertEquals(0, mixTick(listOf(s))[0].toInt())       // underrun → hold → silence
        s.offer(960, frame(), FRAME_SAMPLES_20MS, false)     // resume at continued timestamp
        assertEquals(-50, mixTick(listOf(s))[0].toInt())     // resumed audio present, not lost
    }

    @Test fun terminatorEndsTalkspurtInMux() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, frame(), FRAME_SAMPLES_20MS, true)        // single terminated frame
        assertEquals(-50, mixTick(listOf(s))[0].toInt())     // plays
        assertEquals(0, mixTick(listOf(s))[0].toInt())       // past terminator → reset → silence
    }

    @Test fun twoSpeakersMuxAsSum() {
        val a = SpeakerStream(codec, prebufferSamples = 0)
        val b = SpeakerStream(codec, prebufferSamples = 0)
        a.offer(0, frame(), FRAME_SAMPLES_20MS, false)
        b.offer(0, frame(), FRAME_SAMPLES_20MS, false)
        assertPattern(mixTick(listOf(a, b)), gain = 2)       // exact integer sum (both under THRESHOLD)
    }

    @Test fun twoSpeakersOneInGapMuxesActiveOnly() {
        val a = SpeakerStream(codec, prebufferSamples = 0)
        val b = SpeakerStream(codec, prebufferSamples = 0)
        a.offer(0, frame(), FRAME_SAMPLES_20MS, false)
        a.offer(960, frame(), FRAME_SAMPLES_20MS, false)
        b.offer(0, frame(), FRAME_SAMPLES_20MS, false)       // b has only one frame
        assertPattern(mixTick(listOf(a, b)), gain = 2)       // tick 1: both active → sum
        assertPattern(mixTick(listOf(a, b)), gain = 1)       // tick 2: b holds (silence) → only a
    }
}
```

- [ ] **Step 2: Run to verify pass**

Run: `./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.VoicePipelineMixTest"`
Expected: PASS (5 tests).

- [ ] **Step 3: Full suite regression**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures (existing 89 + the new cases).

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/voice/VoicePipelineMixTest.kt
git commit -m "test(voice): decode→mix pipeline tests — gaps, terminators, multi-speaker (#56)"
```

---

### Task 5: On-device verification — lateDrops ≈ 0 against a VAD peer

**Goal:** USER-ORDERED GATE — NON-SKIPPABLE. This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

Confirm on a real device that received voice is continuous and `lateDrops` no longer climbs when a desktop (VAD) Mumble peer is speaking over UDP.

**Files:**
- None (manual on-device verification; uses the existing per-5 s diagnostic logs).

**Acceptance Criteria:**
- [ ] Remote speech from a desktop Mumble peer is continuous and intelligible for a ≥ 60 s conversation — NOT "mostly silence with occasional bursts."
- [ ] In the periodic `adb logcat` stats, `lateDrops` stays ≈ 0 and does not climb over the session (was ~30 % of `voiceRx` and climbing before the fix).
- [ ] `udpAudioRx == voiceRx` (1:1) and `decryptFail == 0` in the same stats (proves the fix is in playout, not transport).

**Verify:** Build + install, connect to the user's live Mumble server with a desktop VAD peer also connected, hold a ≥ 60 s two-way conversation over UDP, and capture the periodic stats:

```bash
./gradlew installDebug
adb logcat -c && adb logcat | grep -E "MumbleManager|AudioVoiceEngine"
```

Expected: voice audible and continuous; a `lateDrops=<small, non-climbing>` alongside `voiceRx`/`udpAudioRx` matching 1:1 and `decryptFail=0`.

**Steps:**

- [ ] **Step 1:** Ensure Tasks 1–4 are merged and `./gradlew testDebugUnitTest` is green.
- [ ] **Step 2:** `./gradlew installDebug` to the test device.
- [ ] **Step 3:** Start `adb logcat -c && adb logcat | grep -E "MumbleManager|AudioVoiceEngine"`.
- [ ] **Step 4:** Connect to the user's Mumble server; have a desktop Mumble client (VAD) join and speak in bursts with natural pauses.
- [ ] **Step 5:** Converse both directions for ≥ 60 s. Capture the periodic stats lines.
- [ ] **Step 6:** Confirm all three acceptance criteria against the captured output. Record the observed `voiceRx` / `udpAudioRx` / `lateDrops` / `decryptFail` values in the task close.

---

## Self-Review

**1. Spec coverage:**
- §2.1 hold-on-underrun / measured-hole / due → Task 2 (`plcHold`/`plcAdvance`/`decodeNext`, tests `resumeAfterHoldIsNotLateDropped`, `measuredHoleIsConcealedThenDecoded`). ✓
- §2.2 boundary reset + keep decoder → Task 2 (`resetToIdle`, `terminatorBoundaryResetsNotRetires`, `terminatorReAnchorsSecondTalkspurtWithPrebuffer`). ✓
- §2.3 long-idle retire decoupling → Task 2 (`retireIdleTicks`, `retiresOnlyAfterLongIdle`, `shortPauseKeepsDecoderAndStream`). ✓
- §3.1 seam → Task 3. ✓  §3.2 monotonic + clearTerminator → Task 1. ✓  §3.4 keep decoder → Task 2 (no destroy on reset). ✓
- §4 threading (reset on playback thread only) → satisfied structurally: `resetToIdle` is only called inside `fillTick`; the receive thread only calls `offer`. ✓
- §5 test matrix → Tasks 1, 2 (unit) + Task 4 (mux/multi-speaker). ✓  §6 on-device gate → Task 5. ✓
- §7 deferred items → intentionally not implemented (adaptive delay, 10 ms-sender granularity, OPUS_RESET_STATE, lateDrop split, debug-log cleanup remain in `docs/BUGS.md`). ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases". The one scratch assertion in Task 2 Step 3 is explicitly flagged and replaced with a corrected test body. ✓

**3. Type consistency:** `plcHold`/`plcAdvance`/`resetToIdle`/`clearTerminator`/`maxHoldTicks`/`retireIdleTicks`/`idleTicks`/`consecutivePlc` are consistent across Tasks 1–4. `onIncomingFrame(..., isTerminator: Boolean)` matches across the interface, all four implementers, and both test doubles. `AudioMixer.accumulate(IntArray, ShortArray, Int)` / `finalizeMix(IntArray, ShortArray, Int)` and `FRAME_SAMPLES_20MS/10MS` match the real code. ✓

---

## Dependencies

- Task 2 blockedBy Task 1 (uses `JitterBuffer.clearTerminator`).
- Task 4 blockedBy Task 2 (asserts the new hold/reset behavior).
- Task 5 blockedBy Tasks 1, 2, 3, 4.
- Task 3 is disjoint-file from Tasks 1, 2, 4 (`VoiceEngine`/`VoiceTransport`/`AudioVoiceEngine`/`SyntheticVoiceSource` vs. `JitterBuffer`/`SpeakerStream`/new test file) and MAY run in parallel with them.
