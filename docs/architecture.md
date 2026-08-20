# Architecture

How the pieces fit together, at the level of what talks to what. Detail lives in the
per-subsystem docs; sections and docs get added as each subsystem stabilises. This file describes
what is, not the history of how it got there — that lives in the design specs and PR descriptions.

Dumble is an Android Mumble client. One TLS connection to the server carries everything: protobuf
control messages and voice as Mumble UDP-tunnel packets on the same TCP transport — there is no
UDP socket. Voice is mono end to end; positional audio is out of scope by decision (see
`CLAUDE.md`).

```
 UI (Compose)                          observes flows, issues commands
      │
 MumbleConnection                      the connection and every lifecycle hanging off it
      │
      ├─ MumbleTcpTransport            TLS (platform SSLSocket, certificate pinning)
      │    └─ SessionStateMachine      the control protocol; channel tree, chat, users as flows
      │
      ├─ VoiceSender ── CaptureEngine ── OboeCapture ── mic          docs/capture.md
      ├─ VoiceReceiver ── PlayoutEngine ── AudioTrack ── speaker     docs/playout.md
      │
      └─ TelecomCall                   self-managed platform call: audio focus, routing,
                                       the mic foreground service, hold/resume
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

**Platform call** (`telecom/`). Registering a self-managed telecom call is what grants audio
focus, communication routing, and the microphone foreground service. An incoming cellular call
holds it, which releases capture entirely for the duration — see the platform-call section of
`docs/capture.md`.
