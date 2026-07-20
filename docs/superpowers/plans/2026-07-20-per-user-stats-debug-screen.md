# Per-user stats debug screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dedicated debug screen that breaks jitter stats out **per remote speaker** (name-attributed) and shows each user's ping-to-server, so a single misbehaving sender (the "wild 4000 ms") is diagnosable instead of hidden in a max-aggregate.

**Architecture:** Three additive, read-only slices. (A) `SpeakerStream` gains per-speaker `bufferedMs()`/`lateDrops`; `JitterStats` gains a `perSpeaker` list; `AudioVoiceEngine` publishes a throttled per-speaker snapshot. (B) Per-user ping via Mumble `UserStats` — request/response wired through `SessionStateMachine`, stored on `MumbleUser`, polled while the screen is open. (C) A new `PerUserStatsScreen` joins the two by session.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlinx.coroutines, protobuf (Mumble.proto), JUnit.

**User decisions (already made):**
- "add a per-user stats page that shows their ping and jitter buffer stats, for debugging" — build it.
- "go ahead and add the per-user pings" — ping is IN scope (not deferred).
- Ping meaning = server RTT once at top + per-speaker jitter (chosen from the ping-scope question).
- Dedicated screen, not a section ("Would it be better to have a per-user settings/debug screen?" → yes).
- "fix that [ping self-report] first as its own commit" — **DONE** (`dc4444d`), so it is NOT a task here.

**Prerequisite already landed (`dc4444d`):** `SessionStateMachine.sendPing(tcpPingAvgMs, udpPingAvgMs)` self-reports our RTT and `MumbleManager.pingLoop` feeds it. Verified against Murmur 1.5.901 that ping is self-reported and reaches peers. This plan consumes that; it does not redo it.

**Spec:** `docs/superpowers/specs/2026-07-20-per-user-stats-debug-screen-design.md` (fable-reviewed + server-verified).

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `mumble/voice/SpeakerStream.kt` | add `bufferedMs()`, per-speaker `lateDrops` | 1 |
| `mumble/voice/JitterStats.kt` | `SpeakerJitter` + `perSpeaker` list, derived aggregate | 2 |
| `mumble/voice/AudioVoiceEngine.kt` | throttled per-speaker snapshot → `_jitter` | 2 |
| `mumble/model/MumbleModel.kt` | per-user ping fields, `onUserStats`, reducer carry-forward | 3 |
| `mumble/protocol/SessionStateMachine.kt` | `UserStats` response + `requestUserStats` sender | 4 |
| `mumble/MumbleManager.kt` | `setUserStatsPolling` + poll loop | 5 |
| `ui/PerUserStatsScreen.kt` (new) | the screen | 6 |
| `ui/AudioDiagnosticsScreen.kt` | "Per-user stats →" entry row | 6 |
| `ui/DumbleApp.kt` | nav + polling `DisposableEffect` | 6 |

Tasks are ordered so **the build compiles and tests pass after every task** (Task 2 changes `JitterStats` and its only construction site together; nothing before it depends on the new shape).

---

### Task 1: SpeakerStream per-speaker accessors

**Goal:** `SpeakerStream` exposes its live buffered depth (ms) and a per-speaker late-drop count.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt`

**Acceptance Criteria:**
- [ ] `SpeakerStream.bufferedMs()` returns `buffer.bufferedSamples() / 48`.
- [ ] `SpeakerStream.lateDrops` (`@Volatile`, private set) increments by 1 on a `LATE` non-terminator `offer`, and does NOT increment on a `LATE` terminator, `QUEUED`, `DUPLICATE`, or `EMPTY`.
- [ ] Existing `SpeakerStreamTest` cases still pass.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*.SpeakerStreamTest"` → all pass.

**Steps:**

- [ ] **Step 1: Add the failing tests** to `SpeakerStreamTest.kt`:

```kotlin
    @Test fun lateNonTerminatorIncrementsLateDrops() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        s.fillTick(out)                                       // decode ts0 → cursor = 960
        assertEquals(0L, s.lateDrops)
        s.offer(0, encoded(960), 960, false, 1_010_000_000L)  // ts 0 < cursor 960 → LATE
        assertEquals(1L, s.lateDrops)
        s.offer(0, encoded(960), 960, true, 1_020_000_000L)   // non-empty LATE *terminator* → guarded
        assertEquals(1L, s.lateDrops)
    }

    @Test fun bufferedMsReflectsQueuedDepth() {
        val s = SpeakerStream(codec, targetSamples = { 999_999 })  // gate never opens → nothing drains
        assertEquals(0, s.bufferedMs())
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)       // 960 samples = 20 ms
        assertEquals(20, s.bufferedMs())
        s.offer(960, encoded(960), 960, false, 1_020_000_000L)     // +20 ms
        assertEquals(40, s.bufferedMs())
    }
```

- [ ] **Step 2: Run → FAIL** (`Unresolved reference: lateDrops` / `bufferedMs`).

`./gradlew :app:testDebugUnitTest --tests "*.SpeakerStreamTest"`

- [ ] **Step 3: Implement in `SpeakerStream.kt`.** Add the field near the other counters (after `var retired` ~line 30):

```kotlin
    /** Per-speaker genuine late-drops (audio behind the cursor), for the per-user debug screen.
     *  Written under the @Synchronized offer(); @Volatile publishes to the playback-thread reader. */
    @Volatile var lateDrops: Long = 0L; private set
```

In `offer(...)`, immediately before `return result` (after the estimator feed, ~line 61):

```kotlin
        if (result == JitterBuffer.OfferResult.LATE && !isTerminator) lateDrops++
        return result
```

Add the accessor near `jitterP95Ms()` (~line 38):

```kotlin
    /** Diagnostic: current buffered audio depth in ms (JitterBuffer is @Synchronized → any thread). */
    fun bufferedMs(): Int = buffer.bufferedSamples() / 48   // 48 samples/ms @48k
```

- [ ] **Step 4: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*.SpeakerStreamTest"`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt
git commit -m "feat(voice): per-speaker lateDrops + bufferedMs accessors"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt", "app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"*.SpeakerStreamTest\"", "acceptanceCriteria": ["bufferedMs() = bufferedSamples()/48", "lateDrops increments on LATE non-terminator only", "existing SpeakerStreamTest passes"], "modelTier": "mechanical"}
```

---

### Task 2: JitterStats.perSpeaker + AudioVoiceEngine snapshot

**Goal:** `JitterStats` carries a per-speaker breakout; `AudioVoiceEngine` publishes it on a ~500 ms cadence, with the existing aggregate derived from it.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterStats.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt:372-375` (the `_jitter` update) and the `playbackLoop` counter
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterStatsTest.kt` (new)

**Acceptance Criteria:**
- [ ] `SpeakerJitter(session, targetMs, p95Ms, bufferedMs, lateDrops)` is a `data class`.
- [ ] `JitterStats(perSpeaker)` derives `targetMs`/`p95Ms` as the max over `perSpeaker` (empty → 10/0).
- [ ] `AudioVoiceEngine` publishes `perSpeaker` (one entry per live speaker) at most ~every 25 playback ticks, using a **new** counter (not the send-thread `diagTick`).
- [ ] Existing `AudioDiagnosticsScreen` still reads `jitter.targetMs`/`jitter.p95Ms` unchanged.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*.JitterStatsTest"` and full `:app:testDebugUnitTest` → all pass.

**Steps:**

- [ ] **Step 1: Failing test** `JitterStatsTest.kt`:

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class JitterStatsTest {
    @Test fun emptyDefaultsToFloor() {
        val j = JitterStats()
        assertEquals(10, j.targetMs); assertEquals(0, j.p95Ms); assertEquals(0, j.perSpeaker.size)
    }
    @Test fun aggregateIsMaxOverSpeakers() {
        val j = JitterStats(perSpeaker = listOf(
            SpeakerJitter(1, targetMs = 20, p95Ms = 40, bufferedMs = 60, lateDrops = 2),
            SpeakerJitter(2, targetMs = 300, p95Ms = 4000, bufferedMs = 120, lateDrops = 0),
        ))
        assertEquals(300, j.targetMs); assertEquals(4000, j.p95Ms)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`Unresolved reference: SpeakerJitter` / `perSpeaker`).

- [ ] **Step 3: Rewrite `JitterStats.kt`:**

```kotlin
package me.danielstiner.dumble.mumble.voice

/** One speaker's jitter snapshot for the per-user debug screen. [p95Ms] is the RAW (unclamped)
 *  estimator p95 — the actual prebuffer [targetMs] is clamped [10,400]; a large p95 is that
 *  speaker's worst delay spike, not real buffering. */
data class SpeakerJitter(
    val session: Int,
    val targetMs: Int,
    val p95Ms: Int,
    val bufferedMs: Int,
    val lateDrops: Long,
)

/** Adaptive-jitter readout for the diagnostics HUD. Aggregate [targetMs]/[p95Ms] are the max across
 *  active speakers (back-compat with the existing HUD); [perSpeaker] is the per-speaker breakout. */
data class JitterStats(
    val perSpeaker: List<SpeakerJitter> = emptyList(),
) {
    val targetMs: Int get() = perSpeaker.maxOfOrNull { it.targetMs } ?: 10
    val p95Ms: Int get() = perSpeaker.maxOfOrNull { it.p95Ms } ?: 0
}
```

- [ ] **Step 4: Update `AudioVoiceEngine.kt`.** Add a playback-thread field near the other playback-loop locals (before `playbackLoop()`, e.g. beside `private var diagTick = 0` add — but a SEPARATE field):

```kotlin
    private var jitterSnapshotTick = 0   // playback-thread only; throttles the per-speaker snapshot
```

Replace the `_jitter.value = JitterStats(...)` block (currently lines ~372-375) with a throttled per-speaker build:

```kotlin
            if (++jitterSnapshotTick % DIAG_INTERVAL == 0) {   // ~500 ms (25 * 20 ms)
                val per = speakers.entries.map { (session, st) ->
                    SpeakerJitter(session, st.jitterTargetMs(), st.jitterP95Ms(), st.bufferedMs(), st.lateDrops)
                }
                _jitter.value = JitterStats(perSpeaker = per)
            }
```

(`DIAG_INTERVAL` = 25 already exists at `AudioVoiceEngine.kt:19`; reused as a constant only — the counter is the new `jitterSnapshotTick`, not the send-thread `diagTick`.)

- [ ] **Step 5: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*.JitterStatsTest"` then full `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterStats.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterStatsTest.kt
git commit -m "feat(voice): per-speaker JitterStats breakout + throttled snapshot"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterStats.kt", "app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt", "app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterStatsTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"*.JitterStatsTest\"", "acceptanceCriteria": ["SpeakerJitter is a data class", "JitterStats derives max aggregate, empty=10/0", "AudioVoiceEngine snapshots perSpeaker every ~25 ticks via a new counter", "AudioDiagnosticsScreen still reads targetMs/p95Ms"], "modelTier": "standard"}
```

---

### Task 3: MumbleModel per-user ping fields + onUserStats + carry-forward

**Goal:** `MumbleUser` carries nullable ping; a `UserStats` reducer writes it; `applyUserState` preserves it across later updates (the fable-caught blocker).

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/model/MumbleModel.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/MumbleModelTest.kt`

**Acceptance Criteria:**
- [ ] `MumbleUser` has `tcpPingMs: Float? = null` and `udpPingMs: Float? = null`.
- [ ] `ModelReducers.applyUserStats` writes ping for an existing user (guarding `hasTcpPingAvg`/`hasUdpPingAvg`); a `UserStats` for an unknown session is a no-op.
- [ ] `applyUserState` carries `tcpPingMs`/`udpPingMs` forward — a later `UserState` does NOT wipe them.
- [ ] `MumbleModel.onUserStats(msg)` delegates to the reducer.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*.MumbleModelTest"` → all pass.

**Steps:**

- [ ] **Step 1: Failing tests** in `MumbleModelTest.kt` (add the helper + 3 tests):

```kotlin
    private fun userStats(session: Int, tcp: Float? = null, udp: Float? = null): MumbleProtos.UserStats {
        val b = MumbleProtos.UserStats.newBuilder().setSession(session)
        tcp?.let { b.setTcpPingAvg(it) }; udp?.let { b.setUdpPingAvg(it) }
        return b.build()
    }

    @Test fun userStatsWritesPing() {
        var m = ServerModel()
        m = ModelReducers.applyUserState(m, userState(42, name = "dan"))
        m = ModelReducers.applyUserStats(m, userStats(42, tcp = 15.5f, udp = 20f))
        assertEquals(15.5f, m.users[42]!!.tcpPingMs!!, 0.001f)
        assertEquals(20f, m.users[42]!!.udpPingMs!!, 0.001f)
    }

    @Test fun userStatsForUnknownSessionIsNoOp() {
        val m = ModelReducers.applyUserStats(ServerModel(), userStats(99, tcp = 5f))
        assertNull(m.users[99])
    }

    @Test fun userStatePreservesPingAcrossUpdate() {
        var m = ServerModel()
        m = ModelReducers.applyUserState(m, userState(42, name = "dan"))
        m = ModelReducers.applyUserStats(m, userStats(42, tcp = 15.5f))
        m = ModelReducers.applyUserState(m, userState(42, channel = 3))   // mute/move must NOT wipe ping
        assertEquals(15.5f, m.users[42]!!.tcpPingMs!!, 0.001f)
        assertEquals(3, m.users[42]!!.channelId)
    }
```

- [ ] **Step 2: Run → FAIL.** `./gradlew :app:testDebugUnitTest --tests "*.MumbleModelTest"`

- [ ] **Step 3: Implement in `MumbleModel.kt`.** Add fields to `MumbleUser` (after `recording`):

```kotlin
    val recording: Boolean = false,
    val tcpPingMs: Float? = null,   // server-reported ping-to-server (via UserStats); null = unknown
    val udpPingMs: Float? = null,
```

Add carry-forward to `applyUserState` (after the `recording = ...` line):

```kotlin
            recording = if (msg.hasRecording()) msg.recording else old?.recording ?: false,
            tcpPingMs = old?.tcpPingMs,   // preserve — UserStats writes these, UserState must not wipe them
            udpPingMs = old?.udpPingMs,
```

Add the reducer (after `applyUserRemove`):

```kotlin
    fun applyUserStats(m: ServerModel, msg: MumbleProtos.UserStats): ServerModel {
        val old = m.users[msg.session] ?: return m       // stats for an unknown user → ignore
        val u = old.copy(
            tcpPingMs = if (msg.hasTcpPingAvg()) msg.tcpPingAvg else old.tcpPingMs,
            udpPingMs = if (msg.hasUdpPingAvg()) msg.udpPingAvg else old.udpPingMs,
        )
        return m.copy(users = m.users + (u.session to u))
    }
```

Add the holder method (beside `onUserRemove`):

```kotlin
    fun onUserStats(msg: MumbleProtos.UserStats) { _state.value = ModelReducers.applyUserStats(_state.value, msg) }
```

- [ ] **Step 4: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*.MumbleModelTest"`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/model/MumbleModel.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/MumbleModelTest.kt
git commit -m "feat(model): per-user ping (UserStats) with UserState carry-forward"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/model/MumbleModel.kt", "app/src/test/java/me/danielstiner/dumble/mumble/MumbleModelTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"*.MumbleModelTest\"", "acceptanceCriteria": ["MumbleUser has nullable tcp/udpPingMs", "applyUserStats writes ping, unknown session no-op", "applyUserState carries ping forward", "onUserStats delegates"], "modelTier": "mechanical"}
```

---

### Task 4: SessionStateMachine UserStats request + response

**Goal:** The state machine can request a peer's `UserStats` and routes incoming `UserStats` into the model.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt` (the `onFrame` `when` ~line 128, and a new sender near `sendSelfMute` ~line 179)
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/SessionStateMachineTest.kt`

**Acceptance Criteria:**
- [ ] `requestUserStats(session)` sends a `UserStats` control message with `session` set and `stats_only = true`.
- [ ] An incoming `UserStats` frame updates `model` (ping appears on the user).

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*.SessionStateMachineTest"` → all pass.

**Steps:**

- [ ] **Step 1: Failing tests** in `SessionStateMachineTest.kt`:

```kotlin
    @Test fun requestUserStatsSendsStatsOnlyForSession() {
        sm.start("dan", null)
        sm.requestUserStats(7)
        val us = channel.sent.last { it.first == TcpMessageType.UserStats }.second as MumbleProtos.UserStats
        assertEquals(7, us.session); assertTrue(us.statsOnly)
    }

    @Test fun onFrameUserStatsUpdatesModel() {
        sm.start("dan", null)
        frame(TcpMessageType.UserState, MumbleProtos.UserState.newBuilder().setSession(7).setName("bob").build())
        frame(TcpMessageType.UserStats, MumbleProtos.UserStats.newBuilder().setSession(7).setTcpPingAvg(12f).build())
        assertEquals(12f, model.state.value.users[7]!!.tcpPingMs!!, 0.001f)
    }
```

- [ ] **Step 2: Run → FAIL** (`Unresolved reference: requestUserStats`; second test fails on null ping).

- [ ] **Step 3: Implement.** Add the `onFrame` case before `else ->` (~line 128):

```kotlin
            TcpMessageType.UserStats -> model.onUserStats(MumbleProtos.UserStats.parseFrom(frame.payload))
```

Add the sender near `sendSelfMute` (~line 179):

```kotlin
    /** Request a peer's mutable stats (ping/packets). Server relays that user's self-reported ping in
     *  its reply; stats_only omits the cert chain. See the per-user stats debug screen. */
    fun requestUserStats(session: Int) {
        channel.send(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder().setSession(session).setStatsOnly(true).build())
    }
```

- [ ] **Step 4: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*.SessionStateMachineTest"`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/SessionStateMachineTest.kt
git commit -m "feat(protocol): UserStats request/response wiring"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt", "app/src/test/java/me/danielstiner/dumble/mumble/SessionStateMachineTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"*.SessionStateMachineTest\"", "acceptanceCriteria": ["requestUserStats sends session+stats_only", "incoming UserStats updates model ping"], "modelTier": "mechanical"}
```

---

### Task 5: MumbleManager UserStats polling

**Goal:** While enabled, the active session polls `UserStats` for each known user on a conservative cadence; disabled/idle sends nothing.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt` (a manager-level forwarder near `setMuted` ~line 115, and a poll loop inside `ActiveSession`)

**Acceptance Criteria:**
- [ ] `MumbleManager.setUserStatsPolling(true)` starts a coroutine on the session scope that, every ~5 s, calls `sm.requestUserStats(session)` for each user in `model.state.value.users`.
- [ ] `setUserStatsPolling(false)` cancels it; with no active session it is a no-op (graceful when the call ended).
- [ ] Full unit suite compiles and passes (no new unit test — coroutine glue in the `object`; covered by Task 4's sender test + the Task 6 end-to-end and the existing `LiveServerIntegrationTest` probe).

**Verify:** `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (no regressions).

**Steps:**

- [ ] **Step 1: Add the manager-level forwarder** near `setMuted` (~line 115):

```kotlin
    /** Poll each visible user's UserStats (ping) while the per-user stats screen is open. Gated so
     *  we send zero extra traffic otherwise. No-op when no call is active. */
    @Synchronized fun setUserStatsPolling(enabled: Boolean) { active?.setUserStatsPolling(enabled) }
```

- [ ] **Step 2: Add the poll loop inside `ActiveSession`** (near the other `fun set…` passthroughs ~line 450). Add a field and method:

```kotlin
        private var userStatsPollJob: Job? = null
        fun setUserStatsPolling(enabled: Boolean) {
            userStatsPollJob?.cancel(); userStatsPollJob = null
            if (!enabled) return
            userStatsPollJob = sessionScope.launch {
                while (currentCoroutineContext().isActive) {
                    for (u in model.state.value.users.values) sm.requestUserStats(u.session)
                    delay(USERSTATS_POLL_MS)
                }
            }
        }
```

Add the interval constant to the `MumbleManager` companion/top (beside other `*_MS` constants, e.g. near `CONNECTING_TIMEOUT_MS`):

```kotlin
    private const val USERSTATS_POLL_MS = 5_000L   // per-user ping poll cadence (server-verified safe)
```

Ensure the imports `kotlinx.coroutines.Job`, `kotlinx.coroutines.currentCoroutineContext`, `kotlinx.coroutines.isActive`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.launch` are present (most already are — `sessionScope.launch` is used at line 372+).

- [ ] **Step 3: Run full suite → PASS.** `./gradlew :app:testDebugUnitTest`

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(net): gated per-user UserStats polling"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest", "acceptanceCriteria": ["setUserStatsPolling(true) polls each user every ~5s on the session scope", "setUserStatsPolling(false) cancels; no-op with no active session", "full suite green"], "modelTier": "standard"}
```

---

### Task 6: PerUserStatsScreen + navigation + polling gate

**Goal:** A dedicated debug screen shows server RTT once plus a per-user row (name · ping · target · p95 · buffered · late-drops), reached from Audio diagnostics, polling only while open.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/PerUserStatsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt` (add `onOpenPerUser` param + a clickable row)
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt` (nav boolean, branch order, wiring, `DisposableEffect`)

**Acceptance Criteria:**
- [ ] `PerUserStatsScreen` renders a server-RTT header (from `NetStats`) and one row per user in the passed list, joining `SpeakerJitter` by session (jitter cells show "—" when no active stream); empty state when no users.
- [ ] A "Per-user stats →" row on `AudioDiagnosticsScreen` opens it; back returns.
- [ ] `MumbleManager.setUserStatsPolling(true)` is called while the screen is shown and `(false)` on dispose (Compose `DisposableEffect`).
- [ ] `./gradlew :app:assembleDebug` succeeds.

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. (UI is verified on-device by Dan.)

**Steps:**

- [ ] **Step 1: Create `PerUserStatsScreen.kt`:**

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.net.NetStats
import me.danielstiner.dumble.mumble.voice.SpeakerJitter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerUserStatsScreen(
    users: List<MumbleUser>, perSpeaker: List<SpeakerJitter>, net: NetStats, onBack: () -> Unit,
) {
    fun rtt(v: Double) = if (v >= 0) "%.1f ms".format(v) else "—"
    fun ping(v: Float?) = if (v != null && v >= 0f) "%.0f ms".format(v) else "—"
    val bySession = perSpeaker.associateBy { it.session }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Per-user stats") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Server (our link)")
            Text("  Transport:  ${net.mode}")
            Text("  TCP RTT:    " + rtt(net.tcpRttMs))
            Text("  UDP RTT:    " + rtt(net.udpRttMs))
            Text("  UDP jitter: %.2f ms".format(net.udpJitterMs))
            Text("")
            Text("Per user  (ping = their ping to server; p95 = raw unclamped jitter spike)")
            if (users.isEmpty()) { Text("  — no users —"); return@Column }
            users.sortedBy { it.name }.forEach { u ->
                val j = bySession[u.session]
                Text("  ${u.name}")
                Text("    ping tcp/udp: ${ping(u.tcpPingMs)} / ${ping(u.udpPingMs)}")
                if (j != null)
                    Text("    jitter: target=${j.targetMs}ms p95=${j.p95Ms}ms buffered=${j.bufferedMs}ms lateDrops=${j.lateDrops}")
                else
                    Text("    jitter: — (no active stream)")
            }
        }
    }
}
```

- [ ] **Step 2: Add the entry row to `AudioDiagnosticsScreen.kt`.** Add a parameter `onOpenPerUser: () -> Unit` to the function signature, and a clickable row at the top of the `Column` (after the `TopAppBar` opens the content, before "Platform effects"):

```kotlin
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Per-user stats →", modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPerUser)
                .padding(vertical = 8.dp))
            Text("")
            Text("Platform effects (device self-report)")
```

Add imports: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.fillMaxWidth`.

- [ ] **Step 3: Wire nav in `DumbleApp.kt`.** Add a state boolean beside `showDiagnostics` (~line 72):

```kotlin
    var showPerUserStats by remember { mutableStateOf(false) }
```

Add a branch **before** the `showDiagnostics ->` branch (~line 102) so it renders over it:

```kotlin
        showPerUserStats -> {
            BackHandler { showPerUserStats = false }
            DisposableEffect(Unit) {
                MumbleManager.setUserStatsPolling(true)
                onDispose { MumbleManager.setUserStatsPolling(false) }
            }
            PerUserStatsScreen(
                users = serverModel.users.values.toList(),
                perSpeaker = jitter.perSpeaker,
                net = netStats,
                onBack = { showPerUserStats = false },
            )
        }
```

Update the `AudioDiagnosticsScreen(...)` call (~line 104) to pass the opener:

```kotlin
            AudioDiagnosticsScreen(diagnostics = audioDiagnostics, net = netStats, voice = voiceStats,
                latency = latency, jitter = jitter, onBack = { showDiagnostics = false },
                onOpenPerUser = { showPerUserStats = true })
```

Add imports if missing: `androidx.compose.runtime.DisposableEffect`. (`BackHandler`, `remember`, `mutableStateOf`, `getValue`, `setValue` are already used for `showDiagnostics`.)

- [ ] **Step 4: Build → PASS.** `./gradlew :app:assembleDebug`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/PerUserStatsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): per-user stats debug screen + gated polling"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/PerUserStatsScreen.kt", "app/src/main/java/me/danielstiner/dumble/ui/AudioDiagnosticsScreen.kt", "app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["screen shows server RTT + per-user rows joined by session, empty state", "entry row opens it, back returns", "DisposableEffect toggles setUserStatsPolling", "assembleDebug succeeds"], "modelTier": "standard"}
```

---

## Post-tasks

- Update `docs/TODO.md`: mark the "per-user (per-speaker) jitter debug breakout — IN PROGRESS" item and the "per-user ping via UserStats" item as done; leave per-user volume deferred.
- On-device (Dan's batch): open Audio diagnostics → Per-user stats during a multi-party call; confirm each speaker's row shows plausible ping + attributed jitter, and the aggregate 4000 ms is now traceable to one user.
- Optional cleanup: the dockerized Murmur (`docs/dev/mumble-server/docker-compose.yml`) can be stopped (`docker compose … down`) once on-device verification is done.
