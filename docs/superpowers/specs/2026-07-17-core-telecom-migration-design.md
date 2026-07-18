# Core-Telecom Migration — Design Spec

**Date:** 2026-07-17
**Status:** approved design (brainstormed with Dan). Basis for the implementation plan.

**Goal:** Replace Drumble's raw `android.telecom` VoIP integration (self-managed
`DumbleConnectionService` / `DumbleConnection` + `PhoneAccount` + `placeCall`) with Jetpack Telecom
`androidx.core:core-telecom` `CallsManager`, on the **stable 1.0.0** release.

**Motivation:** The immediate win is architectural — `CallsManager.addCall {}` (a coroutine
`CallControlScope`) collapses the ConnectionService + Connection + PhoneAccount + manifest-service
boilerplate into one coroutine-scoped call, with **Flow-based** audio endpoints and **library-managed**
audio focus / audio mode / foreground service. It also positions us for the Android 16.1 native
VoIP-visibility features (unified call log, native callback) as a later one-line bump to 1.1.0 once
that stabilizes — those features live in the `1.1.0-alpha` line today and are hardware-gated to
Android 16.1, so they are **out of scope** here.

**User decisions (already made):**
- **Version:** stable **`androidx.core:core-telecom:1.0.0`** (not `1.1.0-alpha`). "Migrate on 1.0.0
  stable … one-line dependency bump to 1.1.0 later." 1.1.0-alpha buys nothing usable on a Pixel 7a
  and its 16.1 features don't fit Mumble's connect-to-a-server model.
- **Bug scope:** **pure behavior-preserving migration**; BT-routing bugs #54/#59 fixed separately
  later on the cleaner base. "Pure migration first."
- **Mute:** **fold in `isMuted` sync** — observe `CallControlScope.isMuted` and drive
  `MumbleManager.setMuted(...)` so system/hardware mute (BT-headset button, system call controls)
  mutes you in Mumble and broadcasts `self_mute`. One-way (system-mute → app-mute); the app's own
  Mute button is unchanged.
- **Incoming calls:** dropped (dead code — Drumble is outgoing-only; no `addNewIncomingCall`).
- **Audio:** fully delegated to the library (required — the library forbids manual `AudioManager`
  communication-device management during a Telecom call).

---

## 1. Current architecture (what we're replacing)

| Piece | Role today |
|---|---|
| `ActiveCallActivity` | Registers a self-managed `PhoneAccount` (`CAPABILITY_SELF_MANAGED`) via `TelecomManager.registerPhoneAccount`; places the call with `telecomManager.placeCall(tel:DumbleUser, EXTRA_PHONE_ACCOUNT_HANDLE)`; hosts the Compose UI. |
| `DumbleConnectionService` (`ConnectionService`, manifest `<service>` + `BIND_TELECOM_CONNECTION_SERVICE`) | `onCreateOutgoing/IncomingConnection` → builds a `DumbleConnection` (`PROPERTY_SELF_MANAGED`, `audioModeIsVoip=true`, `setInitializing()`) → `CallManager.setConnection`. |
| `DumbleConnection` (`Connection`) | Lifecycle callbacks (`onAnswer`→`setActive`, `onDisconnect`/`onReject`/`onAbort`→`MumbleManager.disconnect` + `setDisconnected` + `destroy` + `CallManager.setConnection(null)`, `onHold`/`onUnhold`); endpoint callbacks (`onAvailableCallEndpointsChanged`→`CallManager.onAvailableEndpoints`, `onCallEndpointChanged`→`CallManager.onActiveEndpoint`). |
| `CallManager` (singleton bridge) | Holds `activeConnection`/`endpoints`/`activeEndpoint`/`isSpeaker` StateFlows; bridges `MumbleManager.state` (`Synchronized`→`connection.setActive` + notification chronometer anchor) and `MumbleManager.failures` (→`setDisconnected(ERROR)` + `destroy`); **manages call audio** (`enterCallAudio`: `MODE_IN_COMMUNICATION` + audio-focus request; `exitCallAudio`); **foreground-service notification** (`startForeground(1001, …, FOREGROUND_SERVICE_TYPE_PHONE_CALL)`); route selection (`selectRoute`/`setSpeaker` → `connection.requestCallEndpointChange` with an empty `OutcomeReceiver`). |
| `AudioRoute` | Maps `android.telecom.CallEndpoint.TYPE_*` → route label + `RouteIcon`. |
| `CallNotificationManager`, `CallActionReceiver` | Notification content (chronometer + label) + notification actions. |

`MumbleManager` (the actual VoIP: network + `AudioVoiceEngine` with `VOICE_COMMUNICATION` capture and
`USAGE_VOICE_COMMUNICATION` playout) is **unchanged** by this migration except for the `isMuted` → `setMuted` bridge.

## 2. Target architecture (`CallsManager`)

**Deleted:**
- `DumbleConnectionService.kt`, `DumbleConnection.kt` — the library provides its own telecom service;
  a `CallControlScope` replaces the `Connection`.
- `ActiveCallActivity`: `registerPhoneAccount()`, `placeTelecomCall()`, and the `telecomManager` /
  `phoneAccountHandle` fields.
- Manifest: the `<service android:name=".telecom.DumbleConnectionService">` block +
  `BIND_TELECOM_CONNECTION_SERVICE` + the `android.telecom.ConnectionService` intent-filter.
- `CallManager.enterCallAudio()` / `exitCallAudio()` (the `MODE_IN_COMMUNICATION` + audio-focus block)
  — **required** removal; the library owns audio focus/mode and manual management now conflicts.
- `CallManager`'s manual `startForeground(...)` in `updateNotification`.

  > **CORRECTED (on-device, 2026-07-17):** the original claim — "the library owns the FGS" — is **false**,
  > verified against the 1.0.0 AAR (no foreground-service or notification code) and a device crash
  > (`IllegalArgumentException: CallStyle notifications must be for a foreground service …`). core-telecom
  > grants foreground *procstate* only. Android rejects the `CallStyle` notification (and cuts background
  > mic on API 34+) without a real foreground *service*, so a minimal `CallForegroundService` (type
  > `microphone`) was reintroduced to carry the notification for the call's lifetime. See
  > `CallForegroundService.kt`; `CallManager` drives its `start`/`stop` from the same paths that used to
  > post/cancel the notification.

**Added:**
- Gradle dependency `androidx.core:core-telecom:1.0.0`.

**Changed — `CallManager` stays the singleton bridge**, restructured around `CallsManager`:
- Owns a `CallsManager` instance and an **app-level `CoroutineScope`** (the call must outlive the
  Activity; foreground state is carried by a dedicated `CallForegroundService` — see the CORRECTED note above).
- `registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)` once (guarded so it runs a single time
  per process).
- `startCall()` launches, on the app scope, a `callJob` that runs:
  ```
  callsManager.addCall(
      callAttributes  = CallAttributesCompat(
          displayName = "Dumble",
          address     = Uri.fromParts("tel", "DumbleUser", null),
          direction   = DIRECTION_OUTGOING,
          callType    = CALL_TYPE_AUDIO_CALL),
      onAnswer        = { /* n/a for outgoing */ },
      onDisconnect    = { teardown() },        // system-initiated end
      onSetActive     = { /* resumed from hold */ },
      onSetInactive   = { /* held */ },
  ) {                                          // CallControlScope
      controlScope = this
      setActive()                              // outgoing, no ringing → mode set immediately
      postCallNotification()                   // within 5 s of addCall
      launch { availableEndpoints.collect { _endpoints.value = it.toList() } }
      launch { currentCallEndpoint.collect { onActiveEndpoint(it) } }
      launch { isMuted.collect { MumbleManager.setMuted(it) } }   // system-mute → app-mute
      launch { MumbleManager.state.collect { onMumbleState(it) } }      // Synchronized → chronometer anchor
      launch { MumbleManager.failures.collect { disconnect(...) } }     // → onDisconnect → teardown
  }
  ```
  The block stays alive via the infinite collectors; `disconnect()` (ours) or an external teardown
  cancels the scope and the block returns.
- **Hold:** no user-facing hold control exists today (parity — keep it that way). The
  `onSetInactive`/`onSetActive` callbacks are retained only so the framework can hold/resume the
  Mumble call around a system interruption (e.g., an incoming cellular call), matching the current
  `DumbleConnection.onHold`/`onUnhold`. `callCapabilities` left at default (no `SUPPORTS_SET_INACTIVE`
  advertised) unless the plan finds the framework requires it for the interruption path.
- `disconnect()` (user hang-up): `controlScope.disconnect(DisconnectCause(LOCAL))` → block ends →
  `teardown()`.
- `selectRoute(type)` / `setSpeaker(on)`: `controlScope.requestEndpointChange(endpoint)` over the
  Compat endpoints, replacing `connection.requestCallEndpointChange`; **surface** the returned
  `CallControlResult` in a log (parity with today's silent no-op, but now attributable — a
  prerequisite the later #59 fix builds on).
- `teardown()`: `MumbleManager.disconnect()`, cancel the notification, reset
  `_endpoints`/`_activeEndpoint`/`_isSpeaker`/`connectedSinceMs`. (No `exitCallAudio` — the library
  restores audio.)
- `AudioRoute`: `android.telecom.CallEndpoint.TYPE_*` → `androidx.core.telecom.CallEndpointCompat`
  types; keep the same label/icon mapping.

`ActiveCallActivity.onConnect` becomes `CallManager.startCall()` + `MumbleManager.connect(config)`
(no `placeCall`).

## 3. Call lifecycle & data flow

1. **Connect:** user taps Connect → `CallManager.startCall()` (launches `addCall`, `setActive()`
   immediately, posts notification) + `MumbleManager.connect(config)`.
2. **Audio:** the library sets `MODE_IN_COMMUNICATION` + audio focus on `setActive()`; `MumbleManager`'s
   `AudioVoiceEngine` starts its `VOICE_COMMUNICATION` capture / `USAGE_VOICE_COMMUNICATION` playout as
   before (unchanged). `setActive`-immediately keeps the mode set at connect time, matching today.
3. **Sync:** `MumbleManager.state` → `Synchronized` → anchor `connectedSinceMs`, update the
   notification chronometer. (No explicit `setActive` here — already active.)
4. **Endpoints:** `availableEndpoints`/`currentCallEndpoint` Flows drive `_endpoints`/`_activeEndpoint`
   → the call-screen route indicator + speaker toggle. Route changes via `requestEndpointChange`.
5. **Mute:** `isMuted` Flow → `MumbleManager.setMuted(...)` (system/hardware mute → Mumble mute +
   `self_mute` broadcast). The app's own Mute button still calls `MumbleManager.setMuted` directly.
6. **Disconnect:**
   - **User hang-up:** `CallManager.disconnect()` → `controlScope.disconnect(LOCAL)` → block ends → `teardown()`.
   - **System-initiated:** `onDisconnect` callback → `teardown()`.
   - **Mumble failure:** `MumbleManager.failures` → `controlScope.disconnect(ERROR-equivalent)` → `teardown()`.
   All paths converge on the single idempotent `teardown()`.

## 4. Error handling
- `requestEndpointChange` returns `CallControlResult` — log `Error` (today it's a silently-empty
  `OutcomeReceiver`). Behavior stays "best-effort route change" for parity; the surfaced error is
  what the later #59 fix consumes.
- `setActive()` / `disconnect()` return `CallControlResult` — log failures.
- `addCall` can throw (e.g., registration/permission failure) → catch in `callJob`, `teardown()`,
  and surface a user-facing failure the way a telecom failure surfaces today.
- `teardown()` is idempotent (guard against double-invocation from converging disconnect paths).

## 5. Testing
- The telecom layer is Android-framework-coupled and has **no unit tests today**; this migration keeps
  that boundary. Verification:
  - `assembleDebug` green; the existing unit suite (211 tests) stays green (this layer isn't covered,
    so it must not regress the build).
  - Keep `AudioRoute` a pure, unit-testable mapping (add a small test for the `CallEndpointCompat`
    `TYPE_*` → label/icon mapping if one doesn't exist).
  - **On-device smoke pass (the real gate, Dan runs it):** connect to a server → the call is
    registered with the system; audio works both ways; the route indicator + speaker toggle work;
    muting via a BT-headset button mutes you in Mumble (peers see the icon); user hang-up and a
    server-side failure both tear down cleanly; the ongoing-call notification shows and its chronometer
    runs; leaving the app keeps the call alive (FGS).

## 6. Risks / plan-time confirmations
These are things the implementation plan MUST confirm against the `core-telecom 1.0.0` API reference
(not assumptions to bake in blind):
- **Exact `CallControlScope` API surface in 1.0.0** — property vs. method names for
  `currentCallEndpoint` / `availableEndpoints` / `isMuted` (Flows) and `requestEndpointChange` /
  `setActive` / `setInactive` / `disconnect` signatures + `CallControlResult` shape.
- **`CallEndpointCompat` type constants** (`TYPE_BLUETOOTH` / `TYPE_WIRED_HEADSET` / `TYPE_EARPIECE` /
  `TYPE_SPEAKER`) — names + parity with the current `AudioRoute` mapping.
- **`addCall` block lifetime** — RESOLVED during implementation (fable-verified against 1.0.0 source +
  AAR bytecode): `addCall` wraps the block in `coroutineScope { }` and the collectors are its children;
  they are infinite and the library does **not** cancel them on a normal disconnect, so `addCall` does
  **not** return on its own when the call ends. `teardown()` must be driven **explicitly** from every
  end-path, and its `callJob.cancel()` is what unwinds the parked scope. (The earlier guess that
  `disconnect()` cancels the scope so the block returns was wrong.)
- **Manifest** — confirm `core-telecom 1.0.0` needs **no** app-declared telecom `<service>`, and which
  foreground-service permissions it requires (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL` on
  API 34+); keep `MANAGE_OWN_CALLS`.
- **Audio-mode timing** — verify the library's `MODE_IN_COMMUNICATION` is in effect around
  `AudioVoiceEngine` start (the `setActive`-immediately choice is the mitigation).
- **`registerAppWithTelecom`** — once-per-process semantics and re-registration behavior.

## 7. Out of scope
- BT-routing bugs **#54** (auto-select BT>wired>earpiece at call start) and **#59** (route-picker
  error surfacing) — separate follow-up on the migrated base.
- Incoming calls — dropped.
- `core-telecom 1.1.0` / Android 16.1 features (unified call log, native callback, `isLogExcluded`).
- Standalone `EchoTestActivity` / `VadDebugActivity` — they set `MODE_IN_COMMUNICATION` themselves and
  are **not** telecom calls, so they are untouched.

## 8. File-by-file change summary
| File | Change |
|---|---|
| `app/build.gradle.kts` / `gradle/libs.versions.toml` | Add `androidx.core:core-telecom:1.0.0`. |
| `telecom/DumbleConnectionService.kt` | **Delete.** |
| `telecom/DumbleConnection.kt` | **Delete.** |
| `telecom/CallManager.kt` | Rewrite around `CallsManager`/`CallControlScope`: app scope, `registerAppWithTelecom`, `startCall`/`addCall`, endpoint+mute+state collectors, `requestEndpointChange`, idempotent `teardown`; remove `enterCallAudio`/`exitCallAudio` + manual `startForeground`. |
| `telecom/AudioRoute.kt` | `android.telecom.CallEndpoint` → `androidx.core.telecom.CallEndpointCompat` types. |
| `ActiveCallActivity.kt` | Remove PhoneAccount registration + `placeCall`; `onConnect` → `CallManager.startCall()` + `MumbleManager.connect`. |
| `AndroidManifest.xml` | Remove the `DumbleConnectionService` `<service>` + `BIND_TELECOM_CONNECTION_SERVICE`; keep `MANAGE_OWN_CALLS`; ensure `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_PHONE_CALL`. |
| `telecom/CallNotificationManager.kt` | Unchanged content; posted from the `addCall` block within 5 s. |
| `ui/DumbleApp.kt`, `ui/ActiveCallScreen.kt` | Unchanged (still consume `CallManager` StateFlows: `isSpeaker`, `activeEndpoint`, `endpoints`). |
