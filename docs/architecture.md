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

## Audio playout

Playout is the inverse pipeline: Mumble UDP-tunnel packets in, mixed PCM to `AudioTrack` out,
split across the same three layers as capture.

**Native engine** (`core/PlayoutEngine.{h,cpp}`), platform-free. Its only clock is the tick — one
`fillQuantum` call, which today's playback loop drives at 10 ms quanta. Whether a count of ticks is
also a span of time depends on what those ticks did, and the constants divide along that line: a
tick that produced audio was paced by the output write, so `kConcealTicks` really is about 100 ms,
while ticks that produced nothing outrun real time — an arriving packet wakes the loop — which is
why the `*IdleTicks` bounds are ceilings on calls rather than periods. It owns
one `PacketQueue` and one `SpeakerDecoder` per sender, built up front by `create()`: a queue is
touched only under the mutex, a decoder only from the playback thread with the mutex released.
`offer()`, on the reader thread, judges the payload before the mutex and answers a status code; no
refusal is terminal — each is a condition a misbehaving server can produce at will, so the caller
latches a log rather than disabling receive. `fillQuantum` is shaped as an audio data callback —
non-blocking, allocation-free, taking its sample count per call — so an Oboe output stream could
drive it; today a Kotlin thread does, between `AudioTrack` writes. Encoded packets wait compressed
in `PacketQueue` behind a prebuffer gate, so a network stall cannot glitch the opening syllable;
`SpeakerDecoder`'s `PcmRing` decouples packet duration from the quantum; the mix finalizes through
`Mixer`'s soft-knee limiter. Retirement is the engine's alone — neither half can tell a speaker
that stopped talking from one still filling its prebuffer — and it is the only place a slot is
released, inside `fillQuantum`, never in `offer`. The tuning constants and their reasoning live in
`PlayoutConstants.h`.

**JNI seam** (`android/playout_jni.cpp`), one `jlong` handle. Payloads and quanta cross on stack
scratch rather than pinned arrays, so nothing holds a GC-visible pin across the engine's mutex or
the mix. It validates every array it writes into and answers `kErrorBufferTooSmall`, keeping `0`
to mean "the engine ran and nobody produced audio" — a caller that sized its arrays wrong must not
be indistinguishable from silence. The calls that answer with more than one number flatten their
outputs into primitive arrays; those layouts belong to the seam, named by `NativePlayout`'s
`STATUS_*` and `COUNTER_*` constants.

**Playback loop** (`VoiceReceiver`). One daemon thread calling `fillQuantum`, paced by the
blocking `AudioOut.write` while any speaker is draining — `AudioTrack` consumes one quantum per
quantum-duration off the audio clock, so no timer exists or should — and parked on `idleLock`
otherwise. `onTunneledAudio`, on the reader coroutine, offers under that same monitor, so a
`stopped` check and an `offer` are atomic against teardown. Kotlin owns the `speakingSessions` and
`playoutStats` flows, publishing the engine's monotonic counters against per-spurt baselines. The
engine exists if and only if the loop is running: `start()` builds both, only the playback thread
destroys the engine, and both destroy sites hold `idleLock`, so the reader can never call into a
freed handle. `stop()` never frees it; a loop wedged in a blocked `AudioTrack.write` destroys the
engine on its own way out rather than have `stop()` race it.

Invariants, pinned by `PlayoutEngineTest.cpp`, `PacketQueueTest.cpp`, `VoiceReceiverTest.kt`,
`NativePlayoutTest.kt` and `PlayoutLoopDeviceTest.kt`:

- The engine is freed only by the playback thread, under the monitor the reader offers under.
- A speaker's slot is released only by retirement inside `fillQuantum`.
- `offer` never throws, and no refusal is terminal for the session.
- The seam's flat layouts match what Kotlin reads — pinned on a device, since `FakePlayoutEngine`
  fills the same arrays from the same constants and cannot catch a drift.
