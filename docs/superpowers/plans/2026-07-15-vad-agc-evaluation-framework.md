# VAD/AGC Evaluation Framework — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a CI evaluation harness that runs the real transmit DSP (host-rebuilt RNNoise + the production `TransmitGate`) over a labeled audio corpus and asserts VAD + gain metrics.

**Architecture:** Extract the per-capture transmit-decision core into a shared `TransmitProcessor` (used by both the engine and the harness); rebuild the pinned RNNoise C for the host so the production JNI binding loads in JVM unit tests; drive labeled clips through it and score speech coverage / onset / hangover / mid-utterance dropouts / false-activation + output loudness.

**Tech Stack:** Kotlin (JVM unit tests), CMake + C (host RNNoise via `find_package(JNI)`), Gradle (`Exec` task + test `jvmArgs`), GitHub Actions.

**User decisions (already made):**
- "run the real thing" in CI → host-rebuild RNNoise (not emulator/QEMU); verified Bionic-vs-glibc.
- "constructed composites" corpus with exact labels.
- "keep the extraction simple" — `TransmitProcessor` wraps suppressor→vad→gate only.
- "both from the start" — VAD + gain metrics.
- "add a minimal CI workflow."

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/.../voice/TransmitProcessor.kt` (new) | Per-capture core: denoise → vad level → gate. Shared by engine + eval. |
| `app/src/main/java/.../voice/AudioVoiceEngine.kt` (modify) | Delegate the per-capture loop to `TransmitProcessor`. |
| `app/src/main/cpp/CMakeLists.txt` (modify) | `if(ANDROID)` guards; host branch builds RNNoise + shim via `find_package(JNI)`. |
| `app/build.gradle.kts` (modify) | `buildHostRnnoise` Exec task; test `dependsOn` + `-Djava.library.path`. |
| `app/src/test/java/.../voice/eval/WavReader.kt` (new) | 48 kHz mono PCM16 WAV read/write. |
| `app/src/test/java/.../voice/eval/Corpus.kt` (new) | Manifest data model + `CorpusBuilder` (composites + exact labels). |
| `app/src/test/java/.../voice/eval/VadEvaluator.kt` (new) | Run clips through `TransmitProcessor`; compute metrics. |
| `app/src/test/java/.../voice/eval/EvalReport.kt` (new) | Write per-clip metrics report. |
| `app/src/test/java/.../voice/eval/VadEvaluationTest.kt` (new) | Assert per-category thresholds; write report. |
| `app/src/test/resources/vad-corpus/speech_a.wav` (new asset) | Committed public-domain speech ingredient. |
| `.github/workflows/ci.yml` (new) | Run unit suite + host build + eval; upload report. |

Package for eval test code: `me.danielstiner.dumble.mumble.voice.eval`.

---

### Task 1: Extract `TransmitProcessor`

**Goal:** Move the per-capture denoise→vad→gate core out of `AudioVoiceEngine.nextOutgoingFrame` into a shared, independently-testable `TransmitProcessor`, with a drift-guard test proving the engine still decides identically.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt` (fields near lines 35-37; loop at lines 95-101)
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt`

**Acceptance Criteria:**
- [ ] `TransmitProcessor(suppressor, vad, gate).process(capturePcm)` returns the gate `Decision` and denoises in place.
- [ ] `AudioVoiceEngine` delegates its per-capture core to a `TransmitProcessor`; `setVadThreshold` still adjusts the gate; the removed `subLevels` field is gone.
- [ ] Drift-guard test: engine and a standalone `TransmitProcessor` (same config) produce identical `(send, terminator)` sequences on identical captures.
- [ ] Existing `AudioVoiceEngineFrameNumberTest` still passes unchanged.

**Verify:** `./gradlew testDebugUnitTest --tests "*TransmitProcessorTest" --tests "*AudioVoiceEngineFrameNumberTest"` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Write the drift-guard test (fails to compile — no `TransmitProcessor`)**

`app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt`:
```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TransmitProcessorTest {
    /** Returns a scripted sequence of captures (±amp square wave, CAPTURE_SAMPLES each). */
    private fun captures(amps: List<Int>): List<ShortArray> = amps.map { amp ->
        ShortArray(CAPTURE_SAMPLES) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }
    }

    private class ListAudioIn(private val caps: List<ShortArray>) : AudioIn {
        var i = 0
        override fun read(out: ShortArray, n: Int): Int {
            val src = caps.getOrElse(i) { caps.last() }; i++
            System.arraycopy(src, 0, out, 0, n); return n
        }
        override fun close() {}
    }

    @Test fun engineAndProcessorDecideIdentically() {
        val amps = List(3) { 8000 } + List(20) { 0 } + List(3) { 8000 }
        val caps = captures(amps)

        // Engine path (delegates to a TransmitProcessor internally).
        val engine = AudioVoiceEngine(
            FakeOpusCodec(), { ListAudioIn(caps) }, { FakeAudioOut() },
            suppressor = NoiseSuppressor.None, vad = EnergyVadDetector(),
        ).also { it.start() }
        val engineDecisions = caps.indices.map {
            val f = engine.nextOutgoingFrame(0)
            (f != null) to (f?.isTerminator ?: false)
        }
        engine.stop()

        // Standalone processor with identical fresh config.
        val proc = TransmitProcessor(NoiseSuppressor.None, EnergyVadDetector(), TransmitGate(openLevel = 0.60f))
        val procDecisions = caps.map { cap ->
            val d = proc.process(cap.copyOf()); d.send to d.terminator
        }

        assertEquals(procDecisions, engineDecisions)
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `./gradlew testDebugUnitTest --tests "*TransmitProcessorTest"`
Expected: FAIL — `unresolved reference: TransmitProcessor`.

- [ ] **Step 3: Create `TransmitProcessor.kt`**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * The per-capture transmit-decision core, shared by [AudioVoiceEngine] and the VAD eval harness so
 * both exercise identical logic. Denoises each 10 ms sub-frame in place, computes its VAD level, and
 * runs the gate. Mute, frame numbering, Opus encode, and terminator emission stay in the engine.
 */
class TransmitProcessor(
    private val suppressor: NoiseSuppressor,
    private val vad: VadDetector,
    val gate: TransmitGate,
) {
    private val subLevels = FloatArray(FRAMES_PER_PACKET)

    /** Denoise [capturePcm] (CAPTURE_SAMPLES) in place, then decide send/terminator for this capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            subLevels[i] = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
        }
        return gate.update(subLevels)
    }

    fun reset() = gate.reset()
}
```

- [ ] **Step 4: Delegate from the engine**

In `AudioVoiceEngine.kt`, near the fields (currently lines ~35-37):
```kotlin
    private val gate = TransmitGate(openLevel = gateOpenLevel)
    private val processor = TransmitProcessor(suppressor, vad, gate)
    private val capturePcm = ShortArray(CAPTURE_SAMPLES)
```
Delete the `private val subLevels = FloatArray(FRAMES_PER_PACKET)` line.

In `nextOutgoingFrame`, replace the per-sub-frame loop + gate call (currently lines ~95-101):
```kotlin
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            subLevels[i] = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
        }
        val d = gate.update(subLevels)
        if (!d.send) return null
```
with:
```kotlin
        val d = processor.process(capturePcm)
        if (!d.send) return null
```
Leave `setVadThreshold` (`gate.openLevel = value`) and the mute-path `gate.reset()` unchanged — the engine keeps the same `gate` instance the processor holds.

- [ ] **Step 5: Run — expect pass**

Run: `./gradlew testDebugUnitTest --tests "*TransmitProcessorTest" --tests "*AudioVoiceEngineFrameNumberTest"`
Expected: PASS (both).

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/TransmitProcessor.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/TransmitProcessorTest.kt
git commit -m "refactor(voice): extract TransmitProcessor (shared by engine + eval harness)"
```

---

### Task 2: Host-native RNNoise build

**Goal:** Rebuild the pinned RNNoise C (+ existing JNI shim) for the CI host so the production `NativeRnnoise` binding loads and runs in JVM unit tests, via a Gradle task that puts the host lib on `java.library.path`.

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/HostRnnoiseLoadTest.kt`

**Acceptance Criteria:**
- [ ] `./gradlew buildHostRnnoise` produces `app/build/host-native/libdumble.{so,dylib}` on Linux/macOS.
- [ ] `HostRnnoiseLoadTest` loads `libdumble` and `NativeRnnoise.processFrame` returns a float in `[0,1]` for a 480-sample frame.
- [ ] The Android debug build still assembles (`:app:assembleDebug`) — the `if(ANDROID)` guards don't break the NDK path.

**Verify:** `./gradlew testDebugUnitTest --tests "*HostRnnoiseLoadTest" && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Guard CMake for host vs Android**

Rewrite `app/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(dumble C)

include(FetchContent)

# RNNoise: portable C — built on BOTH Android and the host eval build.
FetchContent_Declare(
    rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG        6cbfd53eb348a8d394e0757b4025c6ded34eb2b6
)
FetchContent_MakeAvailable(rnnoise)
file(GLOB RNNOISE_SRC ${rnnoise_SOURCE_DIR}/src/*.c)

if(ANDROID)
    # Full Android lib: Opus + both JNI shims + RNNoise.
    FetchContent_Declare(
        opus
        GIT_REPOSITORY https://github.com/xiph/opus.git
        GIT_TAG        v1.5.2
    )
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(opus)

    add_library(dumble SHARED opus_jni.c rnnoise_jni.c ${RNNOISE_SRC})
    target_include_directories(dumble PRIVATE ${rnnoise_SOURCE_DIR}/include)
    target_link_libraries(dumble opus)
    target_link_options(dumble PRIVATE "-Wl,-z,max-page-size=16384")  # macOS ld64 rejects -z
else()
    # Host eval lib: RNNoise + its JNI shim only (no Opus — the harness uses FakeOpusCodec).
    find_package(JNI REQUIRED)   # populates JNI_INCLUDE_DIRS incl. the platform jni_md.h dir
    add_library(dumble SHARED rnnoise_jni.c ${RNNOISE_SRC})
    target_include_directories(dumble PRIVATE ${rnnoise_SOURCE_DIR}/include ${JNI_INCLUDE_DIRS})
    set_target_properties(dumble PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
endif()
```
Note: a plain `cmake` invocation (no Android toolchain file) leaves `ANDROID` undefined → the host branch runs. `-ffast-math` is not set in either branch (keep it that way).

- [ ] **Step 2: Add the Gradle host-build task + test wiring**

In `app/build.gradle.kts`, at the top level (outside `android { }`):
```kotlin
import org.gradle.internal.os.OperatingSystem

val hostNativeDir = layout.buildDirectory.dir("host-native")

val buildHostRnnoise by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds RNNoise + JNI shim for the host JVM (VAD eval harness)."
    val srcDir = file("src/main/cpp")
    val outDir = hostNativeDir.get().asFile
    inputs.dir(srcDir)
    outputs.dir(outDir)
    doFirst { outDir.mkdirs() }
    // Configure then build. commandLine runs one process; chain via a shell for portability.
    val javaHome = System.getProperty("java.home")
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
```
If the CMake generator nests the artifact (multi-config), adjust `LIBRARY_OUTPUT_DIRECTORY`/`java.library.path` so `libdumble.{so,dylib}` sits directly in `host-native/`.

- [ ] **Step 3: Write the load-check test**

`app/src/test/java/me/danielstiner/dumble/mumble/voice/HostRnnoiseLoadTest.kt`:
```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRnnoiseLoadTest {
    @Test fun loadsAndProcessesAFrame() {
        val state = NativeRnnoise.createState()
        assertNotEquals(0L, state)
        val pcm = ShortArray(FRAME_SAMPLES_10MS) { ((it % 100) - 50).toShort() }
        val prob = NativeRnnoise.processFrame(state, pcm, 0)
        assertTrue("VAD prob in [0,1], was $prob", prob in 0f..1f)
        NativeRnnoise.destroyState(state)
    }
}
```

- [ ] **Step 4: Run — build host lib + test + confirm Android still builds**

Run: `./gradlew testDebugUnitTest --tests "*HostRnnoiseLoadTest"`
Expected: PASS (host `libdumble` built, loaded, RNNoise ran).
Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (NDK path intact).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/CMakeLists.txt app/build.gradle.kts \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/HostRnnoiseLoadTest.kt
git commit -m "build(eval): host-build RNNoise for JVM tests (find_package JNI; if(ANDROID) guards)"
```

---

### Task 3: `WavReader`

**Goal:** A dependency-free 48 kHz mono PCM16 WAV reader/writer for the corpus.

**Files:**
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/WavReader.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/WavReaderTest.kt`

**Acceptance Criteria:**
- [ ] `WavReader.write(file, pcm)` then `WavReader.read(file)` round-trips a `ShortArray` exactly.
- [ ] `read` rejects non-PCM16/non-mono files with a clear error.

**Verify:** `./gradlew testDebugUnitTest --tests "*WavReaderTest"` → PASS

**Steps:**

- [ ] **Step 1: Write the round-trip test**

`WavReaderTest.kt`:
```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTrips() {
        val pcm = ShortArray(1000) { (it * 31 % 65536 - 32768).toShort() }
        val f = tmp.newFile("t.wav")
        WavReader.write(f, pcm)
        assertArrayEquals(pcm, WavReader.read(f))
    }
}
```

- [ ] **Step 2: Run — expect fail (no `WavReader`)**

Run: `./gradlew testDebugUnitTest --tests "*WavReaderTest"` → FAIL (unresolved reference).

- [ ] **Step 3: Implement `WavReader`**

```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal canonical (44-byte header) 48 kHz mono PCM16 little-endian WAV read/write. */
object WavReader {
    fun read(file: File): ShortArray {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44) { "WAV too short" }
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV" }
        // Walk chunks to find 'fmt ' and 'data' (skip any extras).
        var pos = 12; var channels = 0; var bits = 0; var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { channels = bb.getShort(body + 2).toInt(); bits = bb.getShort(body + 14).toInt() }
                "data" -> { dataOff = body; dataLen = len }
            }
            pos = body + len + (len and 1)  // chunks are word-aligned
        }
        require(channels == 1 && bits == 16) { "expected mono PCM16, got ch=$channels bits=$bits" }
        require(dataOff >= 0) { "no data chunk" }
        val out = ShortArray(dataLen / 2)
        for (i in out.indices) out[i] = bb.getShort(dataOff + i * 2)
        return out
    }

    fun write(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)  // PCM, mono
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        file.writeBytes(bb.array())
    }
}
```

- [ ] **Step 4: Run — expect pass.** `./gradlew testDebugUnitTest --tests "*WavReaderTest"` → PASS

- [ ] **Step 5: Commit**
```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/WavReader*.kt
git commit -m "test(eval): add dependency-free PCM16 WavReader"
```

---

### Task 4: `CorpusBuilder` + corpus asset

**Goal:** A committed public-domain speech ingredient plus a `CorpusBuilder` that assembles labeled composite clips (speech / pause / silence / noise) with exact, machine-known segment labels.

> **Asset dependency:** this task commits one short public-domain speech clip. If the executor cannot source/convert audio, it must surface that (NEEDS_CONTEXT) and ask the user to drop a ~3 s, 48 kHz, mono, PCM16 WAV at `app/src/test/resources/vad-corpus/speech_a.wav`. Do not fabricate audio.

**Files:**
- Create asset: `app/src/test/resources/vad-corpus/speech_a.wav` (48 kHz mono PCM16, ~3 s, public domain)
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/Corpus.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/CorpusBuilderTest.kt`

**Acceptance Criteria:**
- [ ] `speech_a.wav` exists, is 48 kHz mono PCM16, and reads via `WavReader` to a non-empty `ShortArray`.
- [ ] `CorpusBuilder.build()` returns ≥5 `Clip`s covering: contiguous speech, speech-with-pause, quiet-onset, noisy speech (known SNR), noise-only.
- [ ] Each `Clip` carries `pcm` plus `segments` whose ms boundaries are exact-by-construction and snap to the 20 ms capture grid.

**Verify:** `./gradlew testDebugUnitTest --tests "*CorpusBuilderTest"` → PASS

**Steps:**

- [ ] **Step 1: Source the speech asset**

Obtain a short public-domain speech clip and convert it (LibriVox recordings are public domain). Example with ffmpeg (3 s from an existing local/public-domain file `source.*`):
```bash
mkdir -p app/src/test/resources/vad-corpus
ffmpeg -i source.flac -ac 1 -ar 48000 -sample_fmt s16 -t 3 \
       app/src/test/resources/vad-corpus/speech_a.wav
```
Confirm: `ffprobe app/src/test/resources/vad-corpus/speech_a.wav` shows `pcm_s16le, 48000 Hz, mono`. If no audio tooling/source is available, stop and request the asset from the user (see the asset-dependency note).

- [ ] **Step 2: Write the builder test**

`CorpusBuilderTest.kt`:
```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusBuilderTest {
    @Test fun buildsLabeledClips() {
        val clips = CorpusBuilder.build()
        assertTrue("≥5 clips", clips.size >= 5)
        for (c in clips) {
            assertTrue("${c.name} has audio", c.pcm.isNotEmpty())
            assertTrue("${c.name} has segments", c.segments.isNotEmpty())
            // boundaries snap to 20 ms (960 samples) grid
            for (s in c.segments) {
                assertTrue("${c.name} start on grid", (s.startMs % 20) == 0)
                assertTrue("${c.name} end on grid", (s.endMs % 20) == 0)
            }
        }
        assertTrue("has a paused clip", clips.any { c -> c.segments.any { it.kind == Kind.PAUSE } })
        assertTrue("has a noise-only clip", clips.any { c -> c.segments.all { it.kind == Kind.NOISE || it.kind == Kind.SILENCE } })
    }
}
```

- [ ] **Step 3: Implement `Corpus.kt` (model + builder)**

```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import java.io.File
import kotlin.math.sqrt

enum class Kind { SPEECH, PAUSE, SILENCE, NOISE }

data class Segment(val startMs: Int, val endMs: Int, val kind: Kind)

data class Thresholds(
    val minCoverage: Double = 0.99,
    val maxMidUtteranceDropoutMs: Int = 0,
    val maxOnsetMs: Int = 60,
    val maxFalseOpeningsPer10s: Double = 1.0,
)

data class Clip(
    val name: String,
    val pcm: ShortArray,
    val segments: List<Segment>,
    val scoreFromMs: Int,
    val thresholds: Thresholds,
)

/**
 * Builds labeled composites from a committed speech ingredient + programmatic silence/noise.
 * Deterministic (fixed LCG for noise) so CI is reproducible. Labels are exact by construction.
 */
object CorpusBuilder {
    private const val MS = SAMPLE_RATE / 1000   // 48 samples per ms
    private const val GRID_MS = 20              // capture grid

    private fun speech(): ShortArray =
        WavReader.read(File("src/test/resources/vad-corpus/speech_a.wav"))

    private fun silence(ms: Int) = ShortArray(ms * MS)

    private fun noise(ms: Int, amp: Int, seed: Long): ShortArray {
        var s = seed
        return ShortArray(ms * MS) {
            s = s * 6364136223846793005L + 1442695040888963407L
            (((s ushr 40).toInt() % (2 * amp + 1)) - amp).toShort()
        }
    }

    private fun rms(a: ShortArray): Double {
        if (a.isEmpty()) return 0.0
        var acc = 0.0; for (v in a) acc += v.toDouble() * v; return sqrt(acc / a.size)
    }

    /** Overlay noise onto a copy of [sig] at the given SNR (dB), clamped to int16. */
    private fun addNoise(sig: ShortArray, snrDb: Double, seed: Long): ShortArray {
        val n = noise(sig.size / MS, 12000, seed)
        val sPow = rms(sig); val nPow = rms(n).coerceAtLeast(1.0)
        val scale = (sPow / nPow) / Math.pow(10.0, snrDb / 20.0)
        return ShortArray(sig.size) { i ->
            (sig[i] + (n.getOrElse(i) { 0 } * scale)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun durMs(a: ShortArray) = a.size / MS

    fun build(): List<Clip> {
        val sp = speech()
        val spMs = (durMs(sp) / GRID_MS) * GRID_MS
        val spTrim = sp.copyOf(spMs * MS)   // trim speech to the 20 ms grid

        // 1) contiguous: 400 ms silence lead-in + speech
        val c1 = concat(silence(400), spTrim)
        val clip1 = Clip(
            "clean_contiguous", c1,
            listOf(Segment(0, 400, Kind.SILENCE), Segment(400, 400 + spMs, Kind.SPEECH)),
            scoreFromMs = 300, thresholds = Thresholds(),
        )

        // 2) speech-with-pause: half speech, 160 ms pause (< 200 ms hangover → MUST be bridged), half
        val half = spTrim.copyOf((spMs / 2 / GRID_MS) * GRID_MS * MS)
        val halfMs = durMs(half)
        val c2 = concat(silence(400), half, silence(160), half)
        val clip2 = Clip(
            "speech_pause", c2,
            listOf(
                Segment(0, 400, Kind.SILENCE),
                Segment(400, 400 + halfMs, Kind.SPEECH),
                Segment(400 + halfMs, 560 + halfMs, Kind.PAUSE),
                Segment(560 + halfMs, 560 + 2 * halfMs, Kind.SPEECH),
            ),
            scoreFromMs = 300, thresholds = Thresholds(maxMidUtteranceDropoutMs = 0),
        )

        // 3) quiet-onset: attenuate speech to 25%
        val quiet = ShortArray(spTrim.size) { (spTrim[it] / 4).toShort() }
        val c3 = concat(silence(400), quiet)
        val clip3 = Clip(
            "quiet_onset", c3,
            listOf(Segment(0, 400, Kind.SILENCE), Segment(400, 400 + spMs, Kind.SPEECH)),
            scoreFromMs = 300, thresholds = Thresholds(minCoverage = 0.90, maxOnsetMs = 120),
        )

        // 4) noisy speech at ~10 dB SNR, with a matching noise lead-in for floor settling
        val noisy = addNoise(concat(silence(400), spTrim), snrDb = 10.0, seed = 42)
        val clip4 = Clip(
            "noisy_10db", noisy,
            listOf(Segment(0, 400, Kind.NOISE), Segment(400, 400 + spMs, Kind.SPEECH)),
            scoreFromMs = 300, thresholds = Thresholds(minCoverage = 0.90),
        )

        // 5) noise-only: no speech at all (false-activation), 10 s so the per-10 s rate is meaningful
        val nOnlyMs = 10000
        val c5 = noise(nOnlyMs, 6000, seed = 7)
        val clip5 = Clip(
            "noise_only", c5,
            listOf(Segment(0, nOnlyMs, Kind.NOISE)),
            scoreFromMs = 300, thresholds = Thresholds(maxFalseOpeningsPer10s = 1.0),
        )

        return listOf(clip1, clip2, clip3, clip4, clip5)
    }
}
```

- [ ] **Step 4: Run — expect pass.** `./gradlew testDebugUnitTest --tests "*CorpusBuilderTest"` → PASS

- [ ] **Step 5: Commit**
```bash
git add app/src/test/resources/vad-corpus/speech_a.wav \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/Corpus.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/CorpusBuilderTest.kt
git commit -m "test(eval): corpus builder + public-domain speech ingredient"
```

---

### Task 5: `VadEvaluator` + `EvalReport`

**Goal:** Run each corpus clip through a fresh `TransmitProcessor` (real host RNNoise + gate) and compute the VAD + gain metrics against the labels; write a per-clip report.

**Files:**
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluator.kt`
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/EvalReport.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluatorTest.kt`

**Acceptance Criteria:**
- [ ] `VadEvaluator.evaluate(clip)` returns `Metrics(coverage, onsetMs, hangoverMs, midDropoutMs, falseOpenings, speechLoudnessDbFs, clipping)`.
- [ ] A fresh `RnnoiseSuppressor` + `TransmitGate` are built per clip and `close()`d (no cross-clip state).
- [ ] On a synthetic all-open decision fixture, metrics compute correctly (coverage=1.0, 0 dropouts) — pure-logic unit test, no RNNoise.

**Verify:** `./gradlew testDebugUnitTest --tests "*VadEvaluatorTest"` → PASS

**Steps:**

- [ ] **Step 1: Write the metrics unit test (pure logic, no native)**

`VadEvaluatorTest.kt`:
```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadEvaluatorTest {
    // Clip: 400 ms silence then 600 ms speech; 20 ms captures => 50 captures.
    private val clip = Clip(
        "t", ShortArray(48 * 1000), // 1000 ms of zeros (audio unused by scoreDecisions)
        listOf(Segment(0, 400, Kind.SILENCE), Segment(400, 1000, Kind.SPEECH)),
        scoreFromMs = 0, thresholds = Thresholds(),
    )

    @Test fun fullCoverageNoDropouts() {
        // send=true for every speech capture (indices 20..49), false in silence.
        val sends = BooleanArray(50) { it >= 20 }
        val m = VadEvaluator.scoreDecisions(clip, sends)
        assertEquals(1.0, m.coverage, 1e-9)
        assertEquals(0, m.midDropoutMs)
        assertEquals(0, m.onsetMs)          // opens on the first speech capture
        assertEquals(0.0, m.falseOpenings, 1e-9)
    }

    @Test fun detectsMidUtteranceDropout() {
        val sends = BooleanArray(50) { it >= 20 }
        sends[30] = false; sends[31] = false   // 40 ms hole inside speech
        val m = VadEvaluator.scoreDecisions(clip, sends)
        assertTrue("dropout detected", m.midDropoutMs >= 40)
    }
}
```

- [ ] **Step 2: Run — expect fail (no `VadEvaluator`)**

Run: `./gradlew testDebugUnitTest --tests "*VadEvaluatorTest"` → FAIL.

- [ ] **Step 3: Implement `VadEvaluator` + `Metrics`**

```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.*
import kotlin.math.log10
import kotlin.math.sqrt

data class Metrics(
    val coverage: Double,        // fraction of speech captures sent
    val onsetMs: Int,            // first-speech-segment start -> first send
    val hangoverMs: Int,         // send past the last speech segment's end
    val midDropoutMs: Int,       // send=false inside speech, after onset
    val falseOpenings: Double,   // gate openings over silence/noise, normalized per 10 s
    val speechLoudnessDbFs: Double,
    val clipping: Int,
)

object VadEvaluator {
    private const val MS = SAMPLE_RATE / 1000
    private const val CAP_MS = CAPTURE_SAMPLES / MS   // 20 ms per capture

    private fun labelAt(clip: Clip, capIndex: Int): Kind {
        val midMs = capIndex * CAP_MS + CAP_MS / 2
        return clip.segments.firstOrNull { midMs >= it.startMs && midMs < it.endMs }?.kind ?: Kind.SILENCE
    }

    /** Pure metric computation from a per-capture send[] decision array (unit-testable, no DSP). */
    fun scoreDecisions(clip: Clip, send: BooleanArray): Metrics {
        val scoreFromCap = clip.scoreFromMs / CAP_MS
        val firstSpeech = clip.segments.firstOrNull { it.kind == Kind.SPEECH }
        val lastSpeechEnd = clip.segments.lastOrNull { it.kind == Kind.SPEECH }?.endMs ?: 0

        // Onset: first sent capture within the first speech segment.
        var onsetMs = -1
        for (c in send.indices) {
            if (c < scoreFromCap || firstSpeech == null) continue
            if (send[c] && labelAt(clip, c) == Kind.SPEECH) {
                onsetMs = (c * CAP_MS - firstSpeech.startMs).coerceAtLeast(0); break
            }
        }

        // Coverage over speech captures; false openings over silence/noise.
        var speechCaps = 0; var speechSent = 0; var falseOpen = 0; var nonSpeechCaps = 0
        for (c in send.indices) {
            if (c < scoreFromCap) continue
            when (labelAt(clip, c)) {
                Kind.SPEECH -> { speechCaps++; if (send[c]) speechSent++ }
                Kind.SILENCE, Kind.NOISE -> {
                    nonSpeechCaps++
                    if (send[c] && (c == 0 || !send[c - 1])) falseOpen++   // count openings, not captures
                }
                Kind.PAUSE -> { /* exempt from coverage + false-activation; see midDrop below */ }
            }
        }

        // Mid-utterance dropout: once open, the gate should stay open — bridging short pauses via
        // hangover — until the last speech ends. Any not-sent capture in that span is a chop.
        var midDropMs = 0
        if (onsetMs >= 0 && firstSpeech != null) {
            val onsetCap = (firstSpeech.startMs + onsetMs) / CAP_MS
            val lastCap = lastSpeechEnd / CAP_MS
            for (c in onsetCap until lastCap) if (c in send.indices && !send[c]) midDropMs += CAP_MS
        }

        var hangoverMs = 0
        for (c in send.indices) if (c * CAP_MS >= lastSpeechEnd && send[c]) hangoverMs += CAP_MS

        val coverage = if (speechCaps == 0) 1.0 else speechSent.toDouble() / speechCaps
        val nonSpeechSecs = (nonSpeechCaps * CAP_MS) / 1000.0
        val falsePer10s = if (nonSpeechSecs <= 0) 0.0 else falseOpen / nonSpeechSecs * 10.0
        return Metrics(coverage, onsetMs.coerceAtLeast(0), hangoverMs, midDropMs, falsePer10s, 0.0, 0)
    }

    /** Full DSP evaluation: fresh RNNoise + gate per clip. */
    fun evaluate(clip: Clip): Metrics {
        val suppressor = RnnoiseSuppressor()
        try {
            val proc = TransmitProcessor(suppressor, suppressor, TransmitGate())
            val caps = clip.pcm.size / CAPTURE_SAMPLES
            val send = BooleanArray(caps)
            var speechSumSq = 0.0; var speechSamples = 0L; var clip16 = 0
            val cap = ShortArray(CAPTURE_SAMPLES)
            for (c in 0 until caps) {
                System.arraycopy(clip.pcm, c * CAPTURE_SAMPLES, cap, 0, CAPTURE_SAMPLES)
                val d = proc.process(cap)   // denoises cap in place
                send[c] = d.send
                if (d.send && labelAt(clip, c) == Kind.SPEECH) {
                    for (s in cap) { speechSumSq += s.toDouble() * s; if (s.toInt() == 32767 || s.toInt() == -32768) clip16++ }
                    speechSamples += CAPTURE_SAMPLES
                }
            }
            val base = scoreDecisions(clip, send)
            val loud = if (speechSamples == 0L) -120.0
                       else 20.0 * log10(sqrt(speechSumSq / speechSamples) / 32768.0)
            return base.copy(speechLoudnessDbFs = loud, clipping = clip16)
        } finally {
            suppressor.close()
        }
    }
}
```

- [ ] **Step 4: Implement `EvalReport`**

```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import java.io.File

object EvalReport {
    fun write(dir: File, results: List<Pair<Clip, Metrics>>) {
        dir.mkdirs()
        val md = buildString {
            appendLine("| clip | coverage | onsetMs | hangMs | midDropMs | falseOpen/10s | loudnessDbFS | clip |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for ((c, m) in results) appendLine(
                "| ${c.name} | %.3f | %d | %d | %d | %.2f | %.1f | %d |"
                    .format(m.coverage, m.onsetMs, m.hangoverMs, m.midDropoutMs, m.falseOpenings, m.speechLoudnessDbFs, m.clipping)
            )
        }
        File(dir, "metrics.md").writeText(md)
    }
}
```

- [ ] **Step 5: Run — expect pass.** `./gradlew testDebugUnitTest --tests "*VadEvaluatorTest"` → PASS

- [ ] **Step 6: Commit**
```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluator.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/EvalReport.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluatorTest.kt
git commit -m "test(eval): VadEvaluator metrics + report (fresh DSP per clip)"
```

---

### Task 6: `VadEvaluationTest` — end-to-end CI assertion

**Goal:** **USER-ORDERED GATE — NON-SKIPPABLE.** This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

Run the whole corpus through the real RNNoise + gate and **assert in CI that each clip meets its per-category thresholds** (the full utterance passes the gate: coverage + zero mid-utterance dropouts on clean speech), writing the metrics report.

**Files:**
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluationTest.kt`

**Acceptance Criteria:**
- [ ] For `clean_contiguous`: `coverage ≥ 0.99` AND `midDropoutMs == 0`.
- [ ] For `speech_pause`: `midDropoutMs == 0` (hangover bridges the 160 ms pause, which is < the 200 ms hangover) — i.e. the full utterance passes.
- [ ] For `noise_only`: `falseOpenings ≤ 1.0` per 10 s.
- [ ] The report `app/build/reports/vad-eval/metrics.md` is written with a row per clip.

**Verify:** `./gradlew testDebugUnitTest --tests "*VadEvaluationTest"` → PASS, and `app/build/reports/vad-eval/metrics.md` exists.

**Steps:**

- [ ] **Step 1: Write the threshold test**

`VadEvaluationTest.kt`:
```kotlin
package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VadEvaluationTest {
    @Test fun corpusMeetsThresholds() {
        val clips = CorpusBuilder.build()
        val results = clips.map { it to VadEvaluator.evaluate(it) }
        EvalReport.write(File("build/reports/vad-eval"), results)

        for ((c, m) in results) {
            val t = c.thresholds
            assertTrue("${c.name} coverage ${"%.3f".format(m.coverage)} < ${t.minCoverage}",
                m.coverage >= t.minCoverage - 1e-9)
            assertTrue("${c.name} midDropout ${m.midDropoutMs}ms > ${t.maxMidUtteranceDropoutMs}",
                m.midDropoutMs <= t.maxMidUtteranceDropoutMs)
            assertTrue("${c.name} onset ${m.onsetMs}ms > ${t.maxOnsetMs}",
                m.onsetMs <= t.maxOnsetMs)
            assertTrue("${c.name} falseOpenings ${"%.2f".format(m.falseOpenings)} > ${t.maxFalseOpeningsPer10s}",
                m.falseOpenings <= t.maxFalseOpeningsPer10s + 1e-9)
        }
        assertTrue(File("build/reports/vad-eval/metrics.md").exists())
    }
}
```

- [ ] **Step 2: Run — real RNNoise over the corpus**

Run: `./gradlew testDebugUnitTest --tests "*VadEvaluationTest"`
Expected: PASS. If a threshold fails, inspect `app/build/reports/vad-eval/metrics.md`; adjust the per-category `Thresholds` in `Corpus.kt` **only** to reflect real, defensible behavior (document the reason in the commit) — never to paper over a genuine mid-utterance dropout on clean speech.

- [ ] **Step 3: Commit**
```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/VadEvaluationTest.kt
git commit -m "test(eval): end-to-end corpus threshold assertions (hysteresis/hangover on real audio)"
```

---

### Task 7: Minimal CI workflow

**Goal:** A GitHub Actions workflow that runs the unit suite (including the host RNNoise build + eval) and uploads the metrics report.

**Files:**
- Create: `.github/workflows/ci.yml`

**Acceptance Criteria:**
- [ ] Workflow triggers on push + PR, sets up JDK 17 + the Android SDK + CMake, runs `./gradlew testDebugUnitTest`.
- [ ] The `vad-eval` report is uploaded as an artifact.
- [ ] `yamllint`/GitHub parses it (valid YAML).

**Verify:** `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('ok')"` → `ok` (structural check; full validation is on push).

**Steps:**

- [ ] **Step 1: Write the workflow**

`.github/workflows/ci.yml`:
```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
jobs:
  unit-and-eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Install CMake + build tools
        run: sudo apt-get update && sudo apt-get install -y cmake build-essential
      - name: Unit tests + VAD/AGC eval
        run: ./gradlew testDebugUnitTest
      - name: Upload eval report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: vad-eval-report
          path: app/build/reports/vad-eval/metrics.md
```
The Android SDK is preinstalled on `ubuntu-latest`; `testDebugUnitTest` triggers `buildHostRnnoiseCompile` (Task 2), which needs the `cmake` installed above.

- [ ] **Step 2: Validate YAML structurally**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**
```bash
git add .github/workflows/ci.yml
git commit -m "ci: run unit suite + VAD/AGC eval on push/PR, upload report"
```

---

## Notes for the executor
- **Build order for parallelism:** Tasks 1, 2, 3 are independent. Task 4 needs 3; Task 5 needs 1+2+3; Task 6 needs 4+5; Task 7 needs 6.
- **Determinism:** CI runs only the host build (single-platform, bit-deterministic); thresholds carry slack. Keep `-ffast-math` off in CMake.
- **This unblocks specs 1 & 2:** `TransmitProcessor` is where spec 1's activation-mode branch and spec 2's AGC stage will land.
