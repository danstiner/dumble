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
- `Person.Builder().setName(serverLabel.ifBlank { "Dumble" })` → the prominent CallStyle line. **Guard non-blank**: AOSP `CallStyle` throws on an empty `Person` name.
- `setContentTitle(serverLabel)` (decorative — CallStyle overrides the title with the Person name; harmless).
- **`setContentText` only when `channelName != null`**: `channelName?.let { builder.setContentText("in $it") }`. Do **NOT** pass `""` — AOSP CallStyle only falls back to the localized "Ongoing call" default when `contentText == null`; an empty string suppresses that and shows a blank line. So during the handshake (channel unknown) the notification reads *server-label / "Ongoing call"*, then fills in the channel.
- Chronometer/incoming behavior unchanged.

**Verified (fable, 2026-07-19, against AOSP `Notification.java` `makeCallLayout`, API 31–35):** `Person.name` replaces the title and `setContentText` renders as the visible secondary line under CallStyle — so the server-prominent / channel-secondary mapping works on AOSP/Pixel. OEM-restyled call notifications (Samsung/MIUI) are the only place the combined-line fallback might be needed; on-device check below.

### 3. FGS + CallManager plumbing — `telecom/CallForegroundService.kt`, `telecom/CallManager.kt`

- `CallForegroundService.start(context, serverLabel, channelName, connectedSinceMs)` — carry both through the intent extras (`EXTRA_SERVER`, `EXTRA_CHANNEL`) and hand them to `createNotification`. Fallback `serverLabel` when the extra is missing = a neutral default (e.g. `"Dumble"`).
- `CallManager` holds Main-confined latest `serverLabel` / `channelName` (defaults: the host / null) plus the existing `connectedSinceMs`. `startCallForeground()` passes all three to `CallForegroundService.start`.
- **Teardown reset (required).** `CallManager.teardown()` resets `serverLabelState` / `channelNameState` / the stored `hostFallback` to their defaults alongside the existing `connectedSinceMs` reset. `MumbleManager.disconnect()` does **not** clear the model, so without this the singleton carries call N's server/channel into call N+1's startup window (a real leak, not just cosmetic).
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
- **On-device** (Dan's batch): the notification shows the server label prominently and the channel as a secondary line; it reads *label / "Ongoing call"* during the handshake, then fills in the channel; it updates when you switch channels. On an OEM-restyled call notification (Samsung/MIUI) confirm the secondary line still renders — if not, fall back to a combined `"server · channel"` Person name (a one-line change). Also test **an admin moving you to another channel while Drumble is backgrounded** (the collector re-posts the FGS from the background — expected fine, worth confirming).

## Non-goals

- No incoming-call changes (outgoing Mumble "call" only; the incoming path is dead code kept as-is).
- No user count / roster on the notification (channel name only).
- No welcome-text or server-name-from-a-formal-field (Mumble has no server-name field; root-channel-name is the convention, host is the fallback).
- No change to the call *screen* header (it already shows server + channel); this only brings the notification to parity.
