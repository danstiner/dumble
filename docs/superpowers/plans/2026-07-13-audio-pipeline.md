# Audio Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Dumble's synthetic voice loopback with a real full-duplex audio engine (mic → Opus encode → send; receive → Opus decode → mix → play) for a two-party Mumble conversation.

**Architecture:** A new `AudioVoiceEngine` implements the existing `VoiceEngine` seam. Capture+encode run on VoiceTransport's send thread (blocking `AudioRecord.read` is the clock); the receive thread enqueues encoded packets into per-speaker jitter buffers; a new playback thread decodes, mixes, and writes `AudioTrack` (blocking write is the clock). Opus is libopus via a thin JNI shim behind an `OpusCodec` interface. The jitter buffer works in the **sample/time domain** so it interoperates with Mumble's 10 ms `frame_number` units and any sender packet size.

**Tech Stack:** Kotlin, Android `AudioRecord`/`AudioTrack` (`VOICE_COMMUNICATION`), NDK/CMake + libopus (JNI), Telecom `CallEndpoint`, Jetpack Compose, JUnit4.

**User decisions (already made):** "Full two-party conversation" · "A: Java I/O + platform AEC (no NDK)" for audio I/O · "libopus JNI now" · "Continuous (open mic) + mute toggle" · "Bump to minSdk 34 now … only have a simple Speaker↔Earpiece toggle" · FEC/DTX/WSOLA/named-BT picker deferred.

**Spec:** `docs/superpowers/specs/2026-07-13-audio-pipeline-design.md`

---

## Shared contracts (defined once, referenced by every task)

**Constants** (define in `AudioVoiceEngine` companion, reused across tasks):

```kotlin
const val SAMPLE_RATE = 48000
const val CHANNELS = 1
const val FRAME_SAMPLES_20MS = 960     // 20 ms @ 48 kHz mono
const val FRAME_SAMPLES_10MS = 480     // Mumble base frame unit
const val MAX_FRAME_SAMPLES = 5760     // Opus max packet = 120 ms @ 48 kHz
const val MAX_ENCODED_BYTES = 4000     // safe Opus packet ceiling
const val OPUS_APPLICATION_VOIP = 2048
```

**`OpusCodec` factory + `OpusEncoder`/`OpusDecoder`** (Task 2) — the swappable codec boundary. A **factory**, because Opus decode is stateful and we need **one decoder per speaker** (PLC continuity), while the engine owns a single encoder:

```kotlin
interface OpusEncoder {
    /** Encode one [frameSamples]-sample mono PCM16 frame → a fresh Opus packet. */
    fun encode(pcm: ShortArray, frameSamples: Int): ByteArray
    fun close()
}
interface OpusDecoder {
    /**
     * Decode one Opus packet into [out] (mono PCM16). opus == null → PLC conceal of
     * exactly [plcFrameSamples] samples. [out] MUST be MAX_FRAME_SAMPLES long.
     * Returns the number of samples written. Stateful — one instance per speaker.
     */
    fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int
    fun close()
}
interface OpusCodec {
    fun newEncoder(): OpusEncoder
    fun newDecoder(): OpusDecoder
    /** Decoder-less span of a packet in 48 kHz samples (opus_packet_get_nb_samples). */
    fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int
}
```

**Task order & dependencies:**

```
1 (NDK+libopus+JNI) ─→ 2 (OpusCodec+LibOpusCodec) ─┐
3 (AudioMixer) ──────────────────────────────────┐ │
4 (JitterBuffer) ─────────────────→ 5 (SpeakerStream) ─┐
6 (seam terminator + VoiceStats) ─────────────────────┤
                                    7 (AudioVoiceEngine) ─→ 8 (MumbleManager) ─→ 9 (Call audio: mode/focus/AEC + routing) ─→ 10 (UI) ─→ 11 (on-device gate)
```

Tasks 3, 4, and 6 are pure Kotlin and depend on nothing (may run in parallel with Task 1). Task 5 needs Tasks 2 (codec) + 4 (buffer). Task 7 needs 2,3,4,5,6.

---

### Task 1: NDK toolchain + libopus + JNI shim (`NativeOpus`)

**Goal:** Stand up the project's first NDK/CMake build, vendor libopus, expose encode/decode/packet-span through a JNI object, and prove it compiles and links for all ABIs.

**Files:**
- Modify: `app/build.gradle.kts` (minSdk 34, `externalNativeBuild`, `ndkVersion`, packaging)
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/opus_jni.c`
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/NativeOpus.kt`

**Acceptance Criteria:**
- [ ] `minSdk = 34` in `app/build.gradle.kts`
- [ ] `./gradlew :app:assembleDebug` builds the native `libdumbleopus.so` for every configured ABI (BUILD SUCCESSFUL)
- [ ] `NativeOpus` declares all seven external functions and loads `dumbleopus` in an `init` block
- [ ] libopus is pinned to a specific release (v1.5.2) via CMake `FetchContent`
- [ ] existing `./gradlew :app:testDebugUnitTest` still passes (0 failures)

**Verify:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Bump minSdk and enable native build** in `app/build.gradle.kts`.

Change `minSdk = 33` to `minSdk = 34`. Inside `defaultConfig`, add:

```kotlin
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
```

Inside the `android { }` block (sibling of `buildFeatures`), add:

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"
```

- [ ] **Step 2: Write `app/src/main/cpp/CMakeLists.txt`** — fetch and link libopus.

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(dumbleopus C)

include(FetchContent)
FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG        v1.5.2
)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opus)

add_library(dumbleopus SHARED opus_jni.c)
target_link_libraries(dumbleopus opus)
```

- [ ] **Step 3: Write `app/src/main/cpp/opus_jni.c`** — the JNI shim (uses opus.h macros directly).

```c
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <opus.h>

#define PKG "Java_me_danielstiner_dumble_mumble_voice_NativeOpus_"

JNIEXPORT jlong JNICALL PKG(createEncoder)(JNIEnv *e, jobject o, jint sr, jint ch, jint app) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sr, ch, app, &err);
    return (err == OPUS_OK) ? (jlong)(intptr_t)enc : 0;
}

JNIEXPORT jint JNICALL PKG(configureEncoder)(JNIEnv *e, jobject o, jlong h, jint bitrate, jint complexity) {
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)h;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(1));       /* CVBR */
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));           /* FEC off in v1 */
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));                  /* DTX off in v1 */
    return OPUS_OK;
}

JNIEXPORT jlong JNICALL PKG(createDecoder)(JNIEnv *e, jobject o, jint sr, jint ch) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sr, ch, &err);
    return (err == OPUS_OK) ? (jlong)(intptr_t)dec : 0;
}

JNIEXPORT jint JNICALL PKG(encode)(JNIEnv *e, jobject o, jlong h, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)h;
    jshort *in = (*e)->GetShortArrayElements(e, pcm, NULL);
    jbyte *ob = (*e)->GetByteArrayElements(e, out, NULL);
    int n = opus_encode(enc, in, frameSize, (unsigned char *)ob, maxBytes);
    (*e)->ReleaseShortArrayElements(e, pcm, in, JNI_ABORT);
    (*e)->ReleaseByteArrayElements(e, out, ob, 0);
    return n;
}

JNIEXPORT jint JNICALL PKG(decode)(JNIEnv *e, jobject o, jlong h, jbyteArray data, jint offset, jint len, jshortArray out, jint frameSize, jint fec) {
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)h;
    jshort *ob = (*e)->GetShortArrayElements(e, out, NULL);
    int n;
    if (data == NULL) {
        n = opus_decode(dec, NULL, 0, ob, frameSize, fec);      /* PLC */
    } else {
        jbyte *db = (*e)->GetByteArrayElements(e, data, NULL);
        n = opus_decode(dec, (const unsigned char *)(db + offset), len, ob, frameSize, fec);
        (*e)->ReleaseByteArrayElements(e, data, db, JNI_ABORT);
    }
    (*e)->ReleaseShortArrayElements(e, out, ob, 0);
    return n;
}

JNIEXPORT jint JNICALL PKG(packetGetNbSamples)(JNIEnv *e, jobject o, jbyteArray data, jint offset, jint len, jint sr) {
    jbyte *db = (*e)->GetByteArrayElements(e, data, NULL);
    int n = opus_packet_get_nb_samples((const unsigned char *)(db + offset), len, sr);
    (*e)->ReleaseByteArrayElements(e, data, db, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL PKG(destroyEncoder)(JNIEnv *e, jobject o, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

JNIEXPORT void JNICALL PKG(destroyDecoder)(JNIEnv *e, jobject o, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}
```

- [ ] **Step 4: Write `NativeOpus.kt`** — the Kotlin binding.

```kotlin
package me.danielstiner.dumble.mumble.voice

/** Thin JNI binding to libopus (see app/src/main/cpp/opus_jni.c). Handles are opaque pointers. */
object NativeOpus {
    init { System.loadLibrary("dumbleopus") }

    external fun createEncoder(sampleRate: Int, channels: Int, application: Int): Long
    external fun configureEncoder(enc: Long, bitrate: Int, complexity: Int): Int
    external fun createDecoder(sampleRate: Int, channels: Int): Long
    external fun encode(enc: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    external fun decode(dec: Long, data: ByteArray?, offset: Int, len: Int, out: ShortArray, frameSize: Int, fec: Int): Int
    external fun packetGetNbSamples(data: ByteArray, offset: Int, len: Int, sampleRate: Int): Int
    external fun destroyEncoder(enc: Long)
    external fun destroyDecoder(dec: Long)
}
```

- [ ] **Step 5: Build.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with `libdumbleopus.so` produced under `app/build/intermediates/.../jni/` for each ABI. (First run fetches libopus — needs network.)

- [ ] **Step 6: Confirm existing tests still pass.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit.**

```bash
git add app/build.gradle.kts app/src/main/cpp/ app/src/main/java/me/danielstiner/dumble/mumble/voice/NativeOpus.kt
git commit -m "feat(voice): NDK + libopus JNI shim (NativeOpus), minSdk 34"
```

---

### Task 2: `OpusCodec` interface + `LibOpusCodec` + `FakeOpusCodec` + native round-trip smoke test

**Goal:** Wrap `NativeOpus` in the swappable `OpusCodec` interface, provide a pure-Kotlin fake for downstream JVM tests, and prove the real codec round-trips on a device/emulator.

> **USER-ORDERED GATE — NON-SKIPPABLE.** This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/OpusCodec.kt` (interface + `LibOpusCodec`)
- Create: `app/src/test/java/me/danielstiner/dumble/mumble/voice/FakeOpusCodec.kt`
- Create: `app/src/androidTest/java/me/danielstiner/dumble/mumble/voice/LibOpusCodecRoundTripTest.kt`

**Acceptance Criteria:**
- [ ] `OpusCodec`/`OpusEncoder`/`OpusDecoder` match the shared contract exactly
- [ ] `LibOpusCodec.newEncoder()` configures the encoder (24 kbps, complexity 5); `newDecoder()` returns a fresh per-speaker decoder; each `close()` frees its native handle
- [ ] `FakeOpusCodec` round-trips a recognizable PCM pattern purely in the JVM (no native), and reports `packetSamples` from its own header
- [ ] Instrumented `LibOpusCodecRoundTripTest`: encoding a 960-sample 440 Hz sine then decoding returns 960 samples with non-zero energy; a synthesized silence decode(null) PLC returns 960
- [ ] `./gradlew :app:connectedDebugAndroidTest` passes the round-trip test on a device/emulator (output captured)

**Verify:** `./gradlew :app:testDebugUnitTest` (fake compiles/uses) and `./gradlew :app:connectedDebugAndroidTest --tests "*LibOpusCodecRoundTripTest"` → PASS

**Steps:**

- [ ] **Step 1: Write `OpusCodec.kt`** (interface + real impl).

```kotlin
package me.danielstiner.dumble.mumble.voice

interface OpusEncoder {
    fun encode(pcm: ShortArray, frameSamples: Int): ByteArray
    fun close()
}
interface OpusDecoder {
    fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int
    fun close()
}
interface OpusCodec {
    fun newEncoder(): OpusEncoder
    fun newDecoder(): OpusDecoder
    fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int
}

/** libopus-backed factory. Each encoder/decoder owns one native handle, used by a single thread. */
class LibOpusCodec(
    private val bitrate: Int = 24_000,
    private val complexity: Int = 5,
) : OpusCodec {
    override fun newEncoder(): OpusEncoder = LibOpusEncoder(bitrate, complexity)
    override fun newDecoder(): OpusDecoder = LibOpusDecoder()
    override fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int =
        NativeOpus.packetGetNbSamples(opus, offset, length, SAMPLE_RATE).coerceAtLeast(0)
}

class LibOpusEncoder(bitrate: Int, complexity: Int) : OpusEncoder {
    private val enc = NativeOpus.createEncoder(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP)
        .also { require(it != 0L) { "opus_encoder_create failed" } }
    private val encBuf = ByteArray(MAX_ENCODED_BYTES)
    init { NativeOpus.configureEncoder(enc, bitrate, complexity) }
    override fun encode(pcm: ShortArray, frameSamples: Int): ByteArray {
        val n = NativeOpus.encode(enc, pcm, frameSamples, encBuf, MAX_ENCODED_BYTES)
        require(n >= 0) { "opus_encode failed: $n" }
        return encBuf.copyOf(n)
    }
    override fun close() { NativeOpus.destroyEncoder(enc) }
}

class LibOpusDecoder : OpusDecoder {
    private val dec = NativeOpus.createDecoder(SAMPLE_RATE, CHANNELS)
        .also { require(it != 0L) { "opus_decoder_create failed" } }
    override fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
        val frameSize = if (opus == null) plcFrameSamples else MAX_FRAME_SAMPLES
        return NativeOpus.decode(dec, opus, offset, length, out, frameSize, 0).coerceAtLeast(0)
    }
    override fun close() { NativeOpus.destroyDecoder(dec) }
}
```

*(Constants `SAMPLE_RATE`/`CHANNELS`/… are defined in `AudioVoiceEngine` (Task 7). To let Tasks 2–6 compile before Task 7, put the shared constants in a top-level file now:)*

- [ ] **Step 2: Create `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioConstants.kt`.**

```kotlin
package me.danielstiner.dumble.mumble.voice

const val SAMPLE_RATE = 48000
const val CHANNELS = 1
const val FRAME_SAMPLES_20MS = 960
const val FRAME_SAMPLES_10MS = 480
const val MAX_FRAME_SAMPLES = 5760
const val MAX_ENCODED_BYTES = 4000
const val OPUS_APPLICATION_VOIP = 2048
```

- [ ] **Step 3: Write `FakeOpusCodec.kt`** (test source) — a pure-JVM stand-in.

Encodes as `[4-byte big-endian sampleCount][first PCM sample as 2 bytes]`; decode fills `out` with a ramp of that many samples (or PLC fills `plcFrameSamples` of a fixed tone). `packetSamples` reads the header.

```kotlin
package me.danielstiner.dumble.mumble.voice

/** Pure-JVM codec factory for unit tests. Packets encode the sample count in a 4-byte header. */
class FakeOpusCodec : OpusCodec {
    override fun newEncoder(): OpusEncoder = FakeEncoder()
    override fun newDecoder(): OpusDecoder = FakeDecoder()
    override fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int =
        ((opus[offset].toInt() and 0xFF) shl 24) or ((opus[offset + 1].toInt() and 0xFF) shl 16) or
        ((opus[offset + 2].toInt() and 0xFF) shl 8) or (opus[offset + 3].toInt() and 0xFF)
}

class FakeEncoder : OpusEncoder {
    override fun encode(pcm: ShortArray, frameSamples: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (frameSamples ushr 24).toByte(); b[1] = (frameSamples ushr 16).toByte()
        b[2] = (frameSamples ushr 8).toByte();  b[3] = frameSamples.toByte()
        return b
    }
    override fun close() {}
}

class FakeDecoder : OpusDecoder {
    override fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int {
        val n = if (opus == null) plcFrameSamples else
            ((opus[offset].toInt() and 0xFF) shl 24) or ((opus[offset + 1].toInt() and 0xFF) shl 16) or
            ((opus[offset + 2].toInt() and 0xFF) shl 8) or (opus[offset + 3].toInt() and 0xFF)
        for (i in 0 until n) out[i] = if (opus == null) 0 else ((i % 100) - 50).toShort()
        return n
    }
    override fun close() {}
}
```

- [ ] **Step 4: Write the instrumented round-trip test** `LibOpusCodecRoundTripTest.kt`.

```kotlin
package me.danielstiner.dumble.mumble.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LibOpusCodecRoundTripTest {
    @Test fun encodeThenDecode_returns960SamplesWithEnergy() {
        val codec = LibOpusCodec()
        val encoder = codec.newEncoder()
        val decoder = codec.newDecoder()
        val pcm = ShortArray(FRAME_SAMPLES_20MS) { i ->
            (sin(2 * PI * 440 * i / SAMPLE_RATE) * 8000).toInt().toShort()
        }
        val packet = encoder.encode(pcm, FRAME_SAMPLES_20MS)
        assertTrue("non-empty packet", packet.isNotEmpty())
        assertEquals(FRAME_SAMPLES_20MS, codec.packetSamples(packet, 0, packet.size))
        val out = ShortArray(MAX_FRAME_SAMPLES)
        val n = decoder.decode(packet, 0, packet.size, out, FRAME_SAMPLES_20MS)
        assertEquals(FRAME_SAMPLES_20MS, n)
        val energy = (0 until n).sumOf { Math.abs(out[it].toInt()).toLong() }
        assertTrue("decoded energy > 0", energy > 0)
        val plc = decoder.decode(null, 0, 0, out, FRAME_SAMPLES_20MS)
        assertEquals(FRAME_SAMPLES_20MS, plc)
        encoder.close(); decoder.close()
    }
}
```

- [ ] **Step 5: Run the device gate** (needs a connected device/emulator).

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:connectedDebugAndroidTest --tests "me.danielstiner.dumble.mumble.voice.LibOpusCodecRoundTripTest"`
Expected: PASS. Capture the output. If no device is available, the coordinator must surface that the runtime gate is unmet — do NOT close on `assembleDebug` alone.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/OpusCodec.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioConstants.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/FakeOpusCodec.kt \
        app/src/androidTest/java/me/danielstiner/dumble/mumble/voice/LibOpusCodecRoundTripTest.kt
git commit -m "feat(voice): OpusCodec interface + LibOpusCodec + fake + round-trip smoke test"
```

---

### Task 3: `AudioMixer` (pure PCM sum + clip)

**Goal:** A pure function that sums N mono PCM16 frames into one, clipping to `Short` range.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioMixer.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioMixerTest.kt`

**Acceptance Criteria:**
- [ ] `mixInto(dst, src, n)` adds `n` samples of `src` into `dst` with saturation at `Short.MAX_VALUE`/`MIN_VALUE`
- [ ] summing two frames whose sum exceeds `Short.MAX_VALUE` clips (not wraps)
- [ ] `./gradlew :app:testDebugUnitTest --tests "*AudioMixerTest"` passes

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*AudioMixerTest"` → PASS

**Steps:**

- [ ] **Step 1: Write the failing test** `AudioMixerTest.kt`.

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixerTest {
    @Test fun sumsSamples() {
        val dst = ShortArray(3) { 100 }
        AudioMixer.mixInto(dst, shortArrayOf(50, 50, 50), 3)
        assertEquals(150, dst[0].toInt())
    }
    @Test fun clipsPositive() {
        val dst = shortArrayOf(30000)
        AudioMixer.mixInto(dst, shortArrayOf(10000), 1)
        assertEquals(Short.MAX_VALUE.toInt(), dst[0].toInt())
    }
    @Test fun clipsNegative() {
        val dst = shortArrayOf(-30000)
        AudioMixer.mixInto(dst, shortArrayOf(-10000), 1)
        assertEquals(Short.MIN_VALUE.toInt(), dst[0].toInt())
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`AudioMixer` unresolved).

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioMixerTest"`
Expected: FAIL (unresolved reference `AudioMixer`).

- [ ] **Step 3: Write `AudioMixer.kt`.**

```kotlin
package me.danielstiner.dumble.mumble.voice

/** Sums mono PCM16 streams with saturation. Playback-thread only (no synchronization). */
object AudioMixer {
    fun mixInto(dst: ShortArray, src: ShortArray, n: Int) {
        for (i in 0 until n) {
            val sum = dst[i] + src[i]
            dst[i] = when {
                sum > Short.MAX_VALUE -> Short.MAX_VALUE
                sum < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> sum.toShort()
            }
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioMixerTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioMixer.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioMixerTest.kt
git commit -m "feat(voice): AudioMixer (sum + clip)"
```

---

### Task 4: `JitterBuffer` (sample/time-domain reorder + watermarks)

**Goal:** A thread-safe, codec-free per-speaker reorder buffer keyed by sample timestamp, with dedup, late-drop, high-water drop, and a terminator tag. It stores each packet's precomputed span (samples) so it can measure buffered depth without decoding.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterBuffer.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterBufferTest.kt`

**Acceptance Criteria:**
- [ ] `offer` inserts sorted by `timestampSamples`; a duplicate timestamp is ignored; a packet older than the supplied `playoutCursor` is dropped
- [ ] a terminator packet sets `terminatorTimestamp` and (having empty audio) is not queued
- [ ] `bufferedSamples()` returns the summed spans of queued packets
- [ ] when buffered depth exceeds `highWaterSamples`, the oldest packet is dropped on `offer`
- [ ] `pollFirst()` returns packets in ascending timestamp order
- [ ] `./gradlew :app:testDebugUnitTest --tests "*JitterBufferTest"` passes

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*JitterBufferTest"` → PASS

**Steps:**

- [ ] **Step 1: Write the failing test** `JitterBufferTest.kt`.

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    private fun pkt(ts: Long, span: Int = 960, term: Boolean = false) =
        JitterBuffer.Packet(ts, if (term) ByteArray(0) else ByteArray(4), span, term)

    @Test fun ordersByTimestamp() {
        val b = JitterBuffer()
        b.offer(pkt(960), 0); b.offer(pkt(0), 0); b.offer(pkt(480), 0)
        assertEquals(0, b.pollFirst()!!.timestampSamples)
        assertEquals(480, b.pollFirst()!!.timestampSamples)
        assertEquals(960, b.pollFirst()!!.timestampSamples)
    }
    @Test fun dropsDuplicateTimestamp() {
        val b = JitterBuffer()
        b.offer(pkt(0), 0); b.offer(pkt(0), 0)
        b.pollFirst(); assertNull(b.pollFirst())
    }
    @Test fun dropsLateFrame() {
        val b = JitterBuffer()
        b.offer(pkt(0), playoutCursor = 480)
        assertNull(b.pollFirst())
    }
    @Test fun terminatorTagsWithoutQueueing() {
        val b = JitterBuffer()
        b.offer(pkt(1920, span = 0, term = true), 0)
        assertEquals(1920L, b.terminatorTimestamp)
        assertNull(b.pollFirst())
    }
    @Test fun bufferedSamplesSumsSpans() {
        val b = JitterBuffer()
        b.offer(pkt(0, span = 960), 0); b.offer(pkt(960, span = 1920), 0)
        assertEquals(2880, b.bufferedSamples())
    }
    @Test fun highWaterDropsOldest() {
        val b = JitterBuffer(highWaterSamples = 1000)
        b.offer(pkt(0, span = 960), 0); b.offer(pkt(960, span = 960), 0)
        // 1920 > 1000 → oldest (ts 0) dropped
        assertEquals(960, b.pollFirst()!!.timestampSamples)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`JitterBuffer` unresolved).

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*JitterBufferTest"`
Expected: FAIL.

- [ ] **Step 3: Write `JitterBuffer.kt`.**

```kotlin
package me.danielstiner.dumble.mumble.voice

import java.util.TreeMap

/**
 * Per-speaker reorder buffer in the SAMPLE/TIME domain. Codec-free: each packet's span
 * (samples) is precomputed by the caller (OpusCodec.packetSamples). Enqueue on the receive
 * thread, drain on the playback thread — all methods synchronized (short critical sections,
 * byte copies only, no decode/alloc under the lock).
 */
class JitterBuffer(
    private val highWaterSamples: Int = 9600, // ~200 ms
) {
    class Packet(
        val timestampSamples: Long,
        val opus: ByteArray,
        val spanSamples: Int,
        val isTerminator: Boolean,
    )

    private val queue = TreeMap<Long, Packet>()
    private var bufferedSpans = 0

    @Volatile var terminatorTimestamp: Long? = null
        private set

    @Synchronized fun offer(p: Packet, playoutCursor: Long) {
        if (p.isTerminator) terminatorTimestamp = p.timestampSamples
        if (p.opus.isEmpty()) return                       // terminator / empty → tag only
        if (p.timestampSamples < playoutCursor) return      // late
        if (queue.containsKey(p.timestampSamples)) return   // duplicate
        queue[p.timestampSamples] = p
        bufferedSpans += p.spanSamples
        while (bufferedSpans > highWaterSamples && queue.size > 1) {
            val dropped = queue.pollFirstEntry().value
            bufferedSpans -= dropped.spanSamples
        }
    }

    @Synchronized fun peekFirstTimestamp(): Long? = if (queue.isEmpty()) null else queue.firstKey()

    @Synchronized fun pollFirst(): Packet? {
        val e = queue.pollFirstEntry() ?: return null
        bufferedSpans -= e.value.spanSamples
        return e.value
    }

    @Synchronized fun bufferedSamples(): Int = bufferedSpans

    @Synchronized fun isEmpty(): Boolean = queue.isEmpty()
}
```

- [ ] **Step 4: Run — expect PASS.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*JitterBufferTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/JitterBuffer.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/JitterBufferTest.kt
git commit -m "feat(voice): sample/time-domain JitterBuffer with watermark"
```

---

### Task 5: `SpeakerStream` (lazy decoder + decoded-PCM FIFO decoupling)

**Goal:** Per-speaker playout: owns the playout cursor, a lazily-created decoder, and a decoded-PCM FIFO. `fillTick` produces exactly one 20 ms frame per call by decoding due packets (any duration), PLC-filling measured holes in 20 ms steps, re-anchoring on large jumps, and retiring after the terminator drains.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt`

**Acceptance Criteria:**
- [ ] first `offer` anchors the cursor to that packet's timestamp + prebuffer (not 0)
- [ ] a 40 ms packet (1920 samples) decoded once yields two 960-sample `fillTick` outputs (FIFO decoupling)
- [ ] a missing range between two packets is PLC-concealed in 960-sample steps
- [ ] the decoder is created lazily on the first `fillTick` decode (not on `offer`)
- [ ] `fillTick` returns `false` (idle) once the terminator timestamp has been passed and the FIFO/buffer are drained; the stream reports `retired`
- [ ] a forward jump beyond `reanchorGapSamples` re-anchors instead of mass-PLC
- [ ] `./gradlew :app:testDebugUnitTest --tests "*SpeakerStreamTest"` passes

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*SpeakerStreamTest"` → PASS

**Steps:**

- [ ] **Step 1: Write the failing test** `SpeakerStreamTest.kt` (uses `FakeOpusCodec`).

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerStreamTest {
    private val codec = FakeOpusCodec()
    private fun encoded(samples: Int): ByteArray {
        // FakeOpusCodec header encodes the sample count; build a packet of that span.
        val b = ByteArray(4)
        b[0] = (samples ushr 24).toByte(); b[1] = (samples ushr 16).toByte()
        b[2] = (samples ushr 8).toByte();  b[3] = samples.toByte()
        return b
    }

    @Test fun fortyMsPacketYieldsTwoTicks() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(1920), 1920, false)          // one 40 ms packet
        val out = ShortArray(FRAME_SAMPLES_20MS)
        assertTrue(s.fillTick(out))                     // tick 1: first 960 from the single decode
        assertTrue(s.fillTick(out))                     // tick 2: second 960 from FIFO, no new decode
    }

    @Test fun lazyDecoderNotCreatedOnOffer() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(960), 960, false)
        assertFalse(s.decoderCreated)                   // still null until first fillTick
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))
        assertTrue(s.decoderCreated)
    }

    @Test fun retiresAfterTerminatorDrains() {
        val s = SpeakerStream(codec, prebufferSamples = 0)
        s.offer(0, encoded(960), 960, false)
        s.offer(960, ByteArray(0), 0, true)             // terminator at 960
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))      // plays ts 0..960
        assertFalse(s.fillTick(ShortArray(FRAME_SAMPLES_20MS))) // nothing left, past terminator → idle
        assertTrue(s.retired)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*SpeakerStreamTest"`
Expected: FAIL.

- [ ] **Step 3: Write `SpeakerStream.kt`.**

```kotlin
package me.danielstiner.dumble.mumble.voice

/**
 * Per-speaker playout. Playback thread calls fillTick(); receive thread calls offer().
 * offer() only touches the (synchronized) JitterBuffer; the decoder is created lazily and
 * decode happens on the playback thread inside fillTick(). One OpusDecoder per speaker.
 */
class SpeakerStream(
    private val codec: OpusCodec,
    private val prebufferSamples: Int = FRAME_SAMPLES_20MS * 2,   // ~40 ms
    private val reanchorGapSamples: Long = SAMPLE_RATE.toLong(),  // 1 s forward jump → re-anchor
) {
    private val buffer = JitterBuffer()
    @Volatile private var cursor = -1L          // -1 = un-anchored; only fillTick writes it
    private var decoder: OpusDecoder? = null    // playback-thread only
    private val decodeOut = ShortArray(MAX_FRAME_SAMPLES)
    private val fifo = ShortArrayFifo(MAX_FRAME_SAMPLES * 4)

    val decoderCreated get() = decoder != null
    var retired = false; private set

    /** Receive thread. Only touches the synchronized JitterBuffer; a slightly stale cursor is safe. */
    fun offer(timestampSamples: Long, opus: ByteArray, spanSamples: Int, isTerminator: Boolean) {
        val cur = cursor
        buffer.offer(JitterBuffer.Packet(timestampSamples, opus, spanSamples, isTerminator),
            if (cur < 0) 0 else cur)
    }

    /** Playback thread. Fills [out] (960 samples). Returns true if real audio was produced. */
    fun fillTick(out: ShortArray): Boolean {
        if (cursor < 0) {                                       // anchor on the first packet
            val first = buffer.peekFirstTimestamp() ?: return idleOrRetire()
            if (buffer.bufferedSamples() < prebufferSamples && buffer.terminatorTimestamp == null) return false
            cursor = first
        }
        while (fifo.size < FRAME_SAMPLES_20MS) {                // ensure ≥ one 20 ms frame
            val next = buffer.peekFirstTimestamp()
            if (next == null) {
                if (isPastTerminator()) { if (fifo.size == 0) return idleOrRetire() else break }
                plcStep(); break                                // live underrun → one PLC step
            }
            when {
                next > cursor + reanchorGapSamples -> { cursor = next; fifo.clear() } // re-anchor
                next > cursor -> plcStep()                                            // measured hole
                else -> decodeNext()                                                  // due
            }
        }
        val produced = fifo.size > 0
        fifo.drainInto(out, FRAME_SAMPLES_20MS)                 // pads with silence if < 960
        return produced
    }

    private fun ensureDecoder(): OpusDecoder = decoder ?: codec.newDecoder().also { decoder = it }

    private fun decodeNext() {
        val p = buffer.pollFirst() ?: return
        val n = ensureDecoder().decode(p.opus, 0, p.opus.size, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
        cursor = p.timestampSamples + n
    }

    private fun plcStep() {
        val n = ensureDecoder().decode(null, 0, 0, decodeOut, FRAME_SAMPLES_20MS)
        fifo.push(decodeOut, n)
        cursor += FRAME_SAMPLES_20MS
    }

    private fun isPastTerminator(): Boolean {
        val t = buffer.terminatorTimestamp ?: return false
        return cursor >= t
    }
    private fun idleOrRetire(): Boolean {
        if (isPastTerminator() && buffer.isEmpty() && fifo.size == 0) retired = true
        return false
    }

    fun close() { decoder?.close(); decoder = null }
}
```

- [ ] **Step 4: Add the `ShortArrayFifo` helper** at the bottom of `SpeakerStream.kt`.

```kotlin
/** Simple growable short FIFO (playback-thread only). */
class ShortArrayFifo(initialCapacity: Int) {
    private var buf = ShortArray(initialCapacity)
    private var head = 0
    var size = 0; private set
    fun push(src: ShortArray, n: Int) {
        ensure(size + n)
        System.arraycopy(src, 0, buf, head + size, n)
        size += n
    }
    /** Copies min(size, count) into dst[0..count); pads remainder with 0. Advances head. */
    fun drainInto(dst: ShortArray, count: Int) {
        val take = minOf(size, count)
        System.arraycopy(buf, head, dst, 0, take)
        for (i in take until count) dst[i] = 0
        head += take; size -= take
        if (size == 0) head = 0
    }
    fun clear() { head = 0; size = 0 }
    private fun ensure(needed: Int) {
        if (head + needed <= buf.size) return
        val compact = ShortArray(maxOf(buf.size * 2, needed))
        System.arraycopy(buf, head, compact, 0, size)
        buf = compact; head = 0
    }
}
```

- [ ] **Step 5: Run — expect PASS.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*SpeakerStreamTest"`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/SpeakerStream.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/SpeakerStreamTest.kt
git commit -m "feat(voice): SpeakerStream (lazy decoder + decoded-PCM FIFO decoupling)"
```

---

### Task 6: Seam terminator flag + `VoiceStats`

**Goal:** Add an additive `isTerminator` flag to `VoiceFrame`, have `VoiceTransport` map it to `Audio.is_terminator`, and add the `VoiceStats` data class. No behavior change to existing loopback.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceEngine.kt` (add `isTerminator`)
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceTransport.kt` (set `is_terminator`)
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceStats.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/VoiceTransportTest.kt` (extend — add a terminator assertion)

**Acceptance Criteria:**
- [ ] `VoiceFrame` has `val isTerminator: Boolean = false` (existing constructions still compile)
- [ ] a `VoiceFrame(isTerminator = true)` produces an `Audio` message with `is_terminator == true`
- [ ] `VoiceStats` data class exists with the fields from the spec
- [ ] `./gradlew :app:testDebugUnitTest --tests "*VoiceTransportTest"` passes

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*VoiceTransportTest"` → PASS

**Steps:**

- [ ] **Step 1: Extend `VoiceFrame`** in `VoiceEngine.kt` line 4.

```kotlin
class VoiceFrame(val opusData: ByteArray, val length: Int, val frameNumber: Long, val isTerminator: Boolean = false)
```

- [ ] **Step 2: Set `is_terminator` in `VoiceTransport.sendLoop`** (`VoiceTransport.kt`, the `Audio.newBuilder()` block). Replace the builder chain with:

```kotlin
            val audio = MumbleUdpProtos.Audio.newBuilder()
                .setTarget(target)
                .setFrameNumber(frame.frameNumber)
                .setIsTerminator(frame.isTerminator)
                .setOpusData(ByteString.copyFrom(frame.opusData, 0, frame.length))
                .build()
```

- [ ] **Step 3: Add a terminator test** to `VoiceTransportTest.kt`. Add this test method (match the file's existing style for building/parsing; if the test uses a fake `udpSend` capturing bytes, decode the captured `Audio`):

```kotlin
    @Test fun terminatorFrameSetsIsTerminator() {
        val captured = ArrayList<ByteArray>()
        val engine = object : VoiceEngine {
            var sent = false
            override fun start() {}
            override fun stop() {}
            override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
                if (sent) return null
                sent = true
                return VoiceFrame(ByteArray(0), 0, 4, isTerminator = true)
            }
            override fun onIncomingFrame(o: ByteArray, off: Int, len: Int, fn: Long, s: Int, a: Long) {}
        }
        val t = VoiceTransport(engine, { me.danielstiner.dumble.mumble.net.VoiceTransportMode.UDP },
            udpSend = { buf, n -> captured.add(buf.copyOf(n)); true },
            tunnelSend = { _, _ -> true })
        t.start(); Thread.sleep(50); t.stop()
        val wire = captured.first()
        val audio = MumbleUdpProtos.Audio.parser().parseFrom(wire, 1, wire.size - 1)
        org.junit.Assert.assertTrue(audio.isTerminator)
    }
```

- [ ] **Step 4: Create `VoiceStats.kt`.**

```kotlin
package me.danielstiner.dumble.mumble.voice

data class VoiceStats(
    val sent: Long = 0,
    val received: Long = 0,
    val lost: Long = 0,
    val concealed: Long = 0,
    val bufferMs: Int = 0,
    val activeSpeakers: Int = 0,
)
```

- [ ] **Step 5: Run — expect PASS** (both the new terminator test and existing VoiceTransport tests).

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*VoiceTransportTest"`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceEngine.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceTransport.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceStats.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/VoiceTransportTest.kt
git commit -m "feat(voice): VoiceFrame.isTerminator seam flag + VoiceStats"
```

---

### Task 7: `AudioVoiceEngine` (capture/encode + playback/mix, four blockers)

**Goal:** The `VoiceEngine` implementation: capture+encode inside `nextOutgoingFrame` (drains the mic even while muted), a playback thread that de-jitters/decodes/mixes/writes `AudioTrack` (always writes 20 ms of silence when idle), non-blocking `onIncomingFrame` (copy + span, lazy decoder on playback thread), mute, `frame_number` in 10 ms units (+2 per 20 ms), and a `stop()` that joins threads before freeing native handles.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt`

**Acceptance Criteria:**
- [ ] implements `VoiceEngine`; `nextOutgoingFrame` advances `frameNumber` by `+2` per 20 ms packet
- [ ] while muted, capture still reads/discards a frame each call and returns `null`; on the mute edge emits one `VoiceFrame(isTerminator = true)`
- [ ] `onIncomingFrame` never decodes/allocates a decoder — copies bytes, computes span via `codec.packetSamples`, and offers to the per-session `SpeakerStream` (`ConcurrentHashMap`, `computeIfAbsent`)
- [ ] the playback thread writes a full 960-sample buffer every tick (silence when no speaker is active)
- [ ] `stop()` sets running=false, joins the playback thread, then closes all `SpeakerStream`s + the encoder codec
- [ ] `voiceStats: StateFlow<VoiceStats>` updates; `setMuted`/`muted` work
- [ ] `frame_number` unit test passes; full suite green
- [ ] the mic/track objects are created behind an injectable factory so the frame-number logic is JVM-testable without Android audio

**Verify:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 0 failures

**Steps:**

- [ ] **Step 1: Write the failing test** `AudioVoiceEngineFrameNumberTest.kt` — isolates the outgoing frame-number logic with fake audio I/O + `FakeOpusCodec`.

```kotlin
package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineFrameNumberTest {
    private fun engine(muted: Boolean = false): AudioVoiceEngine {
        val e = AudioVoiceEngine(
            codec = FakeOpusCodec(),
            recorderFactory = { FakeAudioIn() },     // returns 960 samples per read
            trackFactory = { FakeAudioOut() },
        )
        e.start(); e.setMuted(muted)
        return e
    }

    @Test fun frameNumberAdvancesByTwoPer20ms() {
        val e = engine()
        val f1 = e.nextOutgoingFrame(0)!!
        val f2 = e.nextOutgoingFrame(0)!!
        assertEquals(0L, f1.frameNumber)
        assertEquals(2L, f2.frameNumber)
        e.stop()
    }

    @Test fun mutedReturnsNullButStillReads() {
        val fakeIn = FakeAudioIn()
        val e = AudioVoiceEngine(FakeOpusCodec(), { fakeIn }, { FakeAudioOut() })
        e.start(); e.setMuted(true)
        assertNull(e.nextOutgoingFrame(0)?.takeIf { !it.isTerminator })  // first is the terminator edge
        e.nextOutgoingFrame(0)                                           // subsequent muted → null
        assertTrue("mic still drained while muted", fakeIn.reads >= 1)
        e.stop()
    }
}
```

Add the fakes in the same test file:

```kotlin
class FakeAudioIn : AudioIn {
    var reads = 0
    override fun read(out: ShortArray, n: Int): Int { reads++; return n }
    override fun close() {}
}
class FakeAudioOut : AudioOut {
    override fun write(pcm: ShortArray, n: Int) {}
    override fun close() {}
}
```

- [ ] **Step 2: Run — expect FAIL.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioVoiceEngineFrameNumberTest"`
Expected: FAIL (unresolved `AudioVoiceEngine`, `AudioIn`, `AudioOut`).

- [ ] **Step 3: Write `AudioVoiceEngine.kt`** — including the `AudioIn`/`AudioOut` seams and the real Android-backed impls.

```kotlin
package me.danielstiner.dumble.mumble.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Abstracts the Android capture device so the engine's logic is JVM-testable. */
interface AudioIn { fun read(out: ShortArray, n: Int): Int; fun close() }
interface AudioOut { fun write(pcm: ShortArray, n: Int); fun close() }

class AudioVoiceEngine(
    private val codec: OpusCodec,
    private val recorderFactory: () -> AudioIn = { AndroidAudioIn() },
    private val trackFactory: () -> AudioOut = { AndroidAudioOut() },
) : VoiceEngine {

    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()

    @Volatile private var muted = false
    @Volatile private var running = false
    private var wasMuted = false

    private val encoder = codec.newEncoder()
    private val capturePcm = ShortArray(FRAME_SAMPLES_20MS)
    private var frameNumber = 0L

    private var recorder: AudioIn? = null
    private var track: AudioOut? = null
    private var playbackThread: Thread? = null

    private val speakers = ConcurrentHashMap<Int, SpeakerStream>()
    @Volatile private var sent = 0L
    @Volatile private var received = 0L

    fun setMuted(value: Boolean) { muted = value }
    val isMuted get() = muted

    override fun start() {
        if (running) return
        running = true
        recorder = recorderFactory()
        track = trackFactory()
        playbackThread = Thread({ playbackLoop() }, "dumble-voice-playback").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    /** Send thread. Always drains the mic; returns null when muted (after emitting one terminator). */
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val rec = recorder ?: return null
        rec.read(capturePcm, FRAME_SAMPLES_20MS)          // capture clock — runs even while muted
        if (muted) {
            if (!wasMuted) { wasMuted = true; return VoiceFrame(ByteArray(0), 0, frameNumber, isTerminator = true) }
            return null
        }
        wasMuted = false
        val opus = encoder.encode(capturePcm, FRAME_SAMPLES_20MS)
        val fn = frameNumber
        frameNumber += 2                                  // 10 ms units: 20 ms = 2 frames
        sent++
        _stats.value = _stats.value.copy(sent = sent)
        return VoiceFrame(opus, opus.size, fn)
    }

    /** Receive thread — must not block, must not allocate a decoder. */
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long) {
        val isTerminator = length == 0
        val copy = if (length == 0) ByteArray(0) else opusData.copyOfRange(offset, offset + length)
        val span = if (length == 0) 0 else codec.packetSamples(copy, 0, copy.size)
        val stream = speakers.computeIfAbsent(senderSession) { SpeakerStream(codec) }
        stream.offer(frameNumber * FRAME_SAMPLES_10MS, copy, span, isTerminator)
        received++
    }

    private fun playbackLoop() {
        val out = track!!
        val mix = ShortArray(FRAME_SAMPLES_20MS)
        val speakerOut = ShortArray(FRAME_SAMPLES_20MS)
        while (running) {
            java.util.Arrays.fill(mix, 0)
            var active = 0
            val it = speakers.entries.iterator()
            while (it.hasNext()) {
                val (session, stream) = it.next()
                val produced = stream.fillTick(speakerOut)
                if (produced) { AudioMixer.mixInto(mix, speakerOut, FRAME_SAMPLES_20MS); active++ }
                if (stream.retired) { stream.close(); it.remove() }
            }
            out.write(mix, FRAME_SAMPLES_20MS)            // ALWAYS write 20 ms (silence when idle)
            _stats.value = _stats.value.copy(received = received, activeSpeakers = active)
        }
    }

    override fun stop() {
        running = false
        playbackThread?.join(500)
        playbackThread = null
        speakers.values.forEach { it.close() }
        speakers.clear()
        recorder?.close(); recorder = null
        track?.close(); track = null
        encoder.close()
    }
}

/** Real capture: 48 kHz mono PCM16 from the VOICE_COMMUNICATION source (platform AEC/NS/AGC). */
class AndroidAudioIn : AudioIn {
    private val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf, FRAME_SAMPLES_20MS * 2 * 4)).also { it.startRecording() }
    override fun read(out: ShortArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = record.read(out, off, n - off, AudioRecord.READ_BLOCKING)
            if (r <= 0) break
            off += r
        }
        return off
    }
    override fun close() { runCatching { record.stop() }; record.release() }
}

/** Real playback: 48 kHz mono PCM16, VOICE_COMMUNICATION usage. */
class AndroidAudioOut : AudioOut {
    private val minBuf = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES_20MS * 2 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
        .also { it.play() }
    override fun write(pcm: ShortArray, n: Int) { track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING) }
    override fun close() { runCatching { track.stop() }; track.release() }
}
```

- [ ] **Step 4: Run — expect PASS.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "*AudioVoiceEngineFrameNumberTest"`
Expected: PASS.

- [ ] **Step 5: Full build + suite.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/voice/AudioVoiceEngineFrameNumberTest.kt
git commit -m "feat(voice): AudioVoiceEngine (capture/encode + playback/mix; four blockers)"
```

---

### Task 8: Wire `AudioVoiceEngine` into `MumbleManager`

**Goal:** Construct `AudioVoiceEngine` in `ActiveSession` (replacing `SyntheticVoiceSource`), transmit to **target 0**, repurpose `loopbackVoice` (default false; true → target 31 self-echo), and expose `voiceStats`/`muted`/`setMuted`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceTransport.kt` (target now supplied, not defaulted to loopback)

**Acceptance Criteria:**
- [ ] `ActiveSession` builds `AudioVoiceEngine(LibOpusCodec())` and a `VoiceTransport` with `target = if (config.loopbackVoice) LOOPBACK_TARGET else 0`
- [ ] `MumbleServerConfig.loopbackVoice` default flips to `false`
- [ ] `MumbleManager.voiceStats: StateFlow<VoiceStats>` reflects the engine's stats
- [ ] `MumbleManager.muted: StateFlow<Boolean>` and `setMuted(Boolean)` forward to the active engine
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 0 failures

**Verify:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Flip the `loopbackVoice` default** in `MumbleManager.kt` (`MumbleServerConfig`): `val loopbackVoice: Boolean = false`.

- [ ] **Step 2: Add stats/mute state** to the `MumbleManager` object (near `_loopbackStats`):

```kotlin
    private val _voiceStats = MutableStateFlow(VoiceStats())
    val voiceStats: StateFlow<VoiceStats> = _voiceStats.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    @Synchronized fun setMuted(value: Boolean) {
        _muted.value = value
        active?.setMuted(value)
    }
```

- [ ] **Step 3: Replace the engine** in `ActiveSession`. Change the field:

```kotlin
        private val codec = LibOpusCodec()
        private val engine = AudioVoiceEngine(codec)
```

Replace `synthetic` usages: the `VoiceTransport` `engine =` argument becomes `engine`; delete the `SyntheticVoiceSource` field and its `synthetic.stats` collector. Set the transmit target:

```kotlin
        private val voice = VoiceTransport(
            engine = engine,
            modeProvider = { selector.mode },
            target = if (config.loopbackVoice) VoiceTransport.LOOPBACK_TARGET else 0,
            udpSend = { buf, n -> udp?.send(buf, n) ?: false },
            tunnelSend = { buf, n -> tcp.sendRaw(TcpMessageType.UDPTunnel, buf, n) },
            onUdpPing = { ts, arrival -> selector.onUdpPong((arrival - ts) / 1e6) },
            threadSetup = ::urgentAudioThread,
        )
```

In `start()`, replace the `synthetic.stats` collector with:

```kotlin
            sessionScope.launch { engine.stats.collect { _voiceStats.value = it } }
```

Add a `setMuted` passthrough on `ActiveSession`:

```kotlin
        fun setMuted(value: Boolean) = engine.setMuted(value)
```

and on the `MumbleManager` object, wire `active?.setMuted` (already added in Step 2 via `active?.setMuted`). Expose it by adding to `ActiveSession` the method above; `active` is `ActiveSession?` so `active?.setMuted(value)` resolves.

- [ ] **Step 4: Keep the loopback self-echo semantics** — `if (config.loopbackVoice) voice.start()` currently gates voice on the flag. Change to always start voice (real calls transmit):

```kotlin
                voice.start()
```

(remove the `if (config.loopbackVoice)` guard around `voice.start()` in `onCryptReady`).

- [ ] **Step 5: Build + suite.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 failures. (If `SyntheticVoiceSourceTest` references the removed collector, leave `SyntheticVoiceSource` class in place — it's untouched; only `ActiveSession` stops using it.)

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/voice/VoiceTransport.kt
git commit -m "feat(voice): wire AudioVoiceEngine into MumbleManager (target 0, mute, stats)"
```

---

### Task 9: Call audio integration — mode/focus/AEC + `CallEndpoint` routing + speaker toggle

**Goal:** Set `MODE_IN_COMMUNICATION` + request audio focus + assert AEC at call start (self-managed Telecom does not do this for us); implement `CallEndpoint` callbacks in `DumbleConnection`; expose route state + `setSpeaker` through `CallManager`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt` (audio mode/focus/AEC + route state + `setSpeaker`)
- Modify: `app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnection.kt` (`CallEndpoint` callbacks)

**Acceptance Criteria:**
- [ ] on `setConnection(non-null)`, `CallManager` sets `AudioManager.mode = MODE_IN_COMMUNICATION`, requests `AUDIOFOCUS_GAIN` (voice-comm attributes), and logs `AcousticEchoCanceler.getEnabled()`; on disconnect it restores `MODE_NORMAL` and abandons focus
- [ ] `DumbleConnection` overrides `onAvailableCallEndpointsChanged` + `onCallEndpointChanged`, storing them into `CallManager`
- [ ] `CallManager.isSpeaker: StateFlow<Boolean>` reflects the active endpoint type; `CallManager.setSpeaker(Boolean)` calls `connection.requestCallEndpointChange` to the `TYPE_SPEAKER`/`TYPE_EARPIECE` endpoint
- [ ] no `BLUETOOTH_CONNECT` added to the manifest
- [ ] `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Add audio-session control to `CallManager`.** Add fields + helpers and call them from `setConnection`/`disconnect`.

```kotlin
    private var audioManager: android.media.AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var priorMode = android.media.AudioManager.MODE_NORMAL

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker
    private val _endpoints = MutableStateFlow<List<android.telecom.CallEndpoint>>(emptyList())

    private fun enterCallAudio(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager = am
        priorMode = am.mode
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        am.requestAudioFocus(req); focusRequest = req
    }
    private fun exitCallAudio() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }; focusRequest = null
        am.mode = priorMode
        audioManager = null
    }
```

`init(context)` already stores an application context — capture it for `enterCallAudio`. Add a field `private var appContext: Context? = null` set in `init` (`appContext = context.applicationContext`). In `setConnection`, when `connection != null` call `appContext?.let { enterCallAudio(it) }`; in `disconnect()` and the failure-teardown branch call `exitCallAudio()`.

- [ ] **Step 2: AEC assertion helper** (call after the AudioRecord exists — simplest: log availability at call start).

```kotlin
    private fun logAecAvailability() {
        android.util.Log.d("CallManager",
            "AEC available=${android.media.audiofx.AcousticEchoCanceler.isAvailable()}")
    }
```

Call `logAecAvailability()` inside `enterCallAudio`.

- [ ] **Step 3: Route state + `setSpeaker` + endpoint intake** on `CallManager`.

```kotlin
    fun onAvailableEndpoints(list: List<android.telecom.CallEndpoint>) { _endpoints.value = list }
    fun onActiveEndpoint(ep: android.telecom.CallEndpoint) {
        _isSpeaker.value = ep.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER
    }
    fun setSpeaker(speaker: Boolean) {
        val conn = _activeConnection.value ?: return
        val target = if (speaker) android.telecom.CallEndpoint.TYPE_SPEAKER
                     else android.telecom.CallEndpoint.TYPE_EARPIECE
        val ep = _endpoints.value.firstOrNull { it.endpointType == target } ?: return
        conn.requestCallEndpointChange(ep, java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: android.telecom.CallEndpointException) {}
            })
    }
```

- [ ] **Step 4: Implement the `CallEndpoint` callbacks** in `DumbleConnection.kt`. Replace the `onCallAudioStateChanged` TODO with the modern callbacks:

```kotlin
    override fun onAvailableCallEndpointsChanged(endpoints: MutableList<android.telecom.CallEndpoint>) {
        CallManager.onAvailableEndpoints(endpoints)
    }
    override fun onCallEndpointChanged(callEndpoint: android.telecom.CallEndpoint) {
        CallManager.onActiveEndpoint(callEndpoint)
    }
```

- [ ] **Step 5: Build.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/telecom/CallManager.kt \
        app/src/main/java/me/danielstiner/dumble/telecom/DumbleConnection.kt
git commit -m "feat(telecom): call audio mode/focus/AEC + CallEndpoint routing + speaker toggle"
```

---

### Task 10: `ActiveCallScreen` controls + `DumbleApp` wiring

**Goal:** Add a **[Mute] · [Speaker] · [Hang Up]** control row and a real `VoiceStats` line; wire mute/speaker state + callbacks through `DumbleApp`.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] `ActiveCallScreen` takes `muted`, `speaker`, `onToggleMute`, `onToggleSpeaker` and renders filled-when-active toggles plus Hang Up
- [ ] `DumbleApp` collects `MumbleManager.voiceStats` + `MumbleManager.muted` + `CallManager.isSpeaker` and passes them + `MumbleManager::setMuted` / `CallManager::setSpeaker`
- [ ] the stats line shows `sent/received/lost/concealed`, `bufferMs`, and `activeSpeakers`
- [ ] `@Preview` compiles; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Rewrite `ActiveCallScreen`** with the control row.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    statusText: String,
    statsText: String,
    muted: Boolean,
    speaker: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Dumble") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(statusText, style = MaterialTheme.typography.headlineSmall)
            Text(statsText, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(selected = muted, onClick = onToggleMute,
                    label = { Text(if (muted) "Unmute" else "Mute") }, modifier = Modifier.weight(1f))
                FilterChip(selected = speaker, onClick = onToggleSpeaker,
                    label = { Text("Speaker") }, modifier = Modifier.weight(1f))
            }
            Button(onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) { Text("Hang Up") }
        }
    }
}
```

Add imports: `androidx.compose.foundation.layout.Row`, `androidx.compose.material3.FilterChip`. Update the `@Preview` to pass `muted = false, speaker = false, onToggleMute = {}, onToggleSpeaker = {}`.

- [ ] **Step 2: Wire `DumbleApp`.** Replace the `loop`/`loopbackStats` collectors with voice stats + mute + speaker, and update the `inCall` branch:

```kotlin
    val voice by MumbleManager.voiceStats.collectAsStateWithLifecycle()
    val muted by MumbleManager.muted.collectAsStateWithLifecycle()
    val speaker by CallManager.isSpeaker.collectAsStateWithLifecycle()
```

(remove the `val loop by MumbleManager.loopbackStats...` line). In the `inCall` branch, replace `statsText` and the `ActiveCallScreen(...)` call:

```kotlin
            val statsText = "state=${state::class.simpleName} mode=${net.mode}\n" +
                "net: tcpRtt=%.1fms udpRtt=%.1fms jit=%.2fms".format(net.tcpRttMs, net.udpRttMs, net.udpJitterMs) + "\n" +
                "voice: sent=${voice.sent} rcvd=${voice.received} lost=${voice.lost} " +
                "concealed=${voice.concealed} buf=${voice.bufferMs}ms spk=${voice.activeSpeakers}"
            ActiveCallScreen(
                statusText = statusText, statsText = statsText,
                muted = muted, speaker = speaker,
                onToggleMute = { MumbleManager.setMuted(!muted) },
                onToggleSpeaker = { CallManager.setSpeaker(!speaker) },
                onHangUp = onHangUp,
            )
```

- [ ] **Step 3: Build.**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(ui): active-call Mute/Speaker controls + real voice stats"
```

---

### Task 11: On-device two-way conversation verification

**Goal:** Prove a real full-duplex conversation against a live Mumble server with a second client, plus the native round-trip, mute, speaker toggle, and Bluetooth auto-route.

> **USER-ORDERED GATE — NON-SKIPPABLE.** This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

**Files:** (none — manual verification on user hardware + a second Mumble client)

**Acceptance Criteria:**
- [ ] `./gradlew :app:installDebug` installs as `me.danielstiner.dumble` on a device/emulator
- [ ] against a live Mumble server with a second client: the second client hears the phone's mic (intelligible speech), and the phone plays the second client's voice (intelligible) — full-duplex
- [ ] no runaway echo on speakerphone (platform AEC engaged; `adb logcat` shows `AEC available=true`)
- [ ] the on-wire `frame_number` for the phone's 20 ms packets increments by 2 (observed via a second client's debug / server log or an added temporary log)
- [ ] Mute silences the far end (second client stops hearing the phone); unmute resumes
- [ ] the Speaker↔Earpiece toggle changes the output route; connecting a Bluetooth headset mid-call auto-routes to it
- [ ] the `loopbackVoice = true` self-echo test plays the phone's own delayed voice back

**Verify:** `./gradlew :app:installDebug`, then walk the criteria on-device with a second Mumble client (manual; needs user hardware + a running Mumble server).

**Steps:**

- [ ] **Step 1: Install.** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug` → SUCCESS.
- [ ] **Step 2: Two-way call.** Connect to the server, join the same channel as a second client, confirm both directions are intelligible.
- [ ] **Step 3: Echo/AEC.** On speakerphone, confirm no runaway echo; `adb logcat | grep "AEC available"` shows `true`.
- [ ] **Step 4: frame_number.** Confirm the phone's packets step `frame_number` by 2 (second-client debug, server log, or a temporary `Log.d` in `nextOutgoingFrame`).
- [ ] **Step 5: Mute / Speaker / Bluetooth.** Toggle mute (far end goes silent), toggle Speaker↔Earpiece, connect a BT headset mid-call (auto-routes).
- [ ] **Step 6: Self-echo.** With `loopbackVoice = true`, confirm delayed self-audio playback.

*(No commit — verification task. Capture observations/logcat in the task close.)*

---

## Deferred (NOT in this plan)

In-band FEC, DTX, adaptive WSOLA time-scaling, mixer soft-limiter for N>2, VAD/PTT, a named-Bluetooth route picker, positional audio + per-packet volume. See the spec's Non-goals and Future extension points.
