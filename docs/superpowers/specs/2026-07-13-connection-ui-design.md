# Connection UI Design

**Date:** 2026-07-13
**Status:** Approved (pending written-spec review)

## Overview

Add a real connection UI to Drumble so the user can enter a Mumble server's
host, port, and username (plus an optional password) instead of the hardcoded
test target in `ActiveCallActivity.placeTestCall()`
(`host = "10.0.2.2"`, username from `Build.MODEL`). The network layer already
accepts everything needed — `MumbleServerConfig(host, port = 64738, username,
password: String? = null, forceTcp, loopbackVoice)` — so this project is
UI + persistence, with no protocol changes.

The launcher screen moves from imperative Kotlin Views (`LinearLayout` /
`Button` / `TextView` on the old Material Components libs) to **Jetpack Compose
+ Material 3** with dynamic color (Material You). A single, state-driven screen
shows a **connect form** when disconnected and the existing **active-call
panel** when connected.

## Goals

- A Compose connect form: host, port, username, optional password.
- Material You theming via Material 3 dynamic color.
- Persist the last-used host / port / username; re-type the password each time.
- Drive connect through the exact path today's `placeTestCall()` uses (Telecom
  `placeCall` + `MumbleManager.connect`), just with form values.
- Surface connection failures back on the form.

## Non-goals (deferred / out of scope)

- **Client-certificate auth** — its own future spec (import .p12/PEM → Android
  KeyStore → wire the `KeyManager` into the existing `SSLContext`, whose
  `KeyManager` slot is currently `null`). The auth area is shaped so a cert
  option can be added without a redesign.
- **Saved-server list / favorites** — single last-used server only for now.
- **Persisting the password** — never written to storage in this iteration.
- **Force-TCP toggle** — the config supports `forceTcp`, but it stays out of the
  form for now (trivial to add later as an Advanced toggle).
- **Rewriting `EchoTestActivity`** — the separate audio echo-test screen stays
  on Views, untouched.

## Architecture

A single state-driven Compose screen, hosted by the existing (singleInstance)
launcher activity. `ActiveCallActivity` retains ownership of the Android-level
concerns it already handles — Telecom setup, `placeCall`, and the runtime
permission flow — and replaces its imperative view tree with
`setContent { DrumbleTheme { DrumbleApp(...) } }`.

Responsibilities split cleanly:

- **Composables** (`DrumbleApp`, `ConnectScreen`, `ActiveCallScreen`) are
  presentation only, with hoisted state.
- **`ConnectViewModel`** owns form state, validation, and persistence.
- **Activity** owns Telecom + permissions and provides `onConnect(config)` /
  `onHangUp()` lambdas.

### File structure

New:

| File | Responsibility |
|------|----------------|
| `ui/theme/Theme.kt` | `DrumbleTheme` — Material 3 dynamic color scheme + typography |
| `ui/DrumbleApp.kt` | Root composable; observes `MumbleManager.state` (+ `CallManager.activeConnection`), switches Connect ↔ ActiveCall, hosts the failure snackbar |
| `ui/ConnectScreen.kt` | Stateless connect form (host/port/username/password fields, inline errors, Connect button) |
| `ui/ActiveCallScreen.kt` | Compose port of today's status / live-stats / hang-up UI |
| `ui/ConnectViewModel.kt` | Form state, `validate()`, builds `MumbleServerConfig`, owns `ServerConfigStore` |
| `data/ServerConfigStore.kt` | Persistence interface + SharedPreferences impl (host/port/username); in-memory fake lives in test sources |

Modified:

| File | Change |
|------|--------|
| `ActiveCallActivity.kt` | Extend `ComponentActivity`; replace view tree with `setContent`; keep Telecom + permission ownership; wire `onConnect`/`onHangUp` |
| `app/build.gradle.kts` | Add Compose dependencies + Kotlin Compose compiler plugin; `buildFeatures { compose = true }` |
| `app/src/main/AndroidManifest.xml` | Activity/application theme → a `NoActionBar` Material 3 base (no legacy ActionBar under Compose) |

## Data flow

1. **Launch** → `ActiveCallActivity.onCreate` inits `CallManager`/`MumbleManager`
   (as today) and calls `setContent { DrumbleTheme { DrumbleApp(vm, onConnect, onHangUp) } }`.
2. **`DrumbleApp`** collects `MumbleManager.state` (via
   `collectAsStateWithLifecycle`) and `CallManager.activeConnection`:
   - `Disconnected` / `Failed` and no active connection → **ConnectScreen**
   - otherwise (`Connecting` / `Handshaking` / `Synchronized`, or an active
     connection) → **ActiveCallScreen**
3. **Connect** → the form calls `viewModel.validateAndBuild()`. On success the
   ViewModel persists host/port/username via `ServerConfigStore`, then the
   Activity's `onConnect(config)` lambda runs the same sequence as today's
   `placeTestCall`: `telecomManager.placeCall(...)` + `MumbleManager.connect(config)`.
   Permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`) are requested at this point
   if not already granted; connect proceeds once granted.
4. **Active call** → `ActiveCallScreen` collects `state` + `netStats` +
   `loopbackStats` and renders status/stats. **Hang-up** → `CallManager.disconnect()`.
5. **Failure** → see below.

## Validation

A pure `validate(form): FormResult` function (no Android deps) drives inline
field errors and the Connect button's enabled state:

- **host** — non-blank (trimmed).
- **port** — parses to an integer in `1..65535`; the field defaults to `64738`.
  A non-numeric or out-of-range value is a field error.
- **username** — non-blank (trimmed).
- **password** — optional; blank maps to `null` in the built `MumbleServerConfig`.

Connect is enabled only when `validate()` returns success.

## Persistence

`ServerConfigStore` is an interface with a SharedPreferences-backed
implementation (file `"drumble_server"`), plus an in-memory fake in test
sources.

- Keys written on a connect attempt: `host`, `port`, `username`.
- The **password is never written**.
- `load()` returns the persisted values (with `port` defaulting to `64738`);
  the form pre-fills from it on launch, with the password field always blank.

The ViewModel receives the store via a `ViewModelProvider.Factory` that builds
the SharedPreferences impl from `applicationContext`.

## Theming (Material You)

`DrumbleTheme` wraps `MaterialTheme` with Material 3. Because `minSdk` is 33
(≥ API 31), dynamic color is always available: pick
`dynamicDarkColorScheme(context)` or `dynamicLightColorScheme(context)` by
`isSystemInDarkTheme()`. A small static fallback scheme is defined for `@Preview`
(which has no real context). Typography uses Material 3 defaults.

## Failure handling

`ConnectionState.Failed` is transient — `MumbleManager` self-heals to
`Disconnected` after a `Failed`. `DrumbleApp` observes the state stream, captures
the failure reason on the `Failed` transition into a one-shot UI event, routes
back to `ConnectScreen`, and shows the reason in a `Snackbar`. Invalid form input
never reaches connect (Connect is disabled), so the snackbar is reserved for real
connection/handshake failures.

## Permissions

The existing runtime-permission flow (`RECORD_AUDIO`, `POST_NOTIFICATIONS`)
stays in the Activity via `registerForActivityResult`. It is triggered when the
user taps Connect: if either permission is missing, request it and proceed on
grant; if denied, surface a message on the form rather than placing the call.

## Testing

- **JVM unit (primary):**
  - `validate()` — blank host/username rejected; port bounds (0, 1, 65535,
    65536, non-numeric); password blank → `null`; a fully valid form builds the
    expected `MumbleServerConfig`.
  - `ServerConfigStore` round-trip through the in-memory fake; **assert the
    password is never persisted** even if present in the form.
- **Compose UI (stretch):** a `createComposeRule` test that fills the fields and
  asserts Connect enable/disable transitions.

## Build / dependency changes

Add to `app/build.gradle.kts`: the Kotlin Compose compiler plugin (matching the
project's Kotlin version), `buildFeatures { compose = true }`, and the Compose
dependency set — Compose BOM, `compose.ui`, `ui-tooling-preview`, `material3`,
`activity-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`
(for `collectAsStateWithLifecycle`); `debugImplementation` `ui-tooling`; and
(stretch) `androidTestImplementation` `ui-test-junit4`. `appcompat` /
`com.google.android.material` remain for the untouched `EchoTestActivity`.

## Future extension points

- **Client certificates:** the auth section of `ConnectScreen` is structured so
  a certificate option (and identity import) can be added; the backend work
  (KeyStore + `KeyManager` wiring) is a separate spec.
- **Saved-server list:** `ServerConfigStore` can grow from a single record to a
  keyed collection without changing the form or connect flow.
