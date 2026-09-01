# Playout

How inbound voice gets from the wire to the speaker, where the delay in that path sits, and
which part of it is margin against a late packet. The numbers are measured; the sections say
where.

## Layers

**PlayoutEngine** (`core/`) owns one `PacketQueue` and one `SpeakerDecoder` per sender. Its only
clock is the `fillQuantum` call, shaped as an audio data callback — non-blocking in realtime
mode, allocation-free, taking its sample count per call — and that is what drives it: the Oboe
output stream's data callback, once per device burst. Packets wait compressed behind a
per-speaker prebuffer gate so a network stall cannot glitch the opening syllable, and the mix
finalizes through `Mixer`'s soft-knee limiter. Every limit the engine keeps — conceal hold, idle
windows, shrink cooldown — is a count of samples accumulated from each fill's size, so a fill of
one device burst means the same time as a fill of one frame; a fill larger than the engine was
sized for is served as consecutive whole chunks.

Two ownership rules carry the design:

- `offer()`, on the reader thread, touches queues only, under the mutex; decode and mix happen on
  the callback thread, with the mutex released around the decodes. No refusal in `offer` is
  terminal — each is a condition a misbehaving server can produce at will, so the caller logs
  rather than disabling receive.
- A speaker's slot is released only by retirement, inside a fill, or by `setOutputDown` when the
  stream is gone — never in `offer`. Neither side of the seam can tell a speaker that stopped
  talking from one still filling its prebuffer, so the engine alone decides.

**OboePlayout** (`android/`) is the only Android-aware piece: it owns the Oboe output stream and
pulls each burst from the engine in `onAudioReady`. It asks for the stream `OboeCapture` does
and refuses one that did not come back mono I16 the same way, but owns differently. Capture is
shared-owned so a stream error — which Oboe delivers on a detached thread it never joins — can
reach the engine after the session is gone. Here the error callback touches nothing but a flag
on the `Callbacks` object, which Oboe keeps alive as long as it can call it; the receiver's poll,
calling `start()` every interval, is what notices the dead stream, tells the engine, and opens
another. So the adapter and the engine have plain lifetimes, and there is no reopen thread. The
`LatencyTuner` is per stream, rebuilt in `open()`. The callback is exactly `fillQuantum`,
`tune()`, and `setWriteAheadSamples` when the tuner changed the buffer; nothing else — no
logging, no allocation, no other stream call — because it runs on AAudio's realtime thread
under that API's contract. A fill that finds the reader holding the engine's mutex answers one
burst of silence and counts it (`contended`) rather than block: bionic's mutex has no priority
inheritance.

**JNI seam** (`android/playout_jni.cpp`): one `jlong` handle to a session — engine and adapter
together. The stream is opened at `create` and started by the receiver's poll. Payloads cross on
stack scratch so no GC-visible pin is held across the engine's mutex; the multi-value stats
answer is flattened into primitive arrays whose layouts are named by `NativePlayout`'s
constants, with the stream's own two readings — underruns and latency — appended after the
engine's.

**The poll** (`VoiceReceiver`): one coroutine, the only owner of stream state. Every 50 ms it
calls the adapter's `start()` — idempotent while a stream runs, and what opens a new one after
any stream error, at most once a second — pauses the stream while the platform holds the call, and
reads the engine's stats: the
audible set for the UI, the counters for `PlayoutStats`, sampled once a second inside a talk
spurt and once at its close. `offer()` and `destroy()` share the receiver's monitor, and `stop()`
joins the poll before destroying, so no packet and no stream call is ever in flight against a
freed session.

The stream stays started for the receiver's life rather than around speech. A start is not free
— `requestStart` measured 6–112 ms on a Pixel 7a's legacy path and ~150 ms on the emulator —
and every packet that lands while it runs piles up ahead of the gate. On a sender that never
pauses, that pile is standing delay for the rest of the session: measured at ~150 ms above the
target on both devices with a start-on-first-packet design (emulator: queue 296 ms against a
141 ms target), and gone with the stream started up front (139 against 130). An idle stream
costs one callback per burst filling silence, well under a percent of a core, and during a call
the capture stream keeps the audio HAL awake regardless.

## Where the delay lives, and which part of it is margin

One speaker's audio, from arrival to ear:

```
arrival ─► PacketQueue ─► SpeakerDecoder ─► stream buffer ─► HAL / mixer ─► speaker
           depth           ≤ one packet     write-ahead       platform
           the only margin                  2 bursts, tuned
```

A packet is popped from its queue when the callback needs the next burst, and the callback
fires when the stream has room for one — a *write-ahead* (the stream's buffer size) before that
burst plays. So a packet that has not arrived by (playout − write-ahead) is concealed, no matter
how much audio the stream already holds. **Only what is still queued is margin against a late
arrival; everything downstream of the pop is delay without margin.**

The engine is told the write-ahead (`setWriteAheadSamples`, from the stream's buffer size at
open and whenever the tuner changes it) and adds it to every target a queue is measured against
— gate, shrink floor, catch-up, and the figure `stats()` reports — so the *queue* holds the
estimator's margin and the reported target reads beside depth. The tuner starts the buffer at
two bursts and grows it one burst per underrun, which is the AAudio guide's procedure and Oboe's
`LatencyTuner` default.

## Queue policy

- **Gate**: a new spurt is held until the target is queued, or the ring is full, or the sender's
  terminator arrives (a short spurt plays whole). Latched open; re-arms only once the spurt has
  fully played out.
- **Catch-up**: at a gate-open that follows a stall rather than opening a spurt, a backlog more
  than 100 ms over target is trimmed to target — the one place audio is discarded without a
  splice, because nothing is playing yet.
- **Shrink**: mid-spurt, one packet is shed per 2 s, only while the decoded audio is quiet, only
  while 20 ms over target — standing delay unwinds where it cannot be heard.
- **Bounds**: 600 ms or 32 packets per queue; past either, the oldest is dropped and counted.
- **Ordering**: a packet not ahead of the packet queued last is refused and counted
  (`outOfOrder`) rather than reordered. Per spurt — a queued terminator ends the check — for the
  senders below. A packet more than 600 ms behind the packet queued last is a restart whose
  terminator was lost, and ends the spurt for it.
- **Conceal**: a mid-spurt shortfall is filled by Opus PLC for up to 100 ms (`kConcealSamples`),
  then the speaker goes silent; a slot retires after 100 ms of silence with its queue drained
  (`kRetireIdleSamples`), or 1 s stalled below its gate (`kStallIdleSamples`).
- **Output down**: after a stream error the poll calls `setOutputDown(true)`, which releases every
slot —
  tallies harvested, queues and decoders reset — and keeps every estimator, so a spurt cut by
  the error prebuffers afresh on reopen against a warm estimate rather than playing its backlog
  as standing delay. Below API 37 an MMAP stream disconnects on every route change, so a
  headset plug or a speakerphone toggle mid-sentence costs a poll interval, the reopen
  (~100 ms) and a fresh prebuffer — a cost the AudioTrack loop, which followed reroutes internally,
  did not have.
  The legacy path still reroutes in place.
- **Realtime fills**: `setRealtime(true)`, on for the life of the session, makes every mutex
  acquisition on the fill path a `try_lock`; a fill that finds the reader inside `offer()`
  answers one burst of silence and counts it.

## What senders put in `frame_number`

The wire's `frame_number` counts 10 ms units, but each client keeps it differently, read at the
source. murmur relays it, and the terminator, unchanged.

| sender | counter | through a pause | terminator |
|---|---|---|---|
| Mumble desktop | per 10 ms frame, always | runs on (wall clock); resets after 5 s of silence | last packet, with zero-stuffed audio |
| pymumble | 10 ms units of wall clock | runs on; resets after 5 s idle | never |
| Humla (Mumla) | advances only while talking | stands still | padded, stamped inside the previous packet's span; omitted about half the time |
| mumble-web | restarts at 0 every spurt | — | a bare flag; murmur forwards it with empty `opus_data` |

Two rules follow. Ordering is judged per spurt, ended by a terminator, so mumble-web's restart at
zero plays instead of being refused behind the spurt before it. And Humla's terminator, which
overlaps the packet before it but starts later, is ahead of the tail and plays.

## What the platform grants, by route

The builder asks for Output, LowLatency, Exclusive, I16, 48 kHz, mono, `Usage::VoiceCommunication`,
`ContentType::Speech`. Every one of those is a request, and what comes back depends on the route
more than on the device. `OboePlayout: open:` in logcat says what was granted.

| device / mode / usage                                   | api / sharing          | burst | buffer | latency |
|---------------------------------------------------------|------------------------|-------|--------|---------|
| Pixel 7a, `MODE_NORMAL`, VoiceCommunication (or Media)  | MMAP / Exclusive       | 96    | 192    | 8 ms    |
| Pixel 7a, `MODE_IN_COMMUNICATION`, VoiceCommunication   | legacy `voip_rx` / Shared | 480 | 960    | 81 ms   |
| Pixel 7a, `MODE_IN_COMMUNICATION`, Media                | legacy fast track / Shared | 128 | 256   | 54 ms   |
| Pixel 7a, Bluetooth SCO headset, in call                | legacy / Shared        | —     | —      | ~170 ms |
| Emulator (no FastMixer)                                 | legacy / Shared        | 960   | 1920   | 110–150 ms |

Probed with the same process on the speaker route, no call object, only `AudioManager.setMode`
changed. Telecom's part in this is the mode: every connection registers a self-managed call,
which puts the device in `MODE_IN_COMMUNICATION`. In that mode the 7a's HAL refuses MMAP for
any usage (`openMmapStream` returns ENOSYS, logged by `MmapStreamInterface`), so AAudio falls
back to legacy. Which legacy path is the usage's doing: `VoiceCommunication` maps to stream
`VOICE_CALL`, and the policy replaces the requested flags with `VOIP_RX|DIRECT`
(`AudioPolicyManager::getOutputForAttrInt`), selecting the DSP voice path with the echo
canceller — burst 480, 81 ms, the route the old AudioTrack loop was on at 89 ms. Media usage
lands on the primary fast track at 54 ms but loses the AEC reference and call-volume pairing;
not taken, pending a listen. The 8 ms is real and reachable only outside a call.

Two consequences of the legacy path worth knowing. Its underrun count stayed at 0 while
AudioFlinger counted 480 underruns on the fast track over a session (mostly around Bluetooth
route flaps), so the tuner never grows the buffer there; and each of those underruns inserts a
burst of silence *into* the stream, so the stream's reported latency steps up and stays up until
a flush. Neither is visible from the app's counters today.

## What the numbers mean

`PlayoutStats`, sampled once a second per spurt and shown per speaker on the user sheet:

- **depth** (`bufferedSamples`, the sheet's "Jitter buffer") — the queue, i.e. the margin.
- **latencyMs** (the sheet's "Audio output") — Oboe's `calculateLatencyMillis`: the stream's
  buffer plus the HAL below it, from the stream's own timestamp. One output stream, so shared by
  every speaker. Null while the stream is not started.
- **target** — the estimator's figure *plus* the write-ahead, so it reads beside depth.
- `depth + latencyMs` is the standing delay for that speaker; neither alone is.
- **underruns** — bursts the stream played as silence over the spurt because the callback did
  not fill them in time.
- **audible** — which live speakers produced in the last fill, read under the same lock as the
  rest so a retire-and-reclaim cannot misattribute it; what the poll publishes as speaking.
- **contended** — fills answered with silence because the reader held the engine's mutex. Each
  is one burst of silence in the output. Measured at 0.1–0.7 per spurt (below).
- **fill** — mean/max wall time per fill since the last sample, decodes included. The host
  benchmark (`PlayoutEngineBench`, Release tree) pins the engine at Opus decode plus at most half
  again; on a device this is the number to read against one burst. Debug builds compile the
  engine at `-O0`, so their fill times overstate a release build's.

## Measured

Test sender: `pymumble`, 20 ms packets on an absolute schedule with uniform 0–30 ms jitter per
packet, 4 s spurts with 2 s off unless stated; a second client in the channel spoke at times.
Debug build.

| device / route / load                     | latency | concealed / spurt | underruns | contended / spurt | fill mean / max |
|-------------------------------------------|---------|-------------------|-----------|-------------------|-----------------|
| Emulator, spurts (25)                     | 154 ms  | 1.52              | 0         | 0.04              | 34 / 312 µs     |
| Emulator, continuous, 60 s                | 155 ms  | 0                 | 2         | 1 / 60 s          | 48 / 292 µs     |
| Pixel 7a earpiece in call, spurts (27)    | 76 ms   | 1.11              | 0         | 0.70              | 257 / 1842 µs   |
| Pixel 7a earpiece, continuous, 90 s       | 76 ms   | 0                 | 0         | 26 / 90 s         | 405 / 1781 µs   |
| Pixel 7a earpiece, continuous, 8 cores busy, 60 s | 78 ms | 1              | 0         | 41 / 60 s         | 112 / 1069 µs   |

Reference, the AudioTrack path (#105): emulator spurts 120 ms / 1.76 per spurt / 0; Pixel
continuous 89 ms / 0 underruns. The ~1–2 conceals per spurt on both paths are the sender's spurt
start and end (`pymumble` sends no terminator), not jitter.

Two things the phone numbers carry that the path does not. The link: the phone's WiFi delivers
the TCP tunnel in bursts of 60–300 ms (power-save batching; the queue depth shows a 60 ms
sawtooth), so the estimator's target on the phone sits at 100–210 ms where the emulator's
loopback sits at 30–100. And a full CPU load moved nothing — 0 underruns, fill times *lower*
(the cores clocked up).

Fill time in the device test, one speaker, MMAP on the speaker route: 18–34 µs mean, 244–390 µs
max, against a 2 ms burst. The 250–600 µs means in the app are the Debug build's `-O0` engine
mixing 480-sample bursts; the Release host benchmark reads 17 µs for eight speakers at 128.

## What the CDD says

The CDD's definitions (§5.6) are the ones this doc uses. *Output latency* is "the interval between
when an application writes a frame of PCM-coded data and when the corresponding sound is
presented"; AAudio's timestamp is what `latencyMs` measures it from. *Continuous output latency*
is strongly recommended (C-SR) to be ≤ 45 ms "when using both the OpenSL ES PCM buffer queue
and AAudio native audio APIs" — the path this is now on, and met on the Pixel's speaker route
(8 ms) but not on its in-call route (75 ms), where the platform's VoIP path is what answers.
*Cold output latency* — up to 500 ms allowed, ≤ 100 ms recommended — is what a stream start
costs, which is why the stream is kept started.

## How we got here

Until #105 playout was a Kotlin thread writing 10 ms frames into an `AudioTrack` whose buffer,
sized by `getMinBufferSize`, held 91 ms on the emulator; a packet was popped a whole track
ahead of playout, so the estimator's target bought delay without margin. #105 capped the track
at `max(2 × HAL buffer, 2 × frame)` — on a Pixel 7a a Kotlin loop refilling a frame at a time
could not survive two 2.7 ms HAL buffers (~3000 underruns a minute), hence the frame floor — and
taught the engine to add the write-ahead to its targets. This PR replaced the loop and the track
with the Oboe stream above; the write-ahead is now the stream's tuned buffer, two bursts, and
the engine's targets account for it the same way.

## Open

- **Exclusive vs Shared under duplex.** Exclusive is requested; whether the platform's echo
  canceller still gets its reference on an exclusive MMAP output has not been tested with a
  listener. The in-call route is Shared regardless, so the question only bites on routes that
  grant MMAP.
- **Legacy-path underruns.** Not surfaced by `getXRunCount`, so the tuner cannot react; a
  larger buffer request on routes that came back legacy is the lever, untested.
- **Concealment at burst grain.** `SpeakerDecoder::conceal` documents that grid-sized PLC
  requests fade to silence after the first; a 96-sample burst asks for exactly those. Not yet
  listened to.

Invariants are pinned by `PlayoutEngineTest.cpp`, `PlayoutEngineBench.cpp`, `PacketQueueTest.cpp`,
`JitterEstimatorTest.cpp`, `VoiceReceiverTest.kt`, `NativePlayoutTest.kt` and
`OboePlayoutDeviceTest.kt`.
