# Architecture

How the pieces fit together, at the level of what talks to what. Detail lives in the
per-subsystem docs; sections and docs get added as each subsystem stabilises. This file describes
what is, not the history of how it got there — that lives in the design specs and PR descriptions.

Dumble is an Android Mumble client. One TLS connection to the server carries the protobuf control
messages and, until a UDP path proves itself, our own voice as Mumble UDP-tunnel packets; a UDP
socket beside it carries voice both ways once one does (`docs/connection.md`). Voice is mono end
to end; positional audio is out of scope by decision (see `CLAUDE.md`).

```
 UI (Compose)                          observes flows, issues commands
      │
 MumbleConnection                      the connection and every lifecycle hanging off it
      │
      ├─ MumbleTcpTransport            TLS (platform SSLSocket, certificate pinning)
      │    └─ SessionStateMachine      the control protocol; channel tree, chat, users as flows
      ├─ MumbleUdpTransport            voice datagrams (DatagramChannel, OCB2 via CryptState)
      │
      ├─ VoiceSender ── CaptureEngine ── OboeCapture ── mic          docs/capture.md
      ├─ VoiceReceiver ── PlayoutEngine ── AudioTrack ── speaker     docs/playout.md
      │
      └─ AndroidVoiceCall              the audio mode and route, the mic foreground service,
                                       hold/resume
```

**Connection** (`mumble/`). `MumbleConnection` owns the blocking TLS connect — trust decisions
happen before the protocol starts — and the `SessionStateMachine` that follows, unified into one
`status` flow. Only one connection is active at a time, and it is the lifecycle owner for both
audio pipelines and the platform call. `docs/connection.md`.

**Audio** (`app/src/main/cpp` + `mumble/voice/`). Capture and playout are independent pipelines
built the same way: a platform-free C++ engine in `core/`, a thin JNI seam in `android/`, and one
dedicated Kotlin thread driving it. They share constants discipline, not code — each side's
constants answer to the protocol, deliberately not to each other.

- **Capture** — microphone to Opus packets: the engine, its lifecycle, the transmit gates, and the
  voice activity detector. `docs/capture.md`.
- **Playout** — packets to the speaker: per-speaker queueing and decode, mixing, and the playback
  loop. `docs/playout.md`.

**Platform call** (`mumble/voice/AndroidVoiceCall`). The session owns the audio mode and route
through AudioManager and registers no Telecom call: Telecom shows a call to every InCallService
that asks, dialers and Bluetooth alike, or to none, so a Telecom call either lets a dialer draw its
screen over ours or leaves a Bluetooth headset without call audio. The phone taking the audio, or
another app capturing voice, holds the session, which releases capture and pauses playout for the
duration — see the platform-call section of `docs/capture.md`.

## Publish last

A published state is a promise. When a `StateFlow` write is a plain write, everything the new
state implies — a timer armed, a mark seeded, a resource built — is written before it, so a reader
acting on the emission finds it in place. Where the write doubles as the guard that settles a race
(`SessionStateMachine`'s `compareAndSet` out of `Handshaking`), the bookkeeping cannot precede it;
it stays on the same thread, right after, and a test that cares drives the scheduler to that point
(`runCurrent()`) rather than resuming on the emission. The two test races of 2026-09 (#165, #167)
were tests resuming on a publish and reading bookkeeping that had not happened yet.
