# Connection UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Drumble's hardcoded test-call screen with a Jetpack Compose + Material 3 connection UI (host/port/username/optional password), rename the app to **Dumble** (package `com.example.drumble` → `me.danielstiner.dumble`), and add a Settings entry that launches the Echo Test debug view.

**Architecture:** One state-driven Compose screen hosted by the existing (singleInstance) `ActiveCallActivity`: it renders a connect form when disconnected and the active-call panel when connected. Pure form validation + a SharedPreferences-backed `ServerConfigStore` (host/port/username only — never the password) sit behind a `ConnectViewModel`. The Activity keeps ownership of Telecom + permissions and drives connect through the *same* path the old `placeTestCall()` used.

**Tech Stack:** AGP 9.2.1 with **built-in Kotlin** (bundles KGP 2.2.10 — do NOT apply `kotlin-android`); Jetpack Compose (BOM 2026.06.00) + Material 3 with dynamic color; `org.jetbrains.kotlin.plugin.compose` compiler plugin; androidx lifecycle-viewmodel-compose / lifecycle-runtime-compose; existing Mumble network layer (`MumbleManager`, `MumbleServerConfig`, `ConnectionState`).

**User decisions (already made):**
- "Compose + Material 3" for the UI toolkit.
- "just a password to start. WE can add certs later" — client certs deferred; optional password only.
- "Single last-used server" persisted; "Don't persist password".
- "One screen, state-driven".
- "Leave out force TCP for now".
- "Full rename incl. package" to **Dumble**; "I have the site danielstiner.me, use that for any package application id's" → `me.danielstiner.dumble`.
- Settings access = a top-app-bar **gear** → Settings screen (M3 pattern), approved ("Looks good").

**Spec:** `docs/superpowers/specs/2026-07-13-connection-ui-design.md`

---

## File Structure

New (all under `app/src/main/java/me/danielstiner/dumble/` after the Task 1 rename):

| File | Responsibility |
|------|----------------|
| `ui/theme/Theme.kt` | `DumbleTheme` — Material 3 dynamic-color scheme + typography |
| `data/ServerConfigStore.kt` | `ServerConfigStore` interface + `SharedPrefsServerConfigStore` impl + `SavedServer` (host/port/username; no password) |
| `ui/ConnectForm.kt` | Pure `ConnectForm`, `FieldErrors`, `validate()`, `toConfig()` — no Android deps |
| `ui/ConnectViewModel.kt` | Holds form `StateFlow`, wires `validate` + `ServerConfigStore`, `persistAndBuild()` |
| `ui/ConnectScreen.kt` | Stateless form composable + `TopAppBar` with settings gear |
| `ui/ActiveCallScreen.kt` | Compose port of the status / stats / hang-up UI |
| `ui/SettingsScreen.kt` | M3 list; "Echo Test" launches `EchoTestActivity` |
| `ui/DumbleApp.kt` | Root: collects state, switches Connect ↔ ActiveCall ↔ Settings, failure snackbar |

Test sources (under `app/src/test/java/me/danielstiner/dumble/`): `data/FakeServerConfigStore.kt`, `data/ServerConfigStoreContractTest.kt` (or fake round-trip), `ui/ConnectFormTest.kt`, `ui/ConnectViewModelTest.kt`.

Modified: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` (root), `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/{strings,themes}.xml`, `ActiveCallActivity.kt` (full rewrite).

## Task dependency graph

```
1 (rename) ──┬─► 2 (compose+theme) ──┬─► 6 (ActiveCallScreen)
             │                       ├─► 7 (SettingsScreen)
             └─► 3 (store) ──┐       └─► 4 (form+viewmodel) ─► 5 (ConnectScreen)
                             └──────────► 4
2,3 ─► 4 ;  2,4 ─► 5 ;  4,5,6,7 ─► 8 (DumbleApp + Activity) ─► 9 (manual verify)
```
Disjoint-file parallelism is available for {2,3} and {6,7}; the coordinator MAY run those concurrently.

---

### Task 1: Rename Drumble → Dumble (package `me.danielstiner.dumble`)

**Goal:** Rename the app to Dumble and move the entire codebase from `com.example.drumble` to `me.danielstiner.dumble`, with the build and existing unit tests still green.

**Files:**
- Modify (move): `app/src/main/java/com/example/drumble/**` → `app/src/main/java/me/danielstiner/dumble/**`; same for `app/src/test/java/**` and `app/src/androidTest/java/**`
- Modify: `app/build.gradle.kts` (`namespace`, `applicationId`), `app/src/main/proto/{Mumble,MumbleUDP}.proto` (`java_package`), `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Rename classes: `telecom/DrumbleConnection.kt` → `DumbleConnection.kt`, `telecom/DrumbleConnectionService.kt` → `DumbleConnectionService.kt`

**Acceptance Criteria:**
- [ ] No `com.example.drumble` or `com\.example\.drumble` remains anywhere under `app/src` or in `app/build.gradle.kts` (`grep -rn 'com.example.drumble' app/src app/build.gradle.kts` → no output).
- [ ] No `DrumbleConnection` identifier remains (`grep -rn 'DrumbleConnection' app/src` → no output); the two telecom classes are `DumbleConnection` / `DumbleConnectionService`.
- [ ] `app_name` string is `Dumble`; the theme style is `Theme.Dumble`; the Manifest references `@style/Theme.Dumble` and `.telecom.DumbleConnectionService`.
- [ ] `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL, 0 failures.
- [ ] `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Verify:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 0 failures.

**Steps:**

- [ ] **Step 1: Move the three source roots** (parent dirs must exist first)

```bash
for root in main test androidTest; do
  src="app/src/$root/java/com/example/drumble"
  [ -d "$src" ] || continue
  mkdir -p "app/src/$root/java/me/danielstiner"
  git mv "$src" "app/src/$root/java/me/danielstiner/dumble"
  rmdir "app/src/$root/java/com/example" "app/src/$root/java/com" 2>/dev/null || true
done
```

- [ ] **Step 2: Rewrite the package string in every file that references it** (BSD/macOS `sed`)

```bash
grep -rl 'com\.example\.drumble' app/src | xargs sed -i '' 's/com\.example\.drumble/me.danielstiner.dumble/g'
sed -i '' 's/com\.example\.drumble/me.danielstiner.dumble/g' app/build.gradle.kts
```

This updates `.kt` `package`/`import` lines, the two `.proto` `java_package` options, the `androidTest` package-name assertion, and the Gradle `namespace`/`applicationId`.

- [ ] **Step 3: Rename the two `Drumble*` telecom classes and their references**

```bash
git mv app/src/main/java/me/danielstiner/dumble/telecom/DrumbleConnection.kt \
       app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnection.kt
git mv app/src/main/java/me/danielstiner/dumble/telecom/DrumbleConnectionService.kt \
       app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnectionService.kt
# One rule handles both (DrumbleConnectionService starts with DrumbleConnection):
grep -rl 'DrumbleConnection' app/src/main/java app/src/main/AndroidManifest.xml \
  | xargs sed -i '' 's/DrumbleConnection/DumbleConnection/g'
```

- [ ] **Step 4: Rename the display name and theme**

```bash
sed -i '' 's#>Drumble<#>Dumble<#' app/src/main/res/values/strings.xml
sed -i '' 's/Theme\.Drumble/Theme.Dumble/g' \
  app/src/main/res/values/themes.xml app/src/main/AndroidManifest.xml
```

(User-facing string literals still reading "Drumble" inside `ActiveCallActivity` — the window title and PhoneAccount label — are replaced when that file is rewritten in Task 8. They are inert strings and do not affect this task's build.)

- [ ] **Step 5: Verify build + tests**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, 0 test failures. If a Kotlin file fails to resolve a symbol, it is almost certainly a missed reference — re-run the Step 2 grep to confirm it returns nothing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: rename Drumble -> Dumble, package com.example.drumble -> me.danielstiner.dumble"
```

```json:metadata
{"files": ["app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml", "app/src/main/res/values/themes.xml", "app/src/main/proto/Mumble.proto", "app/src/main/proto/MumbleUDP.proto"], "verifyCommand": "./gradlew :app:testDebugUnitTest :app:assembleDebug", "acceptanceCriteria": ["no com.example.drumble under app/src or in build.gradle.kts", "no DrumbleConnection identifier remains", "app_name=Dumble, Theme.Dumble, .telecom.DumbleConnectionService in Manifest", "testDebugUnitTest BUILD SUCCESSFUL 0 failures", "assembleDebug BUILD SUCCESSFUL"], "modelTier": "standard"}
```

---

### Task 2: Enable Compose + Material 3; add `DumbleTheme`; drop the ActionBar

**Goal:** Turn on Jetpack Compose under AGP 9 built-in Kotlin, add the Material 3 dependency set, create `DumbleTheme` (dynamic color), and switch the app theme to a `NoActionBar` parent so Compose draws cleanly.

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts` (root), `app/build.gradle.kts`, `app/src/main/res/values/themes.xml`
- Create: `app/src/main/java/me/danielstiner/dumble/ui/theme/Theme.kt`

**Acceptance Criteria:**
- [ ] `app/build.gradle.kts` applies `org.jetbrains.kotlin.plugin.compose` and sets `buildFeatures { compose = true }`.
- [ ] `DumbleTheme` composable exists and wraps `MaterialTheme` with a dynamic color scheme (falls back to `darkColorScheme()`/`lightColorScheme()` below API 31).
- [ ] The `Theme.Dumble` style parent is a `NoActionBar` Material Components theme.
- [ ] `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (proves the Compose compiler plugin resolves and a `@Composable` compiles).

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Add the Kotlin/Compose versions, the compiler plugin, and Compose libraries to the version catalog**

Edit `gradle/libs.versions.toml`. Under `[versions]` add:

```toml
kotlin = "2.2.10"
composeBom = "2026.06.00"
activityCompose = "1.13.0"
lifecycle = "2.10.0"
```

Under `[libraries]` add:

```toml
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
```

Under `[plugins]` add:

```toml
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Register the compiler plugin at the root (apply-false)**

Edit `build.gradle.kts` (root):

```kotlin
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

- [ ] **Step 3: Apply Compose in the app module**

Edit `app/build.gradle.kts`. Add to the `plugins { }` block:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.compose.compiler)
}
```

Add `buildFeatures` inside the `android { }` block (next to `compileOptions`):

```kotlin
    buildFeatures {
        compose = true
    }
```

Add to `dependencies { }`:

```kotlin
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
```

- [ ] **Step 4: Switch the app theme to NoActionBar**

Edit `app/src/main/res/values/themes.xml` — change only the parent of `Theme.Dumble`:

```xml
    <style name="Theme.Dumble" parent="Theme.MaterialComponents.DayNight.NoActionBar">
```

(Leave the `<item>` color entries; `EchoTestActivity` still uses this MaterialComponents theme.)

- [ ] **Step 5: Create `DumbleTheme`**

Create `app/src/main/java/me/danielstiner/dumble/ui/theme/Theme.kt`:

```kotlin
package me.danielstiner.dumble.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun DumbleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}
```

- [ ] **Step 6: Verify + commit**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

> **AGP-9 note (only if this step fails):** If the build fails with a Compose-compiler/Kotlin version-mismatch error, the error message names the exact Kotlin version AGP 9.2.1 bundles (e.g. "…requires Kotlin version X.Y.Z"). Set `kotlin = "X.Y.Z"` in `libs.versions.toml` to that named version and re-run. The `compose-compiler` plugin version is `version.ref = "kotlin"`, so it tracks automatically.

```bash
git add -A && git commit -m "build: enable Jetpack Compose + Material 3, add DumbleTheme, NoActionBar app theme"
```

```json:metadata
{"files": ["gradle/libs.versions.toml", "build.gradle.kts", "app/build.gradle.kts", "app/src/main/res/values/themes.xml", "app/src/main/java/me/danielstiner/dumble/ui/theme/Theme.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["compose compiler plugin applied + buildFeatures.compose=true", "DumbleTheme wraps MaterialTheme with dynamic color + pre-31 fallback", "Theme.Dumble parent is NoActionBar", "assembleDebug BUILD SUCCESSFUL"], "modelTier": "standard"}
```

---

### Task 3: `ServerConfigStore` persistence (host/port/username, never password)

**Goal:** A small persistence seam that remembers the last-used host/port/username in SharedPreferences and never stores a password, with an in-memory fake and JVM tests.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/data/ServerConfigStore.kt`
- Create (test): `app/src/test/java/me/danielstiner/dumble/data/FakeServerConfigStore.kt`
- Create (test): `app/src/test/java/me/danielstiner/dumble/data/ServerConfigStoreTest.kt`

**Acceptance Criteria:**
- [ ] `ServerConfigStore.save(host, port, username)` persists exactly those three; `load()` returns them with `port` defaulting to `64738` when unset.
- [ ] There is no API path that writes a password (the interface has no password parameter).
- [ ] `FakeServerConfigStore` round-trips a saved value through `load()`.
- [ ] `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.data.*"` → PASS.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.data.*"` → all pass.

**Steps:**

- [ ] **Step 1: Write the failing fake + round-trip test**

Create `app/src/test/java/me/danielstiner/dumble/data/FakeServerConfigStore.kt`:

```kotlin
package me.danielstiner.dumble.data

class FakeServerConfigStore(private var saved: SavedServer = SavedServer()) : ServerConfigStore {
    override fun load(): SavedServer = saved
    override fun save(host: String, port: Int, username: String) {
        saved = SavedServer(host, port, username)
    }
}
```

Create `app/src/test/java/me/danielstiner/dumble/data/ServerConfigStoreTest.kt`:

```kotlin
package me.danielstiner.dumble.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigStoreTest {
    @Test fun defaultsWhenEmpty() {
        assertEquals(SavedServer("", 64738, ""), FakeServerConfigStore().load())
    }

    @Test fun roundTripsSavedValues() {
        val store = FakeServerConfigStore()
        store.save("mumble.example.com", 64738, "danielstiner")
        assertEquals(SavedServer("mumble.example.com", 64738, "danielstiner"), store.load())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.data.ServerConfigStoreTest"`
Expected: FAIL — unresolved `ServerConfigStore` / `SavedServer`.

- [ ] **Step 3: Implement `ServerConfigStore`**

Create `app/src/main/java/me/danielstiner/dumble/data/ServerConfigStore.kt`:

```kotlin
package me.danielstiner.dumble.data

import android.content.Context

/** Last-used server details. The password is intentionally NOT part of this model — it is never persisted. */
data class SavedServer(
    val host: String = "",
    val port: Int = 64738,
    val username: String = "",
)

interface ServerConfigStore {
    fun load(): SavedServer
    fun save(host: String, port: Int, username: String)
}

class SharedPrefsServerConfigStore(context: Context) : ServerConfigStore {
    private val prefs = context.getSharedPreferences("dumble_server", Context.MODE_PRIVATE)

    override fun load(): SavedServer = SavedServer(
        host = prefs.getString(KEY_HOST, "") ?: "",
        port = prefs.getInt(KEY_PORT, 64738),
        username = prefs.getString(KEY_USERNAME, "") ?: "",
    )

    override fun save(host: String, port: Int, username: String) {
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.data.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: ServerConfigStore (host/port/username persistence, no password)"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/data/ServerConfigStore.kt", "app/src/test/java/me/danielstiner/dumble/data/FakeServerConfigStore.kt", "app/src/test/java/me/danielstiner/dumble/data/ServerConfigStoreTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"me.danielstiner.dumble.data.*\"", "acceptanceCriteria": ["save persists host/port/username only", "load defaults port to 64738", "no password parameter anywhere in the interface", "fake round-trips", "data.* tests pass"], "modelTier": "mechanical"}
```

---

### Task 4: `ConnectForm` validation + `ConnectViewModel`

**Goal:** Pure form validation that builds a `MumbleServerConfig` (blank password → null), plus a `ConnectViewModel` that holds the form as a `StateFlow`, pre-fills from `ServerConfigStore`, and persists on connect.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/ConnectForm.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/ui/ConnectViewModel.kt`
- Create (test): `app/src/test/java/me/danielstiner/dumble/ui/ConnectFormTest.kt`
- Create (test): `app/src/test/java/me/danielstiner/dumble/ui/ConnectViewModelTest.kt`

**Acceptance Criteria:**
- [ ] `validate()` rejects blank host, blank username, and a port that is non-numeric or outside `1..65535`; accepts port `1`, `64738`, `65535`.
- [ ] `toConfig()` maps a blank password to `null` and a non-blank one through; trims host/username.
- [ ] `ConnectViewModel` pre-fills its form from `ServerConfigStore.load()`; `persistAndBuild()` saves host/port/username and returns the `MumbleServerConfig`.
- [ ] `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.ui.*"` → PASS.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.ui.*"` → all pass.

**Steps:**

- [ ] **Step 1: Write the failing validation tests**

Create `app/src/test/java/me/danielstiner/dumble/ui/ConnectFormTest.kt`:

```kotlin
package me.danielstiner.dumble.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormTest {
    private val valid = ConnectForm(host = "mumble.example.com", port = "64738", username = "dan", password = "")

    @Test fun validFormHasNoErrors() {
        assertTrue(validate(valid).isValid)
    }

    @Test fun blankHostRejected() {
        assertFalse(validate(valid.copy(host = "  ")).isValid)
        assertEquals("Host required", validate(valid.copy(host = "")).host)
    }

    @Test fun blankUsernameRejected() {
        assertEquals("Username required", validate(valid.copy(username = "")).username)
    }

    @Test fun portBounds() {
        assertNull(validate(valid.copy(port = "1")).port)
        assertNull(validate(valid.copy(port = "65535")).port)
        assertEquals("Port 1-65535", validate(valid.copy(port = "0")).port)
        assertEquals("Port 1-65535", validate(valid.copy(port = "65536")).port)
        assertEquals("Port 1-65535", validate(valid.copy(port = "abc")).port)
    }

    @Test fun toConfigMapsBlankPasswordToNull() {
        val c = valid.toConfig()
        assertNull(c.password)
        assertEquals("mumble.example.com", c.host)
        assertEquals(64738, c.port)
        assertEquals("dan", c.username)
    }

    @Test fun toConfigKeepsPasswordAndTrims() {
        val c = valid.copy(host = "  h  ", username = "  u  ", password = "pw").toConfig()
        assertEquals("h", c.host)
        assertEquals("u", c.username)
        assertEquals("pw", c.password)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.ui.ConnectFormTest"`
Expected: FAIL — unresolved `ConnectForm` / `validate` / `toConfig`.

- [ ] **Step 3: Implement `ConnectForm`**

Create `app/src/main/java/me/danielstiner/dumble/ui/ConnectForm.kt`:

```kotlin
package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.MumbleServerConfig

/** Raw text form state. `port` is a String because it is bound to a text field. */
data class ConnectForm(
    val host: String = "",
    val port: String = "64738",
    val username: String = "",
    val password: String = "",
)

/** Per-field error messages; null means the field is valid. */
data class FieldErrors(
    val host: String? = null,
    val port: String? = null,
    val username: String? = null,
) {
    val isValid: Boolean get() = host == null && port == null && username == null
}

fun validate(form: ConnectForm): FieldErrors = FieldErrors(
    host = if (form.host.isBlank()) "Host required" else null,
    port = form.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { null } ?: "Port 1-65535",
    username = if (form.username.isBlank()) "Username required" else null,
)

/** Build a config from a form assumed valid (blank password → null). */
fun ConnectForm.toConfig(): MumbleServerConfig = MumbleServerConfig(
    host = host.trim(),
    port = port.trim().toInt(),
    username = username.trim(),
    password = password.ifBlank { null },
)
```

- [ ] **Step 4: Run to verify validation passes**

Run: `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.ui.ConnectFormTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing ViewModel test**

Create `app/src/test/java/me/danielstiner/dumble/ui/ConnectViewModelTest.kt`:

```kotlin
package me.danielstiner.dumble.ui

import me.danielstiner.dumble.data.FakeServerConfigStore
import me.danielstiner.dumble.data.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectViewModelTest {
    @Test fun prefillsFromStore() {
        val vm = ConnectViewModel(FakeServerConfigStore(SavedServer("h", 5000, "u")))
        val f = vm.form.value
        assertEquals("h", f.host)
        assertEquals("5000", f.port)
        assertEquals("u", f.username)
        assertEquals("", f.password) // password never pre-filled
    }

    @Test fun canConnectReflectsValidation() {
        val vm = ConnectViewModel(FakeServerConfigStore())
        assertFalse(vm.canConnect()) // empty host/username
        vm.update { it.copy(host = "h", username = "u") }
        assertTrue(vm.canConnect())
    }

    @Test fun persistAndBuildSavesAndReturnsConfig() {
        val store = FakeServerConfigStore()
        val vm = ConnectViewModel(store)
        vm.update { it.copy(host = " h ", port = "64738", username = " u ", password = "pw") }
        val config = vm.persistAndBuild()
        assertEquals("h", config.host)
        assertEquals("pw", config.password)
        assertEquals(SavedServer("h", 64738, "u"), store.load()) // password not saved
    }
}
```

- [ ] **Step 6: Implement `ConnectViewModel`**

Create `app/src/main/java/me/danielstiner/dumble/ui/ConnectViewModel.kt`:

```kotlin
package me.danielstiner.dumble.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.danielstiner.dumble.data.ServerConfigStore
import me.danielstiner.dumble.mumble.MumbleServerConfig

class ConnectViewModel(private val store: ServerConfigStore) : ViewModel() {
    private val _form = MutableStateFlow(
        store.load().let { ConnectForm(host = it.host, port = it.port.toString(), username = it.username) }
    )
    val form: StateFlow<ConnectForm> = _form.asStateFlow()

    fun update(transform: (ConnectForm) -> ConnectForm) { _form.value = transform(_form.value) }

    fun errors(): FieldErrors = validate(_form.value)
    fun canConnect(): Boolean = errors().isValid

    /** Persist the non-secret fields and return the config to connect with. Call only when [canConnect]. */
    fun persistAndBuild(): MumbleServerConfig {
        val f = _form.value
        store.save(f.host.trim(), f.port.trim().toInt(), f.username.trim())
        return f.toConfig()
    }
}
```

- [ ] **Step 7: Run all `ui` tests, then commit**

Run: `./gradlew :app:testDebugUnitTest --tests "me.danielstiner.dumble.ui.*"`
Expected: PASS.

```bash
git add -A && git commit -m "feat: ConnectForm validation + ConnectViewModel"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/ConnectForm.kt", "app/src/main/java/me/danielstiner/dumble/ui/ConnectViewModel.kt", "app/src/test/java/me/danielstiner/dumble/ui/ConnectFormTest.kt", "app/src/test/java/me/danielstiner/dumble/ui/ConnectViewModelTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"me.danielstiner.dumble.ui.*\"", "acceptanceCriteria": ["validate rejects blank host/username and port outside 1..65535", "toConfig blank password -> null and trims host/username", "ConnectViewModel pre-fills from store, password blank", "persistAndBuild saves host/port/username (not password) and returns config", "ui.* tests pass"], "modelTier": "standard"}
```

---

### Task 5: `ConnectScreen` composable (form + settings gear)

**Goal:** The stateless connect form: a `TopAppBar` titled "Dumble" with a settings gear, four fields with inline errors, and a Connect button enabled only when the form is valid.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/ConnectScreen.kt`

**Acceptance Criteria:**
- [ ] `ConnectScreen` is stateless: it takes `form`, `errors`, `canConnect`, per-field change callbacks, `onConnect`, `onOpenSettings`, and a `SnackbarHostState`.
- [ ] The password field uses `PasswordVisualTransformation`; the port field uses a numeric keyboard; each field shows its `FieldErrors` message when non-null.
- [ ] The Connect button's `enabled` is bound to `canConnect`.
- [ ] A `@Preview` renders the screen; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Implement `ConnectScreen`**

Create `app/src/main/java/me/danielstiner/dumble/ui/ConnectScreen.kt`:

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    form: ConnectForm,
    errors: FieldErrors,
    canConnect: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dumble") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.host,
                onValueChange = onHostChange,
                label = { Text("Server host") },
                isError = errors.host != null,
                supportingText = errors.host?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                isError = errors.port != null,
                supportingText = errors.port?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                isError = errors.username != null,
                supportingText = errors.username?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = onPasswordChange,
                label = { Text("Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        }
    }
}

@Preview
@Composable
private fun ConnectScreenPreview() {
    DumbleTheme {
        val form = ConnectForm(host = "mumble.example.com", username = "dan")
        ConnectScreen(
            form = form,
            errors = validate(form),
            canConnect = validate(form).isValid,
            onHostChange = {}, onPortChange = {}, onUsernameChange = {}, onPasswordChange = {},
            onConnect = {}, onOpenSettings = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}
```

- [ ] **Step 2: Verify + commit**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "feat: ConnectScreen form composable with settings gear"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/ConnectScreen.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["ConnectScreen stateless with form/errors/canConnect/callbacks/snackbarHostState", "password field masked, port numeric keyboard, per-field error text", "Connect button enabled=canConnect", "@Preview present, assembleDebug BUILD SUCCESSFUL"], "modelTier": "mechanical"}
```

---

### Task 6: `ActiveCallScreen` composable

**Goal:** Compose port of the current active-call UI — status line, live stats text, and a Hang Up button.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`

**Acceptance Criteria:**
- [ ] `ActiveCallScreen` takes `statusText: String`, `statsText: String`, `onHangUp: () -> Unit` (no direct flow collection — the caller supplies the strings).
- [ ] Renders a `TopAppBar` titled "Dumble", the status + stats, and a Hang Up button wired to `onHangUp`.
- [ ] A `@Preview` renders; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Implement `ActiveCallScreen`**

Create `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`:

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    statusText: String,
    statsText: String,
    onHangUp: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Dumble") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(statusText, style = MaterialTheme.typography.headlineSmall)
            Text(statsText, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Hang Up") }
        }
    }
}

@Preview
@Composable
private fun ActiveCallScreenPreview() {
    DumbleTheme {
        ActiveCallScreen(
            statusText = "In Call",
            statsText = "state=Synchronized mode=UDP\nudpRtt=11.5ms jit=1.4ms",
            onHangUp = {},
        )
    }
}
```

- [ ] **Step 2: Verify + commit**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "feat: ActiveCallScreen composable"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["ActiveCallScreen takes statusText/statsText/onHangUp strings+callback", "TopAppBar + status + stats + Hang Up wired to onHangUp", "@Preview present, assembleDebug BUILD SUCCESSFUL"], "modelTier": "mechanical"}
```

---

### Task 7: `SettingsScreen` composable (Echo Test entry)

**Goal:** A Material 3 settings/advanced list with a back arrow, whose first item launches the existing Echo Test debug view.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`

**Acceptance Criteria:**
- [ ] `SettingsScreen` takes `onBack: () -> Unit` and `onLaunchEchoTest: () -> Unit`.
- [ ] Renders a `TopAppBar` titled "Settings" with a back navigation icon wired to `onBack`, and a clickable "Echo Test" list item wired to `onLaunchEchoTest`.
- [ ] A `@Preview` renders; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**Steps:**

- [ ] **Step 1: Implement `SettingsScreen`**

Create `app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt`:

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLaunchEchoTest: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Echo Test") },
                supportingContent = { Text("Local audio loopback debug tool") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLaunchEchoTest),
            )
        }
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    DumbleTheme { SettingsScreen(onBack = {}, onLaunchEchoTest = {}) }
}
```

- [ ] **Step 2: Verify + commit**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A && git commit -m "feat: SettingsScreen with Echo Test entry"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/SettingsScreen.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["SettingsScreen takes onBack + onLaunchEchoTest", "TopAppBar 'Settings' with back icon; clickable Echo Test list item", "@Preview present, assembleDebug BUILD SUCCESSFUL"], "modelTier": "mechanical"}
```

---

### Task 8: `DumbleApp` root + `ActiveCallActivity` rewrite (integration)

**Goal:** Wire everything: a state-driven root composable that switches Connect ↔ ActiveCall ↔ Settings, surfaces `Failed` in a snackbar, and an `ActiveCallActivity` (now `ComponentActivity`) that keeps Telecom + permissions and drives connect from the form.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`
- Modify (full rewrite): `app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt`

**Acceptance Criteria:**
- [ ] `DumbleApp` collects `MumbleManager.state` + `CallManager.activeConnection` + `netStats` + `loopbackStats`; shows `ActiveCallScreen` when a connection is active or state is Connecting/Handshaking/Synchronized, `SettingsScreen` when the gear is tapped (idle only), else `ConnectScreen`.
- [ ] A `ConnectionState.Failed` transition shows a snackbar carrying `reason` (+ `detail` if present); a `BackHandler` closes Settings.
- [ ] `ActiveCallActivity` extends `ComponentActivity`, registers the phone account, requests `RECORD_AUDIO`/`POST_NOTIFICATIONS`, and on Connect places the Telecom call **and** calls `MumbleManager.connect(config)` — the same sequence the old `placeTestCall()` used, now with form values.
- [ ] `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL and `./gradlew :app:testDebugUnitTest` → 0 failures.

**Verify:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 0 failures.

**Steps:**

- [ ] **Step 1: Create `DumbleApp`**

Create `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`:

```kotlin
package me.danielstiner.dumble.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.danielstiner.dumble.data.SharedPrefsServerConfigStore
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.MumbleServerConfig
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.telecom.CallManager

@Composable
fun DumbleApp(
    onConnect: (MumbleServerConfig) -> Unit,
    onHangUp: () -> Unit,
    onLaunchEchoTest: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ConnectViewModel = viewModel {
        ConnectViewModel(SharedPrefsServerConfigStore(context.applicationContext))
    }

    val state by MumbleManager.state.collectAsStateWithLifecycle()
    val connection by CallManager.activeConnection.collectAsStateWithLifecycle()
    val net by MumbleManager.netStats.collectAsStateWithLifecycle()
    val loop by MumbleManager.loopbackStats.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    // Latch failures: MumbleManager self-heals Failed -> Disconnected, so capture the reason
    // independently of the transient state and show it from its own effect.
    var pendingError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        (state as? ConnectionState.Failed)?.let { f ->
            pendingError = "Connection failed: ${f.reason}" + (f.detail?.let { " – $it" } ?: "")
        }
    }
    LaunchedEffect(pendingError) {
        pendingError?.let { snackbarHostState.showSnackbar(it); pendingError = null }
    }

    val inCall = connection != null ||
        state is ConnectionState.Connecting ||
        state is ConnectionState.Handshaking ||
        state is ConnectionState.Synchronized

    when {
        inCall -> {
            val statusText = if (state is ConnectionState.Synchronized) "In Call" else "Connecting…"
            val statsText = "state=${state::class.simpleName} mode=${net.mode}\n" +
                "tcpRtt=%.1fms udpRtt=%.1fms jit=%.2fms".format(net.tcpRttMs, net.udpRttMs, net.udpJitterMs) + "\n" +
                "loop: sent=${loop.sent} rcvd=${loop.received} lost=${loop.lost} rtt=%.1fms".format(loop.lastRttMs)
            ActiveCallScreen(statusText = statusText, statsText = statsText, onHangUp = onHangUp)
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(onBack = { showSettings = false }, onLaunchEchoTest = onLaunchEchoTest)
        }
        else -> {
            val errors = validate(form)
            ConnectScreen(
                form = form,
                errors = errors,
                canConnect = errors.isValid,
                onHostChange = { v -> vm.update { it.copy(host = v) } },
                onPortChange = { v -> vm.update { it.copy(port = v) } },
                onUsernameChange = { v -> vm.update { it.copy(username = v) } },
                onPasswordChange = { v -> vm.update { it.copy(password = v) } },
                onConnect = { if (vm.canConnect()) onConnect(vm.persistAndBuild()) },
                onOpenSettings = { showSettings = true },
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
```

- [ ] **Step 2: Rewrite `ActiveCallActivity`**

Replace the entire contents of `app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt`:

```kotlin
package me.danielstiner.dumble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.MumbleServerConfig
import me.danielstiner.dumble.telecom.CallManager
import me.danielstiner.dumble.telecom.DumbleConnectionService
import me.danielstiner.dumble.ui.DumbleApp
import me.danielstiner.dumble.ui.theme.DumbleTheme

class ActiveCallActivity : ComponentActivity() {

    private lateinit var telecomManager: TelecomManager
    private lateinit var phoneAccountHandle: PhoneAccountHandle

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily on the next Connect tap */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)
        MumbleManager.init(this)

        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        phoneAccountHandle = PhoneAccountHandle(
            ComponentName(this, DumbleConnectionService::class.java),
            "DumbleID",
        )
        registerPhoneAccount()
        requestCallPermissions()

        setContent {
            DumbleTheme {
                DumbleApp(
                    onConnect = { config -> onConnect(config) },
                    onHangUp = { CallManager.disconnect() },
                    onLaunchEchoTest = {
                        startActivity(Intent(this, EchoTestActivity::class.java))
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
            requestCallPermissions()
            return
        }
        placeTelecomCall()
        MumbleManager.connect(config)
    }

    private fun placeTelecomCall() {
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
        }
        try {
            telecomManager.placeCall(Uri.fromParts("tel", "DumbleUser", null), extras)
        } catch (_: SecurityException) {
            // Permission revoked between check and call; the next tap re-requests.
        }
    }

    private fun registerPhoneAccount() {
        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Dumble")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        telecomManager.registerPhoneAccount(phoneAccount)
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

- [ ] **Step 3: Verify build + full unit suite**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, 0 test failures.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: DumbleApp state-driven root + ActiveCallActivity Compose rewrite"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt", "app/src/main/java/me/danielstiner/dumble/ActiveCallActivity.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest :app:assembleDebug", "acceptanceCriteria": ["DumbleApp switches Connect/ActiveCall/Settings from state; snackbar on Failed; BackHandler closes Settings", "ActiveCallActivity is ComponentActivity; registers account; requests RECORD_AUDIO/POST_NOTIFICATIONS; onConnect places Telecom call + MumbleManager.connect(config)", "testDebugUnitTest 0 failures", "assembleDebug BUILD SUCCESSFUL"], "modelTier": "standard"}
```

---

### Task 9: Manual on-device verification

**Goal:** Install the app on an emulator or device and confirm the end-to-end flow against a real Mumble server: form-driven connect (no-password and password), Settings → Echo Test, and clean hang-up.

**Files:** none (manual verification; requires the user's device/emulator and a reachable Mumble ≥ 1.5 server).

**Acceptance Criteria:**
- [ ] `./gradlew :app:installDebug` succeeds on a running emulator/device.
- [ ] Launching the app shows the **Connect form** titled "Dumble" (not the old imperative screen); host/port/username pre-fill blank on first run, port shows `64738`.
- [ ] Entering a valid host + username (no password) and tapping **Connect** reaches an **In Call** state (`state=Synchronized`) with live stats.
- [ ] A password-protected server connects when the password is supplied and shows a **failure snackbar** with a wrong password (`reason=AUTH_REJECT`).
- [ ] The top-bar **gear → Settings → Echo Test** opens `EchoTestActivity`; back returns to the form.
- [ ] **Hang Up** returns to the Connect form and the ongoing-call notification clears.

**Verify:** `./gradlew :app:installDebug`, then walk the criteria above on the device. This is a manual checkpoint — the coordinator pauses and asks the user to run it (needs their hardware + server); it is not auto-runnable.

**Steps:**

- [ ] **Step 1: Build + install**

```bash
./gradlew :app:installDebug
```
Expected: `BUILD SUCCESSFUL`, app installed as `me.danielstiner.dumble`.

- [ ] **Step 2: Manual walk-through** — connect with no password, connect with password, wrong-password snackbar, Settings→Echo Test, hang-up. Capture the observed `state=`/`mode=` line for the successful connect.

- [ ] **Step 3: Record the result** — report pass/fail per criterion. If all pass, the milestone is complete; if any fail, file the specific gap and address it before finishing the branch.

```json:metadata
{"files": [], "verifyCommand": "./gradlew :app:installDebug", "acceptanceCriteria": ["installDebug succeeds as me.danielstiner.dumble", "Connect form 'Dumble' shows on launch, port pre-fills 64738", "valid host+username no-password reaches state=Synchronized with live stats", "password server connects; wrong password shows AUTH_REJECT snackbar", "gear->Settings->Echo Test opens EchoTestActivity, back returns", "Hang Up returns to form and clears the notification"], "modelTier": "standard"}
```

---

## Self-Review

**1. Spec coverage:**
- Connection UI form (host/port/username/optional password) → Tasks 4, 5, 8. ✅
- Material You dynamic color → Task 2 (`DumbleTheme`). ✅
- Persist host/port/username, never password → Task 3 (store, no password key) + Task 4 (`persistAndBuild`) + test assertion. ✅
- Connect via the old `placeTestCall` path → Task 8 (`onConnect` = place Telecom call + `MumbleManager.connect`). ✅
- Failure snackbar → Task 8 (`pendingError` latch). ✅
- App rename Drumble→Dumble, `me.danielstiner.dumble` → Task 1. ✅
- Settings gear → SettingsScreen → Echo Test; hoisted-state nav + BackHandler, no Navigation-Compose → Tasks 7, 8. ✅
- One state-driven screen, EchoTestActivity untouched (Views) → Task 8 keeps it, only launched via Settings. ✅
- Non-goals honored: no certs, no server list, no force-TCP, password never stored. ✅

**2. Placeholder scan:** No TBD/TODO; every code step is complete. The one conditional ("AGP-9 note") is a keyed fallback whose value the build error names — not a placeholder. ✅

**3. Type consistency:** `ConnectForm`/`FieldErrors`/`validate`/`toConfig` defined in Task 4 and used identically in Tasks 5, 8. `SavedServer`/`ServerConfigStore`/`SharedPrefsServerConfigStore` defined in Task 3, consumed in Tasks 4, 8. `ConnectViewModel` API (`form`, `update`, `canConnect`, `persistAndBuild`) defined in Task 4, used in Task 8. `DumbleTheme` (Task 2) used in Tasks 5–8. `MumbleServerConfig`/`ConnectionState`/`FailReason`/`MumbleManager`/`CallManager` match the existing code. ✅

**Gate check:** The user's brief for this project contains no verification-gate language (no "smoke test"/"prove it works"/ordering commitment) — Task 9 is a normal final acceptance task, not tagged `userGate`.
