# Drumble Mumble Network Layer — Design

**Date:** 2026-07-11
**Status:** Approved for planning
**Scope:** Control channel + low-latency voice packet transport. No audio capture/playback/codec this milestone.

## Goal

Replace the `MumbleManager` placeholder with a clean-room Kotlin Mumble client that:

1. Connects to a Mumble server (TLS + protobuf handshake) and reaches **Synchronized**.
2. Tracks the channel/user tree and connection state, exposed as `StateFlow`s.
3. Runs a low-latency, bidirectional **voice packet transport**: UDP + OCB2-AES128, TCP-tunnel fallback, RTT/loss/jitter stats.
4. Exposes a frame-level **audio seam** shaped as the permanent JNI boundary, exercised this milestone with synthetic frames via server loopback.
5. Bridges connection state into the existing Telecom call shell (`CallManager`/`DrumbleConnection`).

## Non-goals (this milestone)

- Audio capture, playback, encode/decode, AEC (future native engine: Oboe + libopus).
- Native code of any kind. The entire milestone is Kotlin + JCE + generated protobuf.
- Legacy (pre-1.5) UDP voice format. Servers must be ≥ 1.5.
- Client-certificate auth/registration, voice targets/whisper, text chat, reconnect polish.

## Background & key decisions

### Why clean-room instead of Humla

Code review of Humla (local checkout `~/git/quite/humla`) found:

- **License:** GPLv3. Linking it forces Drumble to GPLv3; Drumble's license is undecided. A clean-room client keeps options open. Humla is used as a *reference only* — no code copied. (Its `CryptState` itself derives from BSD Mumble desktop sources; the canonical protobuf definitions are BSD from upstream `mumble-voip/mumble`.)
- **Audio coupling:** Humla is an all-in-one `HumlaService` — networking and audio welded together, raw audio deliberately not exposed. Drumble intends to own the audio path (custom AEC work), so Humla's main value is the part we'd discard.
- **Latency ceiling:** legacy `AudioRecord`/`AudioTrack` (no AAudio/Oboe), fixed 100 ms Speex jitter-buffer margin, ~120 ms output buffer cap. Hardware-only AEC (Speex echo cancel is a TODO). Not tunable to our goals.
- **Toolchain:** 2014-era JavaCPP + ndk-build; crusty against AGP 8 / compileSdk 36.

Humla's *networking* design is sound (dedicated TCP/UDP threads, UDP-preferred with TCP fallback) and informs this design.

### Protocol version

**New protobuf UDP protocol only** (Mumble ≥ 1.5, `MumbleUDP.proto`: `Audio`, `Ping` messages). No hand-rolled varint/bit-packed legacy voice framing. The protobuf switch changes only the payload format — **the encryption envelope is unchanged: OCB2-AES128 keyed via `CryptSetup`**. If the server's `Version` indicates < 1.5, fail with a clear error.

### Language split (researched + agreed)

At the target operating point (network RTT 20–100 ms), packet-path language changes mouth-to-ear latency by ~0: decrypt+parse is microseconds; jitter buffers must absorb 10–30 ms of network variance regardless; and **native threads get no better scheduling than JVM threads** (only system-created Oboe/AAudio callback threads receive `SCHED_FIFO`). Therefore:

- **Kotlin owns the network world** (sockets, OCB2, protobuf, control channel) — permanently, not as a stopgap.
- **The future native side owns the real-time world** (Oboe callbacks, libopus, jitter/ring buffers). libopus decode must live playout-side because PLC and Opus in-band FEC are playout-clock decisions (also why MediaCodec is rejected: it exposes no PLC/FEC control).
- **JNI boundary = decrypted Opus frame in / encoded Opus frame out.** One JNI down-call per packet per direction (≈50–100/s, ~1 µs each — negligible). Down-calls, not up-calls: no `JNIEnv` attachment, no managed callback refs.

## Architecture

Package `com.example.drumble.mumble`:

```
mumble/
├── MumbleManager.kt            # facade (replaces placeholder)
├── net/
│   ├── MumbleTcpTransport.kt   # TLS socket, framing, read loop + writer
│   ├── MumbleUdpTransport.kt   # DatagramChannel, encrypt/decrypt at edges
│   ├── CryptState.kt           # OCB2-AES128 (pure Kotlin + JCE)
│   └── TransportSelector.kt    # UDP↔TCP-tunnel policy + NetStats
├── protocol/
│   ├── MumbleCodec.kt          # TCP frame [u16 type][u32 len][protobuf]; UDP [u8 type][protobuf]
│   └── SessionStateMachine.kt  # handshake, pings, CryptSetup/resync, message dispatch
├── model/
│   ├── MumbleModel.kt          # channel tree + user map from state messages
│   └── (Channel, User, ServerInfo data classes)
└── voice/
    ├── VoiceTransport.kt       # send/receive voice frames over selected transport
    ├── VoiceEngineSeam.kt      # frame-level seam (future JNI boundary); blocking
    │                           #   nextEncodedFrame(timeout) / putIncomingFrame(frame, ts)
    └── SyntheticVoiceSource.kt # this milestone's stand-in behind the seam
```

Protobuf: vendor `Mumble.proto` + `MumbleUDP.proto` from upstream `mumble-voip/mumble` (BSD), generate with the `com.google.protobuf` Gradle plugin, **javalite** runtime.

### Component responsibilities

**MumbleTcpTransport** — `SSLSocket` over a dedicated single-thread coroutine dispatcher for the read loop (frame → decode → emit `Flow<MumbleMessage>`); separate writer coroutine drains a `Channel` so sends never block reads. TLS trust: **accept-and-pin on first use (TOFU)** — persist the server cert's SHA-256 fingerprint; mismatch on reconnect = hard fail. *Flagged: proper pinning/CA validation required before real-world use.*

**MumbleUdpTransport** — connected `DatagramChannel`, blocking mode, **dedicated raw threads** (not coroutine dispatchers — no dispatch-queue wakeup hops on the hot path):

- *Receive thread* (`THREAD_PRIORITY_URGENT_AUDIO`): blocking `receive()` into a pooled direct `ByteBuffer`; stamp arrival with `System.nanoTime()` (same `CLOCK_MONOTONIC` domain Oboe uses); OCB2-decrypt; route by type byte (Audio → voice path, Ping → stats).
- *Send thread* (`URGENT_AUDIO`): drains an `ArrayBlockingQueue`/SPSC of outgoing frames; OCB2-encrypt; `send()`.
- **Zero-allocation discipline in both loops**: pooled direct buffers, preallocated frame holders, no per-packet objects.

**CryptState** — OCB2-AES128 port (JCE `AES/ECB/NoPadding` as block primitive; OCB2 mode logic — S2/S3 GF(2¹²⁸) doubling, XOR, 3-byte tag — in Kotlin). IV counter management, late/lost/reorder window (±30, history table), replay rejection, and the good/late/lost/resync counters that feed both `NetStats` and the transport selector. Resync: on persistent decrypt failure (>5 s since last good and last request), send empty `CryptSetup` to request server resync. **Highest-risk component → known-answer tests first** (vectors cross-checked against Mumble desktop's BSD test suite), plus round-trip, tamper-reject, replay/reorder tests. *Known caveat: OCB2 has published cryptographic weaknesses; it is required for Mumble compatibility and is not ours to change.*

**SessionStateMachine** — drives: TCP connect → send `Version` (advertising 1.5+ / protobuf UDP) + `Authenticate` (username, optional server password, opus=true) → receive `Version` (reject < 1.5), `CryptSetup` (init `CryptState`, start UDP), `ChannelState`/`UserState` stream → `ServerSync` → **Synchronized**. Steady state: TCP `Ping` every 5 s carrying crypt stats; UDP protobuf `Ping` every 5 s with timestamp for RTT. States: `Disconnected → Connecting → Handshaking → Synchronized → Disconnecting`, plus `Failed(cause)`.

**TransportSelector** — Mumble-compatible policy, evaluated each ping tick: start optimistic-UDP; if using UDP and crypt `good`/`remoteGood` counters stall for ~2 consecutive ping intervals → switch voice to TCP-tunnel (`UDPTunnel` messages; same `[u8 type][protobuf]` plaintext, no OCB2 — TLS protects it); when UDP pings flow again (good > 3 both directions) → switch back. Exposes `NetStats(transport, tcpRttMs, udpRttMs, udpJitterMs, good, late, lost, resync)` as `StateFlow`.

**MumbleModel** — applies `ChannelState`/`UserState`/`UserRemove`/`ChannelRemove` to an immutable snapshot; exposes `StateFlow<ServerModel>`.

**MumbleManager** (facade) — `connect(server: ServerAddress, identity: Identity)`, `disconnect()`, `state`, `model`, `stats`, and the voice seam accessors. Owned by a service-lifecycle scope (survives UI teardown; ties into the existing foreground-service model).

## Threading & latency model

Clock-master principle: **the mic pushes, the speaker pulls.** Send-side jitter after frame capture is absorbed by the *receiver's* jitter buffer; receive-side playout is hard-real-time and belongs to the (future) native audio engine.

**Send path (end state; this milestone substitutes `SyntheticVoiceSource`):**

```
Oboe capture callback (RT, native):  memcpy burst → lock-free SPSC PCM ring, atomic index
                                     advance; on crossing a frame boundary (once per ~10 ms,
                                     not per burst): one semaphore post — bounded ~1 µs,
                                     enters the kernel only if a waiter is parked.
                                     No locks, no JNI, no unbounded calls.
Kotlin voice-send thread:            JNI ↓ nextEncodedFrame(timeout = frameInterval + ε):
                                     parks on the semaphore; woken edge-triggered at actual
                                     frame completion (send jitter ≈ scheduler wake latency,
                                     ~0.1 ms). Timeout is a missed-signal backstop (costs
                                     ≤ 1 frame). Drains in whole frames — back-to-back if
                                     late — opus_encode → bytes; then per frame:
                                     protobuf Audio{frame_number, opus_data} → OCB2 → send.
```

- **Signal-driven, not polled.** A fixed wall-clock poll grid drifts in phase against the audio hardware clock; when the phase sweeps near zero, frame send times go bimodal (now vs. +1 interval) in slow beats — wandering jitter that inflates the receiver's adaptive jitter buffer. Edge-triggered wakes eliminate this by construction. Signaling from audio callbacks via a bounded wake (`sem_post`/`eventfd`) is established practice (WebRTC's audio device layer does it); a pure timed-poll mode is retained as a per-device fallback config, accepting the drift-beat jitter.
- Reads are **frame-quantized** (Opus fixed frame sizes), never "all available."
- **Ring capacity ≠ latency**: latency is what you *leave* in the ring (drain-to-empty each wake ⇒ ~0); capacity is stall insurance — size ~8 frames (80 ms).
- Encoding is native *code* on the Kotlin thread — a JNI down-call is the same OS thread crossing a function boundary, not a handoff. Thread census on send: exactly two.
- **This milestone:** `SyntheticVoiceSource` is self-clocked — absolute-deadline `parkNanos` to 10 ms boundaries (no hardware clock exists to phase-match). The seam's blocking-with-timeout semantics are specified now so nothing changes shape when the native engine lands.

**Receive path (end state; this milestone loops frames back through the seam):**

```
Kotlin voice-recv thread:  blocking receive → arrival ts → OCB2 decrypt →
                           protobuf parse → JNI ↓ jitterPut(frameNo, opusData, ts)
Native playout side:       Oboe render callback pulls by playout clock:
                           frame present → opus_decode; missing → PLC / FEC-from-next.
                           (Optional later: tiny native decode thread pre-filling one burst.)
```

**This milestone** implements everything above the seam. Behind the seam sits `SyntheticVoiceSource`: emits sequenced dummy Opus-payload frames on the 10 ms cadence and consumes received frames, recording per-frame RTT/jitter/loss.

Frame interval default **10 ms** (Mumble's low-latency setting); packetization is config (`framesPerPacket = 1`) — revisit 20 ms (halves packet rate/power, +10 ms latency) in the audio milestone.

## Voice loopback validation (no audio required)

Use Mumble's **server loopback**: `Audio.target = 31` echoes packets back to the sender. The synthetic source sends stamped frames → server → back → seam consumer measures:

- per-frame RTT and jitter distribution over UDP;
- loss/late/reorder via `frame_number` gaps + crypt counters;
- **fallback drill**: force/deny UDP → voice continues via TCP tunnel, `NetStats.transport` flips, loopback stream unbroken; restore UDP → switches back.

This proves "consistent low latency both directions" with zero audio code.

## Telecom integration

- `MumbleManager.state` drives the call shell: `Connecting/Handshaking` → connection initializing; `Synchronized` → `DrumbleConnection.setActive()`; `Failed/Disconnected` → `setDisconnected()` + teardown.
- `ActiveCallActivity` "Start Call" → `MumbleManager.connect(testServerConfig)` (placeholder server + username config; a server-picker UI is a later feature). Hang-up path (`CallManager.disconnect()`) also calls `MumbleManager.disconnect()`.
- The client lives in the service scope so calls survive activity teardown.

## Error handling

- Every failure funnels to `SessionStateMachine.fail(cause)`: sockets closed, threads stopped, crypt zeroized, state → `Failed(cause)` (typed: DNS, TLS/pin mismatch, auth reject, version-too-old, timeout, I/O).
- UDP failures never fail the session — voice degrades to TCP tunnel; only TCP loss ends the session.
- No auto-reconnect this milestone (explicit non-goal); `Failed` is surfaced to UI + Telecom.

## Testing

1. **CryptState (first, highest risk):** OCB2 known-answer vectors (cross-checked against Mumble desktop's test suite), encrypt/decrypt round-trip, tamper rejection, IV reorder/replay window behavior, counter accounting.
2. **Codec/framing:** TCP frame round-trip, UDP `[type][protobuf]` round-trip, `Audio`/`Ping` encode-decode.
3. **State machine:** fake transport → scripted handshake to Synchronized; version-too-old rejection; CryptSetup→resync flow; model updates from state messages.
4. **Integration (local `mumble-server` ≥ 1.5 via Docker):** connect → Synchronized; channel/user tree correct; loopback voice RTT/jitter/loss over UDP; forced-TCP fallback drill; clean disconnect.
5. **On-device:** end-to-end call via Telecom shell against the Docker server; `NetStats` visible in `ActiveCallActivity` (debug text is sufficient).

## Risks

| Risk | Mitigation |
|---|---|
| OCB2 port correctness | KATs before anything else; Docker-server integration test |
| UDP blocked/NAT-unfriendly networks | TCP-tunnel fallback is in-scope and drilled |
| New-protocol details (type byte values, ping semantics) | Verify against upstream `docs/dev/network-protocol` + packet capture vs. official client during implementation |
| TOFU TLS is spoofable on first connect | Acceptable for dev; hard requirement to revisit before real use (tracked as flag) |
| GC/scheduling jitter on voice threads | Zero-alloc pools, raw dedicated `URGENT_AUDIO` threads, absolute-deadline wakeups; jitter measured by loopback harness |

## Future work (explicitly deferred)

- **Native audio engine:** Oboe (I/O) + libopus (codec; PLC/FEC playout-side) + adaptive jitter buffer + SPSC rings, behind the already-shaped seam. AEC decision (hardware vs. WebRTC APM) happens there, informed by `EchoTestActivity`.
- Reconnect/backoff, client certificates, server picker UI, text chat, whisper/targets, positional audio.
