# Mumble Network Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean-room Kotlin Mumble client for Drumble — TLS control channel to Synchronized, channel/user model, OCB2-AES128 UDP voice transport with TCP-tunnel fallback, frame-level audio seam validated via server loopback, bridged into the existing Telecom call shell.

**Architecture:** Pure-Kotlin/JVM core (`net/`, `protocol/`, `model/`, `voice/` under `com.example.drumble.mumble`) with no Android imports so it unit-tests on the JVM; a thin Android layer (facade, SharedPreferences pin store, Telecom bridge) on top. Voice hot path on dedicated raw threads with pooled buffers; control channel on coroutines. Spec: `docs/superpowers/specs/2026-07-11-mumble-network-layer-design.md`.

**Tech Stack:** Kotlin (AGP 9.2.1 built-in), kotlinx-coroutines, protobuf-javalite (vendored BSD `Mumble.proto`/`MumbleUDP.proto` from upstream mumble-voip), JCE AES, JUnit4, Docker `mumble-server` for integration.

**User decisions (already made):**
- Clean-room Kotlin client; Humla (GPL) is reference-only, no code copied. Local reference checkout: `~/git/quite/humla`.
- New protobuf UDP protocol only (servers ≥ 1.5); no legacy bit-packed voice framing.
- Encryption envelope unchanged: OCB2-AES128 via `CryptSetup` (still required despite new protocol).
- Scope: control channel + voice packet transport now; audio pipeline (Oboe + libopus native engine) is a later milestone. MediaCodec rejected (no PLC/FEC control).
- Kotlin owns the network world permanently; JNI seam = "decrypted Opus frame in / encoded Opus frame out"; seam calls are blocking-with-timeout (semaphore wake + one-frame timeout backstop; pure-timer as fallback config).
- 10 ms frame interval default, `framesPerPacket = 1`.
- Voice hot path: dedicated raw threads (`URGENT_AUDIO`), zero-allocation discipline, no coroutine dispatchers.
- TLS: TOFU (pin cert SHA-256 on first use) — flagged insecure-for-dev-only.
- Auth: username + optional server password; no client certs this milestone.
- Loopback validation via `Audio.target = 31` (server echo); UDP→TCP fallback drill required.

---

## File Structure

```
app/src/main/proto/
├── Mumble.proto                  # vendored from mumble-voip/mumble (BSD), java options added
└── MumbleUDP.proto               # vendored, java options added

app/src/main/java/com/example/drumble/mumble/
├── MumbleManager.kt              # Android-facing facade (rewrites placeholder): config, scope,
│                                 #   SharedPrefsPinStore, wiring of all components
├── util/MumbleLog.kt             # pluggable logger (core stays android-free)
├── net/
│   ├── CryptState.kt             # OCB2-AES128, IV window/replay, counters (pure JVM)
│   ├── PinStore.kt               # TOFU interface + InMemoryPinStore + TofuTrustManager
│   ├── MumbleTcpTransport.kt     # TLS socket, reader loop + writer channel (coroutines)
│   ├── MumbleUdpTransport.kt     # DatagramChannel, receive thread, in-line encrypted send
│   └── TransportSelector.kt      # UDP↔TCP policy + NetStats
├── protocol/
│   ├── MumbleCodec.kt            # TCP [u16 type][u32 len][pb] framing; UDP [u8 type][pb]; enums
│   └── SessionStateMachine.kt    # handshake→Synchronized, pings, CryptSetup/resync, dispatch
├── model/
│   └── MumbleModel.kt            # Channel/User/ServerModel data classes + reducers + StateFlow
└── voice/
    ├── VoiceEngine.kt            # the seam: blocking nextOutgoingFrame / onIncomingFrame
    ├── SyntheticVoiceSource.kt   # self-clocked test frames + LoopbackStats (RTT/jitter/loss)
    └── VoiceTransport.kt         # send thread + receive routing over selected transport

app/src/test/java/com/example/drumble/mumble/   # JVM unit tests (JUnit4)
├── CryptStateTest.kt
├── MumbleCodecTest.kt
├── MumbleModelTest.kt
├── TofuTrustTest.kt
├── SessionStateMachineTest.kt
├── TransportSelectorTest.kt
├── SyntheticVoiceSourceTest.kt
└── integration/LiveServerIntegrationTest.kt    # env-gated (MUMBLE_TEST_SERVER)

docs/dev/mumble-server/docker-compose.yml       # local murmur ≥1.5 for integration

Modified: gradle/libs.versions.toml, app/build.gradle.kts,
          ActiveCallActivity.kt, telecom/CallManager.kt (bridge + debug stats)
```

Execution order: Task 1 → (2, 3, 4 independent) → 5 → 6 → 7 → 8 → 9 → 10.

---

### Task 1: Build setup — protobuf toolchain, coroutines, vendored protos

**Goal:** Project compiles with generated javalite classes for `Mumble.proto`/`MumbleUDP.proto` and coroutines on the classpath.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/proto/Mumble.proto`, `app/src/main/proto/MumbleUDP.proto`
- Test: `app/src/test/java/com/example/drumble/mumble/ProtoSmokeTest.kt`

**Acceptance Criteria:**
- [ ] `./gradlew :app:assembleDebug` succeeds with generated `MumbleProtos` / `MumbleUdpProtos` classes
- [ ] Proto round-trip smoke test passes on JVM
- [ ] Working tree baseline (wrapper upgrade etc.) committed separately before feature changes

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.ProtoSmokeTest"` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Commit the pre-existing working-tree baseline** (gradle wrapper 9.6.1 upgrade + manifest/settings leftovers from the earlier library experiment — commit as-is so feature diffs stay clean):

```bash
git add -A && git commit -m "chore: baseline — gradle 9.6.1 wrapper, manifest, settings"
```

- [ ] **Step 2: Add versions/libraries/plugins to `gradle/libs.versions.toml`:**

```toml
# under [versions]
kotlinxCoroutines = "1.10.2"
protobuf = "4.31.1"
protobufPlugin = "0.9.5"

# under [libraries]
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
protobuf-javalite = { group = "com.google.protobuf", name = "protobuf-javalite", version.ref = "protobuf" }

# under [plugins]
protobuf = { id = "com.google.protobuf", version.ref = "protobufPlugin" }
```

- [ ] **Step 3: Apply plugin + deps in `app/build.gradle.kts`** — add to `plugins {}`: `alias(libs.plugins.protobuf)`; add after the `android {}` block:

```kotlin
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.31.1" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}
```

and to `dependencies {}`:

```kotlin
implementation(libs.kotlinx.coroutines.core)
implementation(libs.kotlinx.coroutines.android)
implementation(libs.protobuf.javalite)
testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 4: Vendor the protos (BSD, upstream) and add Java options:**

```bash
mkdir -p app/src/main/proto
curl -fsSL https://raw.githubusercontent.com/mumble-voip/mumble/master/src/Mumble.proto -o app/src/main/proto/Mumble.proto
curl -fsSL https://raw.githubusercontent.com/mumble-voip/mumble/master/src/MumbleUDP.proto -o app/src/main/proto/MumbleUDP.proto
```

Immediately after the `package MumbleProto;` line in `Mumble.proto` insert:

```proto
option java_package = "com.example.drumble.mumble.proto";
option java_outer_classname = "MumbleProtos";
```

Immediately after the `package MumbleUDP;` line in `MumbleUDP.proto` insert:

```proto
option java_package = "com.example.drumble.mumble.proto";
option java_outer_classname = "MumbleUdpProtos";
```

(Wire format is unaffected by java options. Do not otherwise edit the vendored files.)

- [ ] **Step 5: Write the smoke test** `app/src/test/java/com/example/drumble/mumble/ProtoSmokeTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoSmokeTest {
    @Test fun versionRoundTrip() {
        val v = MumbleProtos.Version.newBuilder().setRelease("Drumble").build()
        assertEquals("Drumble", MumbleProtos.Version.parseFrom(v.toByteArray()).release)
    }

    @Test fun udpAudioRoundTrip() {
        val a = MumbleUdpProtos.Audio.newBuilder().setFrameNumber(42L).setTarget(31).build()
        val parsed = MumbleUdpProtos.Audio.parseFrom(a.toByteArray())
        assertEquals(42L, parsed.frameNumber)
        assertEquals(31, parsed.target)
    }
}
```

- [ ] **Step 6: Build + run test.** Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.ProtoSmokeTest"` → Expected: BUILD SUCCESSFUL.

  *Known-risk fallback (AGP 9.2 + protobuf plugin incompatibility):* if the plugin fails to register generate tasks against AGP 9's variant API, generate sources manually and commit them instead: `brew install protobuf` (or use protoc matching 4.31.x), then `mkdir -p app/src/generated/proto && protoc --java_out=lite:app/src/generated/proto -Iapp/src/main/proto app/src/main/proto/*.proto`, add `sourceSets { getByName("main") { java.srcDir("src/generated/proto") } }` inside `android {}`, remove the plugin alias, keep the `protobuf-javalite` dependency. Note which path was taken in the commit message.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/proto app/src/test/java/com/example/drumble/mumble/ProtoSmokeTest.kt
git commit -m "feat: protobuf toolchain + vendored Mumble protos + coroutines"
```

---

### Task 2: CryptState — OCB2-AES128 with known-answer tests

**Goal:** Wire-compatible OCB2-AES128 encrypt/decrypt with Mumble's IV window/replay handling and good/late/lost/resync counters, proven by ported desktop-Mumble test vectors.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/net/CryptState.kt`
- Create: `app/src/main/java/com/example/drumble/mumble/util/MumbleLog.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/CryptStateTest.kt`

**Acceptance Criteria:**
- [ ] Known-answer vectors ported from desktop Mumble's `TestCrypt.cpp` (BSD) pass
- [ ] Two mirrored CryptStates round-trip payloads of 1, 15, 16, 17, 100 bytes
- [ ] Tampered packet → decrypt returns -1, IV state restored
- [ ] Replayed packet → -1; late (out-of-order within window) packet → accepted, `late` counter increments
- [ ] 300-packet loop wraps the IV LSB twice with `good == 300`
- [ ] Zero allocation in encrypt/decrypt steady state (scratch buffers are fields)

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.CryptStateTest"` → BUILD SUCCESSFUL, all tests pass

**Steps:**

- [ ] **Step 1: Fetch the BSD reference sources** (algorithm + vectors; these are the license-clean lineage, NOT the GPL Humla copy):

```bash
mkdir -p /tmp/mumble-ref
curl -fsSL https://raw.githubusercontent.com/mumble-voip/mumble/master/src/crypto/CryptStateOCB2.cpp -o /tmp/mumble-ref/CryptStateOCB2.cpp
curl -fsSL https://raw.githubusercontent.com/mumble-voip/mumble/master/src/tests/TestCrypt/TestCrypt.cpp -o /tmp/mumble-ref/TestCrypt.cpp
```

If either path 404s (upstream reorganizes occasionally), locate them with the GitHub code search UI for `CryptStateOCB2` in `mumble-voip/mumble` and adjust.

- [ ] **Step 2: Write `util/MumbleLog.kt`** (keeps core android-free):

```kotlin
package com.example.drumble.mumble.util

/** Pluggable logger so core classes never import android.util.Log. */
object MumbleLog {
    @Volatile var sink: (tag: String, msg: String, t: Throwable?) -> Unit =
        { tag, msg, t -> println("[$tag] $msg${t?.let { " — $it" } ?: ""}") }

    fun d(tag: String, msg: String) = sink(tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = sink(tag, msg, t)
}
```

- [ ] **Step 3: Write the failing tests first** — `CryptStateTest.kt`. The KAT test bodies get their hex constants in Step 5; write the structural tests now:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.net.CryptState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CryptStateTest {
    private lateinit var a: CryptState // "client"
    private lateinit var b: CryptState // "server"

    private val key = ByteArray(16) { it.toByte() }
    private val nonceA = ByteArray(16) { (0x40 + it).toByte() }
    private val nonceB = ByteArray(16) { (0x80 + it).toByte() }

    @Before fun setUp() {
        a = CryptState().apply { setKeys(key, nonceA, nonceB) } // encrypts with nonceA
        b = CryptState().apply { setKeys(key, nonceB, nonceA) } // decrypts A's output
    }

    private fun roundTrip(size: Int) {
        val plain = ByteArray(size) { (it * 7).toByte() }
        val wire = ByteArray(size + CryptState.OVERHEAD)
        val out = ByteArray(size)
        assertEquals(size + CryptState.OVERHEAD, a.encrypt(plain, size, wire))
        assertEquals(size, b.decrypt(wire, wire.size, out))
        assertArrayEquals(plain, out)
    }

    @Test fun roundTripSizes() { intArrayOf(1, 15, 16, 17, 100).forEach { roundTrip(it) } }

    @Test fun tamperRejected() {
        val plain = ByteArray(32) { it.toByte() }
        val wire = ByteArray(36); val out = ByteArray(32)
        a.encrypt(plain, 32, wire)
        wire[10] = (wire[10].toInt() xor 0x01).toByte()
        assertEquals(-1, b.decrypt(wire, 36, out))
        // state restored: a fresh good packet still decrypts
        val wire2 = ByteArray(36)
        a.encrypt(plain, 32, wire2)
        assertEquals(32, b.decrypt(wire2, 36, out))
    }

    @Test fun replayRejectedLateAccepted() {
        val out = ByteArray(8)
        val w1 = ByteArray(12); a.encrypt(ByteArray(8) { 1 }, 8, w1)
        val w2 = ByteArray(12); a.encrypt(ByteArray(8) { 2 }, 8, w2)
        val w3 = ByteArray(12); a.encrypt(ByteArray(8) { 3 }, 8, w3)
        assertEquals(8, b.decrypt(w1, 12, out))
        assertEquals(8, b.decrypt(w3, 12, out))     // w2 skipped → lost detected
        assertEquals(8, b.decrypt(w2, 12, out))     // late but in window → accepted
        assertEquals(1, b.stats().late)
        assertEquals(-1, b.decrypt(w2, 12, out))    // replay → rejected
    }

    @Test fun ivWraparound() {
        val out = ByteArray(4)
        repeat(300) { i ->
            val w = ByteArray(8)
            a.encrypt(byteArrayOf(1, 2, 3, i.toByte()), 4, w)
            assertEquals("packet $i", 4, b.decrypt(w, 8, out))
        }
        assertEquals(300, b.stats().good)
    }

    @Test fun invalidBeforeKeys() {
        val c = CryptState()
        assertFalse(c.isValid())
        assertEquals(-1, c.decrypt(ByteArray(8), 8, ByteArray(4)))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.CryptStateTest"` → Expected: FAIL (CryptState unresolved).

- [ ] **Step 4: Implement `net/CryptState.kt`.** Follow **desktop** semantics exactly (note: Humla's replay check compares against `encryptIV[0]` — a deviation from desktop's `decryptIV[1]`; use desktop's):

```kotlin
package com.example.drumble.mumble.net

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OCB2-AES128 for Mumble's UDP channel, ported from the BSD-licensed desktop
 * Mumble CryptStateOCB2. Wire format: [ivLSB][tag0][tag1][tag2][ciphertext].
 * OCB2 has known weaknesses (Inoue et al. 2018); required for wire compat.
 * All public methods are synchronized; scratch buffers are fields (zero-alloc).
 */
class CryptState {
    companion object {
        const val BLOCK = 16
        const val OVERHEAD = 4
        private const val SHIFTBITS = 7
    }

    data class Stats(
        val good: Int, val late: Int, val lost: Int, val resync: Int,
        val remoteGood: Int, val remoteLate: Int, val remoteLost: Int, val remoteResync: Int,
    )

    private var encryptCipher: Cipher? = null
    private var decryptCipher: Cipher? = null
    private val encryptIV = ByteArray(BLOCK)
    private val decryptIV = ByteArray(BLOCK)
    private val decryptHistory = ByteArray(256)

    private var good = 0; private var late = 0; private var lost = 0; private var resync = 0
    private var remoteGood = 0; private var remoteLate = 0; private var remoteLost = 0; private var remoteResync = 0
    @Volatile private var lastGoodNanos = 0L
    @Volatile private var lastRequestNanos = 0L

    // scratch (guarded by synchronized methods)
    private val delta = ByteArray(BLOCK)
    private val tmp = ByteArray(BLOCK)
    private val pad = ByteArray(BLOCK)
    private val checksum = ByteArray(BLOCK)
    private val tag = ByteArray(BLOCK)
    private val saveIV = ByteArray(BLOCK)
    private val blockBuf = ByteArray(BLOCK)

    fun isValid(): Boolean = synchronized(this) { encryptCipher != null }

    @Synchronized fun setKeys(key: ByteArray, encryptNonce: ByteArray, decryptNonce: ByteArray) {
        val spec = SecretKeySpec(key.copyOf(BLOCK), "AES")
        encryptCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        decryptCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec) }
        encryptNonce.copyInto(encryptIV, 0, 0, BLOCK)
        decryptNonce.copyInto(decryptIV, 0, 0, BLOCK)
        decryptHistory.fill(0)
    }

    @Synchronized fun setDecryptIV(serverNonce: ByteArray) {
        serverNonce.copyInto(decryptIV, 0, 0, BLOCK); resync++
    }

    @Synchronized fun encryptNonceCopy(): ByteArray = encryptIV.copyOf()

    @Synchronized fun setRemoteStats(g: Int, l: Int, lo: Int, r: Int) {
        remoteGood = g; remoteLate = l; remoteLost = lo; remoteResync = r
    }

    @Synchronized fun stats() =
        Stats(good, late, lost, resync, remoteGood, remoteLate, remoteLost, remoteResync)

    fun lastGoodElapsedNanos() = System.nanoTime() - lastGoodNanos
    fun lastRequestElapsedNanos() = System.nanoTime() - lastRequestNanos
    fun markResyncRequested() { lastRequestNanos = System.nanoTime() }

    /** Encrypts src[0..len) into dst (size >= len+4). Returns len+4. */
    @Synchronized fun encrypt(src: ByteArray, len: Int, dst: ByteArray): Int {
        check(encryptCipher != null) { "setKeys not called" }
        for (i in 0 until BLOCK) { encryptIV[i] = (encryptIV[i] + 1).toByte(); if (encryptIV[i] != 0.toByte()) break }
        ocb(encrypt = true, input = src, inOff = 0, len = len, output = dst, outOff = OVERHEAD, nonce = encryptIV)
        dst[0] = encryptIV[0]; dst[1] = tag[0]; dst[2] = tag[1]; dst[3] = tag[2]
        return len + OVERHEAD
    }

    /** Decrypts src[0..len) into dst. Returns plaintext length or -1 on any failure. */
    @Synchronized fun decrypt(src: ByteArray, len: Int, dst: ByteArray): Int {
        if (decryptCipher == null || len < OVERHEAD + 1) return -1
        val plainLen = len - OVERHEAD
        val ivByte = src[0].toInt() and 0xFF
        var restore = false
        var lateThis = 0; var lostThis = 0
        decryptIV.copyInto(saveIV)
        val iv0 = decryptIV[0].toInt() and 0xFF

        if (((iv0 + 1) and 0xFF) == ivByte) {
            // In order.
            if (ivByte > iv0) decryptIV[0] = ivByte.toByte()
            else if (ivByte < iv0) { // wrapped
                decryptIV[0] = ivByte.toByte()
                for (i in 1 until BLOCK) { decryptIV[i] = (decryptIV[i] + 1).toByte(); if (decryptIV[i] != 0.toByte()) break }
            } else return -1
        } else {
            var diff = ivByte - iv0
            if (diff > 128) diff -= 256 else if (diff < -128) diff += 256
            when {
                ivByte < iv0 && diff > -30 && diff < 0 -> { // late, no wrap
                    lateThis = 1; lostThis = -1; decryptIV[0] = ivByte.toByte(); restore = true
                }
                ivByte > iv0 && diff > -30 && diff < 0 -> { // late, wrapped
                    lateThis = 1; lostThis = -1; decryptIV[0] = ivByte.toByte()
                    for (i in 1 until BLOCK) { val o = decryptIV[i]; decryptIV[i] = (o - 1).toByte(); if (o != 0.toByte()) break }
                    restore = true
                }
                ivByte > iv0 && diff > 0 -> { // lost some
                    lostThis = ivByte - iv0 - 1; decryptIV[0] = ivByte.toByte()
                }
                ivByte < iv0 && diff > 0 -> { // lost some, wrapped
                    lostThis = 256 - iv0 + ivByte - 1; decryptIV[0] = ivByte.toByte()
                    for (i in 1 until BLOCK) { decryptIV[i] = (decryptIV[i] + 1).toByte(); if (decryptIV[i] != 0.toByte()) break }
                }
                else -> return -1
            }
            if (decryptHistory[decryptIV[0].toInt() and 0xFF] == decryptIV[1]) { // replay
                saveIV.copyInto(decryptIV); return -1
            }
        }

        ocb(encrypt = false, input = src, inOff = OVERHEAD, len = plainLen, output = dst, outOff = 0, nonce = decryptIV)
        if (tag[0] != src[1] || tag[1] != src[2] || tag[2] != src[3]) {
            saveIV.copyInto(decryptIV); return -1
        }
        decryptHistory[decryptIV[0].toInt() and 0xFF] = decryptIV[1]
        if (restore) saveIV.copyInto(decryptIV)
        good++; late += lateThis; lost += lostThis
        lastGoodNanos = System.nanoTime()
        return plainLen
    }

    /** OCB2 core. Leaves the 16-byte auth tag in [tag]. */
    private fun ocb(encrypt: Boolean, input: ByteArray, inOff: Int, len: Int, output: ByteArray, outOff: Int, nonce: ByteArray) {
        val enc = encryptCipher!!; val dec = decryptCipher!!
        enc.doFinal(nonce, 0, BLOCK, delta, 0)
        checksum.fill(0)
        var remaining = len; var io = inOff; var oo = outOff
        while (remaining > BLOCK) {
            s2(delta)
            if (encrypt) {
                for (i in 0 until BLOCK) { blockBuf[i] = input[io + i]; checksum[i] = (checksum[i].toInt() xor blockBuf[i].toInt()).toByte() }
                for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor blockBuf[i].toInt()).toByte()
                enc.doFinal(tmp, 0, BLOCK, tmp, 0)
                for (i in 0 until BLOCK) output[oo + i] = (delta[i].toInt() xor tmp[i].toInt()).toByte()
            } else {
                for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor input[io + i].toInt()).toByte()
                dec.doFinal(tmp, 0, BLOCK, tmp, 0)
                for (i in 0 until BLOCK) {
                    val p = (delta[i].toInt() xor tmp[i].toInt()).toByte()
                    output[oo + i] = p; checksum[i] = (checksum[i].toInt() xor p.toInt()).toByte()
                }
            }
            remaining -= BLOCK; io += BLOCK; oo += BLOCK
        }
        // final (possibly full) block
        s2(delta)
        tmp.fill(0)
        val numBits = remaining * 8
        tmp[BLOCK - 2] = ((numBits shr 8) and 0xFF).toByte()
        tmp[BLOCK - 1] = (numBits and 0xFF).toByte()
        for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor delta[i].toInt()).toByte()
        enc.doFinal(tmp, 0, BLOCK, pad, 0)
        tmp.fill(0)
        if (encrypt) {
            input.copyInto(tmp, 0, io, io + remaining)
            pad.copyInto(tmp, remaining, remaining, BLOCK)
            for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor tmp[i].toInt()).toByte()
            for (i in 0 until BLOCK) tmp[i] = (pad[i].toInt() xor tmp[i].toInt()).toByte()
            tmp.copyInto(output, oo, 0, remaining)
        } else {
            input.copyInto(tmp, 0, io, io + remaining)
            for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor pad[i].toInt()).toByte()
            for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor tmp[i].toInt()).toByte()
            tmp.copyInto(output, oo, 0, remaining)
        }
        s3(delta)
        for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor checksum[i].toInt()).toByte()
        enc.doFinal(tmp, 0, BLOCK, tag, 0)
    }

    private fun s2(block: ByteArray) {
        val carry = (block[0].toInt() shr SHIFTBITS) and 0x1
        for (i in 0 until BLOCK - 1)
            block[i] = (((block[i].toInt() shl 1) or ((block[i + 1].toInt() shr SHIFTBITS) and 0x1)) and 0xFF).toByte()
        block[BLOCK - 1] = (((block[BLOCK - 1].toInt() shl 1) xor (carry * 0x87)) and 0xFF).toByte()
    }

    private fun s3(block: ByteArray) {
        val carry = (block[0].toInt() shr SHIFTBITS) and 0x1
        for (i in 0 until BLOCK - 1)
            block[i] = ((block[i].toInt() xor ((block[i].toInt() shl 1) or ((block[i + 1].toInt() shr SHIFTBITS) and 0x1))) and 0xFF).toByte()
        block[BLOCK - 1] = ((block[BLOCK - 1].toInt() xor ((block[BLOCK - 1].toInt() shl 1) xor (carry * 0x87))) and 0xFF).toByte()
    }
}
```

*Byte-arithmetic caution:* `Byte.toInt()` sign-extends. `s2`/`s3` mask with `and 0xFF` after shifting; the decrypt-window comparisons always compare `and 0xFF`-masked ints. The desktop C++ operates on `unsigned char` — when in doubt during KAT debugging, diff against `/tmp/mumble-ref/CryptStateOCB2.cpp` line by line.

- [ ] **Step 5: Port the known-answer vectors.** Open `/tmp/mumble-ref/TestCrypt.cpp`, find the OCB vector tests (`testvectors` / `authcrypt` / `xexstar` test functions). Port each as a Kotlin test into `CryptStateTest.kt` using a `fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()` helper — key, nonce, plaintext, expected ciphertext, expected tag copied verbatim from the C++ arrays. To drive OCB with an exact nonce (bypassing the IV auto-increment), add an `internal fun ocbEncryptRaw(plain: ByteArray, len: Int, nonce: ByteArray, cipherOut: ByteArray, tagOut: ByteArray)` wrapper on CryptState that calls `ocb(...)` directly and copies `tag` into `tagOut` (internal visibility, test-only use).

- [ ] **Step 6: Port desktop's XEX* attack mitigation.** In `/tmp/mumble-ref/CryptStateOCB2.cpp`, locate the `modifyPlainOnXEXStarAttack` logic in `ocb_encrypt` (flips the low bit of a crafted final plaintext block to break the Inoue forgery precondition) and the corresponding `success &= ...` check in `ocb_decrypt`. Mirror both faithfully in `ocb(...)`, matching the upstream conditions exactly, and add one test reproducing the crafted-block case from `TestCrypt.cpp` if present.

- [ ] **Step 7: Run all tests.** Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.CryptStateTest"` → Expected: PASS (all).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/net/CryptState.kt app/src/main/java/com/example/drumble/mumble/util/MumbleLog.kt app/src/test/java/com/example/drumble/mumble/CryptStateTest.kt
git commit -m "feat: OCB2-AES128 CryptState with KAT + window/replay tests"
```

---

### Task 3: MumbleCodec — TCP framing + UDP plaintext format

**Goal:** Encode/decode the TCP control framing `[u16 type][u32 len][protobuf]` (big-endian) and the new-protocol UDP plaintext `[u8 type][protobuf]`, with the message-type registry.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/protocol/MumbleCodec.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/MumbleCodecTest.kt`

**Acceptance Criteria:**
- [ ] TCP frame round-trips through streams; oversized/negative length rejected
- [ ] UDP plaintext writes into a caller-supplied buffer (no wire-buffer allocation per packet)
- [ ] All 26 TCP message type IDs mapped

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.MumbleCodecTest"` → PASS

**Steps:**

- [ ] **Step 1: Write failing tests** — `MumbleCodecTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.proto.MumbleUdpProtos
import com.example.drumble.mumble.protocol.MumbleCodec
import com.example.drumble.mumble.protocol.TcpMessageType
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class MumbleCodecTest {
    @Test fun tcpFrameRoundTrip() {
        val ping = MumbleProtos.Ping.newBuilder().setTimestamp(123L).build()
        val bos = ByteArrayOutputStream()
        MumbleCodec.writeFrame(DataOutputStream(bos), TcpMessageType.Ping.id, ping.toByteArray())
        val frame = MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
        assertEquals(TcpMessageType.Ping.id, frame.type)
        assertEquals(123L, MumbleProtos.Ping.parseFrom(frame.payload).timestamp)
    }

    @Test fun tcpFrameWireLayout() { // [u16 type][u32 len] big-endian
        val bos = ByteArrayOutputStream()
        MumbleCodec.writeFrame(DataOutputStream(bos), 5, byteArrayOf(0x7F))
        val b = bos.toByteArray()
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 0, 1, 0x7F), b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFrameRejected() {
        val header = byteArrayOf(0, 3, 0x7F, -1, -1, -1) // len = 0x7FFFFFFF
        MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(header)))
    }

    @Test fun udpPlaintextRoundTrip() {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setTarget(31).setFrameNumber(7L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(ByteArray(40) { it.toByte() }))
            .build()
        val buf = ByteArray(1024)
        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, buf)
        assertEquals(MumbleCodec.UDP_TYPE_AUDIO, buf[0].toInt())
        val parsed = MumbleUdpProtos.Audio.parser().parseFrom(buf, 1, n - 1)
        assertEquals(7L, parsed.frameNumber)
        assertEquals(31, parsed.target)
    }

    @Test fun typeRegistry() {
        assertEquals(TcpMessageType.Version, TcpMessageType.from(0))
        assertEquals(TcpMessageType.UDPTunnel, TcpMessageType.from(1))
        assertEquals(TcpMessageType.CryptSetup, TcpMessageType.from(15))
        assertEquals(TcpMessageType.PluginDataTransmission, TcpMessageType.from(26))
        assertNull(TcpMessageType.from(99))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.MumbleCodecTest"` → Expected: FAIL (unresolved references).

- [ ] **Step 2: Implement `protocol/MumbleCodec.kt`:**

```kotlin
package com.example.drumble.mumble.protocol

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import java.io.DataInputStream
import java.io.DataOutputStream

/** TCP control-channel message types (stable protocol IDs). */
enum class TcpMessageType(val id: Int) {
    Version(0), UDPTunnel(1), Authenticate(2), Ping(3), Reject(4), ServerSync(5),
    ChannelRemove(6), ChannelState(7), UserRemove(8), UserState(9), BanList(10),
    TextMessage(11), PermissionDenied(12), ACL(13), QueryUsers(14), CryptSetup(15),
    ContextActionModify(16), ContextAction(17), UserList(18), VoiceTarget(19),
    PermissionQuery(20), CodecVersion(21), UserStats(22), RequestBlob(23),
    ServerConfig(24), SuggestConfig(25), PluginDataTransmission(26);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun from(id: Int): TcpMessageType? = byId[id]
    }
}

class TcpFrame(val type: Int, val payload: ByteArray)

object MumbleCodec {
    /** New-protocol UDP plaintext type bytes (verify against upstream during integration). */
    const val UDP_TYPE_AUDIO = 0
    const val UDP_TYPE_PING = 1

    /** Sanity bound for inbound control frames (server messages are far smaller). */
    const val MAX_TCP_PAYLOAD = 8 * 1024 * 1024

    fun writeFrame(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeShort(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readFrame(inp: DataInputStream): TcpFrame {
        val type = inp.readUnsignedShort()
        val len = inp.readInt()
        require(len in 0..MAX_TCP_PAYLOAD) { "bad frame length $len" }
        val buf = ByteArray(len)
        inp.readFully(buf)
        return TcpFrame(type, buf)
    }

    /** Serializes [u8 type][protobuf] into dst without allocating a wire buffer. Returns bytes written. */
    fun writeUdpPlaintext(type: Int, message: MessageLite, dst: ByteArray): Int {
        val size = message.serializedSize
        require(dst.size >= size + 1) { "buffer too small: need ${size + 1}" }
        dst[0] = type.toByte()
        val cos = CodedOutputStream.newInstance(dst, 1, size)
        message.writeTo(cos)
        cos.checkNoSpaceLeft()
        return size + 1
    }
}
```

- [ ] **Step 3: Run tests.** Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.MumbleCodecTest"` → Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/protocol/MumbleCodec.kt app/src/test/java/com/example/drumble/mumble/MumbleCodecTest.kt
git commit -m "feat: TCP framing + UDP plaintext codec with type registry"
```

---

### Task 4: Model — channel tree, user map, reducers

**Goal:** Immutable `ServerModel` snapshots built by pure reducers over `ChannelState`/`UserState`/`UserRemove`/`ChannelRemove`/`ServerSync`, exposed via `StateFlow`.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/model/MumbleModel.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/MumbleModelTest.kt`

**Acceptance Criteria:**
- [ ] Partial `ChannelState`/`UserState` updates preserve unset fields (proto2 `has*` semantics)
- [ ] User add / move-channel / remove and channel add / remove reflected in snapshots
- [ ] `ServerSync` records our session id and max bandwidth

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.MumbleModelTest"` → PASS

**Steps:**

- [ ] **Step 1: Write failing tests** — `MumbleModelTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.model.*
import com.example.drumble.mumble.proto.MumbleProtos
import org.junit.Assert.*
import org.junit.Test

class MumbleModelTest {
    private fun channelState(id: Int, name: String? = null, parent: Int? = null): MumbleProtos.ChannelState {
        val b = MumbleProtos.ChannelState.newBuilder().setChannelId(id)
        name?.let { b.setName(it) }; parent?.let { b.setParent(it) }
        return b.build()
    }
    private fun userState(session: Int, name: String? = null, channel: Int? = null): MumbleProtos.UserState {
        val b = MumbleProtos.UserState.newBuilder().setSession(session)
        name?.let { b.setName(it) }; channel?.let { b.setChannelId(it) }
        return b.build()
    }

    @Test fun channelTreeAndPartialUpdate() {
        var m = ServerModel()
        m = ModelReducers.applyChannelState(m, channelState(0, name = "Root"))
        m = ModelReducers.applyChannelState(m, channelState(1, name = "Lobby", parent = 0))
        m = ModelReducers.applyChannelState(m, channelState(1, parent = 0)) // no name → preserved
        assertEquals("Lobby", m.channels[1]!!.name)
        assertEquals(0, m.channels[1]!!.parentId)
        m = ModelReducers.applyChannelRemove(m, MumbleProtos.ChannelRemove.newBuilder().setChannelId(1).build())
        assertNull(m.channels[1])
    }

    @Test fun userLifecycle() {
        var m = ServerModel()
        m = ModelReducers.applyUserState(m, userState(42, name = "dan"))
        assertEquals(0, m.users[42]!!.channelId) // joins root by default
        m = ModelReducers.applyUserState(m, userState(42, channel = 3))
        assertEquals("dan", m.users[42]!!.name)  // preserved
        assertEquals(3, m.users[42]!!.channelId)
        m = ModelReducers.applyUserRemove(m, MumbleProtos.UserRemove.newBuilder().setSession(42).build())
        assertNull(m.users[42])
    }

    @Test fun serverSync() {
        var m = ServerModel()
        val sync = MumbleProtos.ServerSync.newBuilder().setSession(7).setMaxBandwidth(72000).build()
        m = ModelReducers.applyServerSync(m, sync)
        assertEquals(7, m.sessionId)
        assertEquals(72000, m.maxBandwidth)
    }

    @Test fun holderEmitsSnapshots() {
        val holder = MumbleModel()
        holder.apply { onChannelState(channelState(1, name = "A", parent = 0)) }
        assertEquals("A", holder.state.value.channels[1]!!.name)
        holder.reset()
        assertTrue(holder.state.value.channels.isEmpty())
    }
}
```

Run → Expected: FAIL (unresolved references).

- [ ] **Step 2: Implement `model/MumbleModel.kt`:**

```kotlin
package com.example.drumble.mumble.model

import com.example.drumble.mumble.proto.MumbleProtos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MumbleChannel(
    val id: Int,
    val parentId: Int?,
    val name: String,
    val position: Int = 0,
    val temporary: Boolean = false,
)

data class MumbleUser(
    val session: Int,
    val name: String,
    val channelId: Int = 0,
    val mute: Boolean = false,
    val deaf: Boolean = false,
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    val suppress: Boolean = false,
)

data class ServerModel(
    val channels: Map<Int, MumbleChannel> = emptyMap(),
    val users: Map<Int, MumbleUser> = emptyMap(),
    val sessionId: Int? = null,
    val maxBandwidth: Int? = null,
    val welcomeText: String? = null,
)

/** Pure reducers: proto2 has-bits decide field-by-field whether to overwrite. */
object ModelReducers {
    fun applyChannelState(m: ServerModel, msg: MumbleProtos.ChannelState): ServerModel {
        val old = m.channels[msg.channelId]
        val ch = MumbleChannel(
            id = msg.channelId,
            parentId = if (msg.hasParent()) msg.parent else old?.parentId,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            position = if (msg.hasPosition()) msg.position else old?.position ?: 0,
            temporary = if (msg.hasTemporary()) msg.temporary else old?.temporary ?: false,
        )
        return m.copy(channels = m.channels + (ch.id to ch))
    }

    fun applyChannelRemove(m: ServerModel, msg: MumbleProtos.ChannelRemove): ServerModel =
        m.copy(channels = m.channels - msg.channelId)

    fun applyUserState(m: ServerModel, msg: MumbleProtos.UserState): ServerModel {
        val old = m.users[msg.session]
        val u = MumbleUser(
            session = msg.session,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            channelId = if (msg.hasChannelId()) msg.channelId else old?.channelId ?: 0,
            mute = if (msg.hasMute()) msg.mute else old?.mute ?: false,
            deaf = if (msg.hasDeaf()) msg.deaf else old?.deaf ?: false,
            selfMute = if (msg.hasSelfMute()) msg.selfMute else old?.selfMute ?: false,
            selfDeaf = if (msg.hasSelfDeaf()) msg.selfDeaf else old?.selfDeaf ?: false,
            suppress = if (msg.hasSuppress()) msg.suppress else old?.suppress ?: false,
        )
        return m.copy(users = m.users + (u.session to u))
    }

    fun applyUserRemove(m: ServerModel, msg: MumbleProtos.UserRemove): ServerModel =
        m.copy(users = m.users - msg.session)

    fun applyServerSync(m: ServerModel, msg: MumbleProtos.ServerSync): ServerModel = m.copy(
        sessionId = if (msg.hasSession()) msg.session else m.sessionId,
        maxBandwidth = if (msg.hasMaxBandwidth()) msg.maxBandwidth else m.maxBandwidth,
        welcomeText = if (msg.hasWelcomeText()) msg.welcomeText else m.welcomeText,
    )
}

/** Mutable holder exposing immutable snapshots. Single-writer (the session dispatcher). */
class MumbleModel {
    private val _state = MutableStateFlow(ServerModel())
    val state: StateFlow<ServerModel> = _state.asStateFlow()

    fun onChannelState(msg: MumbleProtos.ChannelState) { _state.value = ModelReducers.applyChannelState(_state.value, msg) }
    fun onChannelRemove(msg: MumbleProtos.ChannelRemove) { _state.value = ModelReducers.applyChannelRemove(_state.value, msg) }
    fun onUserState(msg: MumbleProtos.UserState) { _state.value = ModelReducers.applyUserState(_state.value, msg) }
    fun onUserRemove(msg: MumbleProtos.UserRemove) { _state.value = ModelReducers.applyUserRemove(_state.value, msg) }
    fun onServerSync(msg: MumbleProtos.ServerSync) { _state.value = ModelReducers.applyServerSync(_state.value, msg) }
    fun reset() { _state.value = ServerModel() }
}
```

- [ ] **Step 3: Run tests.** Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.MumbleModelTest"` → Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/model/MumbleModel.kt app/src/test/java/com/example/drumble/mumble/MumbleModelTest.kt
git commit -m "feat: server model with pure reducers and StateFlow snapshots"
```

---

### Task 5: MumbleTcpTransport — TLS + TOFU pinning, reader/writer loops

**Goal:** TLS control-channel transport: TOFU cert pinning, blocking reader loop delivering `TcpFrame`s, writer coroutine draining a send queue; loop logic unit-testable via injected streams.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/net/PinStore.kt`
- Create: `app/src/main/java/com/example/drumble/mumble/net/MumbleTcpTransport.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/TofuTrustTest.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/MumbleTcpTransportTest.kt`

**Acceptance Criteria:**
- [ ] TOFU: first-use stores fingerprint; match passes; mismatch throws `CertificateException`
- [ ] Reader delivers inbound frames to listener; writer emits correctly framed bytes; `close()` idempotent
- [ ] No Android imports (JVM-testable)

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.TofuTrustTest" --tests "com.example.drumble.mumble.MumbleTcpTransportTest"` → PASS

**Steps:**

- [ ] **Step 1: Write failing tests.** `TofuTrustTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.net.*
import org.junit.Assert.*
import org.junit.Test

class TofuTrustTest {
    @Test fun firstUseStoresThenMatches() {
        val store = InMemoryPinStore()
        val v = TofuVerifier(store, "h:64738")
        val cert = ByteArray(64) { it.toByte() }
        assertTrue(v.verify(cert) is PinResult.FirstUse)
        assertTrue(v.verify(cert) is PinResult.Match)
    }

    @Test fun mismatchDetected() {
        val store = InMemoryPinStore()
        val v = TofuVerifier(store, "h:64738")
        v.verify(ByteArray(64) { it.toByte() })
        val r = v.verify(ByteArray(64) { (it + 1).toByte() })
        assertTrue(r is PinResult.Mismatch)
    }

    @Test fun distinctKeysIndependent() {
        val store = InMemoryPinStore()
        TofuVerifier(store, "a:1").verify(ByteArray(4) { 1 })
        assertTrue(TofuVerifier(store, "b:2").verify(ByteArray(4) { 2 }) is PinResult.FirstUse)
    }
}
```

`MumbleTcpTransportTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.net.*
import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MumbleTcpTransportTest {
    @Test fun readerDeliversAndWriterFrames() {
        val transport = MumbleTcpTransport(InMemoryPinStore())
        val inboundPipe = PipedOutputStream()
        val input = DataInputStream(PipedInputStream(inboundPipe, 64 * 1024))
        val outBytes = ByteArrayOutputStream()
        val received = LinkedBlockingQueue<TcpFrame>()
        transport.startLoops(input, DataOutputStream(outBytes), object : MumbleTcpTransport.Listener {
            override fun onFrame(frame: TcpFrame) { received.add(frame) }
            override fun onClosed(cause: Throwable?) {}
        })
        // inbound: server → client
        val ping = MumbleProtos.Ping.newBuilder().setTimestamp(9L).build()
        MumbleCodec.writeFrame(DataOutputStream(inboundPipe), TcpMessageType.Ping.id, ping.toByteArray())
        val f = received.poll(2, TimeUnit.SECONDS)
        assertNotNull(f); assertEquals(TcpMessageType.Ping.id, f!!.type)
        assertEquals(9L, MumbleProtos.Ping.parseFrom(f.payload).timestamp)
        // outbound: client → server
        assertTrue(transport.send(TcpMessageType.Version, MumbleProtos.Version.newBuilder().setRelease("t").build()))
        val deadline = System.currentTimeMillis() + 2000
        while (outBytes.size() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val out = MumbleCodec.readFrame(DataInputStream(ByteArrayInputStream(outBytes.toByteArray())))
        assertEquals(TcpMessageType.Version.id, out.type)
        transport.close(); transport.close() // idempotent
    }
}
```

Run → Expected: FAIL (unresolved references).

- [ ] **Step 2: Implement `net/PinStore.kt`:**

```kotlin
package com.example.drumble.mumble.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

interface PinStore {
    fun get(key: String): String?
    fun put(key: String, fingerprint: String)
}

class InMemoryPinStore : PinStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, fingerprint: String) { map[key] = fingerprint }
}

sealed class PinResult {
    object FirstUse : PinResult()
    object Match : PinResult()
    data class Mismatch(val stored: String, val presented: String) : PinResult()
}

class TofuVerifier(private val store: PinStore, private val key: String) {
    fun verify(encodedCert: ByteArray): PinResult {
        val fp = MessageDigest.getInstance("SHA-256").digest(encodedCert)
            .joinToString("") { "%02x".format(it) }
        return when (val stored = store.get(key)) {
            null -> { store.put(key, fp); PinResult.FirstUse }
            fp -> PinResult.Match
            else -> PinResult.Mismatch(stored, fp)
        }
    }
}

/** INSECURE-FOR-DEV: trust-on-first-use pinning, no CA validation. Must be replaced before real-world use. */
class TofuTrustManager(store: PinStore, key: String) : X509TrustManager {
    private val verifier = TofuVerifier(store, key)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
        throw CertificateException("client auth not supported")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val r = verifier.verify(chain[0].encoded)
        if (r is PinResult.Mismatch)
            throw CertificateException("TOFU pin mismatch: stored=${r.stored} presented=${r.presented}")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
```

- [ ] **Step 3: Implement `net/MumbleTcpTransport.kt`:**

```kotlin
package com.example.drumble.mumble.net

import com.example.drumble.mumble.protocol.MumbleCodec
import com.example.drumble.mumble.protocol.TcpFrame
import com.example.drumble.mumble.protocol.TcpMessageType
import com.example.drumble.mumble.util.MumbleLog
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class MumbleTcpTransport(private val pinStore: PinStore) {
    companion object {
        private const val TAG = "MumbleTcpTransport"
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    interface Listener {
        /** Called on the reader coroutine. */
        fun onFrame(frame: TcpFrame)
        /** cause == null for local close, non-null for remote/error close. Called at most once. */
        fun onClosed(cause: Throwable?)
    }

    private val closed = AtomicBoolean(false)
    private val closedReported = AtomicBoolean(false)
    private var socket: SSLSocket? = null
    private val sendQueue = Channel<ByteArray>(capacity = 256)
    private var scope: CoroutineScope? = null

    /** Blocking TLS connect + handshake, then starts reader/writer loops. Throws on failure. */
    suspend fun connect(host: String, port: Int, listener: Listener) = withContext(Dispatchers.IO) {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(TofuTrustManager(pinStore, "$host:$port")), null)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.startHandshake()
        socket = s
        startLoops(
            DataInputStream(s.inputStream.buffered()),
            DataOutputStream(s.outputStream.buffered()),
            listener,
        )
    }

    /** Split out for JVM tests: drive with piped streams, no TLS needed. */
    internal fun startLoops(input: DataInputStream, output: DataOutputStream, listener: Listener) {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch(CoroutineName("mumble-tcp-read")) {
            try {
                while (isActive) listener.onFrame(MumbleCodec.readFrame(input))
            } catch (t: Throwable) {
                val local = closed.get()
                if (closedReported.compareAndSet(false, true))
                    listener.onClosed(if (local) null else t)
                close()
            }
        }
        sc.launch(CoroutineName("mumble-tcp-write")) {
            try {
                for (framed in sendQueue) { output.write(framed); output.flush() }
            } catch (t: Throwable) {
                MumbleLog.w(TAG, "writer stopped", t)
            }
        }
    }

    /** Thread-safe, non-blocking. False if the queue is full or transport closed. */
    fun send(type: TcpMessageType, message: MessageLite): Boolean {
        if (closed.get()) return false
        val bos = ByteArrayOutputStream(6 + message.serializedSize)
        MumbleCodec.writeFrame(DataOutputStream(bos), type.id, message.toByteArray())
        return sendQueue.trySend(bos.toByteArray()).isSuccess
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            sendQueue.close()
            runCatching { socket?.close() }
            scope?.cancel()
        }
    }
}
```

- [ ] **Step 4: Run tests.** Run: the Verify command above → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/net/PinStore.kt app/src/main/java/com/example/drumble/mumble/net/MumbleTcpTransport.kt app/src/test/java/com/example/drumble/mumble/TofuTrustTest.kt app/src/test/java/com/example/drumble/mumble/MumbleTcpTransportTest.kt
git commit -m "feat: TLS transport with TOFU pinning and framed reader/writer loops"
```

---

### Task 6: SessionStateMachine — handshake to Synchronized, pings, CryptSetup

**Goal:** Drive `Version`+`Authenticate` → `CryptSetup` → `ServerSync`, expose `ConnectionState` as StateFlow, handle TCP pings (RTT + crypt stats exchange), CryptSetup resync, and route `UDPTunnel` payloads — all against a fake channel in tests.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/protocol/SessionStateMachine.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/SessionStateMachineTest.kt`

**Acceptance Criteria:**
- [ ] `start()` sends Version (v1 + v2 encodings, release "Drumble") then Authenticate (username, opus=true, password if set)
- [ ] Full `CryptSetup` initializes CryptState and fires `onCryptReady`; server-nonce-only updates decrypt IV (resync count++); empty CryptSetup answered with our client nonce
- [ ] `ServerSync` → `Synchronized(sessionId)`; server version < 1.5 → `Failed(VERSION_TOO_OLD)`; `Reject` → `Failed(AUTH_REJECT)`
- [ ] TCP `Ping` echo yields RTT callback and records remote crypt stats
- [ ] `ChannelState`/`UserState`/removes forwarded to the model

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.SessionStateMachineTest"` → PASS

**Steps:**

- [ ] **Step 1: Write failing tests** — `SessionStateMachineTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.model.MumbleModel
import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionStateMachineTest {
    private class FakeChannel : ControlChannel {
        val sent = mutableListOf<Pair<TcpMessageType, MessageLite>>()
        var closedCount = 0
        override fun send(type: TcpMessageType, message: MessageLite): Boolean { sent.add(type to message); return true }
        override fun close() { closedCount++ }
    }

    private class RecordingEvents : SessionStateMachine.Events {
        var cryptReady = 0
        var lastRttMs = -1.0
        val tunneled = mutableListOf<ByteArray>()
        override fun onCryptReady() { cryptReady++ }
        override fun onTcpRtt(rttMs: Double) { lastRttMs = rttMs }
        override fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long) {
            tunneled.add(plaintext.copyOf(len))
        }
    }

    private lateinit var channel: FakeChannel
    private lateinit var events: RecordingEvents
    private lateinit var crypt: CryptState
    private lateinit var model: MumbleModel
    private lateinit var sm: SessionStateMachine
    private var nowNanos = 1_000_000_000L

    private val key = ByteArray(16) { it.toByte() }
    private val nA = ByteArray(16) { (0x40 + it).toByte() }
    private val nB = ByteArray(16) { (0x80 + it).toByte() }

    @Before fun setUp() {
        channel = FakeChannel(); events = RecordingEvents()
        crypt = CryptState(); model = MumbleModel()
        sm = SessionStateMachine(channel, model, crypt, events, clockNanos = { nowNanos })
    }

    private fun frame(type: TcpMessageType, msg: MessageLite) = sm.onFrame(TcpFrame(type.id, msg.toByteArray()))

    private fun fullCryptSetup() = MumbleProtos.CryptSetup.newBuilder()
        .setKey(ByteString.copyFrom(key))
        .setClientNonce(ByteString.copyFrom(nA))
        .setServerNonce(ByteString.copyFrom(nB)).build()

    @Test fun happyPathToSynchronized() {
        sm.start(username = "dan", password = null)
        assertEquals(TcpMessageType.Version, channel.sent[0].first)
        assertEquals(TcpMessageType.Authenticate, channel.sent[1].first)
        val auth = channel.sent[1].second as MumbleProtos.Authenticate
        assertEquals("dan", auth.username); assertTrue(auth.opus)

        frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 5, 634)).build())
        frame(TcpMessageType.CryptSetup, fullCryptSetup())
        assertEquals(1, events.cryptReady); assertTrue(crypt.isValid())

        frame(TcpMessageType.ChannelState, MumbleProtos.ChannelState.newBuilder().setChannelId(0).setName("Root").build())
        frame(TcpMessageType.UserState, MumbleProtos.UserState.newBuilder().setSession(7).setName("dan").build())
        frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(7).build())

        assertEquals(ConnectionState.Synchronized(7), sm.state.value)
        assertEquals("Root", model.state.value.channels[0]!!.name)
        assertEquals(7, model.state.value.sessionId)
    }

    @Test fun versionTooOldFails() {
        sm.start("dan", null)
        frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 4, 287)).build())
        val s = sm.state.value
        assertTrue(s is ConnectionState.Failed && s.reason == FailReason.VERSION_TOO_OLD)
        assertEquals(1, channel.closedCount)
    }

    @Test fun rejectFails() {
        sm.start("dan", null)
        frame(TcpMessageType.Reject, MumbleProtos.Reject.newBuilder().setReason("bad pw").build())
        val s = sm.state.value
        assertTrue(s is ConnectionState.Failed && s.reason == FailReason.AUTH_REJECT)
    }

    @Test fun cryptResyncFlows() {
        sm.start("dan", null)
        frame(TcpMessageType.CryptSetup, fullCryptSetup())
        // server-nonce-only → decrypt IV updated, resync counted
        frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
            .setServerNonce(ByteString.copyFrom(nB)).build())
        assertEquals(1, crypt.stats().resync)
        // empty CryptSetup → we reply with our client nonce
        val sentBefore = channel.sent.size
        frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder().build())
        val reply = channel.sent.drop(sentBefore).single()
        assertEquals(TcpMessageType.CryptSetup, reply.first)
        assertArrayEquals(crypt.encryptNonceCopy(), (reply.second as MumbleProtos.CryptSetup).clientNonce.toByteArray())
    }

    @Test fun pingEchoYieldsRttAndRemoteStats() {
        sm.start("dan", null)
        nowNanos = 5_000_000_000L
        sm.sendPing() // captures timestamp = 5e9
        nowNanos = 5_020_000_000L // +20ms
        frame(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder()
            .setTimestamp(5_000_000_000L).setGood(10).setLate(1).setLost(2).setResync(0).build())
        assertEquals(20.0, events.lastRttMs, 0.5)
        assertEquals(10, crypt.stats().remoteGood)
    }

    @Test fun udpTunnelRouted() {
        sm.start("dan", null)
        val payload = byteArrayOf(0, 1, 2, 3)
        sm.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, payload))
        assertArrayEquals(payload, events.tunneled.single())
    }
}
```

Run → Expected: FAIL (unresolved references).

- [ ] **Step 2: Implement `protocol/SessionStateMachine.kt`:**

```kotlin
package com.example.drumble.mumble.protocol

import com.example.drumble.mumble.model.MumbleModel
import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.util.MumbleLog
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Abstraction over MumbleTcpTransport so the state machine tests with a fake. */
interface ControlChannel {
    fun send(type: TcpMessageType, message: MessageLite): Boolean
    fun close()
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Handshaking : ConnectionState()
    data class Synchronized(val sessionId: Int) : ConnectionState()
    data class Failed(val reason: FailReason, val detail: String? = null, val cause: Throwable? = null) : ConnectionState()
}

enum class FailReason { DNS, TLS, PIN_MISMATCH, AUTH_REJECT, VERSION_TOO_OLD, TIMEOUT, IO }

object MumbleVersion {
    fun encodeV2(major: Int, minor: Int, patch: Int): Long =
        (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)
    fun encodeV1(major: Int, minor: Int, patch: Int): Int =
        (major shl 16) or (minor shl 8) or patch
    fun majorOf(v2: Long): Int = (v2 ushr 48).toInt()
    fun minorOf(v2: Long): Int = ((v2 ushr 32) and 0xFFFF).toInt()
}

class SessionStateMachine(
    private val channel: ControlChannel,
    private val model: MumbleModel,
    private val crypt: CryptState,
    private val events: Events,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    companion object {
        private const val TAG = "SessionStateMachine"
        const val CLIENT_MAJOR = 1; const val CLIENT_MINOR = 5; const val CLIENT_PATCH = 0
        const val PING_INTERVAL_MS = 5_000L
    }

    interface Events {
        /** Full CryptSetup received — UDP may start. Called on the reader coroutine. */
        fun onCryptReady()
        /** TCP ping echo measured. */
        fun onTcpRtt(rttMs: Double)
        /** UDPTunnel payload = new-protocol UDP plaintext [u8 type][protobuf]. */
        fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long)
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var lastPingSentNanos = 0L

    fun start(username: String, password: String?) {
        _state.value = ConnectionState.Handshaking
        val version = MumbleProtos.Version.newBuilder()
            .setVersionV1(MumbleVersion.encodeV1(CLIENT_MAJOR, CLIENT_MINOR, CLIENT_PATCH))
            .setVersionV2(MumbleVersion.encodeV2(CLIENT_MAJOR, CLIENT_MINOR, CLIENT_PATCH))
            .setRelease("Drumble").setOs("Android").build()
        channel.send(TcpMessageType.Version, version)
        val auth = MumbleProtos.Authenticate.newBuilder()
            .setUsername(username).setOpus(true)
            .apply { password?.let { setPassword(it) } }.build()
        channel.send(TcpMessageType.Authenticate, auth)
    }

    /** Called from the transport reader coroutine (single-threaded). */
    fun onFrame(frame: TcpFrame) {
        when (TcpMessageType.from(frame.type)) {
            TcpMessageType.Version -> handleVersion(MumbleProtos.Version.parseFrom(frame.payload))
            TcpMessageType.Reject -> {
                val r = MumbleProtos.Reject.parseFrom(frame.payload)
                fail(FailReason.AUTH_REJECT, r.reason)
            }
            TcpMessageType.CryptSetup -> handleCryptSetup(MumbleProtos.CryptSetup.parseFrom(frame.payload))
            TcpMessageType.ServerSync -> {
                val sync = MumbleProtos.ServerSync.parseFrom(frame.payload)
                model.onServerSync(sync)
                _state.value = ConnectionState.Synchronized(sync.session)
            }
            TcpMessageType.ChannelState -> model.onChannelState(MumbleProtos.ChannelState.parseFrom(frame.payload))
            TcpMessageType.ChannelRemove -> model.onChannelRemove(MumbleProtos.ChannelRemove.parseFrom(frame.payload))
            TcpMessageType.UserState -> model.onUserState(MumbleProtos.UserState.parseFrom(frame.payload))
            TcpMessageType.UserRemove -> model.onUserRemove(MumbleProtos.UserRemove.parseFrom(frame.payload))
            TcpMessageType.Ping -> handlePingEcho(MumbleProtos.Ping.parseFrom(frame.payload))
            TcpMessageType.UDPTunnel -> events.onTunneledVoice(frame.payload, frame.payload.size, clockNanos())
            else -> MumbleLog.d(TAG, "ignoring message type ${frame.type}")
        }
    }

    private fun handleVersion(v: MumbleProtos.Version) {
        val v2 = if (v.hasVersionV2()) v.versionV2 else {
            // derive from legacy v1 encoding (major<<16|minor<<8|patch)
            val v1 = v.versionV1
            MumbleVersion.encodeV2((v1 shr 16) and 0xFFFF, (v1 shr 8) and 0xFF, v1 and 0xFF)
        }
        val major = MumbleVersion.majorOf(v2); val minor = MumbleVersion.minorOf(v2)
        if (major < 1 || (major == 1 && minor < 5)) {
            fail(FailReason.VERSION_TOO_OLD, "server $major.$minor — need >= 1.5 (new UDP protocol)")
        }
    }

    private fun handleCryptSetup(cs: MumbleProtos.CryptSetup) {
        when {
            cs.hasKey() && cs.hasClientNonce() && cs.hasServerNonce() -> {
                crypt.setKeys(cs.key.toByteArray(), cs.clientNonce.toByteArray(), cs.serverNonce.toByteArray())
                events.onCryptReady()
            }
            cs.hasServerNonce() -> crypt.setDecryptIV(cs.serverNonce.toByteArray())
            else -> channel.send(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
                .setClientNonce(ByteString.copyFrom(crypt.encryptNonceCopy())).build())
        }
    }

    private fun handlePingEcho(p: MumbleProtos.Ping) {
        if (p.hasTimestamp() && p.timestamp == lastPingSentNanos && lastPingSentNanos != 0L) {
            events.onTcpRtt((clockNanos() - p.timestamp) / 1e6)
        }
        crypt.setRemoteStats(p.good, p.late, p.lost, p.resync)
    }

    /** Called every PING_INTERVAL_MS by the owner (manager's ping loop) and by tests. */
    fun sendPing() {
        lastPingSentNanos = clockNanos()
        val s = crypt.stats()
        channel.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder()
            .setTimestamp(lastPingSentNanos)
            .setGood(s.good).setLate(s.late).setLost(s.lost).setResync(s.resync).build())
    }

    /** Request crypt resync (persistent UDP decrypt failures). */
    fun requestCryptResync() {
        crypt.markResyncRequested()
        channel.send(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder().build())
    }

    fun fail(reason: FailReason, detail: String? = null, cause: Throwable? = null) {
        if (_state.value is ConnectionState.Failed) return
        _state.value = ConnectionState.Failed(reason, detail, cause)
        channel.close()
    }

    fun disconnectLocal() {
        _state.value = ConnectionState.Disconnected
        channel.close()
    }
}
```

*Proto setter caution:* if the vendored `Mumble.proto` names differ (e.g. field 1 of `Version` may generate `setVersionV1` or legacy `setVersion`), adjust the builder calls to match the generated code — the wire field numbers are what matter.

- [ ] **Step 3: Make `MumbleTcpTransport` implement `ControlChannel`** — in `MumbleTcpTransport.kt`, change the class declaration to `class MumbleTcpTransport(private val pinStore: PinStore) : ControlChannel {` and add `override` to `send` and `close` (import `com.example.drumble.mumble.protocol.ControlChannel`).

- [ ] **Step 4: Run tests.** Run: `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.SessionStateMachineTest"` → Expected: PASS (and Task 5 tests still pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/protocol/SessionStateMachine.kt app/src/main/java/com/example/drumble/mumble/net/MumbleTcpTransport.kt app/src/test/java/com/example/drumble/mumble/SessionStateMachineTest.kt
git commit -m "feat: session state machine — handshake, pings, crypt setup/resync"
```

---

### Task 7: MumbleUdpTransport + TransportSelector — encrypted UDP with fallback policy

**Goal:** Blocking-receive UDP thread (pooled buffers, decrypt at the edge, resync trigger), in-line encrypted sends, and a delta-based UDP↔TCP-tunnel policy exposing `NetStats`.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/net/TransportSelector.kt`
- Create: `app/src/main/java/com/example/drumble/mumble/net/MumbleUdpTransport.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/TransportSelectorTest.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/MumbleUdpTransportTest.kt`

**Acceptance Criteria:**
- [ ] Selector: crypt `good`/`remoteGood` deltas stalled for 2 consecutive ticks while sending → `TCP_TUNNEL`; deltas flowing again → `UDP`; `forceTcp` pins tunnel mode
- [ ] UDP round-trip against an in-test encrypted echo server delivers decrypted plaintext with arrival timestamps
- [ ] Persistent decrypt failure (>5 s since last good and last request) triggers exactly one resync request per window
- [ ] Receive loop reuses buffers (no per-packet wire allocations); `close()` unblocks the receive thread

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.TransportSelectorTest" --tests "com.example.drumble.mumble.MumbleUdpTransportTest"` → PASS

**Steps:**

- [ ] **Step 1: Write failing tests.** `TransportSelectorTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.net.TransportSelector
import com.example.drumble.mumble.net.VoiceTransportMode
import org.junit.Assert.*
import org.junit.Test

class TransportSelectorTest {
    private fun stats(good: Int, remoteGood: Int) =
        CryptState.Stats(good, 0, 0, 0, remoteGood, 0, 0, 0)

    @Test fun startsOptimisticUdp() {
        assertEquals(VoiceTransportMode.UDP, TransportSelector(forceTcp = false).mode)
    }

    @Test fun forceTcpPinsTunnel() {
        val s = TransportSelector(forceTcp = true)
        s.evaluate(stats(100, 100), sendingVoice = true)
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
    }

    @Test fun stallTwoTicksFallsBackAndRecovers() {
        val s = TransportSelector(forceTcp = false)
        s.evaluate(stats(10, 10), sendingVoice = true)  // baseline
        s.evaluate(stats(20, 20), sendingVoice = true)  // flowing
        assertEquals(VoiceTransportMode.UDP, s.mode)
        s.evaluate(stats(20, 20), sendingVoice = true)  // stall tick 1
        assertEquals(VoiceTransportMode.UDP, s.mode)
        s.evaluate(stats(20, 20), sendingVoice = true)  // stall tick 2 → fall back
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
        s.evaluate(stats(25, 24), sendingVoice = true)  // UDP pings flowing again → recover
        assertEquals(VoiceTransportMode.UDP, s.mode)
    }

    @Test fun noStallDetectionWhenNotSending() {
        val s = TransportSelector(forceTcp = false)
        repeat(5) { s.evaluate(stats(0, 0), sendingVoice = false) }
        assertEquals(VoiceTransportMode.UDP, s.mode)
    }

    @Test fun statsPublished() {
        val s = TransportSelector(forceTcp = false)
        s.onTcpRtt(12.5); s.onUdpPong(4.0)
        s.evaluate(stats(3, 2), sendingVoice = true)
        val ns = s.stats.value
        assertEquals(12.5, ns.tcpRttMs, 0.01)
        assertEquals(4.0, ns.udpRttMs, 0.01)
        assertEquals(3, ns.good); assertEquals(2, ns.remoteGood)
    }
}
```

`MumbleUdpTransportTest.kt` — encrypted echo server on localhost:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.net.MumbleUdpTransport
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MumbleUdpTransportTest {
    private val key = ByteArray(16) { it.toByte() }
    private val nA = ByteArray(16) { (0x40 + it).toByte() }
    private val nB = ByteArray(16) { (0x80 + it).toByte() }

    @Test fun encryptedRoundTripViaEchoServer() {
        // "server": decrypts with mirrored keys, re-encrypts, echoes back
        val serverCrypt = CryptState().apply { setKeys(key, nB, nA) }
        val serverSock = DatagramSocket(0)
        val serverThread = Thread {
            val wire = ByteArray(2048); val plain = ByteArray(2048); val out = ByteArray(2048)
            try {
                while (true) {
                    val p = DatagramPacket(wire, wire.size)
                    serverSock.receive(p)
                    val n = serverCrypt.decrypt(wire, p.length, plain)
                    if (n < 0) continue
                    val m = serverCrypt.encrypt(plain, n, out)
                    serverSock.send(DatagramPacket(out, m, p.address, p.port))
                }
            } catch (_: Exception) { /* socket closed */ }
        }.apply { isDaemon = true; start() }

        val clientCrypt = CryptState().apply { setKeys(key, nA, nB) }
        val received = LinkedBlockingQueue<ByteArray>()
        val resyncs = AtomicInteger()
        val transport = MumbleUdpTransport(clientCrypt, object : MumbleUdpTransport.Listener {
            override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {
                received.add(buf.copyOf(len))
                assertTrue(arrivalNanos > 0)
            }
            override fun onUdpError(e: Exception) {}
            override fun requestCryptResync() { resyncs.incrementAndGet() }
        })
        transport.connect("127.0.0.1", serverSock.localPort)

        val msg = byteArrayOf(0, 9, 8, 7, 6)
        assertTrue(transport.send(msg, msg.size))
        val echoed = received.poll(3, TimeUnit.SECONDS)
        assertNotNull(echoed); assertArrayEquals(msg, echoed)
        assertEquals(0, resyncs.get())

        transport.close()
        serverSock.close()
    }

    @Test fun sendFailsBeforeCryptReady() {
        val transport = MumbleUdpTransport(CryptState(), object : MumbleUdpTransport.Listener {
            override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {}
            override fun onUdpError(e: Exception) {}
            override fun requestCryptResync() {}
        })
        assertFalse(transport.send(byteArrayOf(1), 1))
    }
}
```

Run → Expected: FAIL (unresolved references).

- [ ] **Step 2: Implement `net/TransportSelector.kt`:**

```kotlin
package com.example.drumble.mumble.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceTransportMode { UDP, TCP_TUNNEL }

data class NetStats(
    val mode: VoiceTransportMode = VoiceTransportMode.UDP,
    val tcpRttMs: Double = -1.0,
    val udpRttMs: Double = -1.0,
    val udpJitterMs: Double = 0.0,
    val good: Int = 0, val late: Int = 0, val lost: Int = 0, val resync: Int = 0,
    val remoteGood: Int = 0, val remoteLate: Int = 0, val remoteLost: Int = 0, val remoteResync: Int = 0,
)

/**
 * Delta-based UDP↔tunnel policy, evaluated once per ping tick (~5 s):
 * counters stalled 2 consecutive ticks while we're sending → tunnel;
 * counters flowing again (UDP pings still run while tunneled) → back to UDP.
 */
class TransportSelector(private val forceTcp: Boolean) {
    private val _stats = MutableStateFlow(NetStats(
        mode = if (forceTcp) VoiceTransportMode.TCP_TUNNEL else VoiceTransportMode.UDP))
    val stats: StateFlow<NetStats> = _stats.asStateFlow()
    val mode: VoiceTransportMode get() = _stats.value.mode

    private var prevGood = 0
    private var prevRemoteGood = 0
    private var stallTicks = 0
    private var udpJitter = 0.0
    private var lastUdpRtt = -1.0

    @Synchronized fun evaluate(c: CryptState.Stats, sendingVoice: Boolean) {
        val goodDelta = c.good - prevGood
        val remoteDelta = c.remoteGood - prevRemoteGood
        prevGood = c.good; prevRemoteGood = c.remoteGood

        val current = _stats.value.mode
        val next = if (forceTcp) VoiceTransportMode.TCP_TUNNEL else when {
            current == VoiceTransportMode.UDP && sendingVoice && (goodDelta == 0 || remoteDelta == 0) -> {
                if (++stallTicks >= 2) VoiceTransportMode.TCP_TUNNEL else current
            }
            current == VoiceTransportMode.TCP_TUNNEL && goodDelta > 0 && remoteDelta > 0 -> {
                stallTicks = 0; VoiceTransportMode.UDP
            }
            else -> { if (goodDelta > 0 && remoteDelta > 0) stallTicks = 0; current }
        }
        _stats.value = _stats.value.copy(
            mode = next,
            good = c.good, late = c.late, lost = c.lost, resync = c.resync,
            remoteGood = c.remoteGood, remoteLate = c.remoteLate,
            remoteLost = c.remoteLost, remoteResync = c.remoteResync,
        )
    }

    @Synchronized fun onTcpRtt(ms: Double) { _stats.value = _stats.value.copy(tcpRttMs = ms) }

    @Synchronized fun onUdpPong(rttMs: Double) {
        if (lastUdpRtt >= 0) {
            val d = kotlin.math.abs(rttMs - lastUdpRtt)
            udpJitter += (d - udpJitter) / 16.0 // RFC3550-flavored smoothing
        }
        lastUdpRtt = rttMs
        _stats.value = _stats.value.copy(udpRttMs = rttMs, udpJitterMs = udpJitter)
    }
}
```

- [ ] **Step 3: Implement `net/MumbleUdpTransport.kt`:**

```kotlin
package com.example.drumble.mumble.net

import com.example.drumble.mumble.util.MumbleLog
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Connected UDP channel. Receive: dedicated blocking thread, pooled buffers,
 * decrypt at the edge, arrival timestamps in CLOCK_MONOTONIC (System.nanoTime).
 * Send: in-line on the caller's thread (voice-send hot path / ping cold path);
 * CryptState.encrypt is synchronized so concurrent callers are safe.
 */
class MumbleUdpTransport(
    private val crypt: CryptState,
    private val listener: Listener,
    /** Runs on the receive thread before the loop — Android layer sets URGENT_AUDIO priority here. */
    private val threadSetup: () -> Unit = {},
) {
    companion object {
        private const val TAG = "MumbleUdpTransport"
        private const val BUFFER_SIZE = 2048
        private const val RESYNC_QUIET_NANOS = 5_000_000_000L
    }

    interface Listener {
        /** Decrypted plaintext [u8 type][protobuf]. buf is reused — copy what you keep. Receive-thread context. */
        fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long)
        fun onUdpError(e: Exception)
        /** Persistent decrypt failures — owner should send an empty CryptSetup. */
        fun requestCryptResync()
    }

    @Volatile private var running = false
    private var channel: DatagramChannel? = null
    private var receiveThread: Thread? = null

    private val sendLock = Any()
    private val sendCipher = ByteArray(BUFFER_SIZE)
    private val sendBuf = ByteBuffer.wrap(sendCipher)

    fun connect(host: String, port: Int) {
        val ch = DatagramChannel.open()
        ch.connect(InetSocketAddress(host, port))
        ch.configureBlocking(true)
        channel = ch
        running = true
        receiveThread = Thread({ receiveLoop(ch) }, "mumble-udp-recv").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    private fun receiveLoop(ch: DatagramChannel) {
        threadSetup()
        val wire = ByteBuffer.allocate(BUFFER_SIZE) // heap: array() feeds decrypt
        val plain = ByteArray(BUFFER_SIZE)
        while (running) {
            try {
                wire.clear()
                val n = ch.read(wire)
                val arrival = System.nanoTime()
                if (n < 5 || !crypt.isValid()) continue
                val plainLen = crypt.decrypt(wire.array(), n, plain)
                if (plainLen < 0) {
                    if (crypt.lastGoodElapsedNanos() > RESYNC_QUIET_NANOS &&
                        crypt.lastRequestElapsedNanos() > RESYNC_QUIET_NANOS) {
                        crypt.markResyncRequested()
                        listener.requestCryptResync()
                    }
                    continue
                }
                listener.onUdpPlaintext(plain, plainLen, arrival)
            } catch (e: Exception) {
                if (running) { listener.onUdpError(e); MumbleLog.w(TAG, "receive error", e) }
                return
            }
        }
    }

    /** Encrypt+send in-line. Thread-safe. False when crypt not ready or I/O fails. */
    fun send(plaintext: ByteArray, len: Int): Boolean {
        if (!crypt.isValid() || !running) return false
        synchronized(sendLock) {
            return try {
                val n = crypt.encrypt(plaintext, len, sendCipher)
                sendBuf.position(0).limit(n)
                channel?.write(sendBuf)
                true
            } catch (e: Exception) {
                MumbleLog.w(TAG, "send failed", e); false
            }
        }
    }

    fun close() {
        running = false
        runCatching { channel?.close() } // unblocks the receive thread
        receiveThread?.join(500)
    }
}
```

- [ ] **Step 4: Run tests.** Run: the Verify command above → Expected: PASS. (Note `requestCryptResync` marks the request time via `crypt.markResyncRequested()` here; `SessionStateMachine.requestCryptResync` also marks it — idempotent, both paths safe.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/net/TransportSelector.kt app/src/main/java/com/example/drumble/mumble/net/MumbleUdpTransport.kt app/src/test/java/com/example/drumble/mumble/TransportSelectorTest.kt app/src/test/java/com/example/drumble/mumble/MumbleUdpTransportTest.kt
git commit -m "feat: encrypted UDP transport + delta-based UDP/TCP-tunnel selector"
```

---

### Task 8: Voice seam + SyntheticVoiceSource + VoiceTransport

**Goal:** The frame-level audio seam (blocking-with-timeout, shaped as the future JNI boundary), a self-clocked synthetic source measuring loopback RTT/jitter/loss, and the voice-send thread routing frames over the selected transport.

**Files:**
- Create: `app/src/main/java/com/example/drumble/mumble/voice/VoiceEngine.kt`
- Create: `app/src/main/java/com/example/drumble/mumble/voice/SyntheticVoiceSource.kt`
- Create: `app/src/main/java/com/example/drumble/mumble/voice/VoiceTransport.kt`
- Modify: `app/src/main/java/com/example/drumble/mumble/protocol/SessionStateMachine.kt` (add `sendRaw` to `ControlChannel`)
- Modify: `app/src/main/java/com/example/drumble/mumble/net/MumbleTcpTransport.kt` (implement `sendRaw`)
- Test: `app/src/test/java/com/example/drumble/mumble/SyntheticVoiceSourceTest.kt`
- Test: `app/src/test/java/com/example/drumble/mumble/VoiceTransportTest.kt`

**Acceptance Criteria:**
- [ ] Seam: `nextOutgoingFrame(timeoutNanos)` blocks with timeout; `onIncomingFrame` non-blocking; interface documented as the permanent JNI boundary
- [ ] Synthetic source emits sequential `frame_number`s on the frame cadence; loopback stats compute RTT from embedded send timestamps, EWMA jitter, and loss from sequence gaps (late arrival un-counts a loss)
- [ ] Voice-send thread builds `Audio{target=31, frame_number, opus_data}` plaintext and routes by selector mode (UDP vs `UDPTunnel` raw TCP frame)
- [ ] Incoming Audio/Ping plaintext routed to engine / ping handler

**Verify:** `./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.SyntheticVoiceSourceTest" --tests "com.example.drumble.mumble.VoiceTransportTest"` → PASS

**Steps:**

- [ ] **Step 1: Write failing tests.** `SyntheticVoiceSourceTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.voice.SyntheticVoiceSource
import org.junit.Assert.*
import org.junit.Test

class SyntheticVoiceSourceTest {
    @Test fun emitsSequentialFramesOnCadence() {
        val src = SyntheticVoiceSource(frameIntervalNanos = 1_000_000L) // 1 ms for test speed
        src.start()
        val frames = (0 until 10).mapNotNull { src.nextOutgoingFrame(50_000_000L) }
        src.stop()
        assertEquals(10, frames.size)
        assertEquals((0L until 10L).toList(), frames.map { it.frameNumber })
        assertTrue(frames.all { it.length >= 8 })
    }

    @Test fun statsFromEmbeddedTimestamps() {
        val src = SyntheticVoiceSource(frameIntervalNanos = 1_000_000L)
        fun payload(sendNanos: Long): ByteArray {
            val p = ByteArray(40)
            for (i in 0 until 8) p[i] = (sendNanos ushr ((7 - i) * 8)).toByte()
            return p
        }
        // frame 0: RTT 15 ms
        src.onIncomingFrame(payload(1_000_000_000L), 0, 40, 0L, 1, 1_015_000_000L)
        assertEquals(15.0, src.stats.value.lastRttMs, 0.1)
        assertEquals(1L, src.stats.value.received)
        // frame 2 arrives (frame 1 missing → lost=1)
        src.onIncomingFrame(payload(2_000_000_000L), 0, 40, 2L, 1, 2_020_000_000L)
        assertEquals(1L, src.stats.value.lost)
        // frame 1 arrives late → loss un-counted
        src.onIncomingFrame(payload(1_500_000_000L), 0, 40, 1L, 1, 2_030_000_000L)
        assertEquals(0L, src.stats.value.lost)
        assertEquals(3L, src.stats.value.received)
    }

    @Test fun timeoutReturnsNullWhenStopped() {
        val src = SyntheticVoiceSource(frameIntervalNanos = 1_000_000L)
        assertNull(src.nextOutgoingFrame(1_000_000L)) // not started
    }
}
```

`VoiceTransportTest.kt`:

```kotlin
package com.example.drumble.mumble

import com.example.drumble.mumble.net.VoiceTransportMode
import com.example.drumble.mumble.proto.MumbleUdpProtos
import com.example.drumble.mumble.protocol.MumbleCodec
import com.example.drumble.mumble.voice.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VoiceTransportTest {
    private class ScriptedEngine(frames: Int) : VoiceEngine {
        private var next = 0L
        private val total = frames
        val incoming = mutableListOf<Triple<Long, Int, Long>>() // frameNumber, senderSession, arrivalNanos
        val done = CountDownLatch(1)
        override fun start() {}
        override fun stop() {}
        override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
            if (next >= total) { done.countDown(); Thread.sleep(5); return null }
            val fn = next++
            return VoiceFrame(ByteArray(20) { fn.toByte() }, 20, fn)
        }
        override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                     frameNumber: Long, senderSession: Int, arrivalNanos: Long) {
            incoming.add(Triple(frameNumber, senderSession, arrivalNanos))
        }
    }

    @Test fun sendsFramesOverUdpWithLoopbackTarget() {
        val engine = ScriptedEngine(3)
        val sent = LinkedBlockingQueue<ByteArray>()
        val vt = VoiceTransport(
            engine = engine,
            modeProvider = { VoiceTransportMode.UDP },
            udpSend = { buf, n -> sent.add(buf.copyOf(n)); true },
            tunnelSend = { _, _ -> fail("tunnel used in UDP mode"); false },
        )
        vt.start()
        assertTrue(engine.done.await(2, TimeUnit.SECONDS))
        vt.stop()
        assertEquals(3, sent.size)
        val first = sent.take()
        assertEquals(MumbleCodec.UDP_TYPE_AUDIO, first[0].toInt())
        val audio = MumbleUdpProtos.Audio.parser().parseFrom(first, 1, first.size - 1)
        assertEquals(31, audio.target)
        assertEquals(0L, audio.frameNumber)
    }

    @Test fun tunnelModeUsesTunnelSender() {
        val engine = ScriptedEngine(1)
        val tunneled = LinkedBlockingQueue<ByteArray>()
        val vt = VoiceTransport(
            engine = engine,
            modeProvider = { VoiceTransportMode.TCP_TUNNEL },
            udpSend = { _, _ -> fail("udp used in tunnel mode"); false },
            tunnelSend = { buf, n -> tunneled.add(buf.copyOf(n)); true },
        )
        vt.start()
        assertTrue(engine.done.await(2, TimeUnit.SECONDS))
        vt.stop()
        assertEquals(1, tunneled.size)
    }

    @Test fun routesIncomingAudioAndPing() {
        val engine = ScriptedEngine(0)
        var pingTs = -1L
        val vt = VoiceTransport(engine, { VoiceTransportMode.UDP }, { _, _ -> true }, { _, _ -> true },
            onUdpPing = { ts, _ -> pingTs = ts })
        val audio = MumbleUdpProtos.Audio.newBuilder().setContext(0).setSenderSession(9)
            .setFrameNumber(5L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(ByteArray(12) { 3 })).build()
        val buf = ByteArray(256)
        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, buf)
        vt.onPlaintext(buf, n, arrivalNanos = 777L)
        assertEquals(Triple(5L, 9, 777L), engine.incoming.single())

        val ping = MumbleUdpProtos.Ping.newBuilder().setTimestamp(1234L).build()
        val m = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_PING, ping, buf)
        vt.onPlaintext(buf, m, arrivalNanos = 888L)
        assertEquals(1234L, pingTs)
    }
}
```

Run → Expected: FAIL (unresolved references).

- [ ] **Step 2: Implement `voice/VoiceEngine.kt`:**

```kotlin
package com.example.drumble.mumble.voice

/** One encoded voice frame crossing the seam. */
class VoiceFrame(val opusData: ByteArray, val length: Int, val frameNumber: Long)

/**
 * THE audio seam — this interface is shaped as the permanent JNI boundary
 * ("decrypted Opus frame in / encoded Opus frame out").
 * Future native engine: nextOutgoingFrame parks on a semaphore posted by the
 * Oboe capture callback at frame boundaries (timeout = missed-signal backstop);
 * onIncomingFrame down-calls into the native jitter buffer (never blocks).
 */
interface VoiceEngine {
    fun start()
    fun stop()
    /** Blocking up to timeoutNanos. Returns null on timeout or when stopped. Voice-send-thread context. */
    fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame?
    /** Must not block; called from UDP receive thread or TCP reader (tunneled). */
    fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                        frameNumber: Long, senderSession: Int, arrivalNanos: Long)
}
```

- [ ] **Step 3: Implement `voice/SyntheticVoiceSource.kt`:**

```kotlin
package com.example.drumble.mumble.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

data class LoopbackStats(
    val sent: Long = 0, val received: Long = 0, val lost: Long = 0,
    val lastRttMs: Double = -1.0, val avgRttMs: Double = -1.0, val jitterMs: Double = 0.0,
)

/**
 * Self-clocked synthetic frame source for loopback validation (no audio hardware,
 * so absolute-deadline pacing is correct here — no producer clock to phase-match).
 * Payload: [8B big-endian send-nanos][filler] — RTT computed on echo.
 */
class SyntheticVoiceSource(
    private val frameIntervalNanos: Long = 10_000_000L,
    private val payloadSize: Int = 40,
    private val clockNanos: () -> Long = System::nanoTime,
) : VoiceEngine {
    private val _stats = MutableStateFlow(LoopbackStats())
    val stats: StateFlow<LoopbackStats> = _stats.asStateFlow()

    @Volatile private var running = false
    private var nextDeadline = 0L
    private var frameNumber = 0L

    // receive-side accounting (synchronized — called from receive threads)
    private var highestSeen = -1L
    private var lostCount = 0L
    private var receivedCount = 0L
    private var avgRtt = -1.0
    private var jitter = 0.0
    private var lastRtt = -1.0

    override fun start() {
        running = true
        frameNumber = 0
        nextDeadline = clockNanos() + frameIntervalNanos
    }

    override fun stop() { running = false }

    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        if (!running) return null
        val wait = nextDeadline - clockNanos()
        if (wait > timeoutNanos) { LockSupport.parkNanos(timeoutNanos); return null }
        if (wait > 0) LockSupport.parkNanos(wait)
        if (!running) return null
        nextDeadline += frameIntervalNanos
        val payload = ByteArray(payloadSize)
        val sendNanos = clockNanos()
        for (i in 0 until 8) payload[i] = (sendNanos ushr ((7 - i) * 8)).toByte()
        val fn = frameNumber++
        _stats.update { it.copy(sent = fn + 1) }
        return VoiceFrame(payload, payloadSize, fn)
    }

    @Synchronized
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long) {
        if (length < 8) return
        var sendNanos = 0L
        for (i in 0 until 8) sendNanos = (sendNanos shl 8) or (opusData[offset + i].toLong() and 0xFF)
        val rttMs = (arrivalNanos - sendNanos) / 1e6
        receivedCount++
        if (frameNumber > highestSeen) {
            if (highestSeen >= 0) lostCount += frameNumber - highestSeen - 1
            highestSeen = frameNumber
        } else if (lostCount > 0) {
            lostCount-- // late arrival of a frame previously counted lost
        }
        if (lastRtt >= 0) jitter += (abs(rttMs - lastRtt) - jitter) / 16.0
        lastRtt = rttMs
        avgRtt = if (avgRtt < 0) rttMs else avgRtt * 0.9 + rttMs * 0.1
        _stats.update {
            it.copy(received = receivedCount, lost = lostCount,
                lastRttMs = rttMs, avgRttMs = avgRtt, jitterMs = jitter)
        }
    }
}
```

- [ ] **Step 4: Implement `voice/VoiceTransport.kt`:**

```kotlin
package com.example.drumble.mumble.voice

import com.example.drumble.mumble.net.VoiceTransportMode
import com.example.drumble.mumble.proto.MumbleUdpProtos
import com.example.drumble.mumble.protocol.MumbleCodec
import com.example.drumble.mumble.util.MumbleLog
import com.google.protobuf.ByteString

/**
 * Voice hot path: dedicated send thread pulls frames from the seam, wraps them in
 * Audio protobuf plaintext, routes via UDP or TCP tunnel per the selector's mode.
 * Incoming plaintext (either transport) routes back through onPlaintext.
 */
class VoiceTransport(
    private val engine: VoiceEngine,
    private val modeProvider: () -> VoiceTransportMode,
    private val udpSend: (ByteArray, Int) -> Boolean,
    private val tunnelSend: (ByteArray, Int) -> Boolean,
    private val target: Int = LOOPBACK_TARGET,
    private val onUdpPing: (timestamp: Long, arrivalNanos: Long) -> Unit = { _, _ -> },
    /** Runs on the voice-send thread before the loop — Android layer sets URGENT_AUDIO here. */
    private val threadSetup: () -> Unit = {},
) {
    companion object {
        private const val TAG = "VoiceTransport"
        const val LOOPBACK_TARGET = 31
        const val FRAME_TIMEOUT_NANOS = 20_000_000L
        private const val WIRE_BUF_SIZE = 1024
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        engine.start()
        thread = Thread({ sendLoop() }, "mumble-voice-send").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    fun stop() {
        running = false
        engine.stop()
        thread?.join(500)
    }

    private fun sendLoop() {
        threadSetup()
        val wireBuf = ByteArray(WIRE_BUF_SIZE) // reused every frame
        while (running) {
            val frame = engine.nextOutgoingFrame(FRAME_TIMEOUT_NANOS) ?: continue
            val audio = MumbleUdpProtos.Audio.newBuilder()
                .setTarget(target)
                .setFrameNumber(frame.frameNumber)
                .setOpusData(ByteString.copyFrom(frame.opusData, 0, frame.length))
                .build()
            val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, wireBuf)
            val ok = when (modeProvider()) {
                VoiceTransportMode.UDP -> udpSend(wireBuf, n)
                VoiceTransportMode.TCP_TUNNEL -> tunnelSend(wireBuf, n)
            }
            if (!ok) MumbleLog.d(TAG, "voice frame ${frame.frameNumber} dropped by transport")
        }
    }

    /** Route decrypted UDP plaintext or tunneled UDPTunnel payload. Receive-thread context. */
    fun onPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {
        if (len < 2) return
        when (buf[0].toInt()) {
            MumbleCodec.UDP_TYPE_AUDIO -> {
                val audio = MumbleUdpProtos.Audio.parser().parseFrom(buf, 1, len - 1)
                engine.onIncomingFrame(audio.opusData.toByteArray(), 0, audio.opusData.size(),
                    audio.frameNumber, audio.senderSession, arrivalNanos)
            }
            MumbleCodec.UDP_TYPE_PING -> {
                val ping = MumbleUdpProtos.Ping.parser().parseFrom(buf, 1, len - 1)
                onUdpPing(ping.timestamp, arrivalNanos)
            }
            else -> MumbleLog.d(TAG, "unknown UDP plaintext type ${buf[0]}")
        }
    }
}
```

- [ ] **Step 5: Add raw sends for tunneling.** In `SessionStateMachine.kt`, extend `ControlChannel`:

```kotlin
interface ControlChannel {
    fun send(type: TcpMessageType, message: MessageLite): Boolean
    /** Raw payload frame (UDPTunnel carries opaque bytes, not protobuf). */
    fun sendRaw(type: TcpMessageType, payload: ByteArray, len: Int): Boolean
    fun close()
}
```

In `MumbleTcpTransport.kt` add:

```kotlin
override fun sendRaw(type: TcpMessageType, payload: ByteArray, len: Int): Boolean {
    if (closed.get()) return false
    val bos = ByteArrayOutputStream(6 + len)
    val dos = DataOutputStream(bos)
    dos.writeShort(type.id); dos.writeInt(len); dos.write(payload, 0, len)
    return sendQueue.trySend(bos.toByteArray()).isSuccess
}
```

Update `SessionStateMachineTest`'s `FakeChannel` with `override fun sendRaw(type: TcpMessageType, payload: ByteArray, len: Int) = true`.

- [ ] **Step 6: Run tests.** Run: the Verify command above, plus `--tests "com.example.drumble.mumble.SessionStateMachineTest"` → Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/voice app/src/main/java/com/example/drumble/mumble/protocol/SessionStateMachine.kt app/src/main/java/com/example/drumble/mumble/net/MumbleTcpTransport.kt app/src/test/java/com/example/drumble/mumble/SyntheticVoiceSourceTest.kt app/src/test/java/com/example/drumble/mumble/VoiceTransportTest.kt app/src/test/java/com/example/drumble/mumble/SessionStateMachineTest.kt
git commit -m "feat: voice seam, synthetic loopback source, voice transport routing"
```

---

### Task 9: MumbleManager facade + Telecom bridge

**Goal:** Rewrite the `MumbleManager` placeholder to wire the whole stack (connect → handshake → UDP → voice loopback → stats), and bridge connection state into the Telecom call shell with a debug stats display.

**Files:**
- Modify (rewrite): `app/src/main/java/com/example/drumble/mumble/MumbleManager.kt`
- Modify: `app/src/main/java/com/example/drumble/telecom/CallManager.kt`
- Modify: `app/src/main/java/com/example/drumble/ActiveCallActivity.kt`
- Modify: `app/src/main/java/com/example/drumble/telecom/DrumbleConnectionService.kt`

**Acceptance Criteria:**
- [ ] `MumbleManager.connect(config)` runs: TLS connect (failure classified into `FailReason`), handshake, UDP start on `onCryptReady`, voice loopback start, 5 s ping loop (TCP ping + UDP ping + selector tick)
- [ ] `state`, `model.state`, `netStats`, `loopbackStats` exposed as StateFlows; `disconnect()` tears everything down (idempotent)
- [ ] Telecom: `Synchronized` → `DrumbleConnection.setActive()`; `Failed` → `setDisconnected(ERROR)`; hangup path (button or notification) calls `MumbleManager.disconnect()`
- [ ] `ActiveCallActivity` shows live debug stats (mode, RTTs, jitter, loss); outgoing connection no longer `setActive()` immediately
- [ ] All prior unit tests still pass; app assembles

**Verify:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: Rewrite `MumbleManager.kt`:**

```kotlin
package com.example.drumble.mumble

import android.content.Context
import android.util.Log
import com.example.drumble.mumble.model.MumbleModel
import com.example.drumble.mumble.net.*
import com.example.drumble.mumble.proto.MumbleUdpProtos
import com.example.drumble.mumble.protocol.*
import com.example.drumble.mumble.util.MumbleLog
import com.example.drumble.mumble.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

data class MumbleServerConfig(
    val host: String,
    val port: Int = 64738,
    val username: String,
    val password: String? = null,
    val forceTcp: Boolean = false,
    val loopbackVoice: Boolean = true,
)

class SharedPrefsPinStore(context: Context) : PinStore {
    private val prefs = context.getSharedPreferences("mumble_tofu_pins", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, fingerprint: String) { prefs.edit().putString(key, fingerprint).apply() }
}

object MumbleManager {
    private const val TAG = "MumbleManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val model = MumbleModel()
    private val _netStats = MutableStateFlow(NetStats())
    val netStats: StateFlow<NetStats> = _netStats.asStateFlow()
    private val _loopbackStats = MutableStateFlow(LoopbackStats())
    val loopbackStats: StateFlow<LoopbackStats> = _loopbackStats.asStateFlow()

    private var pinStore: PinStore = InMemoryPinStore()
    private var active: ActiveSession? = null

    fun init(context: Context) {
        pinStore = SharedPrefsPinStore(context.applicationContext)
        MumbleLog.sink = { tag, msg, t -> if (t != null) Log.w(tag, msg, t) else Log.d(tag, msg) }
    }

    @Synchronized fun connect(config: MumbleServerConfig) {
        if (active != null) { Log.w(TAG, "connect ignored — session active"); return }
        model.reset()
        active = ActiveSession(config).also { it.start() }
    }

    @Synchronized fun disconnect() {
        active?.shutdown()
        active = null
    }

    private fun urgentAudioThread() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        catch (t: Throwable) { Log.w(TAG, "setThreadPriority failed", t) }
    }

    private class ActiveSession(private val config: MumbleServerConfig) {
        private val jobs = mutableListOf<Job>()
        private val crypt = CryptState()
        private val tcp = MumbleTcpTransport(pinStore)
        private val selector = TransportSelector(config.forceTcp)
        private val synthetic = SyntheticVoiceSource()
        @Volatile private var udp: MumbleUdpTransport? = null
        private val pingBuf = ByteArray(256)

        private val voice = VoiceTransport(
            engine = synthetic,
            modeProvider = { selector.mode },
            udpSend = { buf, n -> udp?.send(buf, n) ?: false },
            tunnelSend = { buf, n -> tcp.sendRaw(TcpMessageType.UDPTunnel, buf, n) },
            onUdpPing = { ts, arrival -> selector.onUdpPong((arrival - ts) / 1e6) },
            threadSetup = ::urgentAudioThread,
        )

        private val events = object : SessionStateMachine.Events {
            override fun onCryptReady() {
                val u = MumbleUdpTransport(crypt, object : MumbleUdpTransport.Listener {
                    override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) =
                        voice.onPlaintext(buf, len, arrivalNanos)
                    override fun onUdpError(e: Exception) { MumbleLog.w(TAG, "udp error — tunnel continues", e) }
                    override fun requestCryptResync() { sm.requestCryptResync() }
                }, threadSetup = ::urgentAudioThread)
                u.connect(config.host, config.port)
                udp = u
                if (config.loopbackVoice) voice.start()
            }
            override fun onTcpRtt(rttMs: Double) = selector.onTcpRtt(rttMs)
            override fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long) =
                voice.onPlaintext(plaintext, len, arrivalNanos)
        }

        private val sm = SessionStateMachine(tcp, model, crypt, events)

        fun start() {
            jobs += scope.launch { sm.state.collect { _state.value = it } }
            jobs += scope.launch { selector.stats.collect { _netStats.value = it } }
            jobs += scope.launch { synthetic.stats.collect { _loopbackStats.value = it } }
            jobs += scope.launch {
                _state.value = ConnectionState.Connecting
                try {
                    tcp.connect(config.host, config.port, object : MumbleTcpTransport.Listener {
                        override fun onFrame(frame: TcpFrame) = sm.onFrame(frame)
                        override fun onClosed(cause: Throwable?) {
                            if (cause != null) sm.fail(FailReason.IO, cause.message, cause)
                        }
                    })
                } catch (t: Throwable) {
                    _state.value = ConnectionState.Failed(classify(t), t.message, t)
                    return@launch
                }
                sm.start(config.username, config.password)
                jobs += scope.launch { pingLoop() }
            }
        }

        private suspend fun pingLoop() {
            while (currentCoroutineContext().isActive) {
                delay(SessionStateMachine.PING_INTERVAL_MS)
                sm.sendPing()
                udp?.let { u ->
                    val ping = MumbleUdpProtos.Ping.newBuilder().setTimestamp(System.nanoTime()).build()
                    synchronized(pingBuf) {
                        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_PING, ping, pingBuf)
                        u.send(pingBuf, n)
                    }
                }
                selector.evaluate(crypt.stats(), sendingVoice = config.loopbackVoice && crypt.isValid())
            }
        }

        private fun classify(t: Throwable): FailReason = when {
            t is UnknownHostException -> FailReason.DNS
            t is SocketTimeoutException -> FailReason.TIMEOUT
            t is CertificateException || t.cause is CertificateException ->
                if ((t.message ?: t.cause?.message ?: "").contains("pin mismatch")) FailReason.PIN_MISMATCH else FailReason.TLS
            t is SSLException -> FailReason.TLS
            else -> FailReason.IO
        }

        fun shutdown() {
            voice.stop()
            udp?.close()
            sm.disconnectLocal()
            jobs.forEach { it.cancel() }
            _state.value = ConnectionState.Disconnected
            _netStats.value = NetStats()
            _loopbackStats.value = LoopbackStats()
        }
    }
}
```

- [ ] **Step 2: Bridge in `CallManager.kt`** — add imports (`kotlinx.coroutines.*`, `android.telecom.DisconnectCause`, `com.example.drumble.mumble.MumbleManager`, `com.example.drumble.mumble.protocol.ConnectionState`) and:

```kotlin
private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var bridgeJob: Job? = null
```

In `setConnection(connection)`, after `updateNotification()`:

```kotlin
bridgeJob?.cancel()
if (connection != null) {
    bridgeJob = bridgeScope.launch {
        MumbleManager.state.collect { s ->
            when (s) {
                is ConnectionState.Synchronized -> connection.setActive()
                is ConnectionState.Failed -> {
                    connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                    connection.destroy()
                    setConnection(null)
                }
                else -> { /* Connecting/Handshaking: stay initializing; Disconnected handled by disconnect() */ }
            }
        }
    }
}
```

In `disconnect()`, first line: `MumbleManager.disconnect()`.

- [ ] **Step 3: `DrumbleConnectionService.kt`** — in `onCreateOutgoingConnection`, delete the `setActive()` line (keep `setInitializing()`); the bridge activates the call at `Synchronized`.

- [ ] **Step 4: `ActiveCallActivity.kt`** — (a) in `onCreate` after `CallManager.init(this)` add `MumbleManager.init(this)`; (b) add a stats `TextView` under `statusView`:

```kotlin
statsView = TextView(this).apply {
    text = ""
    textSize = 12f
    setTextColor(Color.GRAY)
    gravity = Gravity.CENTER
    setPadding(0, 16, 0, 32)
}
layout.addView(statsView)
```

(field: `private lateinit var statsView: TextView`); (c) in `placeTestCall()` after `telecomManager.placeCall(...)`:

```kotlin
MumbleManager.connect(MumbleServerConfig(
    host = TEST_HOST, username = "drumble-${android.os.Build.MODEL.take(8)}"))
```

with `companion object { /** Emulator alias for host machine; use LAN IP for physical devices. */ const val TEST_HOST = "10.0.2.2" }`; (d) in `observeCallState()` add a second collector:

```kotlin
lifecycleScope.launch {
    combine(MumbleManager.netStats, MumbleManager.loopbackStats, MumbleManager.state) { net, loop, st ->
        "state=${st::class.simpleName} mode=${net.mode}\n" +
        "tcpRtt=%.1fms udpRtt=%.1fms jit=%.2fms".format(net.tcpRttMs, net.udpRttMs, net.udpJitterMs) + "\n" +
        "loop: sent=${loop.sent} rcvd=${loop.received} lost=${loop.lost} rtt=%.1fms".format(loop.lastRttMs)
    }.collect { statsView.text = it }
}
```

(imports: `kotlinx.coroutines.flow.combine`, `com.example.drumble.mumble.*`).

- [ ] **Step 5: Build + tests.** Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/drumble/mumble/MumbleManager.kt app/src/main/java/com/example/drumble/telecom/CallManager.kt app/src/main/java/com/example/drumble/telecom/DrumbleConnectionService.kt app/src/main/java/com/example/drumble/ActiveCallActivity.kt
git commit -m "feat: MumbleManager facade wiring + Telecom bridge + debug stats"
```

---

### Task 10: Live-server integration — Synchronized, loopback latency, fallback drill

**Goal:** Prove the stack end-to-end against a real Mumble server ≥ 1.5: reach Synchronized, measure UDP loopback RTT/jitter/loss, demonstrate the TCP-tunnel fallback, and verify the on-device Telecom call.

**USER-ORDERED GATE — NON-SKIPPABLE.** This task was requested by the user in the current conversation. It MUST NOT be closed by walking around it, by declaring it "verified inline", or by substituting a cheaper check. Close only after every item in `acceptanceCriteria` has been re-validated independently, with output captured.

**Files:**
- Create: `docs/dev/mumble-server/docker-compose.yml`
- Test: `app/src/test/java/com/example/drumble/mumble/integration/LiveServerIntegrationTest.kt`

**Acceptance Criteria:**
- [ ] JVM integration test reaches `Synchronized` against Dockerized `mumble-server` within 10 s; channel tree non-empty
- [ ] UDP loopback (`target=31`): ≥ 100 frames echoed, avg RTT < 250 ms local, loss and jitter reported — output captured (evidence: `udp` mode)
- [ ] Forced-TCP run: loopback flows via `UDPTunnel` with `mode=TCP_TUNNEL` in NetStats — output captured (evidence: `tcp-tunnel` mode)
- [ ] On-device: Start Call → Telecom call goes active at Synchronized; stats TextView updates live; hangup (button and notification) disconnects cleanly — logcat/screenshot captured
- [ ] If the UDP type-byte constants (`Audio=0`, `Ping=1`) prove wrong against the real server, correct `MumbleCodec` and note it in the commit

**Verify:** `docker compose -f docs/dev/mumble-server/docker-compose.yml up -d && MUMBLE_TEST_SERVER=127.0.0.1 ./gradlew :app:testDebugUnitTest --tests "com.example.drumble.mumble.integration.LiveServerIntegrationTest"` → PASS with printed `LOOPBACK[udp]` and `LOOPBACK[tcp-tunnel]` stat lines

**Steps:**

- [ ] **Step 1: Create `docs/dev/mumble-server/docker-compose.yml`:**

```yaml
services:
  mumble-server:
    image: mumblevoip/mumble-server:latest
    container_name: drumble-murmur
    restart: unless-stopped
    ports:
      - "64738:64738/tcp"
      - "64738:64738/udp"
    environment:
      MUMBLE_CONFIG_WELCOMETEXT: "Drumble test server"
```

Start and confirm version ≥ 1.5: `docker compose -f docs/dev/mumble-server/docker-compose.yml up -d && docker logs drumble-murmur 2>&1 | head -20`.

- [ ] **Step 2: Write `integration/LiveServerIntegrationTest.kt`** (env-gated; assembles the pure-JVM core without the Android facade):

```kotlin
package com.example.drumble.mumble.integration

import com.example.drumble.mumble.model.MumbleModel
import com.example.drumble.mumble.net.*
import com.example.drumble.mumble.protocol.*
import com.example.drumble.mumble.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class LiveServerIntegrationTest {
    private val host: String? = System.getenv("MUMBLE_TEST_SERVER")

    @Before fun requiresServer() = assumeTrue("set MUMBLE_TEST_SERVER to run", host != null)

    private class Harness(val host: String, forceTcp: Boolean) {
        val crypt = CryptState()
        val model = MumbleModel()
        val tcp = MumbleTcpTransport(InMemoryPinStore())
        val selector = TransportSelector(forceTcp)
        val synthetic = SyntheticVoiceSource()
        @Volatile var udp: MumbleUdpTransport? = null
        lateinit var sm: SessionStateMachine
        lateinit var voice: VoiceTransport

        init {
            voice = VoiceTransport(
                engine = synthetic,
                modeProvider = { selector.mode },
                udpSend = { b, n -> udp?.send(b, n) ?: false },
                tunnelSend = { b, n -> tcp.sendRaw(TcpMessageType.UDPTunnel, b, n) },
                onUdpPing = { ts, ar -> selector.onUdpPong((ar - ts) / 1e6) },
            )
            sm = SessionStateMachine(tcp, model, crypt, object : SessionStateMachine.Events {
                override fun onCryptReady() {
                    udp = MumbleUdpTransport(crypt, object : MumbleUdpTransport.Listener {
                        override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) =
                            voice.onPlaintext(buf, len, arrivalNanos)
                        override fun onUdpError(e: Exception) {}
                        override fun requestCryptResync() = sm.requestCryptResync()
                    }).also { it.connect(host, 64738) }
                    voice.start()
                }
                override fun onTcpRtt(rttMs: Double) = selector.onTcpRtt(rttMs)
                override fun onTunneledVoice(p: ByteArray, len: Int, ar: Long) = voice.onPlaintext(p, len, ar)
            })
        }

        suspend fun run(username: String, label: String) {
            tcp.connect(host, 64738, object : MumbleTcpTransport.Listener {
                override fun onFrame(frame: TcpFrame) = sm.onFrame(frame)
                override fun onClosed(cause: Throwable?) {}
            })
            sm.start(username, null)
            withTimeout(10_000) { sm.state.first { it is ConnectionState.Synchronized } }
            assertTrue(model.state.value.channels.isNotEmpty())
            repeat(3) { sm.sendPing(); delay(1_000) }
            withTimeout(20_000) { synthetic.stats.first { it.received >= 100 } }
            val st = synthetic.stats.value
            println("LOOPBACK[$label] mode=${selector.mode} $st")
            assertTrue("avg RTT sane: ${st.avgRttMs}", st.avgRttMs in 0.0..250.0)
        }

        fun shutdown() { voice.stop(); udp?.close(); sm.disconnectLocal() }
    }

    @Test fun udpLoopback() = runBlocking {
        val h = Harness(host!!, forceTcp = false)
        try {
            h.run("drumble-it-udp", "udp")
            assertEquals(VoiceTransportMode.UDP, h.selector.mode)
        } finally { h.shutdown() }
    }

    @Test fun forcedTcpTunnelLoopback() = runBlocking {
        val h = Harness(host!!, forceTcp = true)
        try {
            h.run("drumble-it-tcp", "tcp-tunnel")
            assertEquals(VoiceTransportMode.TCP_TUNNEL, h.selector.mode)
        } finally { h.shutdown() }
    }
}
```

- [ ] **Step 3: Run both integration tests** (Verify command above). Expected: PASS, with both `LOOPBACK[udp] ...` and `LOOPBACK[tcp-tunnel] ...` lines printed — capture them. If the handshake stalls or loopback frames never return, first suspects in order: UDP type-byte values (flip `UDP_TYPE_AUDIO`/`UDP_TYPE_PING` per upstream `docs/dev/network-protocol/voice_data.md` and packet capture), `Version` setter names, `Audio.target` loopback support on the server version — fix, re-run, note in commit.

- [ ] **Step 4: On-device drill.** `./gradlew :app:installDebug` on an emulator (server reachable at `10.0.2.2`). Start Call → observe: Telecom call becomes active only at Synchronized; stats TextView ticking (mode/RTTs/loop counters); hang up via button, reconnect, hang up via notification. Capture `adb logcat -s MumbleManager SessionStateMachine` excerpts and a screenshot as evidence.

- [ ] **Step 5: Commit**

```bash
git add docs/dev/mumble-server/docker-compose.yml app/src/test/java/com/example/drumble/mumble/integration/LiveServerIntegrationTest.kt
git commit -m "test: live-server integration — Synchronized, UDP loopback, TCP-tunnel drill"
```

---

## Notes for the executor

- **Order:** 1 → (2, 3, 4 in any order) → 5 → 6 → 7 → 8 → 9 → 10.
- **Full suite between tasks:** `./gradlew :app:testDebugUnitTest` must stay green after every task.
- **Generated-proto setter names** are the plan's main guess-surface (`setVersionV1` vs `setVersion` etc.); trust the generated code, keep wire field numbers authoritative.
- **No Android imports** outside `MumbleManager.kt`, the Telecom files, and `ActiveCallActivity.kt` — this is what keeps Tasks 2–8 JVM-testable and the integration test runnable headless.
- **Reference checkouts:** Humla at `~/git/quite/humla` (GPL — read for semantics, never copy); BSD desktop sources fetched to `/tmp/mumble-ref/` in Task 2.
