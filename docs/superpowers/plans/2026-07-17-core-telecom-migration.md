# Core-Telecom Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Drumble's raw `android.telecom` VoIP integration (self-managed `DumbleConnectionService`/`DumbleConnection`/`PhoneAccount`/`placeCall`) with Jetpack Telecom `androidx.core:core-telecom:1.0.0` `CallsManager`, behavior-preserving.

**Architecture:** `CallManager` stays the singleton bridge but is rebuilt around `CallsManager.addCall {}` — a coroutine `CallControlScope` that lives for the call's duration, launched on a process-level scope so the call outlives the Activity. The library owns audio focus, audio mode (`MODE_IN_COMMUNICATION`), and foreground-execution priority; we only post the `CallStyle` notification and mirror the call's endpoint/mute/Mumble-state Flows into the StateFlows the call screen already observes. Cutover is atomic (the three telecom files + Activity + manifest + one UI file must change together to compile), preceded by two independently-committable prep tasks.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.core:core-telecom:1.0.0`, kotlinx.coroutines, Android telecom.

**User decisions (already made):**
- Version: stable **`androidx.core:core-telecom:1.0.0`** (not 1.1.0-alpha). "Migrate on 1.0.0 stable … one-line dependency bump to 1.1.0 later."
- Scope: **pure behavior-preserving migration**; BT-routing bugs #54/#59 fixed separately later. "Pure migration first."
- Mute: **fold in `isMuted` sync** — system/hardware mute → `MumbleManager.setMuted(...)`. One-way (system-mute → app-mute); the app's own Mute button is unchanged.
- Incoming calls: dropped (dead code — Drumble is outgoing-only).
- Audio: fully delegated to the library (it forbids manual `AudioManager` communication-device management during a Telecom call).

**Spec:** `docs/superpowers/specs/2026-07-17-core-telecom-migration-design.md`

**Verified API (core-telecom 1.0.0, checked against androidx source — resolves every spec §6 plan-time confirmation):**
- `CallsManager(context: Context)`; `fun registerAppWithTelecom(@Capability capabilities: Int, backwardsCompatSdkLevel: Int = TIRAMISU)` — `@RequiresPermission(MANAGE_OWN_CALLS)`. `CAPABILITY_BASELINE = 1`.
- `suspend fun addCall(callAttributes: CallAttributesCompat, onAnswer: suspend (Int)->Unit, onDisconnect: suspend (DisconnectCause)->Unit, onSetActive: suspend ()->Unit, onSetInactive: suspend ()->Unit, block: CallControlScope.()->Unit)`. Throws `UnsupportedOperationException` (invalid build) / `androidx.core.telecom.CallException` (platform can't add) / times out after 5000 ms. The `block` is **not** suspend; it launches collectors on the scope.
- **CORRECTED cancellation model (fable-verified against 1.0.0 source + AAR bytecode during T3 review):** `addCall` wraps the `block` in `coroutineScope { }`, and the `launch{}` collectors inside the block are its children. Those collectors are infinite (channel receives + StateFlow/SharedFlow), and the library does **not** cancel them on a normal disconnect (no `cancelChildren`/`Job.cancel` on the session scope anywhere in the AAR). Therefore **`addCall` does NOT return on its own when the call ends** — `teardown()` must be driven **explicitly** from every end-path (user hang-up, `onDisconnect`, Mumble failure), and `teardown()`'s `callJob.cancel()` is what unwinds the parked `coroutineScope`. (The plan's original assumption — "disconnect() cancels the scope so the block returns" — was wrong; caught in code-quality review before device testing.)
- `interface CallControlScope : CoroutineScope` — so `launch {}` inside `block` is valid. Members: `val currentCallEndpoint: Flow<CallEndpointCompat>`, `val availableEndpoints: Flow<List<CallEndpointCompat>>`, `val isMuted: Flow<Boolean>`; `suspend fun setActive(): CallControlResult`, `suspend fun setInactive(): CallControlResult`, `suspend fun disconnect(disconnectCause: android.telecom.DisconnectCause): CallControlResult`, `suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult`.
- `sealed class CallControlResult { class Success; class Error(val errorCode: Int) }`.
- `class CallEndpointCompat(val name: CharSequence, val type: Int, val identifier: ParcelUuid)`; `const val` TYPE_UNKNOWN=-1, TYPE_EARPIECE=1, TYPE_BLUETOOTH=2, TYPE_WIRED_HEADSET=3, TYPE_SPEAKER=4, TYPE_STREAMING=5 (values identical to `android.telecom.CallEndpoint`, so the `AudioRoute` int mapping is unchanged; `const val` ⇒ inlines ⇒ `AudioRouteTest` stays a pure JVM test).
- `CallAttributesCompat(displayName: CharSequence, address: Uri, @Direction direction: Int, @CallType callType: Int = CALL_TYPE_AUDIO_CALL, @CallCapability callCapabilities: Int = SUPPORTS_SET_INACTIVE, …)`. DIRECTION_OUTGOING=2, CALL_TYPE_AUDIO_CALL=1. **The default `callCapabilities = SUPPORTS_SET_INACTIVE`** already advertises the hold/resume capability, so the retained `onSetInactive`/`onSetActive` interruption path works at the default — no explicit capability flag needed.
- Foreground service: the library grants foreground-execution priority **while the app posts a `Notification.CallStyle` notification** (via `NotificationManager.notify`, **not** `startForeground`). `CallNotificationManager.createNotification(...)` already builds `Notification.CallStyle.forOngoingCall(...)`; we post it with `showNotification(...)` and clear it with `cancelNotification()`.

  > **CORRECTED (on-device, 2026-07-17):** this is **wrong**. core-telecom grants foreground *procstate* only — no foreground *service* — and Android rejects the `CallStyle` notification (and cuts background mic on API 34+) without a real FGS. A minimal `CallForegroundService` (type `microphone`) was reintroduced to `startForeground` with the notification for the call's lifetime; `CallManager` starts it on `setActive` success / refreshes it at Mumble-Synchronized, and stops it in `teardown`. See the design-doc CORRECTED note.

---

## File Structure

| File | Task | Responsibility after migration |
|---|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | T1 | Declare `androidx.core:core-telecom:1.0.0`. |
| `app/src/main/java/me/danielstiner/dumble/telecom/AudioRoute.kt` | T2 | Pure `CallEndpointCompat.TYPE_*` → label/icon mapping (int-based, JVM-testable). |
| `app/src/test/java/me/danielstiner/dumble/telecom/AudioRouteTest.kt` | T2 | Unit test of the mapping, referencing `CallEndpointCompat.TYPE_*`. |
| `app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt` | T3 | Singleton bridge rebuilt on `CallsManager`/`CallControlScope`: register once, `startCall`/`addCall`, endpoint+mute+state collectors, `requestEndpointChange`, idempotent `teardown`. Exposes `callActive`/`isSpeaker`/`endpoints`/`activeEndpoint` StateFlows. |
| `app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnectionService.kt` | T3 | **Deleted.** |
| `app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnection.kt` | T3 | **Deleted.** |
| `app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt` | T3 | Drop PhoneAccount registration + `placeCall`; `onConnect` → `CallManager.startCall()` + `MumbleManager.connect`. |
| `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt` | T3 | Consume `CallManager.callActive` (was `activeConnection`) + `CallEndpointCompat` `.type`/`.name` (were `.endpointType`/`.endpointName`). |
| `app/src/main/AndroidManifest.xml` | T3 | Remove the `DumbleConnectionService` `<service>` + `BIND_TELECOM_CONNECTION_SERVICE`; keep `MANAGE_OWN_CALLS` + FGS permissions. |

Unchanged (verified): `ui/ActiveCallScreen.kt` (fully `Int`-based: `RouteOption.type: Int`, `activeRouteType: Int?`), `telecom/CallNotificationManager.kt`, `telecom/CallActionReceiver.kt`, `mumble/**`.

**Sequencing keeps the build green at every commit:** T1 adds an unused dependency (green). T2 changes `AudioRoute` internals to `CallEndpointCompat` constants (same int values) while its callers still pass `Int` (green, test green). T3 is the atomic cutover (green).

---

### Task 1: Add the core-telecom dependency

**Goal:** Declare `androidx.core:core-telecom:1.0.0` so the library types are on the classpath; the app still builds unchanged.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Acceptance Criteria:**
- [ ] `gradle/libs.versions.toml` has `coreTelecom = "1.0.0"` under `[versions]` and an `androidx-core-telecom` entry under `[libraries]`.
- [ ] `app/build.gradle.kts` has `implementation(libs.androidx.core.telecom)`.
- [ ] `./gradlew assembleDebug` succeeds (the version resolves).

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

**Steps:**

- [ ] **Step 1: Add the version + library alias in `gradle/libs.versions.toml`**

Under `[versions]`, add after the `onnxruntime = "1.27.0"` line:

```toml
coreTelecom = "1.0.0"
```

Under `[libraries]`, add after the `onnxruntime-jvm` line:

```toml
androidx-core-telecom = { group = "androidx.core", name = "core-telecom", version.ref = "coreTelecom" }
```

- [ ] **Step 2: Add the dependency in `app/build.gradle.kts`**

In the `dependencies { }` block, add after `implementation(libs.androidx.core.ktx)`:

```kotlin
    implementation(libs.androidx.core.telecom)
```

- [ ] **Step 3: Verify the build**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If the artifact fails to resolve, the version pin is wrong — do not proceed; report it.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(telecom): add androidx.core:core-telecom:1.0.0"
```

> Commit trailers (both required on every commit in this plan):
> ```
> Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
> Claude-Session: https://claude.ai/code/session_01TqQFX1KY7q3eqvRBUoCTNz
> ```
> Never stage `.idea/gradle.xml`.

---

### Task 2: Point AudioRoute at CallEndpointCompat

**Goal:** Change `AudioRoute`'s `when` branches from `android.telecom.CallEndpoint.TYPE_*` to `androidx.core.telecom.CallEndpointCompat.TYPE_*` (identical int values), keeping it a pure int→label/icon mapping, and update its unit test.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/AudioRoute.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/telecom/AudioRouteTest.kt`

**Acceptance Criteria:**
- [ ] `AudioRoute.kt` imports `androidx.core.telecom.CallEndpointCompat` (not `android.telecom.CallEndpoint`) and its `label`/`icon` `when` branches reference `CallEndpointCompat.TYPE_*`.
- [ ] `AudioRoute.label(type: Int, name: CharSequence?)` and `AudioRoute.icon(type: Int)` signatures are unchanged (still `Int`-keyed).
- [ ] `AudioRouteTest` references `CallEndpointCompat.TYPE_*` and passes on the JVM.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.telecom.AudioRouteTest"` → all AudioRouteTest cases PASS.

**Steps:**

- [ ] **Step 1: Update the test first to the new constants (still passing on the same int values)**

Replace the entire contents of `app/src/test/java/me/danielstiner/dumble/telecom/AudioRouteTest.kt`:

```kotlin
package me.danielstiner.dumble.telecom

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteTest {
    @Test fun bluetoothUsesDeviceNameWhenPresent() {
        assertEquals("WH-1000XM4", AudioRoute.label(CallEndpointCompat.TYPE_BLUETOOTH, "WH-1000XM4"))
    }

    @Test fun bluetoothFallsBackToGenericWhenNameNullOrBlank() {
        assertEquals("Bluetooth", AudioRoute.label(CallEndpointCompat.TYPE_BLUETOOTH, null))
        assertEquals("Bluetooth", AudioRoute.label(CallEndpointCompat.TYPE_BLUETOOTH, "   "))
    }

    @Test fun genericTypesHaveFixedLabels() {
        assertEquals("Wired headset", AudioRoute.label(CallEndpointCompat.TYPE_WIRED_HEADSET))
        assertEquals("Earpiece", AudioRoute.label(CallEndpointCompat.TYPE_EARPIECE))
        assertEquals("Speaker", AudioRoute.label(CallEndpointCompat.TYPE_SPEAKER))
    }

    @Test fun unknownTypeFallsBack() {
        assertEquals("Unknown", AudioRoute.label(999))
    }

    @Test fun iconMapsEachType() {
        assertEquals(AudioRoute.RouteIcon.BLUETOOTH, AudioRoute.icon(CallEndpointCompat.TYPE_BLUETOOTH))
        assertEquals(AudioRoute.RouteIcon.WIRED, AudioRoute.icon(CallEndpointCompat.TYPE_WIRED_HEADSET))
        assertEquals(AudioRoute.RouteIcon.EARPIECE, AudioRoute.icon(CallEndpointCompat.TYPE_EARPIECE))
        assertEquals(AudioRoute.RouteIcon.SPEAKER, AudioRoute.icon(CallEndpointCompat.TYPE_SPEAKER))
        assertEquals(AudioRoute.RouteIcon.UNKNOWN, AudioRoute.icon(999))
    }
}
```

- [ ] **Step 2: Run the test — it must FAIL to compile (AudioRoute still on `android.telecom.CallEndpoint`)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.telecom.AudioRouteTest"`
Expected: compile error / FAIL (the test now references `CallEndpointCompat` but that's fine to compile since the dep exists — the real red is Step 3's production change). If it already passes here (constants inline to identical ints), that's acceptable — proceed to Step 3 for the production-source change the spec requires.

- [ ] **Step 3: Update `AudioRoute.kt` to `CallEndpointCompat`**

Replace the entire contents of `app/src/main/java/me/danielstiner/dumble/telecom/AudioRoute.kt`:

```kotlin
package me.danielstiner.dumble.telecom

import androidx.core.telecom.CallEndpointCompat

/**
 * Pure mapping of a Telecom [CallEndpointCompat]'s type to a human-readable audio-route label, shown
 * as a read-only route indicator on the call screen (a stopgap until the call-screen redesign hosts a
 * first-class route control). The referenced `TYPE_*` values are compile-time `const val` constants, so
 * this has no Android runtime dependency and is JVM-unit-testable.
 */
object AudioRoute {
    /**
     * Display label for an endpoint [type], preferring the Bluetooth device [name] when present
     * (a null/blank name falls back to the generic "Bluetooth" label).
     */
    fun label(type: Int, name: CharSequence? = null): String = when (type) {
        CallEndpointCompat.TYPE_BLUETOOTH ->
            name?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
        CallEndpointCompat.TYPE_WIRED_HEADSET -> "Wired headset"
        CallEndpointCompat.TYPE_EARPIECE -> "Earpiece"
        CallEndpointCompat.TYPE_SPEAKER -> "Speaker"
        else -> "Unknown"
    }

    /** Stable route-icon key for the Speaker control (mapped to a Material icon in the UI). */
    enum class RouteIcon { BLUETOOTH, WIRED, EARPIECE, SPEAKER, UNKNOWN }

    fun icon(type: Int): RouteIcon = when (type) {
        CallEndpointCompat.TYPE_BLUETOOTH -> RouteIcon.BLUETOOTH
        CallEndpointCompat.TYPE_WIRED_HEADSET -> RouteIcon.WIRED
        CallEndpointCompat.TYPE_EARPIECE -> RouteIcon.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> RouteIcon.SPEAKER
        else -> RouteIcon.UNKNOWN
    }
}
```

- [ ] **Step 4: Run the test — PASS**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "me.danielstiner.dumble.telecom.AudioRouteTest"`
Expected: PASS (all 5 test methods).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/telecom/AudioRoute.kt app/src/test/java/me/danielstiner/dumble/telecom/AudioRouteTest.kt
git commit -m "refactor(telecom): map AudioRoute over CallEndpointCompat types"
```

---

### Task 3: Atomic cutover to CallsManager

**Goal:** Rebuild `CallManager` on `CallsManager`/`CallControlScope`, repoint `ActiveCallActivity` and `DumbleApp`, delete `DumbleConnectionService`/`DumbleConnection`, and strip the manifest `<service>` — in one commit that compiles and keeps the unit suite green.

**Files:**
- Rewrite: `app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`
- Delete: `app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnectionService.kt`
- Delete: `app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnection.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Acceptance Criteria:**
- [ ] `CallManager` owns a process-level `CoroutineScope` + a `CallsManager`, calls `registerAppWithTelecom(CAPABILITY_BASELINE)` exactly once per process, and `startCall()` launches an `addCall` coroutine that calls `setActive()` immediately and posts the CallStyle notification.
- [ ] The `addCall` block launches collectors mirroring `availableEndpoints`→`endpoints`, `currentCallEndpoint`→`activeEndpoint`/`isSpeaker`, `isMuted`→`MumbleManager.setMuted`, `MumbleManager.state`(Synchronized)→chronometer anchor, and `MumbleManager.failures`→`disconnect(ERROR)`.
- [ ] All disconnect paths (user `disconnect()`, system `onDisconnect`, Mumble failure, `addCall` throw) converge on one idempotent `teardown()`.
- [ ] `selectRoute`/`setSpeaker` call `controlScope.requestEndpointChange(...)` and log `CallControlResult.Error.errorCode` (no longer a silent no-op).
- [ ] `CallManager.enterCallAudio`/`exitCallAudio` and all `AudioManager`/`startForeground`/`Connection`/`serviceRef` code are gone.
- [ ] `DumbleConnectionService.kt` and `DumbleConnection.kt` are deleted; the manifest no longer declares them; `MANAGE_OWN_CALLS` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_PHONE_CALL` remain.
- [ ] `ActiveCallActivity` no longer registers a `PhoneAccount` or calls `placeCall`; `onConnect` calls `CallManager.startCall()` then `MumbleManager.connect(config)`.
- [ ] `DumbleApp` reads `CallManager.callActive` and `CallEndpointCompat` `.type`/`.name`.
- [ ] `assembleDebug` green and the full unit suite green.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`, all unit tests pass.

**Steps:**

- [ ] **Step 1: Rewrite `app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt`**

Replace the entire file:

```kotlin
package me.danielstiner.dumble.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.protocol.ConnectionState

/**
 * Singleton bridge between the app UI and Jetpack Telecom [CallsManager]. Owns the outgoing Mumble
 * "call": registers the app with Telecom once per process, launches the [CallsManager.addCall]
 * coroutine that lives for the call's duration, and mirrors the call's audio endpoints / mute /
 * Mumble connection-state into StateFlows the call screen observes.
 *
 * The library owns audio focus, audio mode (MODE_IN_COMMUNICATION), and foreground-execution
 * priority (granted while a CallStyle notification is posted). We do NOT touch AudioManager or
 * startForeground anymore — that would conflict with the library.
 */
object CallManager {
    private const val TAG = "CallManager"

    // The call must outlive the Activity, so this scope is process-scoped (not tied to any UI).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var callsManager: CallsManager? = null
    private var notificationManager: CallNotificationManager? = null
    private var registered = false

    // The live call: the addCall coroutine + the CallControlScope handle used to drive the call.
    private var callJob: Job? = null
    @Volatile private var controlScope: CallControlScope? = null
    private var tornDown = true

    // Wall-clock moment the call reached Synchronized; anchors the notification chronometer.
    private var connectedSinceMs: Long? = null

    private var isUiVisible = false

    private val _callActive = MutableStateFlow(false)
    /** True while a Telecom call is registered — drives the in-call UI (replaces the old Connection). */
    val callActive: StateFlow<Boolean> = _callActive

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker

    private val _endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    /** Available call audio routes (for the route picker when a headset is connected). */
    val endpoints: StateFlow<List<CallEndpointCompat>> = _endpoints

    // The currently-active call audio endpoint (BT / wired / earpiece / speaker), surfaced read-only
    // as a route indicator on the call screen. Reset on teardown so the previous call's route can't
    // linger into the next one before the framework reports the new active endpoint.
    private val _activeEndpoint = MutableStateFlow<CallEndpointCompat?>(null)
    val activeEndpoint: StateFlow<CallEndpointCompat?> = _activeEndpoint

    fun init(context: Context) {
        val app = context.applicationContext
        if (callsManager == null) callsManager = CallsManager(app)
        if (notificationManager == null) notificationManager = CallNotificationManager(app)
    }

    fun setUiVisible(visible: Boolean) {
        isUiVisible = visible
        // The CallStyle notification is posted for the whole call (it's what grants foreground
        // priority), so leaving the app needs no extra action — the notification is already up.
    }

    /** Start the outgoing Mumble call: register once, then launch the addCall coroutine. */
    fun startCall() {
        val cm = callsManager ?: run { Log.e(TAG, "startCall before init"); return }
        if (callJob?.isActive == true) { Log.w(TAG, "startCall while a call is active"); return }
        ensureRegistered(cm)
        tornDown = false
        callJob = appScope.launch {
            try {
                cm.addCall(
                    CallAttributesCompat(
                        displayName = "Dumble",
                        address = Uri.fromParts("tel", "DumbleUser", null),
                        direction = CallAttributesCompat.DIRECTION_OUTGOING,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        // Default callCapabilities (SUPPORTS_SET_INACTIVE) already lets the framework
                        // hold/resume the call around a system interruption (e.g. an incoming cellular
                        // call), so onSetInactive/onSetActive below work without an explicit flag.
                    ),
                    onAnswer = { /* n/a: outgoing call, never rings */ },
                    // System-initiated end: after this returns the block's scope is cancelled ->
                    // the finally below runs teardown().
                    onDisconnect = { cause -> Log.d(TAG, "system onDisconnect: $cause") },
                    onSetActive = { Log.d(TAG, "onSetActive (resumed from hold)") },
                    onSetInactive = { Log.d(TAG, "onSetInactive (held)") },
                ) {
                    controlScope = this
                    _callActive.value = true
                    // Outgoing, no ringing -> go active immediately so MODE_IN_COMMUNICATION is set
                    // around AudioVoiceEngine start; post the required CallStyle notification within 5s.
                    launch {
                        val res = setActive()
                        Log.d(TAG, "setActive result=$res")
                        postCallNotification()
                    }
                    launch { availableEndpoints.collect { _endpoints.value = it } }
                    launch { currentCallEndpoint.collect { onActiveEndpoint(it) } }
                    // System/hardware mute (BT-headset button, system call controls) -> mute in Mumble
                    // (broadcasts self_mute). One-way; the app's own Mute button is unaffected.
                    launch { isMuted.collect { MumbleManager.setMuted(it) } }
                    launch {
                        MumbleManager.state.collect { s ->
                            if (s is ConnectionState.Synchronized && connectedSinceMs == null) {
                                connectedSinceMs = System.currentTimeMillis()
                                postCallNotification() // re-post with the chronometer anchor
                            }
                        }
                    }
                    // Failure teardown MUST use the non-conflated failures flow: MumbleManager self-heals
                    // Failed -> Disconnected too fast for a conflated collector to observe.
                    launch {
                        MumbleManager.failures.collect {
                            val res = disconnect(DisconnectCause(DisconnectCause.ERROR))
                            Log.d(TAG, "disconnect(ERROR) on Mumble failure result=$res")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "addCall failed", t)
            } finally {
                teardown()
            }
        }
    }

    private fun ensureRegistered(cm: CallsManager) {
        if (registered) return
        cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        registered = true
    }

    private fun postCallNotification() {
        val nm = notificationManager ?: return
        nm.showNotification(
            nm.createNotification("Dumble User", isIncoming = false, connectedSinceMs = connectedSinceMs)
        )
    }

    /** User hang-up (call screen / notification action). */
    fun disconnect() {
        val scope = controlScope
        if (scope != null) {
            appScope.launch {
                val res = scope.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                Log.d(TAG, "user disconnect result=$res") // block ends -> finally -> teardown
            }
        } else {
            teardown() // no active control scope (call never fully started) — clean up anyway
        }
    }

    /** Single idempotent cleanup all disconnect paths converge on. */
    private fun teardown() {
        if (tornDown) return
        tornDown = true
        MumbleManager.disconnect()
        notificationManager?.cancelNotification()
        controlScope = null
        _callActive.value = false
        _endpoints.value = emptyList()
        _activeEndpoint.value = null
        _isSpeaker.value = false
        connectedSinceMs = null
        callJob?.cancel()
        callJob = null
    }

    private fun onActiveEndpoint(ep: CallEndpointCompat) {
        _activeEndpoint.value = ep
        _isSpeaker.value = ep.type == CallEndpointCompat.TYPE_SPEAKER
    }

    /** Route to the first available endpoint of [endpointType] (BT / wired / earpiece / speaker). */
    fun selectRoute(endpointType: Int) {
        val scope = controlScope ?: return
        val ep = _endpoints.value.firstOrNull { it.type == endpointType } ?: return
        appScope.launch {
            // Surfaced (parity with today's silent no-op, but now attributable — the later #59 fix
            // consumes this error).
            when (val res = scope.requestEndpointChange(ep)) {
                is CallControlResult.Error ->
                    Log.w(TAG, "requestEndpointChange type=$endpointType error=${res.errorCode}")
                is CallControlResult.Success ->
                    Log.d(TAG, "requestEndpointChange type=$endpointType ok")
            }
        }
    }

    /** Speaker on/off toggle for the no-headset case (earpiece <-> speaker). */
    fun setSpeaker(speaker: Boolean) = selectRoute(
        if (speaker) CallEndpointCompat.TYPE_SPEAKER else CallEndpointCompat.TYPE_EARPIECE
    )
}
```

- [ ] **Step 2: Delete the obsolete telecom classes**

```bash
git rm app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnectionService.kt \
       app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnection.kt
```

- [ ] **Step 3: Update `app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt`**

Replace the entire file:

```kotlin
package me.danielstiner.dumble

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.MumbleServerConfig
import me.danielstiner.dumble.telecom.CallManager
import me.danielstiner.dumble.ui.DumbleApp
import me.danielstiner.dumble.ui.theme.DumbleTheme

class ActiveCallActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily on the next Connect tap */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)
        MumbleManager.init(this)
        requestCallPermissions()

        setContent {
            DumbleTheme {
                DumbleApp(
                    onConnect = { config -> onConnect(config) },
                    onHangUp = { CallManager.disconnect() },
                    onLaunchEchoTest = {
                        startActivity(Intent(this, EchoTestActivity::class.java))
                    },
                    onLaunchVadDebug = {
                        startActivity(Intent(this, VadDebugActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CallManager.setUiVisible(true)
    }

    override fun onPause() {
        super.onPause()
        CallManager.setUiVisible(false)
    }

    private fun onConnect(config: MumbleServerConfig) {
        if (!hasRecordAudio()) {
            Toast.makeText(
                this,
                "Microphone permission required — grant it, then tap Connect again",
                Toast.LENGTH_LONG,
            ).show()
            requestCallPermissions()
            return
        }
        CallManager.startCall()
        MumbleManager.connect(config)
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCallPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }
}
```

- [ ] **Step 4: Update `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`**

Apply these five edits (everything else in the file is unchanged):

Edit 4a — line 34, swap the connection flow for the boolean:

```kotlin
    val callActive by CallManager.callActive.collectAsStateWithLifecycle()
```
(replaces `val connection by CallManager.activeConnection.collectAsStateWithLifecycle()`)

Edit 4b — the `routeOptions` mapping (was `ep.endpointType` / `ep.endpointName`):

```kotlin
    val routeOptions = endpoints.map { ep ->
        RouteOption(
            type = ep.type,
            icon = AudioRoute.icon(ep.type),
            label = ep.name.toString().trim().takeIf { it.isNotEmpty() } ?: AudioRoute.label(ep.type),
        )
    }
```

Edit 4c — `activeRouteLabel`:

```kotlin
    val activeRouteLabel = activeEndpoint?.let { ep ->
        ep.name.toString().trim().takeIf { it.isNotEmpty() } ?: AudioRoute.label(ep.type)
    }
```

Edit 4d — the `inCall` predicate (was `connection != null`):

```kotlin
    val inCall = callActive ||
        state is ConnectionState.Connecting ||
        state is ConnectionState.Handshaking ||
        state is ConnectionState.Synchronized
```

Edit 4e — the two remaining `endpointType` reads inside the `inCall && !showSettings` branch:

```kotlin
            val routeIcon = activeEndpoint?.let { AudioRoute.icon(it.type) } ?: AudioRoute.RouteIcon.SPEAKER
```
and
```kotlin
                activeRouteType = activeEndpoint?.type,
```

> Note: `CallEndpointCompat.name` is a non-null `CharSequence`, so the `?.` before `.toString()` from the old `android.telecom.CallEndpoint.endpointName` (nullable) is dropped — `ep.name.toString().trim()`.

- [ ] **Step 5: Strip the manifest `<service>` block**

In `app/src/main/AndroidManifest.xml`, delete the entire `DumbleConnectionService` service element (the block starting `<service android:name=".telecom.DumbleConnectionService"` through its closing `</service>`):

```xml
        <service
            android:name=".telecom.DumbleConnectionService"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE"
            android:foregroundServiceType="phoneCall"
            android:exported="true">
            <intent-filter>
                <action android:name="android.telecom.ConnectionService" />
            </intent-filter>
        </service>
```

Leave everything else untouched — the `<uses-permission>` lines (`MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `POST_NOTIFICATIONS`, …), the three `<activity>` blocks, and the `CallActionReceiver` `<receiver>` all stay.

- [ ] **Step 6: Build + run the full unit suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests pass. If `DumbleApp.kt` still references `connection`, `endpointType`, or `endpointName`, the compile fails — fix the missed reference. There should be no remaining reference to `DumbleConnection`, `DumbleConnectionService`, `PhoneAccount`, `activeConnection`, `enterCallAudio`, or `exitCallAudio` anywhere:

```bash
grep -rn "DumbleConnection\|PhoneAccount\|activeConnection\|enterCallAudio\|exitCallAudio\|placeCall" app/src/main/java app/src/main/AndroidManifest.xml
```
Expected: no matches.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt \
        app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt \
        app/src/main/AndroidManifest.xml
git add -u app/src/main/java/me/danielstiner/dumble/telecom/
git commit -m "feat(telecom): migrate to core-telecom CallsManager

Replace self-managed ConnectionService/Connection/PhoneAccount with
CallsManager.addCall {}: library-managed audio focus/mode + foreground
priority, Flow-based endpoints, and system-mute -> Mumble-mute sync.
Behavior-preserving; BT-routing #54/#59 unchanged."
```

---

## On-device gate (Dan runs this — not an automated task)

Per the spec §5, the real acceptance gate is a manual on-device smoke pass on the Pixel 7a, which Dan batches separately after implementation. It is **not** an automated plan task (no subagent can run it). Do **not** merge the branch on completion — stop after Task 3's whole-plan review and report. The smoke checklist Dan will run: connect to a server → call registered with the system; two-way audio; route indicator + speaker toggle; BT-headset-button mute mutes you in Mumble (peers see the icon); user hang-up and a server-side failure both tear down cleanly; the ongoing-call notification shows with a running chronometer; leaving the app keeps the call alive.

## Self-Review

**Spec coverage:**
- §2 deletions (DumbleConnectionService/DumbleConnection, PhoneAccount/placeCall, enterCallAudio/exitCallAudio, manual startForeground) → T3. ✓
- §2 additions (gradle dep) → T1. ✓
- §2 CallManager rewrite (app scope, registerAppWithTelecom, startCall/addCall, endpoint+mute+state collectors, requestEndpointChange, idempotent teardown) → T3 Step 1. ✓
- §2 hold via onSetInactive/onSetActive at default capabilities → T3 (verified default = SUPPORTS_SET_INACTIVE). ✓
- §2/§8 AudioRoute → CallEndpointCompat → T2. ✓
- §2/§8 ActiveCallActivity onConnect → startCall + connect → T3 Step 3. ✓
- §2/§8 manifest → T3 Step 5. ✓
- Mute isMuted sync → T3 Step 1 collector. ✓
- §5 testing (assembleDebug + unit suite green, AudioRoute test) → T1/T2/T3 verify commands. ✓
- §6 plan-time confirmations → all resolved in the "Verified API" header. ✓
- §7 out-of-scope (#54/#59, incoming, 1.1.0, EchoTest/VadDebug) → untouched. ✓
- DumbleApp `activeConnection`/`endpointType`/`endpointName` consumers (not called out in spec §8's "unchanged" claim, but real) → T3 Step 4. ✓ (caught during exploration)

**Placeholder scan:** none — every step has complete code/commands.

**Type consistency:** `callActive: StateFlow<Boolean>`, `endpoints: StateFlow<List<CallEndpointCompat>>`, `activeEndpoint: StateFlow<CallEndpointCompat?>`, `isSpeaker: StateFlow<Boolean>` — names match between CallManager (T3) and DumbleApp (T3). `CallEndpointCompat.type`/`.name`, `CallControlResult.Error.errorCode`, `CallsManager.CAPABILITY_BASELINE`, `CallAttributesCompat.DIRECTION_OUTGOING`/`.CALL_TYPE_AUDIO_CALL` — all match the verified 1.0.0 source. `AudioRoute.label(Int, CharSequence?)`/`icon(Int)` signatures unchanged, so `ActiveCallScreen` (Int-based) needs no edit.
