# Adaptive Jitter Buffer (#56 Part B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `SpeakerStream`'s static ~100 ms prebuffer with a per-speaker adaptive target driven by a pure `DownlinkJitterEstimator`, plus a Mumble-style mid-spurt PLC deepen valve, cutting playout latency on good paths without reintroducing late-drop starvation.

**Architecture:** A new pure, JVM-testable `DownlinkJitterEstimator` (one per `SpeakerStream`, fed every arriving packet incl. late) produces a `@Volatile targetSamples` + `@Volatile lateBurst`. `SpeakerStream` reads the target at its anchor gate and, on a sustained late-burst without underrun, deepens by one PLC frame via a new `plcDeepen()` that (crucially) does NOT touch the underrun `consecutivePlc` counter. Engine plumbs `arrivalNanos` into `offer` and aggregates a `JitterStats` flow rendered on the Audio diagnostics screen (verify with the existing latency HUD).

**Tech Stack:** Kotlin, existing `mumble/voice` (`SpeakerStream`/`JitterBuffer`/`AudioVoiceEngine`), kotlinx StateFlow, Compose, JUnit4.

**User decisions (already made):**
- Adaptive-at-anchor only; mid-spurt **grow** via PLC valve, **no** mid-spurt shrink, no WSOLA.
- Aggressive posture: cold-start anchors on first packet; **floor 10 ms**, target cap **400 ms**.
- **Per-speaker** estimator (matches Mumble).
- Two depth ceilings only: 400 ms target + unchanged 600 ms high-water. No `+margin` fudge.

**Spec:** `docs/superpowers/specs/2026-07-18-adaptive-jitter-buffer-design.md` — every load-bearing number fable-verified twice against Speex/Mumble/WebRTC-NetEq source. Do not re-derive.

**Load-bearing facts (from the spec):**
- Incoming ts = `frameNumber * FRAME_SAMPLES_10MS` (480-sample granularity); `FRAME_SAMPLES_10MS`=480, `FRAME_SAMPLES_20MS`=960 (`AudioConstants.kt`); 48 samples/ms.
- `rtpNanos = (rtpSamples / 480) * 10_000_000L` is exact (all ts are multiples of 480).
- `JitterBuffer.offer` returns `LATE` when `ts < cursor`; `OfferResult` ∈ {QUEUED, LATE, DUPLICATE, EMPTY}.
- **The valve MUST NOT increment `consecutivePlc`** (else it trips `consecutivePlc >= maxHoldTicks → resetToIdle` and discards the anchor). Cross-thread: `LATE` is seen on the receive thread (`offer`), the valve fires on the playback thread (`fillTick`) — so the burst signal is published `@Volatile` from the estimator.

---

## File Structure

| File | Task | Responsibility |
|------|------|----------------|
| `mumble/voice/DownlinkJitterEstimator.kt` | T1 | Pure per-speaker estimator: relative-delay → 200 ms peak-hold buckets → p95 → `@Volatile targetSamples`; late-burst signal |
| `test/.../voice/DownlinkJitterEstimatorTest.kt` | T1 | JVM tests (cold/steady/burst/spike/clamp/drift/late-burst) |
| `mumble/voice/SpeakerStream.kt` | T2 | Own the estimator; `offer` gains `arrivalNanos` + feeds it; anchor gate reads target; `plcDeepen()` valve |
| `test/.../voice/SpeakerStreamTest.kt` | T2 | Valve fires without touching `consecutivePlc`; anchor uses target (extend Part A harness) |
| `mumble/voice/JitterStats.kt` | T3 | `data class JitterStats(targetMs, p95Ms)` |
| `mumble/voice/AudioVoiceEngine.kt` | T3 | Pass `arrivalNanos` into `offer`; aggregate `_jitter` flow in the playback tick |
| `mumble/MumbleManager.kt` | T4 | `jitterStats` StateFlow mirror + shutdown reset |
| `ui/AudioDiagnosticsScreen.kt` + `ui/DumbleApp.kt` | T4 | "Jitter" section (Target / p95) |

**Task order (sequential):** T1 → T2 → T3 → T4. All gradle prefixed with `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Never stage `.idea/gradle.xml`. Commit trailers: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz`.

**On-device verification is NOT a task** — Dan batches it. Stop after the final whole-plan review and report; the "Jitter" readout + Latency HUD are the on-device instruments.

---

### Task 1: DownlinkJitterEstimator (pure, JVM-tested)

**Goal:** A pure, Android-free per-speaker estimator producing an adaptive prebuffer `targetSamples` and a `lateBurst` signal from relative arrival delay, with JVM unit tests.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/DownlinkJitterEstimator.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/DownlinkJitterEstimatorTest.kt`

**Acceptance Criteria:**
- [ ] `onPacket(rtpSamples, arrivalNanos, wasLate)` updates 200 ms peak-hold buckets (40 = 8 s window) of `d = arrivalNanos − rtpNanos`, and recomputes `@Volatile targetSamples`, `@Volatile p95Ms`, `@Volatile lateBurst`.
- [ ] `targetSamples = clamp(p95(liveBucketPeaks), FLOOR, MAX)` where peak = `bucketMaxD − min(liveBucketMinD)`; cold/empty → `FLOOR_SAMPLES` (480).
- [ ] p95 index = `ceil(0.95·n) − 1` over **live** (filled) buckets only; empty buckets never counted as 0.
- [ ] `lateBurst` = ≥ 3 `LATE` arrivals within a 200 ms window.
- [ ] No allocation on the hot path (reusable scratch); `rtpNanos = (rtpSamples/480)*10_000_000L`.
- [ ] Tests pass on the JVM.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.DownlinkJitterEstimatorTest"` → PASS.

**Steps:**

- [ ] **Step 1: Write `DownlinkJitterEstimatorTest.kt` (failing)**

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownlinkJitterEstimatorTest {
    private val ms = 1_000_000L        // ns per ms
    private val frame = 960L           // 20 ms packet = 960 samples (frameNumber += 2)

    /** Feed `count` in-order 20 ms packets starting at bucket-spaced arrivals, jitter per packet from [jit]. */
    private fun feed(est: DownlinkJitterEstimator, count: Int, startTs: Long, startArr: Long, stepNs: Long, jit: (Int) -> Long) {
        var ts = startTs
        var arr = startArr
        for (k in 0 until count) {
            est.onPacket(ts, arr + jit(k), false)
            ts += frame
            arr += stepNs
        }
    }

    @Test fun coldStartIsFloor() {
        val est = DownlinkJitterEstimator()
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun steadyLowJitterStaysAtFloor() {
        val est = DownlinkJitterEstimator()
        // 60 packets, one per 20 ms, zero jitter → relative delay 0 → target clamps up to floor.
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { 0 }
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun sustainedJitterGrowsTarget() {
        val est = DownlinkJitterEstimator()
        // Every packet 120 ms late vs the fastest → fills all buckets with a 120 ms peak.
        // First packet defines the baseline (jit 0), rest +120 ms.
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> if (k == 0) 0 else 120 * ms }
        // p95 peak ≈ 120 ms → target ≈ 120 ms = 5760 samples (well under the 400 ms cap).
        assertEquals(120 * 48, est.targetSamples) // 48 samples/ms
    }

    @Test fun oneOffSpikeIsIgnored() {
        val est = DownlinkJitterEstimator()
        // 45 steady buckets (advance 200 ms per packet so each lands in its own bucket) + a single 300 ms spike.
        var ts = 0L; var arr = 1_000_000_000L
        for (k in 0 until 45) { est.onPacket(ts, arr, false); ts += frame; arr += 200 * ms }
        est.onPacket(ts, arr + 300 * ms, false) // spike in one fresh bucket
        // p95 over 40 live buckets excludes the top ~2 → target stays at floor.
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun clampsAtMax() {
        val est = DownlinkJitterEstimator()
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> if (k == 0) 0 else 5_000 * ms }
        assertEquals((DownlinkJitterEstimator.MAX_NS * 48 / ms).toInt(), est.targetSamples) // 400 ms → 19200
    }

    @Test fun windowLocalMinimaCancelsDrift() {
        val est = DownlinkJitterEstimator()
        // Arrival drifts +1 ms/packet on top of rtp; relative delay is ~0 because the baseline re-bases.
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> k.toLong() * ms }
        // Within any 200 ms window the drift spread is small (~10 ms across 10 pkts); target stays near floor.
        assertTrue("target ${est.targetSamples} should be near floor", est.targetSamples <= 10 * 48 + 480)
    }

    @Test fun lateBurstFiresOnThreeLatesInWindow() {
        val est = DownlinkJitterEstimator()
        val base = 1_000_000_000L
        est.onPacket(0, base + 0 * ms, true)
        est.onPacket(frame, base + 50 * ms, true)
        assertFalse(est.lateBurst)
        est.onPacket(2 * frame, base + 100 * ms, true) // 3 lates within 100 ms < 200 ms
        assertTrue(est.lateBurst)
    }

    @Test fun lateBurstClearsWhenLatesAgeOut() {
        val est = DownlinkJitterEstimator()
        val base = 1_000_000_000L
        est.onPacket(0, base, true)
        est.onPacket(frame, base + 50 * ms, true)
        est.onPacket(2 * frame, base + 100 * ms, true)
        assertTrue(est.lateBurst)
        // A later non-late packet 500 ms on → the three lates are now outside the 200 ms window.
        est.onPacket(3 * frame, base + 600 * ms, false)
        assertFalse(est.lateBurst)
    }
}
```

- [ ] **Step 2: Run to confirm it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.voice.DownlinkJitterEstimatorTest"` → FAIL (unresolved `DownlinkJitterEstimator`).

- [ ] **Step 3: Create `DownlinkJitterEstimator.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Per-speaker downlink jitter estimator. Produces an adaptive prebuffer [targetSamples] and a
 * mid-spurt [lateBurst] signal from the speaker's recent relative arrival delay. Pure (no Android
 * deps) → JVM-testable. Fed on the RECEIVE thread via [onPacket]; [targetSamples]/[lateBurst]/[p95Ms]
 * are @Volatile and read on the PLAYBACK thread (single-writer publication, JMM 17.7 safe).
 *
 * Model (design doc): d = arrivalNanos − rtpNanos; relative delay = d − min(d over the live window)
 * (offset/skew-cancelling); peak-held into 200 ms buckets over an 8 s window; target = p95 of the
 * live bucket peaks, clamped to [10 ms, 400 ms]. Mirrors NetEq's underrun_optimizer minus the
 * forgetting histogram.
 */
class DownlinkJitterEstimator {
    // Bucket ring: each 200 ms slot holds min/max of d, or is empty. Receive-thread-only state.
    private val bucketMinD = LongArray(WINDOW_BUCKETS)
    private val bucketMaxD = LongArray(WINDOW_BUCKETS)
    private val bucketFilled = BooleanArray(WINDOW_BUCKETS)
    private var newestEpoch = Long.MIN_VALUE
    private val peaksScratch = LongArray(WINDOW_BUCKETS)   // reused; no hot-path alloc

    // Late-burst ring: arrivalNanos of the last LATE_BURST_COUNT late packets.
    private val lateRing = LongArray(LATE_BURST_COUNT)
    private var lateHead = 0

    @Volatile var targetSamples: Int = FLOOR_SAMPLES
        private set
    @Volatile var lateBurst: Boolean = false
        private set
    @Volatile var p95Ms: Int = 0
        private set

    /** Feed one arriving voice packet (receive thread). [wasLate] = JitterBuffer returned LATE. */
    fun onPacket(rtpSamples: Long, arrivalNanos: Long, wasLate: Boolean) {
        val rtpNanos = (rtpSamples / FRAME_SAMPLES_10MS) * 10_000_000L   // exact: 480 samples = 10 ms
        val d = arrivalNanos - rtpNanos
        val epoch = Math.floorDiv(arrivalNanos, BUCKET_NS)

        advanceTo(epoch)
        val idx = Math.floorMod(epoch, WINDOW_BUCKETS.toLong()).toInt()
        if (!bucketFilled[idx]) {
            bucketMinD[idx] = d; bucketMaxD[idx] = d; bucketFilled[idx] = true
        } else {
            if (d < bucketMinD[idx]) bucketMinD[idx] = d
            if (d > bucketMaxD[idx]) bucketMaxD[idx] = d
        }

        if (wasLate) {
            lateRing[lateHead] = arrivalNanos
            lateHead = (lateHead + 1) % LATE_BURST_COUNT
        }
        lateBurst = countLateWithin(arrivalNanos) >= LATE_BURST_COUNT

        recomputeTarget()
    }

    /** Advance the ring so [epoch] is newest; clear each newly-entered (aged-out) slot. Older/reordered
     *  epochs are kept (they still update their existing bucket above). */
    private fun advanceTo(epoch: Long) {
        if (newestEpoch == Long.MIN_VALUE) { newestEpoch = epoch; return }
        if (epoch <= newestEpoch) return
        var e = newestEpoch + 1
        while (e <= epoch) {
            bucketFilled[Math.floorMod(e, WINDOW_BUCKETS.toLong()).toInt()] = false
            e++
        }
        newestEpoch = epoch
    }

    private fun countLateWithin(nowArrival: Long): Int {
        var count = 0
        for (t in lateRing) if (t != 0L && nowArrival - t <= LATE_WINDOW_NS) count++
        return count
    }

    private fun recomputeTarget() {
        var baseline = Long.MAX_VALUE
        for (i in 0 until WINDOW_BUCKETS) if (bucketFilled[i] && bucketMinD[i] < baseline) baseline = bucketMinD[i]
        if (baseline == Long.MAX_VALUE) { targetSamples = FLOOR_SAMPLES; p95Ms = 0; return }
        var n = 0
        for (i in 0 until WINDOW_BUCKETS) if (bucketFilled[i]) {
            peaksScratch[n++] = (bucketMaxD[i] - baseline).coerceAtLeast(0L)
        }
        java.util.Arrays.sort(peaksScratch, 0, n)
        val idx = Math.max(0, Math.ceil(0.95 * n).toInt() - 1)
        val p95Ns = peaksScratch[idx]
        p95Ms = (p95Ns / 1_000_000L).toInt()
        val clampedNs = p95Ns.coerceIn(FLOOR_NS, MAX_NS)
        targetSamples = (clampedNs * 48L / 1_000_000L).toInt()   // ns → samples (48 samples/ms)
    }

    companion object {
        const val BUCKET_NS = 200_000_000L         // 200 ms peak-hold slot
        const val WINDOW_BUCKETS = 40              // 8 s window
        const val FLOOR_NS = 10_000_000L           // 10 ms
        const val MAX_NS = 400_000_000L            // 400 ms target cap
        const val LATE_WINDOW_NS = 200_000_000L    // late-burst window
        const val LATE_BURST_COUNT = 3             // >= 3 LATE in the window
        val FLOOR_SAMPLES = (FLOOR_NS * 48L / 1_000_000L).toInt()   // 480
    }
}
```

- [ ] **Step 4: Run tests → PASS** — same command as Step 2. If a test fails, re-check the transcription (do not weaken assertions).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/DownlinkJitterEstimator.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/DownlinkJitterEstimatorTest.kt
git commit -m "feat(voice): per-speaker DownlinkJitterEstimator (adaptive prebuffer target)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 2: SpeakerStream — adaptive anchor + PLC deepen valve

**Goal:** `SpeakerStream` owns a `DownlinkJitterEstimator`; `offer` gains `arrivalNanos` and feeds it; the anchor gate reads `estimator.targetSamples`; add a `plcDeepen()` valve that deepens on a late-burst without touching the underrun counter.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt` (extend existing Part A harness)

**Acceptance Criteria:**
- [ ] Constructor takes `estimator: DownlinkJitterEstimator = DownlinkJitterEstimator()` and a `targetSamples: () -> Int = { estimator.targetSamples }` supplier seam; the static `prebufferSamples: Int` param is replaced by these two.
- [ ] `offer(timestampSamples, opus, spanSamples, isTerminator, arrivalNanos)` feeds `estimator.onPacket(ts, arrivalNanos, result == LATE)` for every non-`EMPTY` result, then returns the result.
- [ ] The anchor gate uses `targetSamples()` instead of the constant.
- [ ] `fillTick` fires `plcDeepen()` when anchored AND `estimator.lateBurst` AND `bufferedSamples() < targetSamples()` AND ≥ `DEEPEN_INTERVAL_TICKS` (10) since the last deepen; `plcDeepen()` pushes a PLC frame + holds the cursor and does NOT increment `consecutivePlc`.
- [ ] All existing Part A `SpeakerStreamTest` cases still pass (migrated: `prebufferSamples = N` → `targetSamples = { N }`, and a trailing `arrivalNanos` added to every `offer(...)` call).
- [ ] Part A's underrun path (`plcHold`/`maxHoldTicks`/`resetToIdle`) is otherwise unchanged.
- [ ] `assembleDebug` + full unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

**Steps:**

- [ ] **Step 1: Update `SpeakerStream.kt`.** Replace the constructor's `prebufferSamples` param with an estimator, add valve state, rewrite `offer`, the anchor gate, and add `plcDeepen()`:

Constructor (the old `prebufferSamples: Int = FRAME_SAMPLES_20MS * 5` param is replaced by `estimator` + a `targetSamples` supplier seam so Part A tests can still force a fixed depth):
```kotlin
class SpeakerStream(
    private val codec: OpusCodec,
    private val estimator: DownlinkJitterEstimator = DownlinkJitterEstimator(),
    private val targetSamples: () -> Int = { estimator.targetSamples },  // prod: adaptive; tests override to a fixed depth
    private val reanchorGapSamples: Long = SAMPLE_RATE.toLong(),  // 1 s forward jump → boundary
    private val maxHoldTicks: Int = 10,                           // ~200 ms held underrun → boundary reset
    private val retireIdleTicks: Int = 500,                       // ~10 s un-anchored + empty → retire
) {
```

Add near the other fields:
```kotlin
    private var ticksSinceDeepen = DEEPEN_INTERVAL_TICKS   // playback-thread only; allow immediate first deepen
```

Rewrite `offer`:
```kotlin
    /** Receive thread. Feeds the estimator (every non-empty packet, incl. late) and enqueues. */
    fun offer(timestampSamples: Long, opus: ByteArray, spanSamples: Int, isTerminator: Boolean, arrivalNanos: Long): JitterBuffer.OfferResult {
        val cur = cursor
        val result = buffer.offer(JitterBuffer.Packet(timestampSamples, opus, spanSamples, isTerminator),
            if (cur < 0) 0 else cur)
        if (result != JitterBuffer.OfferResult.EMPTY) {
            estimator.onPacket(timestampSamples, arrivalNanos, result == JitterBuffer.OfferResult.LATE)
        }
        return result
    }
```

In `fillTick`, change the anchor gate line from `prebufferSamples` to `targetSamples()`:
```kotlin
            if (buffer.bufferedSamples() < targetSamples() && buffer.terminatorTimestamp == null) return false
```

In `fillTick`, immediately after the `if (cursor < 0) { … }` anchor block (i.e. once anchored, before the `while (fifo.size < FRAME_SAMPLES_20MS)` loop), insert the valve:
```kotlin
        // Mid-spurt grow valve: sustained lates without underrun, still shallow, rate-limited to 1/200 ms.
        // Deepen by one PLC frame while HOLDING the cursor — WITHOUT touching consecutivePlc (that
        // counter belongs to the underrun/resetToIdle path; bumping it here would trip a spurious reset).
        ticksSinceDeepen++
        if (estimator.lateBurst && buffer.bufferedSamples() < targetSamples() &&
            ticksSinceDeepen >= DEEPEN_INTERVAL_TICKS) {
            ticksSinceDeepen = 0
            plcDeepen()
            fifo.drainInto(out, FRAME_SAMPLES_20MS)
            return true
        }
```

Add the `plcDeepen` method next to `plcHold`/`plcAdvance`:
```kotlin
    private fun plcDeepen() {               // mid-spurt grow — conceal + HOLD cursor; does NOT touch consecutivePlc
        val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
    }
```

Add the constant to the file (top-level, near the class or in `AudioConstants` — keep it local):
```kotlin
private const val DEEPEN_INTERVAL_TICKS = 10   // >= 200 ms between valve deepens (20 ms/tick)
```

- [ ] **Step 2: Migrate the existing `SpeakerStreamTest` (Part A) so it compiles + passes.** Two mechanical changes across the file:
  1. Constructor: `SpeakerStream(codec, prebufferSamples = N, …)` → `SpeakerStream(codec, targetSamples = { N }, …)`. (`prebufferSamples = 0` → `targetSamples = { 0 }`; `prebufferSamples = FRAME_SAMPLES_20MS * 2` → `targetSamples = { FRAME_SAMPLES_20MS * 2 }`.)
  2. Every `s.offer(ts, opus, span, term)` → `s.offer(ts, opus, span, term, arrivalNanos)` with a monotonic test value (e.g. a running `var arr = 1_000_000_000L; arr += 20_000_000L` per call, or reuse `ts`). The other production caller (`AudioVoiceEngine.onIncomingFrame`) is updated in Task 3.

- [ ] **Step 3: Add valve tests to `SpeakerStreamTest.kt`.** Use an injected estimator you can drive. Because `DownlinkJitterEstimator` has no setters, drive it through real `onPacket` calls, or add a test-only subclass/fake. Simplest: a tiny fake implementing the same surface via a shared interface is overkill — instead construct a real estimator and feed it a late-burst, then assert the valve holds the cursor without a reset. Concretely:

```kotlin
    @Test fun valveDeepensOnLateBurstWithoutResettingAnchor() {
        val est = DownlinkJitterEstimator()
        // Force a large target so `bufferedSamples() < target` always holds → the valve is free to fire;
        // `codec`/`encoded(...)` are the existing Part A helpers.
        val s = SpeakerStream(codec, estimator = est, targetSamples = { 100_000 }, maxHoldTicks = 10)
        val out = ShortArray(FRAME_SAMPLES_20MS)

        // Anchor the cursor (target 100_000 samples means it anchors as soon as anything is buffered? No —
        // it would wait; so anchor via a terminator, which bypasses the prebuffer gate, then keep playing).
        var arr = 1_000_000_000L
        s.offer(0, encoded(960), 960, false, arr); arr += 20_000_000L
        s.offer(960, encoded(960), 960, true, arr); arr += 20_000_000L   // terminator → anchors despite big target
        s.fillTick(out)
        assertTrue("anchored", s.playoutCursor() >= 0)

        // Drive a late-burst: 3 packets with ts behind the cursor (→ LATE) within 200 ms.
        val cur = s.playoutCursor()
        for (k in 0 until 3) { s.offer(cur - 5_000L, encoded(960), 960, false, arr); arr += 50_000_000L }
        assertTrue("late burst should be set", est.lateBurst)

        // Fill many ticks; the valve deepens but must NEVER resetToIdle (cursor stays anchored ≥ 0).
        repeat(30) {
            s.fillTick(out)
            assertTrue("cursor must remain anchored (no spurious reset)", s.playoutCursor() >= 0)
        }
        assertFalse(s.retired)
    }
```

(Mirror Part A's exact helper names if they differ. The assertion that matters is **cursor stays ≥ 0 across a sustained late-burst** = no spurious `resetToIdle` — the Item-3 bug this task exists to prevent. Note the anchor-gate detail: `terminatorTimestamp != null` bypasses the prebuffer wait, which is how the test anchors despite the large forced target.)

- [ ] **Step 4: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt
git commit -m "feat(voice): adaptive prebuffer anchor + PLC deepen valve in SpeakerStream

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 3: Engine plumb + JitterStats flow

**Goal:** Pass `arrivalNanos` into `stream.offer` and emit an aggregate `JitterStats` flow from the playback tick.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterStats.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` (`onIncomingFrame` offer call ~line 315; new flow near the other flows ~line 43; playback tick ~line 357; `SpeakerStream` accessors)

**Acceptance Criteria:**
- [ ] `JitterStats(targetMs: Int = 10, p95Ms: Int = 0)` data class exists.
- [ ] `SpeakerStream` exposes `fun jitterTargetMs(): Int` and `fun jitterP95Ms(): Int` (reading its estimator).
- [ ] `onIncomingFrame` calls `stream.offer(tsSamples, copy, span, terminator, arrivalNanos)`.
- [ ] `AudioVoiceEngine` exposes `val jitter: StateFlow<JitterStats>`, updated each playback tick to the **max** target/p95 across active speakers.
- [ ] `assembleDebug` + full unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

**Steps:**

- [ ] **Step 1: Create `JitterStats.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/** Aggregate adaptive-jitter readout (max across active speakers), for the diagnostics HUD. */
data class JitterStats(
    val targetMs: Int = 10,
    val p95Ms: Int = 0,
)
```

- [ ] **Step 2: Add the `SpeakerStream` accessors** (in `SpeakerStream.kt`, next to `playoutCursor()`):

```kotlin
    /** Diagnostic read-only: current adaptive prebuffer target / p95 relative delay, in ms. */
    fun jitterTargetMs(): Int = estimator.targetSamples / 48     // 48 samples/ms
    fun jitterP95Ms(): Int = estimator.p95Ms
```

- [ ] **Step 3: Add the engine flow.** In `AudioVoiceEngine.kt`, after the `_latency`/`latency` pair (near line 43):

```kotlin
    private val _jitter = MutableStateFlow(JitterStats())
    val jitter: StateFlow<JitterStats> = _jitter.asStateFlow()
```

- [ ] **Step 4: Pass `arrivalNanos` into offer.** In `onIncomingFrame` (~line 315) change the call to:

```kotlin
        val result = stream.offer(tsSamples, copy, span, terminator, arrivalNanos)
```

- [ ] **Step 5: Emit in the playback tick.** In `playbackLoop()`, immediately after the `_latency.value = …` block added by the latency feature (end of the loop body), add:

```kotlin
            _jitter.value = JitterStats(
                targetMs = speakers.values.maxOfOrNull { it.jitterTargetMs() } ?: 10,
                p95Ms = speakers.values.maxOfOrNull { it.jitterP95Ms() } ?: 0,
            )
```

- [ ] **Step 6: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterStats.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt
git commit -m "feat(voice): aggregate JitterStats flow (engine tick) + arrivalNanos plumb

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 4: Diagnostics "Jitter" readout

**Goal:** Mirror `JitterStats` onto `MumbleManager` and render a "Jitter" line (Target / p95) on the Audio diagnostics screen.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt` (flow near line 65; collector near line 370; shutdown near line 458)
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] `MumbleManager` exposes `val jitterStats: StateFlow<JitterStats>`, fed by `engine.jitter` in `ActiveSession.start()`, reset in `shutdown()`.
- [ ] `AudioDiagnosticsScreen` takes a `jitter: JitterStats` param and renders a "Jitter" section (Target / p95) after the "Latency" section.
- [ ] `DumbleApp` collects `MumbleManager.jitterStats` and passes it.
- [ ] `assembleDebug` + unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

**Steps:**

- [ ] **Step 1: MumbleManager flow.** After the `_latencyStats`/`latencyStats` pair (~line 66) add:

```kotlin
    private val _jitterStats = MutableStateFlow(JitterStats())
    val jitterStats: StateFlow<JitterStats> = _jitterStats.asStateFlow()
```

(No new import needed — `JitterStats` is in the wildcard-imported `voice.*`.) Add the collector after the `engine.latency` collector (~line 371):

```kotlin
            sessionScope.launch { engine.jitter.collect { _jitterStats.value = it } }
```

And the reset in `shutdown()` after `_latencyStats.value = LatencyStats()`:

```kotlin
            _jitterStats.value = JitterStats()
```

- [ ] **Step 2: AudioDiagnosticsScreen.** Add the import `import me.danielstiner.dumble.mumble.voice.JitterStats`; add `jitter: JitterStats` to the signature (before `onBack`); render after the Latency section's trailing `Text("")`:

```kotlin
            Text("Jitter (adaptive prebuffer)")
            Text("  Target:     ${jitter.targetMs} ms")
            Text("  p95 delay:  ${jitter.p95Ms} ms")
            Text("")
```

- [ ] **Step 3: DumbleApp.** Add the collector with the others:

```kotlin
    val jitter by MumbleManager.jitterStats.collectAsStateWithLifecycle()
```

and pass `jitter = jitter` into the `AudioDiagnosticsScreen(...)` call.

- [ ] **Step 4: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt \
        app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): show adaptive jitter target on the diagnostics screen

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

## Self-Review

**Spec coverage:** DownlinkJitterEstimator (metric, window-local minima, 200 ms buckets, p95-over-live, clamp, late-burst) → T1 ✓; per-speaker ownership + adaptive anchor + `plcDeepen` valve NOT touching `consecutivePlc` + cross-thread `@Volatile` late signal → T2 ✓; `arrivalNanos` plumb + aggregate flow → T3 ✓; diagnostics readout → T4 ✓. Non-goals (no shrink/WSOLA/pooled/10 ms-framing/high-water change) respected ✓. Feed-late-drops ✓ (`offer` feeds on LATE). Two ceilings only (400 target / 600 high-water) ✓.

**Type consistency:** `DownlinkJitterEstimator.{targetSamples:Int, lateBurst:Boolean, p95Ms:Int, onPacket(Long,Long,Boolean)}`; `SpeakerStream.offer(...,arrivalNanos:Long)` / `jitterTargetMs()` / `jitterP95Ms()` / `plcDeepen()` / `DEEPEN_INTERVAL_TICKS`; `JitterStats(targetMs,p95Ms)`; `jitter`/`_jitter` (engine) / `jitterStats`/`_jitterStats` (manager) — consistent T1–T4.

**Placeholders:** none — full code for every new file; precise diffs for every modification. The one soft spot (T2 Step 3 test uses Part A's fake-codec helper names) is called out with the invariant that actually matters (cursor stays ≥ 0 = no spurious reset).
