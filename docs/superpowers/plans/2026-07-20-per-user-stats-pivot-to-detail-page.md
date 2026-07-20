# Per-user stats — pivot to a per-user detail page

**Why:** The shipped Task 6 built the wrong UX — a standalone list of *all* users reached from Audio
diagnostics. The user wants a **detail page for one user, opened by tapping that user's row on the
call screen**, polling only that user. All backend (Tasks 1–5: per-speaker jitter data, `UserStats`
wiring, model ping, self-report) is correct and reused unchanged. This rewrites only the Task 6 UI
layer + narrows the poll to one session.

**Scope:** UI + a one-line poll-API change. No change to the audio path, the protocol, or the model.

---

## Change 1 — `MumbleManager`: poll one user, not the roster

`setUserStatsPolling(enabled: Boolean)` → `setUserStatsPolling(session: Int?)` (null = off).

Manager forwarder (was `active?.setUserStatsPolling(enabled)`):
```kotlin
    /** Poll one user's UserStats (ping) every ~5s while their detail page is open; null = stop.
     *  No-op when no call is active. */
    @Synchronized fun setUserStatsPolling(session: Int?) { active?.setUserStatsPolling(session) }
```

`ActiveSession.setUserStatsPolling` (replace the `for (u in …)` loop with a single request):
```kotlin
        // Mutated only via MumbleManager.setUserStatsPolling (@Synchronized) — that lock serializes
        // the cancel/relaunch read-modify-write; do not mutate this from an unsynchronized context.
        private var userStatsPollJob: Job? = null
        fun setUserStatsPolling(session: Int?) {
            userStatsPollJob?.cancel(); userStatsPollJob = null
            if (session == null) return
            userStatsPollJob = sessionScope.launch {
                while (currentCoroutineContext().isActive) {
                    try { sm.requestUserStats(session) }
                    catch (t: Throwable) { Log.e(TAG, "userStats poll tick threw (continuing)", t) }
                    delay(USERSTATS_POLL_MS)
                }
            }
        }
```
(`USERSTATS_POLL_MS = 5_000L` stays.)

## Change 2 — `ActiveCallScreen`: tappable user rows

Add a parameter (after `onOpenSettings: () -> Unit,`):
```kotlin
    onOpenUserStats: (Int) -> Unit,
```
At the user list (currently `items(ch.users, key = { "u-${it.session}" }) { u -> UserRow(u) }`, ~line 115):
```kotlin
                    items(ch.users, key = { "u-${it.session}" }) { u ->
                        UserRow(u, onClick = { onOpenUserStats(u.session) })
                    }
```
`UserRow` gains the click (ListItem accepts a `modifier`):
```kotlin
private fun UserRow(u: UserVm, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(u) },
        …unchanged…
    )
}
```
Add import `androidx.compose.foundation.clickable`. Update the `@Preview` that calls `ActiveCallScreen`
(bottom of the file, ~line 360+) to pass `onOpenUserStats = {}`.

## Change 3 — new `UserStatsDetailScreen.kt` (replaces `PerUserStatsScreen.kt`)

Delete `app/src/main/java/me/danielstiner/dumble/ui/PerUserStatsScreen.kt`; create:
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

/** Debug detail for one user: their ping-to-server (via UserStats) + their downlink jitter (via the
 *  per-speaker snapshot). [jitter] is null when the user has no active stream (not speaking); [user]
 *  is null if they left the server while this page was open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserStatsDetailScreen(
    user: MumbleUser?, jitter: SpeakerJitter?, net: NetStats, onBack: () -> Unit,
) {
    fun rtt(v: Double) = if (v >= 0) "%.1f ms".format(v) else "—"
    fun ping(v: Float?) = if (v != null && v >= 0f) "%.0f ms".format(v) else "—"
    Scaffold(topBar = {
        TopAppBar(title = { Text(user?.name ?: "User") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (user == null) { Text("User is no longer connected."); return@Column }
            Text("Ping to server")
            Text("  TCP: ${ping(user.tcpPingMs)}")
            Text("  UDP: ${ping(user.udpPingMs)}")
            Text("")
            Text("Jitter buffer  (p95 = raw unclamped delay spike)")
            if (jitter != null) {
                Text("  Target:     ${jitter.targetMs} ms")
                Text("  p95 (raw):  ${jitter.p95Ms} ms")
                Text("  Buffered:   ${jitter.bufferedMs} ms")
                Text("  Late drops: ${jitter.lateDrops}")
            } else {
                Text("  — not currently speaking —")
            }
            Text("")
            Text("Your link (for comparison)")
            Text("  TCP RTT: " + rtt(net.tcpRttMs))
            Text("  UDP RTT: " + rtt(net.udpRttMs))
        }
    }
}
```

## Change 4 — `DumbleApp`: session-keyed nav + gated single-user poll

Replace `var showPerUserStats by remember { mutableStateOf(false) }` with:
```kotlin
    var userStatsSession by remember { mutableStateOf<Int?>(null) }
```
Replace the `showPerUserStats -> { … }` branch (still FIRST in the `when`, before `inCall`) with:
```kotlin
        userStatsSession != null -> {
            val session = userStatsSession!!
            BackHandler { userStatsSession = null }
            DisposableEffect(session) {
                MumbleManager.setUserStatsPolling(session)
                onDispose { MumbleManager.setUserStatsPolling(null) }
            }
            UserStatsDetailScreen(
                user = serverModel.users[session],
                jitter = jitter.perSpeaker.firstOrNull { it.session == session },
                net = netStats,
                onBack = { userStatsSession = null },
            )
        }
```
In the `inCall` branch's `ActiveCallScreen(…)` call, add:
```kotlin
                onOpenUserStats = { userStatsSession = it },
```
In the `showDiagnostics` branch, remove the `onOpenPerUser = { showPerUserStats = true }` argument from the `AudioDiagnosticsScreen(…)` call.

## Change 5 — `AudioDiagnosticsScreen`: remove the entry row

Revert the Task 6 additions to this file: remove the `onOpenPerUser: () -> Unit` parameter, the
`Text("Per-user stats →", …clickable…)` row + its trailing `Text("")`, and the now-unused imports
`androidx.compose.foundation.clickable` / `androidx.compose.foundation.layout.fillMaxWidth` (only if
nothing else in the file uses them — check first).

---

## Behavior notes / edge cases
- **Poll follows the open page exactly:** one `UserStats` request / 5 s for the single viewed session;
  `onDispose` (`setUserStatsPolling(null)`) stops it on back. Server-verified safe (probe: 60 rapid
  requests, no throttle) — one every 5 s is trivial.
- **User leaves while open:** `serverModel.users[session]` becomes null → the page shows "User is no
  longer connected." The poll keeps requesting a gone session until you back out; harmless (the server
  ignores an unknown session). Not worth auto-closing.
- **Not-speaking user:** ping still shows; jitter shows "— not currently speaking —" (no `SpeakerJitter`
  entry because no `SpeakerStream` exists until they send audio).
- **Tapping yourself:** shows your self-reported ping + "not currently speaking" (we never create a
  `SpeakerStream` for our own uplink). Acceptable.
- **`DisposableEffect(session)` key:** you can only reach a new user's page by backing out first
  (session → null → new value), so keying on `session` is safe (never a live swap).

## Verify
- `./gradlew :app:testDebugUnitTest` (Tasks 1–5 tests unchanged, still green) and `./gradlew :app:assembleDebug`.
- No unit test for the pure-UI change; on-device: tap a user on the call screen → their detail page;
  during multi-party speech confirm the raw p95 is attributed to the right user.

## Files
- `mumble/MumbleManager.kt` — `setUserStatsPolling(session: Int?)` (poll one user).
- `ui/ActiveCallScreen.kt` — `onOpenUserStats` param + clickable `UserRow` + preview arg.
- `ui/UserStatsDetailScreen.kt` — NEW.
- `ui/PerUserStatsScreen.kt` — DELETE.
- `ui/DumbleApp.kt` — session-keyed nav branch + `onOpenUserStats` wire + drop `onOpenPerUser`.
- `ui/AudioDiagnosticsScreen.kt` — remove the entry row + param.
