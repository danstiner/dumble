# Playout

How received voice gets from the network to the speaker, where the delay on that path sits, and
which part of the delay protects against a late packet. The numbers are measured; each section
says where.

## Goals

- **As little standing delay as the network allows.** Delay that does not protect against a late
  packet is waste.
- **No glitch on the first syllable,** and nothing audible when delay is shed.
- **The audio callback never blocks.**
- **A misbehaving server cannot break receive.** Every queue is bounded and no refusal is final.
- **Every client's audio plays,** whatever it does with `frame_number`.
- **Each stage is measured** and shown per speaker.

## Design

One speaker's audio, from arrival to ear. Voice arrives from the TLS tunnel or the UDP socket;
both hand the same packet to the same queue, and nothing after that knows which carried it.

```
tunnel or UDP ─► PacketQueue ─► SpeakerDecoder ─► stream buffer ─► device ─► speaker
                 depth           ≤ one packet     write-ahead      platform
                 the only margin                  2 bursts, tuned
```

**`PlayoutEngine`** (`core/`) owns one `PacketQueue` and one `SpeakerDecoder` per sender, for up to
8 concurrent speakers. Packets wait compressed in the queue. `fillQuantum`, shaped as an audio data
callback, pops packets, decodes them, and mixes through `Mixer`'s soft-knee limiter. That call is
the engine's only clock: every limit it keeps is a count of samples accumulated across fills, so
it means the same time at any burst size.

**`OboePlayout`** (`android/`) owns the Oboe output stream and calls `fillQuantum` from
`onAudioReady`, once per device burst. It also runs Oboe's `LatencyTuner`.

**`VoiceReceiver`** (Kotlin) hands packets to the engine with `offer()` and runs **the poll**, one
coroutine that owns the stream's state. Every 50 ms it calls the adapter's `start()`, pauses the
stream while the platform holds the call, and reads the engine's stats.

**JNI** (`android/playout_jni.cpp`): one `jlong` handle to the engine and adapter together.
Payloads are copied to the stack, so no Java array is pinned across the engine's mutex. Stats come
back as primitive arrays laid out by `NativePlayout`'s constants.

### Only queued audio is margin

A packet is popped from its queue when the callback needs the next burst. The callback fires when
the stream has room for a burst, which is one *write-ahead* (the stream's buffer size) before that
burst plays. So a packet that has not arrived by (playout time − write-ahead) is concealed,
however much audio the stream already holds.

**Only what is still queued protects against a late packet. Everything after the pop is delay
without margin.**

The engine is told the write-ahead (`setWriteAheadSamples`, at open and whenever the tuner changes
it) and adds it to every target a queue is measured against. The queue then holds the estimator's
margin, and the reported target can be read beside the reported depth. The tuner starts the buffer
at two bursts and grows it one burst per underrun, which is the AAudio guide's procedure and
`LatencyTuner`'s default.

### The target

Each sender has a `JitterEstimator`, modelled on WebRTC's NetEq. It measures each arrival against
the fastest packet of the last one to two seconds, and keeps a histogram of the worst delay in
each 500 ms, in 20 ms buckets, with about a 29 s memory. The target is the top of the bucket
holding the 95th percentile, plus a 10 ms margin, clamped to at most 450 ms; on a clean link that
is 30 ms. Estimates
live in a table of 64 senders that outlasts a speaker's slot, so someone who was silent for thirty
seconds does not return with a cold estimate. The first speaker of a session starts at 80 ms.

## Decisions

| Decision | Why | Cost |
|---|---|---|
| Packets wait compressed and are decoded at the pop, on the callback thread | Queued packets are the only margin, and `offer()` never waits for a decode. | Decode time is spent on the realtime thread. Measured: 18–34 µs mean per fill against a 2 ms burst. |
| The output stream stays started for the receiver's life | A start takes 6–112 ms (about 150 ms on the emulator), and packets arriving meanwhile become standing delay: about 150 ms over target on a sender that never pauses. | One silent callback per burst while idle, well under 1% of a core. |
| The realtime fill only ever tries the lock | `std::mutex` has no priority inheritance, so blocking could stall the audio thread behind a preempted reader. | A fill that loses the race plays one burst of silence. Measured: 0.04–0.70 per spurt. |
| Out-of-order packets are refused, not reordered | A single network path does not reorder. It takes parallel paths skewed by more than the 20 ms between packets, or a route change mid-flow. | A reordered packet is lost. The `outOfOrder` count from the field decides whether a reorder buffer is worth building. |
| The stream asks for `Usage::VoiceCommunication` | It pairs with capture's preset for the platform echo canceller and call volume. | In call mode that selects the platform's voice path: 81 ms measured, where media usage gets 54 ms. |
| A stream error only sets a flag; the poll reopens the stream | Oboe reports errors on a detached thread it never joins. With a flag, the adapter and engine have plain lifetimes and there is no reopen thread. | Recovery waits up to one poll interval. |
| A speaker's slot is released only by the engine: inside a fill, or when the output goes down | Neither the queue nor the decoder can tell a speaker who stopped from one still filling its prebuffer. Only the engine can. | A slot is held up to 100 ms after a speaker stops, or 1 s if it stalled. |
| Missing frames are concealed one at a time, up to a budget | The spurt keeps its timing instead of being compressed. | A wide hole can leave the queue up to 100 ms deeper than before it. |

**Starting on the first packet, measured.** `requestStart` took 6–112 ms on a Pixel 7a's legacy
path and about 150 ms on the emulator, and the packets that arrived meanwhile stayed queued for
the rest of the session: on the emulator, 296 ms queued against a 141 ms target. Started up
front, the same test read 139 ms against 130.

## Threads and ownership

- **`offer()`** runs on whichever transport thread delivered the packet, serialised by
  `VoiceReceiver`'s monitor. It touches queues only, under the engine's mutex. Its refusals are
  logged and nothing more, since a misbehaving server can cause each of them at will.
- **`fillQuantum`** runs on the callback thread. It takes the mutex to pop and releases it to
  decode. `setRealtime(true)`, on for the life of the session, makes every acquisition a
  `try_lock`; a contended fill is counted (`contended`).
- **The callback** does three things only: `fillQuantum`, `tune()`, and `setWriteAheadSamples`
  when the tuner changed the buffer. No logging, no allocation, no other stream call.
- **The poll** is the only caller of `start()` and `pause()`. `start()` is idempotent while a
  stream runs. After a stream error it tells the engine and opens another, at most once a second.
- **Teardown.** `stop()` joins the poll before destroying the session, and `offer()` and
  `destroy()` share the receiver's monitor, so nothing is in flight against a freed session.
- **A slot** is released by retirement inside a fill, or by `setOutputDown` when the stream is
  gone. Never in `offer()`.

## Queue policy

- **Gate.** A new spurt is held until the target is queued, or the ring is full, or the sender's
  terminator arrives, so a short spurt plays whole. Once open the gate stays open, and re-arms
  only when the spurt has fully played out.
- **Catch-up.** When the gate opens after a stall, as opposed to at the start of a spurt, a
  backlog more than 100 ms over target is trimmed to target. Nothing is playing yet, so this is
  the one place audio is discarded without a splice.
- **Shrink.** Mid-spurt, one packet is shed per 2 s, only while the decoded audio is quiet and
  the queue is 20 ms over target. Standing delay unwinds where it cannot be heard.
- **Bounds.** 600 ms or 32 packets per queue. Past either, the oldest packet is dropped and
  counted.
- **Conceal.** When the queue runs dry mid-spurt, Opus packet loss concealment fills in for up to
  100 ms (`kConcealSamples`), then the speaker goes silent. A slot retires after 100 ms of silence
  with its queue drained (`kRetireIdleSamples`), or after 1 s stalled below its gate
  (`kStallIdleSamples`).
- **Output down.** After a stream error the poll calls `setOutputDown(true)`. Every slot is
  released, with tallies collected and queues and decoders reset, and every estimator is kept. A
  spurt cut by the error prebuffers afresh on reopen against a warm estimate, instead of playing
  its backlog as standing delay.

### Holes

Each queue entry carries the sender's `frame_number`. When the head of the queue starts later than
playout has reached, there is a hole.

- A pop returns one frame of concealment instead of a packet and counts it as `lost`. It does so
  for the first 100 ms always, beyond that only while the queue is below target, and never past
  the ring's capacity.
- Then the cursor jumps to the head, and the rest of the gap is counted without being played.
- So a hole wider than 100 ms resumes at max(target, depth at the hole + 100 ms): never below
  target, never more than 100 ms above where it was.
- A gap in front of a packet that shrink or overflow discards is counted only.
- Continuity ends at a terminator, at a pop that came up empty, at a re-arm and at reset, so a
  pause between spurts is not a hole. A pause with none of those is charged as a hole, because at
  the pop it cannot be told from burst loss. pymumble never sends a terminator, and desktop's can
  be lost on UDP.

Holes and stalls go through the same libopus call, so they fade alike. Measured on libopus 1.6.1 in
CELT mode, concealment fades per request, not per millisecond: 10 ms requests are 30 dB down within
70 ms for 20 ms and 60 ms senders alike, while the 20 ms requests libopus splits longer ones into
fall 24 dB over 100 ms for a 20 ms sender and 9 dB for a 60 ms one. Hybrid mode barely fades in 100
ms at any request size.

### Ordering

- A packet whose audio ends behind playout is refused. Played after its successor it is worse
  than the gap, and it would corrupt the decoder's prediction state.
- A packet ahead of playout but not ahead of the packet queued last is refused too. The queue is
  a ring in arrival order.
- Both are counted as `outOfOrder`. A normal path produces none over hours.
- Both checks are per spurt, and a queued terminator ends the spurt.
- A packet without the terminator flag, stamped with the frame number the current spurt began on,
  is a restart whose terminator was lost. It ends the spurt. Anything behind the start is refused.

## What senders put in `frame_number`

It counts 10 ms frames, but each client keeps it differently (read from each client's source).
Murmur relays it, and the terminator, unchanged.

| sender | counter | through a pause | terminator |
|---|---|---|---|
| Mumble desktop | per 10 ms frame, always | runs on (wall clock); resets after 5 s of silence | last packet, with zero-stuffed audio |
| pymumble | 10 ms frames of wall clock | runs on; resets after 5 s idle | never |
| Humla (Mumla) | advances only while talking | stands still | padded, stamped inside the previous packet's span; omitted about half the time |
| mumble-web | restarts at 0 every spurt | — | a bare flag; murmur forwards it with empty `opus_data` |

Two rules follow. Ordering is judged per spurt, so mumble-web's restart at zero plays instead of
being refused as behind the previous spurt. And Humla's terminator, which overlaps the packet
before it but starts later, is ahead of the tail and plays.

## What the platform grants, by route

The stream asks for output, low latency, exclusive sharing, 16-bit, 48 kHz, mono,
`Usage::VoiceCommunication` and `ContentType::Speech`. Each is a request, and what comes back
depends on the route more than on the device. The `OboePlayout: open:` line in logcat says what
was granted.

| device / mode / usage | path / sharing | burst | buffer | latency |
|---|---|---|---|---|
| Pixel 7a, `MODE_NORMAL`, VoiceCommunication (or Media) | memory-mapped (MMAP) / Exclusive | 96 | 192 | 8 ms |
| Pixel 7a, `MODE_IN_COMMUNICATION`, VoiceCommunication | legacy `voip_rx` / Shared | 480 | 960 | 81 ms |
| Pixel 7a, `MODE_IN_COMMUNICATION`, Media | legacy fast track / Shared | 128 | 256 | 54 ms |
| Pixel 7a, Bluetooth call-link (SCO) headset, in call | legacy / Shared | — | — | ~170 ms |
| Emulator (no FastMixer) | legacy / Shared | 960 | 1920 | 110–150 ms |

Probed in one process on the speaker route, changing only `AudioManager.setMode`.

- **Every connection is in `MODE_IN_COMMUNICATION`,** and in that mode the 7a refuses the
  memory-mapped path for any usage (`openMmapStream` returns ENOSYS), so AAudio falls back to its
  legacy path. The 8 ms figure is real and reachable only outside a call.
- **Which legacy path is decided by the usage.** `VoiceCommunication` maps to stream `VOICE_CALL`,
  and the audio policy replaces the requested flags with `VOIP_RX|DIRECT`
  (`AudioPolicyManager::getOutputForAttrInt`). That is the voice path with the echo canceller: burst
  480, 81 ms.
- **Media usage would be 54 ms** but loses the echo canceller's reference signal and the pairing
  with call volume. Not taken, pending a listening test.
- **Below API 37 a memory-mapped stream disconnects on every route change.** A headset plugged in or
  a speakerphone toggle mid-sentence costs a poll interval, the reopen (about 100 ms) and a fresh
  prebuffer. The legacy path reroutes in place.
- **The legacy path hides underruns.** Its count stayed at 0 while AudioFlinger counted 480 on the
  fast track over a session, mostly around Bluetooth route changes. So the tuner never grows the
  buffer there, and each underrun inserts a burst of silence into the stream, raising its reported
  latency until a flush. Neither shows in the app's counters today.

## What the numbers mean

`PlayoutStats` is sampled once a second inside a spurt and once at its close, and shown per speaker
on the user sheet.

| Stat | Meaning |
|---|---|
| depth (`bufferedSamples`; "Jitter buffer" on the sheet) | Audio queued for this speaker: the margin. |
| latencyMs ("Audio output" on the sheet) | Oboe's `calculateLatencyMillis`: the stream's buffer plus the device below it. One output stream, so shared by every speaker. Null while the stream is not started. |
| target | The estimator's figure plus the write-ahead, so it reads beside depth. |
| underruns | Bursts the stream played as silence because the callback did not fill them in time. |
| concealed | Gaps in a speaker's audio where playout ran ahead of the sender, counted once per gap, whether the fill was concealed or padded with silence. Holes are not included; they count as lost. |
| lost (`lostSamples`) | Audio the network never delivered: frame-number gaps, concealed per frame. |
| dropped | Audio we had and discarded at the queue's bounds. |
| outOfOrder | Packets refused as behind playout or behind the packet queued last. |
| contended | Fills answered with silence because `offer()` or the poll's stats read held the mutex. One burst of silence each. |
| fill | Mean and maximum wall time per fill since the previous stats read, decodes included. Read it against one burst. |
| audible | Which speakers produced audio in the last fill; what the UI shows as speaking. |

`depth + latencyMs` is the standing delay for a speaker. Neither alone is.

Debug builds compile the engine at `-O0`, so their fill times overstate a release build's. The
host benchmark (`PlayoutEngineBench`, Release) pins the engine at Opus decode time plus at most
half again.

## Measured

Test sender: pymumble, 20 ms packets on an absolute schedule with uniform 0–30 ms jitter, 4 s
spurts with 2 s off unless stated. A second client in the channel spoke at times. Debug build.

| device / route / load | latency | concealed / spurt | underruns | contended / spurt | fill mean / max |
|---|---|---|---|---|---|
| Emulator, spurts (25) | 154 ms | 1.52 | 0 | 0.04 | 34 / 312 µs |
| Emulator, continuous, 60 s | 155 ms | 0 | 2 | 1 / 60 s | 48 / 292 µs |
| Pixel 7a earpiece in call, spurts (27) | 76 ms | 1.11 | 0 | 0.70 | 257 / 1842 µs |
| Pixel 7a earpiece, continuous, 90 s | 76 ms | 0 | 0 | 26 / 90 s | 405 / 1781 µs |
| Pixel 7a earpiece, continuous, 8 cores busy, 60 s | 78 ms | 1 | 0 | 41 / 60 s | 112 / 1069 µs |

- **The 1 to 2 conceals per spurt** are the sender's spurt start and end, since pymumble sends no
  terminator. They are not jitter.
- **The phone's WiFi delivers the TCP tunnel in bursts of 60–300 ms** (power-save batching; the
  queue depth shows a 60 ms sawtooth). The estimator's target on the phone sits at 100–210 ms,
  where the emulator's loopback sits at 30–100 ms.
- **A full CPU load changed nothing:** 0 underruns, and fill times went down because the cores
  clocked up.
- **Fill time in the device test,** one speaker, memory-mapped path on the speaker route: 18–34 µs
  mean, 244–390 µs max, against a 2 ms burst. The 250–600 µs means in the app are the Debug
  build mixing 480-sample bursts. The Release host benchmark reads 17 µs for eight speakers at
  128 samples.

## Against the Android compatibility definition

The Compatibility Definition Document (§5.6) defines *output latency* as the interval between an
app writing a frame and the sound being presented. `latencyMs` measures it from AAudio's
timestamp.

- **Continuous output latency** is strongly recommended to be 45 ms or less on AAudio. Met on the
  Pixel's speaker route (8 ms), not on its in-call route (76–81 ms in the tables above).
- **Cold output latency,** the cost of a stream start, may be up to 500 ms; 100 ms or less is
  recommended.

## Open questions

- **Exclusive or shared output with capture running.** Exclusive is requested. Whether the
  platform's echo canceller still gets its reference from an exclusive memory-mapped output has
  not been tested by ear. The in-call route is shared regardless, so this matters only on routes
  that grant the memory-mapped path.
- **Underruns on the legacy path.** `getXRunCount` does not report them, so the tuner cannot
  react. Asking for a larger buffer on routes that come back legacy is the lever; untested.
- **Concealment at small bursts.** `SpeakerDecoder::conceal` notes that concealment requests of
  the minimum size fade to silence after the first, and a 96-sample burst asks for exactly those.
  Not yet listened to.

## Tests

`PlayoutEngineTest.cpp`, `PlayoutEngineBench.cpp`, `PacketQueueTest.cpp`, `JitterEstimatorTest.cpp`,
`VoiceReceiverTest.kt`, and on a device `NativePlayoutTest.kt` and `OboePlayoutDeviceTest.kt`.

**End-to-end latency** is pinned by `LatencyHarnessTest.cpp`: capture through playout on a virtual
clock, with the results committed in `app/src/test/resources/latency/expected.txt`. The totals
include the write-ahead the engine is told about and no other device latency. They also leave out
the sending thread's wait to notice a packet, up to 5 ms.

A change that moves the figures must rebuild, rewrite the file and commit the diff:

```bash
cmake --build build/host && DUMBLE_UPDATE_EXPECTED=1 ctest --test-dir build/host -R Latency
```

`ctest` alone does not rebuild. It would measure the old code, match the old file and pass. The
run that rewrites the file fails and names it, so leaving the variable set cannot hide a change.
