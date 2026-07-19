# Ongoing-call notification: server + channel (#55) — Design

**Goal:** Show the **server label** (prominent) and the **current channel** (secondary) on the ongoing-call notification, live-updating as the handshake completes and the user moves channels, instead of the hardcoded "Dumble User".

**Architecture:** Extract the server-label + current-channel derivation into two pure, JVM-testable helpers (reused by the call screen). `CallManager` observes `MumbleManager.model.state`, derives `(serverLabel, channelName)`, and re-posts the foreground-service `CallStyle` notification on change (the same idempotent path the chronometer already uses). The notification builder takes `(serverLabel, channelName)` and maps them to the `Person` name + `setContentText`.

**Tech Stack:** Kotlin, `Notification.CallStyle.forOngoingCall`, existing `CallManager`/`CallForegroundService`/`CallNotificationManager` (telecom), `MumbleModel`/`ServerModel`, StateFlow.

**User decisions (already made):**
- Format: **server label prominent, channel secondary** (`Person` name = server; `contentText` = "in <channel>").
- Server label = root-channel name, or the config **hostname** fallback (reuse the existing call-screen rule).
- Live-refresh the notification as the model updates (required — server/channel arrive post-connect and change on channel moves).

---

## Context — what exists

- **Server-label logic already exists**, inline in `buildCallScreenState` (`ui/CallScreenState.kt:47-49`):
  `rootName = channels.values.firstOrNull { it.parentId == null }?.name`; `serverName = rootName?.takeIf { it.isNotBlank() && it != "Root" } ?: configHostFallback`. ("Root" is murmur's default for an unregistered server → treat as no name.)
- **Current channel**: `myId = model.sessionId`; `myChannelId = model.users[myId]?.channelId`; channel name = `model.channels[myChannelId]?.name`.
- **`MumbleManager.model.state: StateFlow<ServerModel>`** — already collected by the UI (`DumbleApp`).
- **`CallNotificationManager.createNotification(callerName, isIncoming, connectedSinceMs)`** (telecom) builds `Notification.CallStyle.forOngoingCall(Person.Builder().setName(callerName)…, hangupPendingIntent)`, `setContentText("Call with $callerName")`, chronometer when `connectedSinceMs != null`. Caller name is currently the hardcoded `"Dumble User"`.
- **`CallForegroundService.start(context, callerName, connectedSinceMs)`** posts/refreshes the FGS notification (idempotent); **`CallManager.startCallForeground()`** calls it; `CallManager` re-posts on `setActive` success and on Mumble-`Synchronized` (chronometer anchor).
- **`CallManager.startCall()`** (no args today) is called from `ActiveCallActivity.onConnect`, which also holds the connect config (with `host`).

## Components

### 1. Pure helpers (JVM-testable) — new `mumble/model/ServerDisplay.kt`

Placed in the neutral `mumble/model` package (next to `ServerModel`), not `ui/`, so both `ui` (`buildCallScreenState`) and `telecom` (`CallManager`) can use them without a `telecom → ui` dependency.

```kotlin
/** Server label for display: the root channel's name, or [hostFallback] if it's blank or murmur's
 *  default "Root". */
fun serverLabel(model: ServerModel, hostFallback: String): String {
    val rootName = model.channels.values.firstOrNull { it.parentId == null }?.name
    return rootName?.takeIf { it.isNotBlank() && it != "Root" } ?: hostFallback
}

/** The name of the channel the local user is currently in, or null if unknown yet. */
fun currentChannelName(model: ServerModel): String? {
    val myChannelId = model.sessionId?.let { model.users[it]?.channelId } ?: return null
    return model.channels[myChannelId]?.name
}
```

`buildCallScreenState` is updated to call `serverLabel(model, configHostFallback)` instead of inlining it (DRY; behavior-preserving).

### 2. Notification content = (server, channel) — `telecom/CallNotificationManager.kt`

Change `createNotification` to take `serverLabel: String, channelName: String?` (replacing `callerName`):
- `Person.Builder().setName(serverLabel)` → the prominent CallStyle line.
- `setContentTitle(serverLabel)`; `setContentText(channelName?.let { "in $it" } ?: "")` → the secondary line.
- Chronometer/incoming behavior unchanged.

### 3. FGS + CallManager plumbing — `telecom/CallForegroundService.kt`, `telecom/CallManager.kt`

- `CallForegroundService.start(context, serverLabel, channelName, connectedSinceMs)` — carry both through the intent extras (`EXTRA_SERVER`, `EXTRA_CHANNEL`) and hand them to `createNotification`. Fallback `serverLabel` when the extra is missing = a neutral default (e.g. `"Dumble"`).
- `CallManager` holds Main-confined latest `serverLabel` / `channelName` (defaults: the host / null) plus the existing `connectedSinceMs`. `startCallForeground()` passes all three to `CallForegroundService.start`.
- `CallManager.startCall(hostFallback: String)` gains the host; store it as the initial/label fallback. `ActiveCallActivity.onConnect` passes `config.host`.
- **New collector** in the `addCall` block:
  ```kotlin
  launch {
      MumbleManager.model.state.collect { m ->
          val label = serverLabel(m, hostFallback)
          val channel = currentChannelName(m)
          if (label != serverLabelState || channel != channelNameState) {
              serverLabelState = label; channelNameState = channel
              startCallForeground()   // re-post with the new server/channel (+ current chronometer)
          }
      }
  }
  ```
  Runs on Main (the appScope dispatcher), so the `…State` fields are Main-confined. Conflated `StateFlow` + the equality guard keep re-posts to genuine changes.

### Data flow

```
ActiveCallActivity.onConnect ─ startCall(config.host) ─► CallManager (hostFallback)
                                                             │
MumbleManager.model.state (ServerModel) ──collect(Main)──────┤ serverLabel(m,host), currentChannelName(m)
                                                             │  on change →
                        setActive-ok / Mumble-Synchronized ──┤ startCallForeground()
                                                             ▼
             CallForegroundService.start(server, channel, connectedSinceMs)
                                                             ▼
   CallNotificationManager.createNotification → CallStyle: Person=server, contentText="in <channel>"
```

## Testing

- **JVM unit tests** for the two helpers (`serverLabel`: root-name present / blank / "Root" / no root → host fallback; `currentChannelName`: self in a named channel / unknown session / missing channel → null). Reuse the existing `ServerModel` test construction from the call-screen tests.
- **On-device** (Dan's batch): the notification shows the server label prominently and the channel as a secondary line; it fills in during the handshake and updates when you switch channels. **Confirm the `CallStyle` secondary line (`contentText`) actually renders** on the device — if it doesn't, fall back to a combined `"server · channel"` Person name (a one-line change).

## Non-goals

- No incoming-call changes (outgoing Mumble "call" only; the incoming path is dead code kept as-is).
- No user count / roster on the notification (channel name only).
- No welcome-text or server-name-from-a-formal-field (Mumble has no server-name field; root-channel-name is the convention, host is the fallback).
- No change to the call *screen* header (it already shows server + channel); this only brings the notification to parity.
