plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
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
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}

val hostNativeDir = layout.buildDirectory.dir("host-native")

// CMake's find_package(JNI) needs a JDK that ships the C headers (include/jni.h).
// The JVM running Gradle can be a headerless runtime (e.g. Android Studio's JBR),
// so prefer java.home / JAVA_HOME when they have headers, else scan common full-JDK
// locations (Homebrew openjdk, /Library/Java/JavaVirtualMachines, /usr/lib/jvm).
fun jdkWithJniHeaders(): String {
    fun hasHeaders(home: String?): Boolean =
        home != null && file(home).resolve("include/jni.h").isFile
    val direct = listOfNotNull(
        System.getProperty("java.home"),
        System.getenv("JAVA_HOME"),
    ).firstOrNull { hasHeaders(it) }
    if (direct != null) return direct
    val macHomebrew = listOf(
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
        "/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
    )
    val macJvms = (file("/Library/Java/JavaVirtualMachines").listFiles().orEmpty())
        .map { it.resolve("Contents/Home").path }
    val linuxJvms = (file("/usr/lib/jvm").listFiles().orEmpty()).map { it.path }
    return (macHomebrew + macJvms + linuxJvms).firstOrNull { hasHeaders(it) }
        ?: System.getProperty("java.home") // last resort; find_package will error clearly
}

val buildHostRnnoise by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds RNNoise + JNI shim for the host JVM (VAD eval harness)."
    val srcDir = file("src/main/cpp")
    val outDir = hostNativeDir.get().asFile
    inputs.dir(srcDir)
    outputs.dir(outDir)
    doFirst { outDir.mkdirs() }
    val javaHome = jdkWithJniHeaders()
    environment("JAVA_HOME", javaHome)
    commandLine(
        "cmake", "-S", srcDir.absolutePath, "-B", outDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DFETCHCONTENT_BASE_DIR=${outDir.resolve("_deps").absolutePath}",
        "-DJAVA_HOME=$javaHome",
    )
}
val buildHostRnnoiseCompile by tasks.registering(Exec::class) {
    dependsOn(buildHostRnnoise)
    inputs.dir(hostNativeDir)
    outputs.dir(hostNativeDir)
    commandLine("cmake", "--build", hostNativeDir.get().asFile.absolutePath, "--config", "Release")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostRnnoiseCompile)
    jvmArgs("-Djava.library.path=${hostNativeDir.get().asFile.absolutePath}")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.protobuf.javalite)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}