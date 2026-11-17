# Audio capture

Microphone to Mumble audio packets. Transmit is push-to-talk or voice activity, chosen in Settings.
The packets leave over UDP or the TLS tunnel ([network.md](network.md)). The constants are in
`CaptureConstants.h`.

## Goals

- **One 20 ms Opus packet at a time, on a steady clock.** `frame_number` keeps counting while
  nothing is sent, as desktop Mumble's does.
- **Never two input streams open, and never an engine freed under a thread still using it.**
- **Give up the microphone** to a phone call or another voice app, and take it back afterwards.
- **Under voice activity, send speech and only speech**
  ([voice-activity.md](voice-activity.md)).

## Design

```
mic ─► OboeCapture ─► PcmRing ─► assemble packet ─► AudioEncoder ─► pollPacket ─► VoiceSender ─► transport
       (android/)     └────────── CaptureEngine (core/) ─────────┘                (Kotlin pump)
```

**`CaptureEngine`** (`core/`, no Android dependency) owns everything between "PCM arrived" and "an
Opus packet is ready". The audio callback calls `onPcm`, which is lock-free and writes into a
ring. The pump thread calls `pollPacket`, which assembles a packet, encodes it and may block.
Under push-to-talk `PacketAssembler` builds the packet. Under voice activity it comes from the
preroll queue (`popPacket`), and `PacketAssembler::takePacket` never runs.

**`OboeCapture`** (`android/`) owns the Oboe input stream. The pump thread is its only caller, so
it has no lock. A poll that finds the stream down reopens it, at most once a second.

**`VoiceSender`** (Kotlin) is the pump: one daemon thread parked in `pollPacket`, wrapping each
packet for the transport. Its exit callback is the only signal that the pump is gone.

## Decisions

| Decision | Why | Cost |
|---|---|---|
| The stream opens with the `VoiceCommunication` input preset | It brings the platform's echo canceller, noise suppressor and gain control, which nothing in `core/` replaces. | The low-latency input path is lost. Measured: a 960-frame burst (20 ms) where `VoiceRecognition` opens at 96 (2 ms). |
| The stream stays open while Talk is released | It is warm for the next press, and the frame clock keeps running. | The input stream runs for the whole session. |
| Every lifecycle change is a command to one consumer | Four sources ask for changes concurrently. | State has to be modelled as levels, so that commands can arrive in any order. |
| A hold closes capture instead of muting it | Which of two voice captures keeps the microphone differs between devices (measured both ways). Closing ours is the only way to be sure the other call gets it. | The stream is reopened on resume. |
| A pump that will not exit leaks its engine | The alternative is freeing an engine a thread may still touch. | Leaked memory until the process ends. |

## Transmit gate

While the gate is closed, `onPcm` advances the frame clock and discards the samples.

The gate is not stored. `apply` derives it from three levels as
`!muted && (pressed || voiceActivity)`, whenever one of them changes and again when the capture
session opens.

- **Muted** is the `self_mute` the app last asked the server for, including one forced by a
  deafen. It is not the server's echo, so the gate closes at the tap.
- **Wanting the gate open also asks for capture again,** which is how capture comes back after a
  terminal failure.
- **The transmit mode lives on the connection, not the session.** A rebuilt engine starts in
  push-to-talk and is given the mode back.

The KDoc on `apply` explains why a press cannot race the session opening.

## Lifecycle

`MumbleConnection` owns it. Four sources ask for transitions concurrently: the Talk button,
platform hold and resume, disconnect and reconnect, and the pump's own exit. Each transition is a
command on one channel with a single consumer. State is kept as levels, not events, and
`reconcile()` compares the levels and is the only place the capture session opens or closes.

The invariants, pinned by `CaptureLifecycleTest`, `CaptureLifecycleChaosTest` and `VoiceSenderTest`:

- Two input streams are never open at once.
- The engine is freed only after its pump has exited.
- Capture is not reopened automatically after a terminal failure.
- A hold reaches only the live session, and pauses playout without waiting for the capture
  consumer.

## Platform call

`AndroidVoiceCall`, behind the `VoiceCall` interface. The session sets `MODE_IN_COMMUNICATION` and
picks the route with `setCommunicationDevice`. There is no Telecom call
([architecture.md](architecture.md), Decisions); `AudioService` still opens a Bluetooth headset's
call link (the two-way link that carries its microphone, SCO on a classic headset) for the device
we set. No audio focus is requested, so other apps' media keeps playing.

Two things hold the session:

- **The phone takes the audio:** the audio mode moves to ringing or in-call.
- **Another app captures voice:** a `VOICE_COMMUNICATION` recording whose audio session id is not
  ours. Every capture stream opens with `CaptureSessionId`'s id, because the platform anonymizes
  every recording it reports but keeps that id.

A hold closes the capture session and pauses playout: the receiver drops incoming packets and
pauses its output stream. The mode and the recording callback also report the hold ending, so the
session resumes by itself. A Talk press held through the hold transmits once it ends.

## Speaking indicator

`VoiceSender` reports whether we are speaking, and `MumbleConnection` publishes it as
`selfSpeaking` in either transmit mode. A packet of speech that reaches the wire raises it. It is
lowered by the first packet of the hangover (flagged `kFlagHangover` by the engine), a terminator,
a refused send, 100 ms with no packet, and the pump ending. The indicator fades over the 200 ms
hangover, so it is dark as the spurt ends.

## What our own sheet shows

`SelfDetailSheet`, opened from the roster row marked YOU, is the send-side counterpart of the
per-speaker sheet in [playout.md](playout.md).

| Row | Meaning |
|---|---|
| Latency, mouth to server | A floor made of the steps we can read (`SendDelay`): the device's input buffer (`CaptureStats.inputLatencyMillis`), the packet's 20 ms plus the encoder's mean time, and half our round trip on the path carrying voice. |
| Packet loss | Murmur's count of our datagrams (`UserStats.from_client`, the rolling window on a 1.5 server). It covers our voice while on UDP, and the UDP pings always, so for a tunneled talker it describes the pings only. |
| Overruns | Microphone audio lost before it was encoded, from both rings. |
| Dropped sends | Packets the transport refused. |

The counters are read from the pump every two seconds (`VoiceSender.onStats`) and logged every ten.

## Voice activity

Under voice activity the gate in `onPcm` stays open for the session while not muted, and a second
gate decides per frame whether to transmit, from a speech probability computed by a Silero model
in the portable core. A spurt opens with 60 ms of audio from before the gate opened and closes
after a 200 ms hangover. The detector, its constants and its measurements are in
[voice-activity.md](voice-activity.md).
