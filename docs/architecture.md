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

A **tick** is one `fillQuantum` call, and the engine's only clock — nothing in it advances between
calls. Every `*IdleTicks` bound below is a count of calls, not a span of time: the caller chooses
the sample count, and the playback loop runs faster than real time whenever nobody is producing,
since an arriving packet wakes it.

**Native engine** (`core/PlayoutEngine.{h,cpp}`), platform-free. It owns one `PacketQueue` and one
`SpeakerDecoder` per sender, built up front by `create()` and never reallocated, so claiming a slot
takes no malloc under the mutex a playback thread is waiting on. The two halves are separate types
because they answer to different threads: a queue is touched only under `mutex_`, a decoder only
from the playback thread with the mutex released.

`offer()`, on the reader thread, judges the payload *before* the mutex — size against
`kMaxPacketBytes`, then sample span from the Opus header via `AudioDecoder::packetSamples`, which
reads at most two bytes. That is also what keeps `PacketQueue` free of Opus knowledge. A refused
payload is dropped without locking, unless it carries `is_terminator`: that is a protobuf field
beside the payload and stays true when the payload is garbage, so the end of the spurt is queued
on its own. Every outcome is a status code (`kOfferAccepted`, `kOfferSpeakerCap`,
`kOfferPacketTooLarge`, `kOfferMalformedPacket`) and none is terminal — each is a condition a
misbehaving server can produce at will, so the caller latches a log rather than disabling receive.

`fillQuantum(out, samples, sessions, liveSpeakers)` is shaped as an audio data callback —
non-blocking, allocation-free, taking its sample count per call — so an Oboe output stream can
call it unchanged; today a Kotlin thread drives it between `AudioTrack` writes. It runs in three
phases: snapshot the claimed slots under the lock; pop, decode and mix with the lock released, one
acquisition per packet so a slow decode never stalls the reader; then commit sessions, tick
bookkeeping and retirement in a single acquisition. The mix accumulates into int32 and finalizes
through `Mixer`'s tanh soft-knee above roughly -2 dBFS, which shapes double-talk without the state
an envelope limiter would need.

**`PacketQueue`**, one per speaker. Encoded packets sit in a fixed pool of `kMaxQueuedPackets` (32)
until a tick needs them, so audio waits compressed for as long as the network requires. A new talk
spurt is gated behind `kPrebufferSamples` (60 ms) so the first network stall does not glitch the
opening syllable; a terminator opens the gate immediately, so a spurt shorter than the prebuffer
still plays. The gate re-arms in `endTick`, judged by whether the queue was empty at *pop* time
rather than at commit time — a packet arriving later belongs to the next spurt and must prebuffer
again. `speaking()` — gate open, no terminator — is what separates a dropout from the two silences
that are not one.

**`SpeakerDecoder`** pairs an `AudioDecoder` with a `PcmRing`, so decoded PCM outlives the packet
it came from by as little as possible and a packet's samples never have to align with a quantum.

**Retirement** is the engine's alone; neither half can tell a speaker that stopped talking from one
still filling its prebuffer. Two idle windows decide it: `kRetireIdleTicks` (10, matching desktop
Mumble's `iMissCount > 10`) once the queue has drained, and `kStallIdleTicks` (100, ~1 s) while
packets remain — a spurt stuck below the prebuffer that never drains, which the short window could
never reach. Retirement is the only place a slot is released, and it happens inside `fillQuantum`
on the playback thread, never in `offer`.

**Statistics.** `stats()` answers a whole `PlayoutEngine::Stats` by value: the live speakers with
their queue depths, plus two totals monotonic since construction. `concealedTicks` counts gaps, not
their length — a tick short of a full quantum, or nothing at all from a sender still mid-spurt,
which is the same gap at full width. `droppedPackets` counts what the jitter queues threw away for
backlog, past `kMaxQueuedPackets` or `kHighWaterSamples`, plus packets refused at the speaker cap,
which have no queue to charge them to. A payload `offer()` refused is deliberately excluded: it is
dropped before the mutex and already carries its own status code, so counting it would report one
packet twice and put lock traffic on the garbage path.

**The JNI seam** (`android/playout_jni.cpp`) exposes five entry points — `create`, `offer`,
`fillQuantum`, `readStats`, `destroy` — behind one `jlong` handle. Payloads and quanta cross on
stack scratch rather than pinned arrays, so nothing holds a GC-visible pin across the engine's
mutex or the mix. It validates every array it writes into and answers `kErrorBufferTooSmall`,
keeping `0` to mean "the engine ran and nobody produced audio" — a caller that sized its arrays
wrong must not be indistinguishable from silence. A refused quantum copies nothing back, since the
engine leaves its output untouched.

JNI carries primitive arrays and not structs, so the two calls that answer with more than one
number flatten their outputs: live speaker count then producing sessions, concealed ticks then
dropped packets. Those layouts belong to the seam — defined in `playout_jni.cpp`, named by
`NativePlayout`'s `STATUS_*` and `COUNTER_*` — and `Stats` knows nothing about them.

**Kotlin playback loop** (`VoiceReceiver`). One daemon thread calling `fillQuantum` in a loop.
While any speaker is draining, the loop is paced by the blocking `AudioOut.write` — `AudioTrack`
consumes one quantum per quantum-duration off the audio clock, so no timer exists or should. When
nobody is draining there is nothing to block on, so the loop parks on `idleLock`: unbounded when no
speaker exists, 10 ms at a time while one is still prebuffering. The live-speaker count is what
tells the loop which park to use. `onTunneledAudio`, on the reader coroutine, parses the tunneled
protobuf and calls `offer` under that same monitor, so a `stopped` check and an `offer` are atomic
against teardown.

Kotlin owns the `speakingSessions` and `playoutStats` flows. Both engine counters are published
against a per-spurt baseline, rearmed from the reading `publishStats` already took rather than a
second call, so a drop landing between two reads cannot fall out of both spurts. The rearm happens
once at spurt close, not at the next spurt's open — by then that spurt's opening tick has already
run, and a partial-fill opening tick would vanish into its own baseline. The underrun count in the
same flow comes from `AudioOut` and is baselined at spurt *open* instead, since a platform write
and not `fillQuantum` is what moves it.

`bufferedSamples` reads 0 on a healthy link and is not the jitter margin, which is the intuitive
reading and the wrong one. Measured on a Pixel 7a: the loop writes ahead until `AudioTrack`'s own
buffer blocks it, so the prebuffered 60 ms ends up downstream of that measurement, inside the
track, and shows up as 120-180 ms of reported latency instead. A nonzero depth means packets
arrived faster than the loop drained them.

**Handle lifetime.** The engine exists if and only if the playback loop is running. `start()`
builds it and spawns the loop; only the playback thread destroys it — from `loop()`'s `finally`, or
immediately if building the `AudioOut` throws before the loop is entered — and both sites destroy
under `idleLock`, the monitor `onTunneledAudio` takes to check `stopped` and call `offer`, so a
reader can never call into a freed handle. `stop()` never frees the engine; its join has a timeout,
and a loop wedged in a blocked `AudioTrack.write` is left to destroy the engine on its way out
rather than have `stop()` race it.

Invariants, pinned by `PlayoutEngineTest.cpp`, `PacketQueueTest.cpp`, `VoiceReceiverTest.kt`,
`NativePlayoutTest.kt` and `PlayoutLoopDeviceTest.kt`:

- The engine is freed only by the playback thread, under the monitor the reader admits offers
  under (`theEngineIsDestroyedExactlyOnceWhenTheLoopExits`).
- A speaker's slot is released only by retirement inside `fillQuantum`
  (`RetiresASilentSpeakerAndFreesItsSlot`, `ASessionReclaimsASlotAfterRetirement`).
- `offer` never throws, and no refusal is terminal for the session (`CapsConcurrentSpeakers`,
  `ReportsAPayloadLibopusCannotParse`, `keepsReadingWhileTheSpeakerCapRefuses`).
- The seam's flat layouts match what Kotlin reads. Nothing in the JVM tests can catch this —
  `FakePlayoutEngine` fills the same arrays from the same constants, so it agrees with itself
  whatever they are — which is why it is pinned on a device
  (`theSpeakerCapRefusesAndIsCountedAsADrop`, `everyProducingSpeakerComesBackInTheStatusArray`).

The 32-packet pool caps a 10 ms sender's backlog at 320 ms, tighter than the 600 ms
`kHighWaterSamples` bound alone would allow; senders at 20 ms and above never see the difference,
because the sample bound binds first for them. A stall long enough to strand more than 320 ms has
already produced an audible gap, and playing out the whole backlog converts that gap into standing
latency rather than removing it.
