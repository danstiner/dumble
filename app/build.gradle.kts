import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "me.danielstiner.dumble"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.danielstiner.dumble"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val keystorePath = providers.gradleProperty("DUMBLE_KEYSTORE_FILE").orNull
        if (keystorePath != null) {
            // Fail here, naming the property, rather than opaquely at packaging time.
            fun credential(name: String): String =
                requireNotNull(providers.gradleProperty(name).orNull) {
                    "DUMBLE_KEYSTORE_FILE is set, so $name must be set too — put all four " +
                        "DUMBLE_* properties in ~/.gradle/gradle.properties."
                }
            create("release") {
                storeFile = file(keystorePath)
                storePassword = credential("DUMBLE_KEYSTORE_PASSWORD")
                keyAlias = credential("DUMBLE_KEY_ALIAS")
                keyPassword = credential("DUMBLE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug is only ever installed on a dev device or an emulator, and CI just needs it to
            // compile. arm64-v8a + x86_64 covers both, halving the CMake work for every debug build.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            ndk {
                // From this branch on the AAB carries our own native code (libopus + opus_jni).
                // Without uploaded symbols, Play shows native crashes as raw addresses. FULL
                // uploads DWARF too, so crash reports get file:line instead of just function names.
                debugSymbolLevel = "FULL"
                // Play has required 64-bit since 2019 and 32-bit-only x86 Android devices never
                // really existed outside emulators, so that ABI is pure build time. armeabi-v7a
                // stays: 32-bit ARM is still shipping hardware.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is shown on the About screen; AGP defaults this off.
        buildConfig = true
    }
    // Connection/trust code logs via android.util.Log and is exercised in JVM unit tests, where the
    // framework is a stub. Return defaults so Log.* no-ops instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"
}

kotlin {
    jvmToolchain(21)
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
    // Settings gear + back arrows; material3 no longer pulls the icons artifact.
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.utils) // io.ktor.util.escapeHTML for outgoing chat text
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle.pkix)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}

// Transitive dependencies arrive without appearing in any diff — that is the blind spot here
// (org.slf4j and okio reached the APK via ktor-utils unnoticed). The resolved group list is
// committed, so an arrival shows up in review; that Attribution.kt covers it is asserted by
// AttributionTest, in Kotlin, against the real data rather than by parsing this source.
tasks.register("verifyShippedGroups") {
    val groups = configurations.named("releaseRuntimeClasspath").flatMap { conf ->
        conf.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.mapNotNull {
                (it.id.componentIdentifier as? ModuleComponentIdentifier)?.moduleIdentifier?.group
            }.toSortedSet()
        }
    }
    // Captured as plain Files at configuration time; the configuration cache forbids touching
    // Project inside doLast.
    val manifest = layout.projectDirectory.file("src/test/resources/shipped-groups.txt").asFile
    val rootLicense = rootProject.layout.projectDirectory.file("LICENSE").asFile
    val bundledLicense = layout.projectDirectory.file("src/main/res/raw/license_apache_2_0.txt").asFile
    val gitmodules = rootProject.layout.projectDirectory.file(".gitmodules").asFile
    val submoduleManifest = layout.projectDirectory.file("src/test/resources/shipped-submodules.txt").asFile

    inputs.property("groups", groups)
    inputs.file(rootLicense)
    inputs.file(bundledLicense)
    inputs.file(gitmodules)

    doLast {
        val resolved = groups.get().joinToString("\n") + "\n"
        if (!manifest.exists() || manifest.readText() != resolved) {
            manifest.writeText(resolved)
            error("shipped-groups.txt was stale and has been rewritten — review the diff, " +
                "attribute any new group in Attribution.kt, and commit.")
        }
        require(rootLicense.readBytes().contentEquals(bundledLicense.readBytes())) {
            "${bundledLicense.name} has drifted from the repo's LICENSE; they must be identical."
        }
        // verifyShippedGroups above only sees Maven coordinates. A vendored native library has
        // none, so libopus would ship unattributed and nothing would fail. Diff the submodule
        // set for the same reason the group set is diffed.
        val paths = Regex("""(?m)^\s*path\s*=\s*(.+)$""")
            .findAll(gitmodules.readText())
            .map { it.groupValues[1].trim() }
            .toSortedSet()
        val expected = paths.joinToString("\n") + "\n"
        if (!submoduleManifest.exists() || submoduleManifest.readText() != expected) {
            submoduleManifest.writeText(expected)
            error("shipped-submodules.txt was stale and has been rewritten — review the diff, " +
                "attribute the new submodule in Attribution.kt, and commit.")
        }
    }
}
