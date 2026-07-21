# Audio cues (join / leave / new chat) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Short audio cues, mixed into the call audio, when someone joins/leaves your channel or a chat message arrives — with a Settings toggle.

**Architecture:** A `SoundCues` player wraps `ToneGenerator` on the `STREAM_VOICE_CALL` stream. Chat cues fire from the existing `MumbleManager.onTextMessage` (incoming only). Join/leave cues come from a **pure** `diffChannelPresence(prev, model)` run by a session collector that only cues after `Synchronized` and re-baselines silently on the initial roster and on your own channel changes. A single persisted "Sound cues" toggle gates everything.

**Tech Stack:** Kotlin, Android `ToneGenerator`/`AudioManager`, kotlinx.coroutines StateFlow, Jetpack Compose (Settings), JUnit.

**User decisions (already made):**
- Sound source: **synthesized ToneGenerator presets** (asset-free) — bundled sounds deferred.
- Join/leave scope: **my current channel** (not server-wide).
- Settings: **one master toggle, default ON** (per-event toggles deferred).
- Cues play on the **voice-call stream** so they mix with call audio.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `mumble/SoundCueLogic.kt` (new) | pure `diffChannelPresence` + `ChannelPresence`/`PresenceDiff` | 1 |
| `mumble/voice/SoundCues.kt` (new) | `ToneGenerator` player (join/leave/chat/release) | 2 |
| `mumble/MumbleManager.kt` | enabled flag + persistence, `SoundCues` instance, presence collector, chat cue | 3 |
| `ui/SettingsScreen.kt` | "Sound cues" toggle card | 4 |
| `ui/DumbleApp.kt` | collect + wire the toggle | 4 |

Each task keeps the build green (Tasks 1–2 add isolated new files; Task 3 wires them; Task 4 is additive UI).

---

### Task 1: Pure channel-presence diff + tests

**Goal:** A pure function that computes who joined/left *your* channel between two model snapshots, silently baselining on first sight and on your own channel change.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/SoundCueLogic.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/DiffChannelPresenceTest.kt`

**Acceptance Criteria:**
- `diffChannelPresence(prev, model)` returns members of *our* channel excluding self.
- `prev == null` OR our own `channelId` changed → empty joins/leaves + fresh baseline (no cues on initial roster / self-move).
- Otherwise `joins = members - prev.members`, `leaves = prev.members - members`.
- Users in other channels never appear in joins/leaves.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*.DiffChannelPresenceTest"` → pass.

**Steps:**

- [ ] **Step 1: Create `SoundCueLogic.kt`:**

```kotlin
package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.model.ServerModel

/** Snapshot of who is in OUR current channel (excluding self). */
data class ChannelPresence(val channelId: Int?, val members: Set<Int>)
data class PresenceDiff(val joins: Set<Int>, val leaves: Set<Int>, val next: ChannelPresence)

/**
 * Diff the members of our current channel (excluding ourselves) against [prev]. Returns empty
 * joins/leaves plus a fresh baseline when [prev] is null (first snapshot) or our OWN channel changed
 * (we moved / just synced) — so the initial roster and our own channel hops never fire cues.
 */
fun diffChannelPresence(prev: ChannelPresence?, model: ServerModel): PresenceDiff {
    val myId = model.sessionId
    val myChannel = myId?.let { model.users[it]?.channelId }
    val members = model.users.values
        .filter { it.session != myId && it.channelId == myChannel }
        .map { it.session }.toSet()
    val next = ChannelPresence(myChannel, members)
    if (prev == null || prev.channelId != myChannel) return PresenceDiff(emptySet(), emptySet(), next)
    return PresenceDiff(joins = members - prev.members, leaves = prev.members - members, next = next)
}
```
(When `myChannel` is null — our user not yet in the model — `members` is empty and `next.channelId` is null; the next real channel triggers a silent re-baseline.)

- [ ] **Step 2: Failing tests `DiffChannelPresenceTest.kt`:**

```kotlin
package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.model.MumbleUser
import me.danielstiner.dumble.mumble.model.ServerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffChannelPresenceTest {
    /** users = (session, channelId); [myId] is our session. */
    private fun model(myId: Int?, vararg users: Pair<Int, Int>): ServerModel =
        ServerModel(
            users = users.associate { (s, c) -> s to MumbleUser(session = s, name = "u$s", channelId = c) },
            sessionId = myId,
        )

    @Test fun firstSnapshotBaselinesNoCues() {
        val d = diffChannelPresence(null, model(1, 1 to 0, 2 to 0))
        assertTrue(d.joins.isEmpty() && d.leaves.isEmpty())
        assertEquals(setOf(2), d.next.members)          // self (1) excluded
        assertEquals(0, d.next.channelId)
    }
    @Test fun detectsJoinInMyChannel() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2)), model(1, 1 to 0, 2 to 0, 3 to 0))
        assertEquals(setOf(3), d.joins); assertTrue(d.leaves.isEmpty())
    }
    @Test fun detectsLeave() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2, 3)), model(1, 1 to 0, 2 to 0))
        assertEquals(setOf(3), d.leaves); assertTrue(d.joins.isEmpty())
    }
    @Test fun ignoresOtherChannels() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2)), model(1, 1 to 0, 2 to 0, 4 to 5))
        assertTrue(d.joins.isEmpty() && d.leaves.isEmpty())   // user 4 is in channel 5, not ours
    }
    @Test fun myOwnChannelChangeRebaselinesNoCues() {
        val d = diffChannelPresence(ChannelPresence(0, setOf(2)), model(1, 1 to 7, 9 to 7))  // we moved to ch 7
        assertTrue(d.joins.isEmpty() && d.leaves.isEmpty())
        assertEquals(ChannelPresence(7, setOf(9)), d.next)
    }
}
```

- [ ] **Step 3: Run → FAIL** (unresolved `diffChannelPresence`), then implement Step 1 → **Run → PASS**.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/SoundCueLogic.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/DiffChannelPresenceTest.kt
git commit -m "feat(cues): pure channel-presence diff for join/leave detection"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/SoundCueLogic.kt", "app/src/test/java/me/danielstiner/dumble/mumble/DiffChannelPresenceTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"*.DiffChannelPresenceTest\"", "acceptanceCriteria": ["members = our channel excl self", "null prev / own-channel-change → no cues + baseline", "joins/leaves = set diffs", "other channels ignored"], "modelTier": "standard"}
```

---

### Task 2: SoundCues player

**Goal:** A `ToneGenerator`-backed player for the three cues on the voice-call stream.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SoundCues.kt`

**Acceptance Criteria:**
- `join()`/`leave()`/`chat()` play distinct `ToneGenerator` presets on `STREAM_VOICE_CALL`; `release()` frees the generator.
- Generator is created lazily and construction failure is swallowed (never crashes a call).

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (Android glue; no unit test).

**Steps:**

- [ ] **Step 1: Create `SoundCues.kt`:**

```kotlin
package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Short in-call audio cues via [ToneGenerator] on the voice-call stream, so they mix into the call
 * audio (earpiece / BT / speaker). Lazily opens the generator; [release] frees it when the call ends
 * (re-created on the next cue). ToneGenerator construction can throw when audio resources are scarce,
 * so it's guarded — a failed cue must never take down a call.
 */
class SoundCues {
    // All access under the instance monitor: chat() fires from the TCP-reader thread while
    // join()/leave() fire from the sessionScope collector, so unsynchronized lazy-init could
    // construct (and leak) two generators, and release() could race a play. @Synchronized on every
    // public method makes construction/play/release mutually exclusive; tones() is only ever called
    // from inside those, so it needs no annotation and gen needs no @Volatile.
    private var gen: ToneGenerator? = null

    private fun tones(): ToneGenerator? = gen ?: runCatching {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME)
    }.onFailure { Log.w(TAG, "ToneGenerator init failed", it) }.getOrNull()?.also { gen = it }

    @Synchronized fun join() { tones()?.startTone(ToneGenerator.TONE_PROP_ACK) }   // ascending blip
    @Synchronized fun leave() { tones()?.startTone(ToneGenerator.TONE_PROP_NACK) } // descending blip
    @Synchronized fun chat() { tones()?.startTone(ToneGenerator.TONE_PROP_BEEP) }  // soft beep
    @Synchronized fun release() { runCatching { gen?.release() }; gen = null }

    companion object { private const val TAG = "SoundCues"; private const val VOLUME = 80 } // 0..100
}
```

- [ ] **Step 2: Build → PASS.** `./gradlew :app:assembleDebug`

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/SoundCues.kt
git commit -m "feat(cues): SoundCues ToneGenerator player on the voice-call stream"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/voice/SoundCues.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["join/leave/chat play distinct presets on STREAM_VOICE_CALL", "release frees generator", "lazy init, construction failure swallowed"], "modelTier": "mechanical"}
```

---

### Task 3: MumbleManager wiring (enabled flag + persistence + collector + chat cue)

**Goal:** Persisted enable flag, a `SoundCues` instance, the chat cue, and the after-sync join/leave collector.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`

**Acceptance Criteria:**
- `soundCuesEnabled: StateFlow<Boolean>` (default true) + `setSoundCuesEnabled(v)` persisting to `dumble_audio` key `sound_cues_enabled`; loaded on init.
- `onTextMessage` plays `soundCues.chat()` when enabled (incoming only — it already runs only on inbound).
- A session collector uses `sm.state.flatMapLatest { if Synchronized model.state else emptyFlow() }`, runs `diffChannelPresence`, and plays `join()`/`leave()` per batch (guarded by the enable flag). (flatMapLatest, not combine — avoids the stale-partial-roster race.)
- `disconnect()` calls `soundCues.release()`.

**Verify:** `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Add the default const** near the other `DEFAULT_*`:

```kotlin
    private const val DEFAULT_SOUND_CUES_ENABLED = true
```
Add the flow + player near the other StateFlows (e.g. after `_rnnoiseEnabled`/`rnnoiseEnabled`):

```kotlin
    private val _soundCuesEnabled = MutableStateFlow(DEFAULT_SOUND_CUES_ENABLED)
    /** Play short cues on channel join/leave + new chat, mixed into the call audio. Persisted. */
    val soundCuesEnabled: StateFlow<Boolean> = _soundCuesEnabled.asStateFlow()
    private val soundCues = SoundCues()
```

- [ ] **Step 2: Setter** (beside `setRnnoiseEnabled`):

```kotlin
    @Synchronized fun setSoundCuesEnabled(value: Boolean) {
        _soundCuesEnabled.value = value
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("sound_cues_enabled", value)?.apply()
    }
```

- [ ] **Step 3: Load on init.** In the init block that reads `audioPrefs` (beside `_rnnoiseEnabled.value = audioPrefs.getBoolean(...)`):

```kotlin
        _soundCuesEnabled.value = audioPrefs.getBoolean("sound_cues_enabled", DEFAULT_SOUND_CUES_ENABLED)
```

- [ ] **Step 4: Chat cue.** In the `events` object's `onTextMessage` (which already runs only on inbound), after the `appendChat(...)` line add:

```kotlin
                if (_soundCuesEnabled.value) soundCues.chat()
```

- [ ] **Step 5: Join/leave collector.** In **`ActiveSession.start()`** (NOT `connect()` — `connect()` is on the outer object and has no `sm` in scope; the sibling `sessionScope.launch { … .collect { … } }` collectors live in `start()`), add:

```kotlin
            sessionScope.launch {
                var presence: ChannelPresence? = null
                // flatMapLatest, NOT combine(sm.state, model.state): combine fans two independently-
                // conflated StateFlows back together with no cross-flow atomicity, so the first
                // Synchronized-tagged emission could carry a STALE/partial roster and then spuriously
                // cue "joins" for users already present at connect. flatMapLatest re-subscribes to
                // model.state fresh at each Synchronized transition, so the baseline is the true roster.
                sm.state.flatMapLatest { conn ->
                    if (conn is ConnectionState.Synchronized) model.state else emptyFlow()
                }.collect { m ->
                    val diff = diffChannelPresence(presence, m)   // empty joins/leaves on first-sync + our own moves
                    presence = diff.next
                    if (_soundCuesEnabled.value) {
                        if (diff.joins.isNotEmpty()) soundCues.join()
                        if (diff.leaves.isNotEmpty()) soundCues.leave()
                    }
                }
            }
```
`flatMapLatest`/`emptyFlow` are in `kotlinx.coroutines.flow.*` (wildcard-imported); `ConnectionState` via `protocol.*`; `diffChannelPresence`/`ChannelPresence` are same-package; `SoundCues` via `voice.*`. **`flatMapLatest` is `@ExperimentalCoroutinesApi`** — add `@OptIn(ExperimentalCoroutinesApi::class)` to the enclosing `ActiveSession.start()` function (or `@file:OptIn(...)` at the top). `ExperimentalCoroutinesApi` is in `kotlinx.coroutines.*` (already wildcard-imported). No `presence = null` reset branch is needed — each reconnect is a fresh `ActiveSession` with its own `presence`.

- [ ] **Step 6: Release on disconnect.** In `MumbleManager.disconnect()` (beside the `_chat.value = emptyList()` clears added for chat), add:

```kotlin
        soundCues.release()
```

- [ ] **Step 7: Run the full suite → PASS.** `./gradlew :app:testDebugUnitTest` (if `buildHostRnnoise` fails on `cmake`, `./gradlew --stop` first, then retry.)

- [ ] **Step 8: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(cues): wire sound cues (join/leave/chat) + persisted toggle in MumbleManager"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest", "acceptanceCriteria": ["soundCuesEnabled flow + setter persist + load (default true)", "onTextMessage plays chat cue when enabled", "flatMapLatest(sm.state->model.state) collector cues join/leave via diffChannelPresence (NOT combine)", "disconnect releases SoundCues"], "modelTier": "standard"}
```

---

### Task 4: Settings toggle

**Goal:** A "Sound cues" switch in Settings, wired to `MumbleManager`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- `SettingsScreen` gains `soundCuesEnabled: Boolean, onSoundCuesEnabledChange: (Boolean) -> Unit` params and a "Sound cues" `ElevatedCard` (Enabled switch + one-line description), matching the AGC/RNNoise card style; the `@Preview` passes the new params.
- `DumbleApp` collects `soundCuesEnabled` and wires `onSoundCuesEnabledChange = { MumbleManager.setSoundCuesEnabled(it) }`.
- `./gradlew :app:assembleDebug` succeeds.

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. (UI verified on-device.)

**Steps:**

- [ ] **Step 1: `SettingsScreen.kt` — params.** Add after `onRnnoiseEnabledChange: (Boolean) -> Unit,`:

```kotlin
    soundCuesEnabled: Boolean,
    onSoundCuesEnabledChange: (Boolean) -> Unit,
```

- [ ] **Step 2: The card.** Immediately after the "Noise suppression (RNNoise)" `ElevatedCard` block (before the `// Tools` section), add:

```kotlin
                        // Sound cues
                        Text(
                            "Sound cues",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        )
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Enabled", modifier = Modifier.weight(1f))
                                    Switch(checked = soundCuesEnabled, onCheckedChange = onSoundCuesEnabledChange)
                                }
                                Text("Short blips when someone joins/leaves your channel or a chat arrives.")
                            }
                        }
```

- [ ] **Step 3: `@Preview`.** Find the `SettingsScreen(...)` call in the file's `@Preview` (passes `rnnoiseEnabled = true, onRnnoiseEnabledChange = {}`) and add:

```kotlin
            soundCuesEnabled = true, onSoundCuesEnabledChange = {},
```

- [ ] **Step 4: `DumbleApp.kt`.** Collect near the other flows:

```kotlin
    val soundCuesEnabled by MumbleManager.soundCuesEnabled.collectAsStateWithLifecycle()
```
In the `SettingsScreen(...)` call, add (near `rnnoiseEnabled = …`):

```kotlin
                soundCuesEnabled = soundCuesEnabled,
                onSoundCuesEnabledChange = { MumbleManager.setSoundCuesEnabled(it) },
```

- [ ] **Step 5: Build → PASS.** `./gradlew :app:assembleDebug`

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(cues): Sound cues Settings toggle"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt", "app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["SettingsScreen sound-cues card + params + preview", "DumbleApp collects + wires setSoundCuesEnabled", "assembleDebug succeeds"], "modelTier": "standard"}
```

---

## Post-tasks
- Update `docs/TODO.md`: add "Sound cues (join/leave/chat)" as DONE with the deferred follow-ups (bundled custom sounds, per-event toggles, server-wide scope).
- On-device (Dan's batch): in a call, have someone join/leave your channel and send a chat — confirm the cues play through the call audio (and route to your headset), the initial roster on connect is silent, moving yourself between channels is silent, and the Settings toggle silences them.
