# Architecture

What the app is built to do, how it is divided, and the decisions that shaped the division. Each
subsystem has its own page with the same structure: goals, design, decisions and their costs.

## Goals

- **Work with a stock Mumble server.** Version 1.5 or newer, alongside desktop clients, behaving
  as desktop does unless a page says why not.
- **Low, known voice delay.** Delay is measured at each stage, shown per speaker, and pinned in a
  CI test, so a change that adds delay shows up as a diff.
- **A call survives the network changing.** WiFi to cellular, a brief outage.
- **Share the phone.** A phone call or another voice app takes the microphone and gets it; the app
  resumes by itself afterwards.
- **Testable without a device.** The audio engines run on the host with the real codec, and the
  Kotlin runs on virtual time.

Non-goals:

- **Positional audio.** Voice is mono end to end, so nothing on the playout path carries a channel
  count.
- **Servers older than 1.5.** They are refused at the handshake.

## Design

```
 UI (Compose, ConnectViewModel)        observes StateFlows, issues commands
      │
 Connection (interface)                what the UI sees; faked in ViewModel tests
      │
 MumbleConnection                      one per process; owns everything below
      │
      ├─ MumbleTcpTransport            TLS on the platform SSLSocket, certificate pinning
      │    └─ SessionStateMachine      control protocol: handshake, pings, channel tree, chat
      ├─ MumbleUdpTransport            voice datagrams, encrypted with OCB2
      │
      ├─ VoiceSender ── CaptureEngine ── OboeCapture ── microphone      capture.md
      ├─ VoiceReceiver ── PlayoutEngine ── OboePlayout ── speaker       playout.md
      │
      └─ AndroidVoiceCall              audio mode and route, foreground service, hold and resume
```

One TLS connection carries the protobuf control messages. Voice initially goes over the TCP
connection, but switches to UDP once a round-trip ping proves UDP connectivity. Audio is 48 kHz
mono 16-bit PCM throughout, counted in 10 ms frames and sent as 20 ms Opus packets.

**Connection** ([network.md](network.md)). `MumbleConnection` is a Hilt singleton bound to the
`Connection` interface. It runs the TLS connect, the protocol session and the reconnects, and
publishes one `status` flow. One session is live at a time, and that session owns both audio
pipelines and the platform call.

**Audio** ([capture.md](capture.md), [playout.md](playout.md)). Capture and playout are separate
pipelines built the same way: a C++ engine with no Android dependency (`cpp/core`), an Oboe
adapter and a JNI file (`cpp/android`), and one Kotlin owner (`VoiceSender`, `VoiceReceiver`).

**Platform call.** `AndroidVoiceCall` sets `MODE_IN_COMMUNICATION` and picks the route with
`AudioManager.setCommunicationDevice`. When the phone or another app takes the microphone the
session is held: capture is released and playout paused until the other call ends.

**Foreground service.** `VoiceService` runs for the life of a call. It is typed `microphone` when
`RECORD_AUDIO` is granted and `mediaPlayback` otherwise, so a user who refuses the microphone can
still listen in the background.

## Decisions

| Decision | Why | Cost |
|---|---|---|
| Audio engines in portable C++, Android confined to a thin adapter | The realtime audio callbacks never enter the JVM, and CI runs the engines on the host against the real libopus, sample-exact. | A JNI seam, and a few constants kept in step by hand on both sides. |
| Oboe streams driven by the device's callbacks | The device paces the audio, so the buffer can be two bursts. A Kotlin loop feeding an `AudioTrack` could not hold a buffer that small (measured: about 3000 underruns a minute). | Below API 37 a low-latency stream disconnects on every route change and must be reopened. |
| Platform `SSLSocket` for TLS | TLS 1.3, patched by the OS. Ktor's own TLS was 1.2 only when this was decided (2026-07). | Reads block and cannot be cancelled, so teardown closes the socket and late results are discarded by generation (below). |
| No Telecom call | Telecom shows a call to every in-call service that asks or to none. The first lets another app's dialer draw over our screen; the second leaves a Bluetooth headset without call audio. | The app detects holds itself, from the audio mode and the recording callback, and has no system call UI. |
| Voice starts inside TLS and moves to UDP on a ping reply | It works at once through any firewall, and UDP is used only once proven in both directions. | Until promotion, voice has TCP's head-of-line blocking. |
| Servers older than 1.5 are refused | A 1.4 server silently drops protobuf voice, so a session there has no audio. Refusing also removes the legacy voice framing and CELT. | No access to old servers, even for chat. |
| Voice activity detector written by hand, no inference runtime ([voice-activity.md](voice-activity.md)) | ONNX Runtime is about 32 MB per ABI; the detector is 1.2 MB of weights and about 300 lines of C++. | Every model change must be re-verified against committed reference traces. |

## Threads

| Thread | Runs | Constraint |
|---|---|---|
| Coroutines on `Dispatchers.Default` | `MumbleConnection`, `SessionStateMachine` | blocking calls (stream opens, socket closes) go to `Dispatchers.IO` |
| TLS reader coroutine | inbound frames; tunneled voice goes straight to `VoiceReceiver.onVoicePacket` | sole caller of the transport's `onClosed` |
| UDP reader thread | decrypts datagrams, hands voice to `VoiceReceiver.onVoicePacket` | |
| Capture audio callback | `CaptureEngine.onPcm` | realtime: no locks, no allocation |
| Capture pump (`VoiceSender`) | blocks in `pollPacket`, which encodes; then sends | owns the input stream once running; the first open is made by the capture consumer, before the pump exists |
| Playout audio callback | `PlayoutEngine.fillQuantum`: decode and mix | realtime: `try_lock` only, answers silence if contended |
| Playout poll coroutine (`VoiceReceiver`) | starts, pauses and reopens the output stream; reads stats every 50 ms | owns the output stream |
| Main looper | Compose, and all `AndroidVoiceCall` state | |

Each stream has one caller at a time, so neither Oboe adapter needs a lock.

## Where the code lives

| Path | Contents |
|---|---|
| `app/src/main/java/.../ui/` | screens, `ConnectViewModel`, theme |
| `.../mumble/connection/` | `Connection`, `MumbleConnection`, `Link`, `ConnectionStatus` |
| `.../mumble/net/` | TLS and UDP transports, OCB2 (`Ocb2`, `CryptState`, `ReplayWindow`), trust and pins, client certificate, `VoicePath` |
| `.../mumble/protocol/` | `SessionStateMachine`, framing (`MumbleCodec`), mute and deafen (`DeafenState`) |
| `.../mumble/voice/` | `VoiceSender`, `VoiceReceiver`, JNI wrappers (`NativeCapture`, `NativePlayout`), `AndroidVoiceCall`, stats |
| `.../service/`, `.../data/`, `.../di/` | foreground service, DataStore-backed stores, Hilt modules |
| `app/src/main/cpp/core/` | portable engines: capture, playout, codec wrappers, voice activity detector |
| `app/src/main/cpp/android/` | Oboe adapters and JNI entry points |
| `app/src/main/cpp/host/` | CMake project that builds `core/` and its tests for the host |
| `app/src/main/proto/` | upstream `Mumble.proto` and `MumbleUDP.proto` |
| `app/src/test/cpp/` | GoogleTest suites for `core/` |
| `third_party/` | libopus and GoogleTest submodules, the Silero model |
| `tools/silero/` | offline scripts that regenerate the detector's weights and reference traces |

## Conventions

These recur across the code. Each exists because of a specific failure.

**Generations.** Teardown cannot always stop a TLS handshake in flight, so a superseded connect can
finish late and overwrite a newer session's state. Every `connect()` bumps a generation, and every
flow write is checked against it under one lock, so a late write does nothing.

**Levels, not events.** Four things ask for capture to open or close, concurrently. Each sends a
command to one channel. Its single consumer sets a level (capture wanted, call held, session live)
and reconciles the levels, and that is the only place the capture session opens or closes. Two
input streams can therefore never be open at once.

**Publish last.** A `StateFlow` emission is a promise that everything the new state implies is
already in place: the timer armed, the resource built. A reader that acts on the emission must
find it there, so the bookkeeping is written before the state. The exception is a write that is
also the guard settling a race, such as `SessionStateMachine`'s `compareAndSet` out of
`Handshaking`. There the bookkeeping follows on the same thread, and a test drives the scheduler
past it with `runCurrent()` instead of resuming on the emission.

**Seams for tests.** Clocks, dispatchers, transports and the native engines are constructor
parameters with production defaults, so the JVM tests run on one `StandardTestDispatcher` and
virtual time. `AGENTS.md` has the test rules.

**Clocks.** Anything that must count time spent asleep (ping age, the reconnect budget) reads the
boot clock, `BootTimeSource`. Kotlin durations are `kotlin.time.Duration`, not raw milliseconds.

**Twin constants.** `AudioConstants.kt` repeats a few values from `CaptureConstants.h` and
`PlayoutConstants.h` on purpose. The sample rate is derived from the protocol on each side, and
the JNI seam checks the speaker limit.
