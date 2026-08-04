# Architecture

One section per subsystem, added as each stabilises. This file describes what is, not the history
of how it got there — that lives in the design specs and PR descriptions.

## Audio capture

Capture is a push-to-talk pipeline from the microphone to Mumble UDP-tunnel packets on the TCP
transport, split across three layers with a single owner for the lifecycle.

**Native engine** (`app/src/main/cpp`). `OboeCapture` (`android/`) owns the Oboe input stream and
its disconnect/reopen backoff. `CaptureEngine` (`core/`) is platform-free and owns everything
between "PCM arrived" and "an Opus packet is ready": the audio callback writes into a lock-free
`PcmRing`, `FrameAssembler` slices fixed frames, `AudioEncoder` wraps libopus. The push-to-talk
gate lives in the engine's `onPcm`: while closed, samples advance the frame-number clock but are
never captured, so the stream stays open and warm across presses. Sample rate and frame size are
owned by `CaptureConstants.h`; bitrate is policy, passed in from Kotlin. `capture_jni.cpp` exposes
one `Session` per engine through the `NativeCapture` handle.

**Pump** (`VoiceSender`). One daemon thread parked in `pollFrame` inside native code, wrapping
each packet for the transport. `stop()` only requests shutdown; the `onExit` callback — fired from
a `finally` on the pump thread itself — is the only signal that the pump is gone, and the
lifecycle owner releases the engine from there. Nothing may call `destroy()` while the pump can
still touch the handle.

**Lifecycle** (`MumbleConnection`). Four producers demand transitions concurrently — the Talk
button, telecom hold/resume callbacks, disconnect/reconnect, and the pump's own exit — so every
transition is a `CaptureCommand` on one unbounded channel consumed by a single coroutine. State is
levels, not events: `wanted` (a capture session is wanted), `heldGen` (the platform holds the call
of this generation), `releasing` (a stop is in flight). `reconcile()` compares the levels and is
the only place a session is opened or released. A live session is a `CaptureSession` — the native
handle and its pump bound as one unit, with a monitor so a push-to-talk edge can never reach an
engine `destroy()` has freed.

Session and transmit are separate controls, and both are levels. `requestCapture()` raises `wanted` —
at connect, and again on every Talk press, which is what lets a session lost to a terminal failure
or a hold come back. `setTransmitting()` raises `transmitting`, then applies it to whatever session
exists at that instant; `openSession` publishes the new session and then reads the level. Those two
orders are mirrored so a press cannot fall between them, which is what makes a Talk button held
across a hold still transmitting when the platform returns the call.

**Platform call** (`TelecomCall`, behind the `VoiceCall` seam). Each connection registers a
self-managed platform call, which is what grants audio focus, `MODE_IN_COMMUNICATION` routing, and
the microphone foreground service (`VoiceService`). A hold — an incoming cellular call is the case
that matters — releases the capture session entirely rather than gating it: the platform owns the
input device for the duration. core-telecom sends no unsolicited resume, so a Talk press while
held doubles as the resume request (`requestActive`).

Invariants, pinned by `CaptureLifecycleTest`, `CaptureLifecycleChaosTest`, and `VoiceSenderTest`:

- Never two open input streams: a release closes the stream synchronously on the consumer
  coroutine, so the channel itself orders it ahead of any later open.
- The engine is freed only after its pump has exited, never on a timeout. A pump that will not
  exit leaks its engine deliberately (logged by the wedge watchdog) rather than risk a
  use-after-free.
- A terminal engine failure does not auto-reopen; the retry is the next Talk press.
- Hold/resume callbacks are keyed by connection generation, so a superseded call's late callback
  cannot latch onto its successor.
