# PR #1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the bare, non-Compose Android Studio scaffold on the `foundation` branch into Dumble's production baseline — an empty Compose app that launches, builds green (debug **and** minified release), and passes CI.

**Architecture:** Single `:app` module. Kotlin + Jetpack Compose (Material 3) UI, Hilt DI, Java 17. AGP 9.2.1 provides built-in Kotlin (no separate `kotlin.android` plugin — confirmed by the prototype, which compiled Kotlin+Compose with only `android.application` + `compose.compiler` plugins).

**Tech Stack:** AGP 9.2.1 · Gradle 9.6.1 · Kotlin 2.2.10 · Compose BOM 2026.06.00 · activity-compose 1.13.0 · lifecycle 2.10.0 · Hilt 2.57.1 · KSP 2.2.10-2.0.2 · minSdk 30 / targetSdk 36 / compileSdk 36.1 · Java 17.

**User decisions (already made):** name "Dumble", `applicationId me.danielstiner.dumble`; minSdk 30; single `:app` module; Hilt DI; Java 17; R8 on for release; CI builds debug + release + unit tests; scaffold generated in Android Studio, Compose added by hand.

### AGP 9 notes (known DSL contingencies for the implementer)
The scaffold uses AGP 9's newer DSL. Three spots may need a small idiom adjustment against live Gradle sync — resolve empirically, do not guess blindly:
1. **Kotlin JVM target 17:** primary approach is `kotlin { jvmToolchain(17) }` (the scaffold's `settings.gradle.kts` already applies the `foojay-resolver-convention`, so a 17 toolchain provisions automatically). If the `kotlin {}` extension is unavailable under built-in Kotlin, set the Kotlin JVM target via the AGP-provided path and keep `compileOptions` at 17.
2. **R8 toggle:** the scaffold writes `optimization { enable = false }`. Enable R8 with `optimization { enable = true }`; if that does not gate minification as expected, fall back to `isMinifyEnabled = true`.
3. **KSP under built-in Kotlin:** applying `com.google.devtools.ksp` should integrate with AGP 9 built-in Kotlin; if the KSP version is rejected for the Kotlin version, bump to the matching `2.2.10-*` KSP release.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `gradle/libs.versions.toml` | Version catalog — Compose/Kotlin/lifecycle (T1), Hilt/KSP (T2) | 1, 2 |
| `build.gradle.kts` (root) | Declare plugins `apply false` | 1, 2 |
| `app/build.gradle.kts` | Module build config — Compose+Java17 (T1), Hilt (T2), release/R8+signing (T3) | 1, 2, 3 |
| `gradle.properties` | AndroidX flags | 1 |
| `app/src/main/java/me/danielstiner/dumble/MainActivity.kt` | Compose entry activity | 1 (+@AndroidEntryPoint in T2) |
| `app/src/main/java/me/danielstiner/dumble/ui/theme/Theme.kt` | Compose Material 3 theme | 1 |
| `app/src/main/java/me/danielstiner/dumble/ui/theme/Type.kt` | Compose typography | 1 |
| `app/src/main/java/me/danielstiner/dumble/DumbleApp.kt` | `@HiltAndroidApp` Application | 2 |
| `app/src/main/AndroidManifest.xml` | Register MainActivity (T1), DumbleApp name (T2) | 1, 2 |
| `app/src/main/res/values/themes.xml`, `values-night/themes.xml` | Platform NoActionBar base theme (replaces Views theme) | 1 |
| `app/src/main/res/values/colors.xml` | **Deleted** (Views-theme colors, now unused) | 1 |
| `app/proguard-rules.pro` | App R8 keep rules | 3 |
| `.github/workflows/ci.yml` | GitHub Actions: build debug+release, unit tests | 4 |

---

## Task 1: Compose + Kotlin + Java 17 base with a launchable empty screen

**Goal:** Replace the Views scaffold with a minimal Compose Material 3 app that builds (`assembleDebug`) and launches to a centered "Dumble" screen.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)
- Modify: `app/build.gradle.kts`
- Modify: `gradle.properties`
- Create: `app/src/main/java/me/danielstiner/dumble/MainActivity.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/ui/theme/Theme.kt`
- Create: `app/src/main/java/me/danielstiner/dumble/ui/theme/Type.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values-night/themes.xml`
- Delete: `app/src/main/res/values/colors.xml`

**Acceptance Criteria:**
- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew testDebugUnitTest` passes (the scaffold's `ExampleUnitTest` still runs).
- [ ] No `appcompat` or `com.google.android.material` (Views) dependency remains in `app/build.gradle.kts`.
- [ ] `app/build.gradle.kts` sets `versionName = "0.1.0"`, Java 17 `compileOptions`, and `buildFeatures { compose = true }`.
- [ ] Manifest declares `.MainActivity` as the `LAUNCHER` activity.

**Verify:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"; ./gradlew --stop; ./gradlew :app:assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Version catalog** — set `gradle/libs.versions.toml` to:

```toml
[versions]
agp = "9.2.1"
kotlin = "2.2.10"
coreKtx = "1.19.0"
composeBom = "2026.06.00"
activityCompose = "1.13.0"
lifecycle = "2.10.0"
junit = "4.13.2"
junitVersion = "1.3.0"
espressoCore = "3.7.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Root build** — set `build.gradle.kts` to:

```kotlin
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

- [ ] **Step 3: App build** — set `app/build.gradle.kts` to:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "me.danielstiner.dumble"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.danielstiner.dumble"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false // Task 3 turns R8 on
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

- [ ] **Step 4: AndroidX flags** — append to `gradle.properties`:

```properties
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Theme** — create `app/src/main/java/me/danielstiner/dumble/ui/theme/Type.kt`:

```kotlin
package me.danielstiner.dumble.ui.theme

import androidx.compose.material3.Typography

val Typography = Typography()
```

and `app/src/main/java/me/danielstiner/dumble/ui/theme/Theme.kt`:

```kotlin
package me.danielstiner.dumble.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme()
private val LightColors = lightColorScheme()

@Composable
fun DumbleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
```

- [ ] **Step 6: MainActivity** — create `app/src/main/java/me/danielstiner/dumble/MainActivity.kt`:

```kotlin
package me.danielstiner.dumble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import me.danielstiner.dumble.ui.theme.DumbleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DumbleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Dumble")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainPreview() {
    DumbleTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Dumble")
        }
    }
}
```

- [ ] **Step 7: Manifest** — set `app/src/main/AndroidManifest.xml` to:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Dumble">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Dumble">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 8: Themes** — set `app/src/main/res/values/themes.xml` to:

```xml
<resources>
    <style name="Theme.Dumble" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

and `app/src/main/res/values-night/themes.xml` to:

```xml
<resources>
    <style name="Theme.Dumble" parent="android:Theme.Material.NoActionBar" />
</resources>
```

- [ ] **Step 9: Delete dead colors** — `git rm app/src/main/res/values/colors.xml` (the purple/teal colors were only referenced by the removed Views theme).

- [ ] **Step 10: Build + test**

Run: `./gradlew --stop && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: Compose + Kotlin + Java 17 base with launchable empty screen"
```

---

## Task 2: Hilt dependency injection

**Goal:** Wire Hilt (`@HiltAndroidApp` Application + `@AndroidEntryPoint` activity) via KSP so the DI toolchain is proven before any injectable code exists.

**Files:**
- Modify: `gradle/libs.versions.toml` (add Hilt + KSP)
- Modify: `build.gradle.kts` (root)
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/me/danielstiner/dumble/DumbleApp.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Acceptance Criteria:**
- [ ] `./gradlew :app:assembleDebug` succeeds with Hilt codegen running (KSP generates `DumbleApp_GeneratedInjector` / `Hilt_MainActivity`).
- [ ] `DumbleApp` is `@HiltAndroidApp` and registered as `android:name=".DumbleApp"` in the manifest.
- [ ] `MainActivity` is annotated `@AndroidEntryPoint`.

**Verify:** `./gradlew --stop && ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Catalog** — add to `gradle/libs.versions.toml`. Under `[versions]`: `hilt = "2.57.1"` and `ksp = "2.2.10-2.0.2"`. Under `[libraries]`:

```toml
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
```

Under `[plugins]`:

```toml
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Root build** — add the two plugins to `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: App build** — add `ksp` + `hilt` to the `app/build.gradle.kts` plugins block, and add the deps:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
```

Add to `dependencies { }`:

```kotlin
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
```

- [ ] **Step 4: Application class** — create `app/src/main/java/me/danielstiner/dumble/DumbleApp.kt`:

```kotlin
package me.danielstiner.dumble

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DumbleApp : Application()
```

- [ ] **Step 5: Annotate activity** — in `MainActivity.kt`, add `import dagger.hilt.android.AndroidEntryPoint` and annotate the class:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
```

- [ ] **Step 6: Register Application** — add `android:name=".DumbleApp"` to the `<application>` tag in `AndroidManifest.xml`:

```xml
    <application
        android:name=".DumbleApp"
        android:allowBackup="true"
```

- [ ] **Step 7: Build**

Run: `./gradlew --stop && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: wire Hilt dependency injection via KSP"
```

---

## Task 3: Release hardening — R8/minify + resource shrinking + signing stub

**Goal:** Enable R8 minification + resource shrinking for `release`, add a keystore-less signing stub (reads from Gradle properties; unsigned if absent), so `assembleRelease` produces a minified APK and R8 keep-rule breakage surfaces here.

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`

**Acceptance Criteria:**
- [ ] `./gradlew :app:assembleRelease` succeeds and produces a minified APK (no keystore configured → unsigned; that is expected).
- [ ] Release build type has minification + `isShrinkResources = true` + `proguardFiles(...)`.
- [ ] Signing config is only created when `DUMBLE_KEYSTORE_FILE` Gradle property is present; no secrets are committed.

**Verify:** `./gradlew --stop && ./gradlew :app:assembleRelease` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: proguard rules** — create `app/proguard-rules.pro`:

```proguard
# App-specific R8 keep rules. Compose, Hilt, and AndroidX ship their own
# consumer rules, so this stays minimal until reflection/JNI/serialization
# surfaces are added in later PRs.
```

- [ ] **Step 2: Signing stub + release block** — in `app/build.gradle.kts`, add a `signingConfigs { }` block inside `android { }` (before `buildTypes`) and replace the `release` build type:

```kotlin
    signingConfigs {
        val keystorePath = providers.gradleProperty("DUMBLE_KEYSTORE_FILE").orNull
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("DUMBLE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("DUMBLE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("DUMBLE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
```

(See "AGP 9 notes" #2: if `optimization { enable = true }` does not gate minification, use `isMinifyEnabled = true`.)

- [ ] **Step 3: Build release**

Run: `./gradlew --stop && ./gradlew :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/release/app-release-unsigned.apk`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "build: enable R8 minify + resource shrinking + signing stub for release"
```

---

## Task 4: GitHub Actions CI

**Goal:** Add a CI workflow that builds debug + minified release and runs unit tests on every push to `main` and every PR.

**Files:**
- Create: `.github/workflows/ci.yml`

**Acceptance Criteria:**
- [ ] `.github/workflows/ci.yml` runs `assembleDebug`, `assembleRelease`, and `testDebugUnitTest` on `pull_request` and pushes to `main`, on JDK 17.
- [ ] The exact Gradle command in the workflow passes locally.

**Verify:** `./gradlew --stop && ./gradlew assembleDebug assembleRelease testDebugUnitTest --stacktrace` → `BUILD SUCCESSFUL` (this is the command CI runs)

**Steps:**

- [ ] **Step 1: Workflow** — create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build and test
        run: ./gradlew assembleDebug assembleRelease testDebugUnitTest --stacktrace
```

- [ ] **Step 2: Verify the CI command locally**

Run: `./gradlew --stop && ./gradlew assembleDebug assembleRelease testDebugUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "ci: build debug + release and run unit tests on PRs"
```

---

## After all tasks

Open PR #1 from `foundation` → `main` for Dan's review. Do **not** merge — Dan reviews and merges on GitHub. CI runs on the PR; confirm it's green before handing over.
