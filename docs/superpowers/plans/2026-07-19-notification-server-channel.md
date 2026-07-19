# Ongoing-call notification: server + channel (#55) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the server label (prominent) + current channel (secondary) on the ongoing-call notification, live-updating as the handshake completes and the user moves channels.

**Architecture:** Two pure JVM-testable helpers (`serverLabel`/`currentChannelName`) in `mumble/model`, reused by the call screen. `CallManager` observes `MumbleManager.model.state`, derives `(serverLabel, channelName)`, and re-posts the FGS `CallStyle` notification on change. The notification builder maps them to `Person` name + `setContentText`.

**Tech Stack:** Kotlin, `Notification.CallStyle.forOngoingCall`, telecom (`CallManager`/`CallForegroundService`/`CallNotificationManager`), `MumbleModel`/`ServerModel`, StateFlow, JUnit4.

**User decisions (already made):** Server label prominent (`Person` name), channel secondary (`contentText` = "in <channel>"); label = root-channel name or hostname fallback; live-refresh from the model.

**Spec:** `docs/superpowers/specs/2026-07-19-notification-server-channel-design.md` (fable-verified: all code claims confirmed; AOSP `CallStyle` renders `Person`=title + `setContentText`=secondary line).

**Load-bearing facts (fable-verified):**
- AOSP `CallStyle` uses `Person.name` as the title and renders `setContentText` as the secondary line **only when non-null** (an empty string suppresses the "Ongoing call" default). Pass `null` when the channel is unknown.
- `CallStyle` throws on a blank `Person` name → guard with `.ifBlank { "Dumble" }`.
- `teardown()` must reset the new server/channel state (`disconnect()` doesn't clear the model → call N leaks into call N+1).
- `MumbleManager.model.state: StateFlow<ServerModel>`; `CallManager.appScope` is `Dispatchers.Main` (collector + state fields are Main-confined).

---

## File Structure

| File | Task | Responsibility |
|------|------|----------------|
| `mumble/model/ServerDisplay.kt` | T1 | Pure `serverLabel(model, hostFallback)` + `currentChannelName(model)` |
| `test/.../mumble/model/ServerDisplayTest.kt` | T1 | JVM tests |
| `ui/CallScreenState.kt` | T1 | DRY: `buildCallScreenState` calls `serverLabel(...)` |
| `telecom/CallNotificationManager.kt` | T2 | `createNotification(serverLabel, channelName, …)` |
| `telecom/CallForegroundService.kt` | T2 | `start(ctx, serverLabel, channelName, connectedSinceMs)` + extras |
| `telecom/CallManager.kt` | T2 | host plumb + state fields + model collector + `startCallForeground`/`teardown` |
| `ActiveCallActivity.kt` | T2 | `startCall(config.host)` |

**Task order:** T1 → T2 (T2 blockedBy T1). All gradle prefixed with `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Never stage `.idea/gradle.xml`. Commit trailers: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz`.

**On-device verification is NOT a task** — Dan batches it (label/channel render, fill-in during handshake, channel-switch update, backgrounded admin-move).

---

### Task 1: ServerDisplay helpers (pure, JVM-tested) + call-screen DRY

**Goal:** Create `serverLabel`/`currentChannelName` in `mumble/model` with JVM tests, and route `buildCallScreenState` through `serverLabel` (behavior-preserving).

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/model/ServerDisplay.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/model/ServerDisplayTest.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/CallScreenState.kt`

**Acceptance Criteria:**
- [ ] `serverLabel(model, hostFallback)` = root-channel name, or `hostFallback` when it's blank / "Root" / no root.
- [ ] `currentChannelName(model)` = the local user's channel name, or `null` if `sessionId`/user/channel is missing.
- [ ] `buildCallScreenState` computes `serverName` via `serverLabel(model, configHostFallback)` (no inline duplication); the existing `CallScreenStateTest` still passes.
- [ ] Both helpers' tests pass on the JVM.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.model.ServerDisplayTest" --tests "me.danielstiner.dumble.ui.CallScreenStateTest"` → all PASS.

**Steps:**

- [ ] **Step 1: Write `ServerDisplayTest.kt` (failing)**

```kotlin
package me.danielstiner.dumble.mumble.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDisplayTest {
    private fun ch(id: Int, parent: Int?, name: String) = MumbleChannel(id = id, parentId = parent, name = name)
    private fun user(session: Int, channel: Int) = MumbleUser(session = session, name = "u$session", channelId = channel)

    @Test fun serverLabel_usesRootChannelName() {
        val m = ServerModel(channels = mapOf(0 to ch(0, null, "My Server"), 1 to ch(1, 0, "General")))
        assertEquals("My Server", serverLabel(m, "host.example"))
    }

    @Test fun serverLabel_fallsBackWhenRootBlankOrDefaultOrMissing() {
        assertEquals("host.example", serverLabel(ServerModel(channels = mapOf(0 to ch(0, null, "Root"))), "host.example"))
        assertEquals("host.example", serverLabel(ServerModel(channels = mapOf(0 to ch(0, null, ""))), "host.example"))
        assertEquals("host.example", serverLabel(ServerModel(), "host.example"))
    }

    @Test fun currentChannelName_selfChannel() {
        val m = ServerModel(
            channels = mapOf(0 to ch(0, null, "Root"), 5 to ch(5, 0, "General")),
            users = mapOf(7 to user(7, 5)),
            sessionId = 7,
        )
        assertEquals("General", currentChannelName(m))
    }

    @Test fun currentChannelName_nullBeforeSyncOrMissing() {
        assertNull(currentChannelName(ServerModel(channels = mapOf(0 to ch(0, null, "Root")))))  // sessionId null
        assertNull(currentChannelName(ServerModel(sessionId = 7)))                                // user missing
    }
}
```

- [ ] **Step 2: Run to confirm it fails** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.mumble.model.ServerDisplayTest"` → FAIL (unresolved `serverLabel`).

- [ ] **Step 3: Create `ServerDisplay.kt`**

```kotlin
package me.danielstiner.dumble.mumble.model

/** Server label for display: the root channel's name, or [hostFallback] if it's blank or murmur's
 *  default "Root" (its literal name for an unregistered server). */
fun serverLabel(model: ServerModel, hostFallback: String): String {
    val rootName = model.channels.values.firstOrNull { it.parentId == null }?.name
    return rootName?.takeIf { it.isNotBlank() && it != "Root" } ?: hostFallback
}

/** The name of the channel the local user is currently in, or null if unknown (pre-ServerSync, or a
 *  missing user/channel). */
fun currentChannelName(model: ServerModel): String? {
    val myChannelId = model.sessionId?.let { model.users[it]?.channelId } ?: return null
    return model.channels[myChannelId]?.name
}
```

- [ ] **Step 4: Run tests → PASS** (same command as Step 2, both classes).

- [ ] **Step 5: DRY `buildCallScreenState`.** In `ui/CallScreenState.kt`, add the import `import me.danielstiner.dumble.mumble.model.serverLabel` (with the other `mumble.model` imports), and replace these three lines (~47-49):

```kotlin
    val rootName = model.channels.values.firstOrNull { it.parentId == null }?.name
    // "Root" is murmur's literal default for an unregistered server; treat it as no name.
    val serverName = rootName?.takeIf { it.isNotBlank() && it != "Root" } ?: configHostFallback
```

with:

```kotlin
    val serverName = serverLabel(model, configHostFallback)
```

- [ ] **Step 6: Run the call-screen test to confirm the refactor is behavior-preserving** — `... --tests "me.danielstiner.dumble.ui.CallScreenStateTest"` → PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/model/ServerDisplay.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/model/ServerDisplayTest.kt \
        app/src/main/java/me/danielstiner/dumble/ui/CallScreenState.kt
git commit -m "feat(model): serverLabel/currentChannelName helpers + call-screen DRY

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

### Task 2: Notification server/channel plumbing

**Goal:** Post the server label + channel on the FGS `CallStyle` notification, live-refreshed from `MumbleManager.model`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/CallNotificationManager.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/CallForegroundService.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt`

**Acceptance Criteria:**
- [ ] `CallNotificationManager.createNotification(serverLabel, channelName, isIncoming, connectedSinceMs)` sets the `Person` name = `serverLabel.ifBlank { "Dumble" }`, and `setContentText("in <channel>")` **only when `channelName != null`**.
- [ ] `CallForegroundService.start(ctx, serverLabel, channelName, connectedSinceMs)` carries both via extras and passes them to `createNotification`.
- [ ] `CallManager.startCall(host: String)` stores the host fallback; a new collector on `MumbleManager.model.state` re-posts the FGS on `(serverLabel, channelName)` change; `startCallForeground()` passes the current server/channel; `teardown()` resets the server/channel state.
- [ ] `ActiveCallActivity.onConnect` calls `CallManager.startCall(config.host)`.
- [ ] `assembleDebug` + full unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

**Steps:**

- [ ] **Step 1: `CallNotificationManager.createNotification`.** Replace the current method (signature `createNotification(callerName: String, isIncoming: Boolean, connectedSinceMs: Long? = null)`) with:

```kotlin
    fun createNotification(serverLabel: String, channelName: String?, isIncoming: Boolean, connectedSinceMs: Long? = null): Notification {
        val channelId = if (isIncoming) INCOMING_CHANNEL_ID else ONGOING_CHANNEL_ID
        val title = serverLabel.ifBlank { "Dumble" }   // CallStyle throws on an empty Person name

        val intent = Intent(appContext, ActiveCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val hangupIntent = Intent(appContext, CallActionReceiver::class.java).apply { action = "ACTION_HANGUP" }
        val hangupPendingIntent = PendingIntent.getBroadcast(appContext, 1, hangupIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = Notification.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
        // Only set contentText when the channel is known — an empty string suppresses CallStyle's
        // localized "Ongoing call" default and would show a blank line during the handshake.
        channelName?.let { builder.setContentText("in $it") }

        val person = android.app.Person.Builder().setName(title).build()
        if (isIncoming) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, hangupPendingIntent, pendingIntent))
        } else {
            builder.setStyle(Notification.CallStyle.forOngoingCall(person, hangupPendingIntent))
            if (connectedSinceMs != null) {
                builder.setWhen(connectedSinceMs).setUsesChronometer(true).setShowWhen(true)
            }
        }
        return builder.build()
    }
```

(`createFallbackNotification`, channels, `showNotification`, `cancelNotification` unchanged.)

- [ ] **Step 2: `CallForegroundService`.** In `onStartCommand`, replace the caller-name read + `createNotification` call:

```kotlin
        val serverLabel = intent?.getStringExtra(EXTRA_SERVER) ?: DEFAULT_SERVER
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL)   // null = channel unknown yet
        val connectedSinceMs = intent?.getLongExtra(EXTRA_CONNECTED_SINCE_MS, 0L)?.takeIf { it > 0L }
        val notification = try {
            notifications.createNotification(serverLabel, channelName, isIncoming = false, connectedSinceMs = connectedSinceMs)
        } catch (t: Throwable) {
            Log.e(TAG, "notification build failed; using fallback", t)
            notifications.createFallbackNotification()
        }
```

And in the companion, replace the caller-name const + `start`:

```kotlin
        private const val TAG = "CallFgService"
        private const val DEFAULT_SERVER = "Dumble"
        private const val EXTRA_SERVER = "server_label"
        private const val EXTRA_CHANNEL = "channel_name"
        private const val EXTRA_CONNECTED_SINCE_MS = "connected_since_ms"

        /** Start the call FGS, or refresh its notification (server/channel/chronometer). Idempotent. */
        fun start(context: Context, serverLabel: String = DEFAULT_SERVER, channelName: String? = null, connectedSinceMs: Long? = null) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_SERVER, serverLabel)
                if (channelName != null) putExtra(EXTRA_CHANNEL, channelName)
                if (connectedSinceMs != null) putExtra(EXTRA_CONNECTED_SINCE_MS, connectedSinceMs)
            }
            context.startForegroundService(intent)
        }
```

(`stop`, `onStartCommand`'s `startForeground` block, `onBind` unchanged.)

- [ ] **Step 3: `CallManager` — imports + state fields.** Add imports:

```kotlin
import me.danielstiner.dumble.mumble.model.currentChannelName
import me.danielstiner.dumble.mumble.model.serverLabel
```

Add fields next to `connectedSinceMs`:

```kotlin
    // Latest server/channel for the notification (Main-confined, like connectedSinceMs).
    private var hostFallback: String = "Dumble"
    private var serverLabelState: String = "Dumble"
    private var channelNameState: String? = null
```

- [ ] **Step 4: `CallManager.startCall` gains the host.** Change the signature to `fun startCall(host: String)` and, right after the `if (callJob?.isActive == true) …` guard (before `ensureRegistered`), seed the state:

```kotlin
        hostFallback = host
        serverLabelState = host
        channelNameState = null
```

- [ ] **Step 5: `CallManager` — the model collector.** Inside the `addCall { … }` block, alongside the other `launch { … }` collectors (e.g. right after the `MumbleManager.state` chronometer collector), add:

```kotlin
                    // Live server/channel for the notification. Runs on Main (appScope); the …State fields
                    // are Main-confined. Conflated StateFlow + the equality guard limit re-posts to changes.
                    launch {
                        MumbleManager.model.state.collect { m ->
                            val label = serverLabel(m, hostFallback)
                            val channel = currentChannelName(m)
                            if (label != serverLabelState || channel != channelNameState) {
                                serverLabelState = label
                                channelNameState = channel
                                startCallForeground()
                            }
                        }
                    }
```

- [ ] **Step 6: `CallManager.startCallForeground` passes server/channel.** Change the `CallForegroundService.start(...)` call:

```kotlin
    private fun startCallForeground() {
        val ctx = appContext ?: return
        try {
            CallForegroundService.start(ctx, serverLabelState, channelNameState, connectedSinceMs)
        } catch (t: Throwable) {
            Log.e(TAG, "starting call foreground service failed", t)
        }
    }
```

- [ ] **Step 7: `CallManager.teardown` resets the state.** Alongside `connectedSinceMs = null`, add:

```kotlin
        serverLabelState = "Dumble"
        channelNameState = null
```

- [ ] **Step 8: `ActiveCallActivity`.** Change the `onConnect` call `CallManager.startCall()` to:

```kotlin
        CallManager.startCall(config.host)
```

- [ ] **Step 9: Build + test** — `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests pass.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/telecom/CallNotificationManager.kt \
        app/src/main/java/me/danielstiner/dumble/telecom/CallForegroundService.kt \
        app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt \
        app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt
git commit -m "feat(telecom): show server + channel on the ongoing-call notification

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz"
```

---

## Self-Review

**Spec coverage:** helpers + DRY (T1) ✓; notification `(serverLabel, channelName)` mapping with null-not-"" and blank-guard (T2 Step 1) ✓; FGS extras (T2 Step 2) ✓; host plumb + state fields + model collector + startCallForeground + teardown reset (T2 Steps 3-8) ✓; on-device out of scope ✓.

**Type consistency:** `serverLabel(ServerModel, String): String`, `currentChannelName(ServerModel): String?`; `createNotification(serverLabel: String, channelName: String?, isIncoming, connectedSinceMs)`; `CallForegroundService.start(ctx, serverLabel, channelName?, connectedSinceMs?)`; `startCall(host: String)`; `serverLabelState: String` / `channelNameState: String?` — consistent T1↔T2.

**Placeholders:** none — full code for every new file + precise before/after for every edit.
