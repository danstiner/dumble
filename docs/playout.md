# Audio playout

Incoming voice packets to mixed PCM on the speaker. Three layers, mirroring capture
(`docs/capture.md`): a platform-free native engine, a thin JNI seam, and one Kotlin thread that
owns the engine's lifetime. Details live in the code's comments; tuning constants and their
reasoning in `PlayoutConstants.h`.

```
transport ─► onTunneledAudio ─► offer() ─► PacketQueue ─► SpeakerDecoder ─► Mixer ─► fillQuantum ─► AudioTrack
             (reader coroutine)            └────────── PlayoutEngine (core/) ──────────┘           (playback thread)
```

## Layers

**PlayoutEngine** (`core/`) owns one `PacketQueue` and one `SpeakerDecoder` per sender. Its only
clock is the `fillQuantum` call, shaped as an audio data callback — non-blocking, allocation-free —
so an Oboe output stream could drive it; today a Kotlin thread does, between `AudioTrack` writes.
Packets wait compressed behind a per-speaker prebuffer gate so a network stall cannot glitch the
opening syllable, and the mix finalizes through `Mixer`'s soft-knee limiter.

Two ownership rules carry the design:

- `offer()`, on the reader thread, touches queues only, under the mutex; decode and mix happen on
  the playback thread with the mutex released. No refusal in `offer` is terminal — each is a
  condition a misbehaving server can produce at will, so the caller logs rather than disabling
  receive.
- A speaker's slot is released only by retirement, inside `fillQuantum`, never in `offer` —
  neither side of the seam can tell a speaker that stopped talking from one still filling its
  prebuffer, so the engine alone decides.

**JNI seam** (`android/playout_jni.cpp`): one `jlong` handle. Data crosses on stack scratch so no
GC-visible pin is held across the engine's mutex or the mix, and multi-value answers are flattened
into primitive arrays whose layouts are named by `NativePlayout`'s constants.

**Playback loop** (`VoiceReceiver`): one daemon thread, paced by the blocking `AudioTrack` write
while any speaker is draining — the audio clock is the only clock, so no timer exists or should —
and parked otherwise. The engine exists iff the loop is running, and only the playback thread
frees it; `stop()` never does, so a loop wedged in a blocked write cannot be raced by its own
teardown. Once the output exists the loop tells the engine how far ahead of playout it holds audio
(`AudioOut.writeAheadSamples` → `setWriteAheadSamples`); see below for why that number matters.

## Where the delay lives, and which part of it is margin

One speaker's audio, from arrival to ear:

```
arrival ─► PacketQueue ─► SpeakerDecoder ─► AudioTrack ─► HAL / mixer ─► speaker
           depth           ≤ one packet     write-ahead    platform
           the only margin                  2 bursts, capped
```

A packet is popped from its queue when the loop needs the next quantum, and the loop needs it
when the blocking write returns — which is when the track has room, i.e. a *write-ahead* before
that quantum plays. So a packet that has not arrived by (playout − write-ahead) is concealed, no
matter how much audio the track already holds. **Only what is still queued is margin against a
late arrival; everything downstream of the pop is delay without margin.**

That is why the engine measures every target against the queue *plus* the write-ahead:

- `JitterEstimator` turns arrival jitter (sender frame numbers against arrival time, 20 ms
  histogram buckets, 95th percentile, +10 ms safety, decayed so it forgets) into a target depth —
  10 ms floor, 450 ms ceiling, 80 ms cold. The target is a *margin*: how late a packet may be.
- `PlayoutEngine::setWriteAheadSamples` adds the track's granted write-ahead to that target at
  every site a queue is compared to it — the prebuffer gate, the shrink floor, the catch-up trim
  and the figure `stats()` reports — so once the gate opens and the write-ahead drains into the
  track, the queue is left holding the estimator's margin. `PacketQueue` itself never learns the
  number; it is handed a target per call.

Without the second half, the cap alone changes nothing: the gate opens on the bare target, the
same audio drains into a smaller track, and the margin at the start of every spurt is
`target − write-ahead` — zero on a healthy link, until concealment has pushed playout back far
enough to create one. That was the shipped behaviour, with a 91 ms track (`getMinBufferSize` on
the emulator) draining 80 ms cold targets whole.

The rest of the queue policy, for orientation — each is reasoned in `PlayoutConstants.h`:

- **Gate**: a new spurt is held until the target is queued, or the ring is full, or the sender's
  terminator arrives (a short spurt plays whole). Latched open; re-arms only once the spurt has
  fully played out.
- **Catch-up**: at a gate-open that follows a stall rather than opening a spurt, a backlog more
  than 100 ms over target is trimmed to target — the one place audio is discarded without a
  splice, because nothing is playing yet.
- **Shrink**: mid-spurt, one packet is shed per 2 s, only while the decoded audio is quiet, only
  while 20 ms over target — standing delay unwinds where it cannot be heard.
- **Bounds**: 600 ms or 32 packets per queue; past either, the oldest is dropped and counted.
- **Conceal**: a mid-spurt shortfall is filled by Opus PLC for up to 10 quanta (100 ms), then the
  speaker goes silent; a slot retires after 10 empty polls.

## The AudioTrack cap: why two device bursts

`AudioTrack.getMinBufferSize` is documented as "an estimate" that "doesn't guarantee a smooth
playback under load, and higher values should be chosen according to the expected frequency at
which the buffer will be refilled" — it is sized for an app that refills lazily, and comes out
generous (91 ms on the emulator) for a thread that refills every 10 ms frame.
`setBufferSizeInFrames` "limits the effective size of the AudioTrack buffer that the application
writes to … a smaller size will give lower latency but there may be more glitches due to buffer
underruns", and "the actual size used may not be equal to this requested size".

The unit to size in is the device burst, `AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER` — the
latency guide says to "construct your audio buffers so that they contain an exact multiple of this
number", and the AAudio guide that "you get the lowest latency if your … buffer size is a multiple
of the reported burst size". Two bursts is double buffering — one being consumed by the HAL while
the other is written — and is the floor Google's own tuner starts from: Oboe's `LatencyTuner`
sets `kDefaultNumBursts = 2` and raises by one burst per underrun, which is also the AAudio
guide's procedure ("start with a small buffer size and if that produces underruns, increase the
buffer size until the output flows cleanly again"). One burst was tried: with the whole track
emptied on every HAL read, the refill has no slack, and the emulator underran twice a minute for
a 23 ms saving. Our 480-sample write not dividing the burst — or even exceeding two of them — does not
matter: `AudioTrack::write` with `WRITE_BLOCKING` loops on `obtainBuffer`, copying whatever fits
each time the HAL frees room, and while it waits the track is full. What the track leaves after
a HAL read is the window the loop has to wake and refill, and on a phone two bursts is not a
window at all: a Pixel 7a reports a 128-sample (2.7 ms) HAL buffer, and with a 256-sample track
the Kotlin loop — which refills a whole 480-sample frame per iteration — underran ~3000 times a
minute, fell behind real time, and drove every queue to its 600 ms high-water mark. The floor is
therefore two of *our* frames, not two of the HAL's; growing by a burst per underrun (Oboe's
`LatencyTuner`) remains the documented answer if a device still underruns.

Pixel 7a, continuous tone with 0–30 ms jitter, 60 s per size:

| write-ahead            | underruns / min | dropped | latency |
|------------------------|-----------------|---------|---------|
| 256 = 2 × HAL (5 ms)   | ~3000           | 3203    | 19 ms   |
| 640 = HAL + frame (13) | 2852            | 1115    | 54 ms   |
| 960 = 2 frames (20 ms) | 0               | 0       | 89 ms   |
| 1920 (40 ms)           | 0               | 0       | 120 ms  |

Shipped: `max(2 × HAL, 2 × frame)` as whole HAL buffers — 1024 on the Pixel (0 underruns, queue
60–69 ms against a 61 ms target over 90 s), 2176 on the emulator.

`AndroidAudioOut` asks for two bursts or two 10 ms frames, whichever is larger, as a whole
number of bursts, and logs what it got beside what it asked
(`AudioOut: track: … writeAhead=2176 (asked 2176)`). What it does *not* do yet is tune upward on
underruns the way `LatencyTuner` does; the underrun count in the playout summary is the reading
that would justify it, and a phone's bursts are a fifth of the emulator's, so that is where to
look first.

What the cap cannot reach is the platform below the track. `latencyMs` on the emulator reads
~120 ms with a 45 ms track because its output thread has `No FastMixer` and a HAL write latency
averaging 89 ms (`dumpsys media.audio_flinger`); the track is a *normal* track on the normal
mixer's 20 ms period (AOSP's latency design: the fast mixer runs at 2–5 ms and only a *fast
track* rides it). We never ask for one — no `PERFORMANCE_MODE_LOW_LATENCY`, and
`USAGE_VOICE_COMMUNICATION` may route around it anyway — so that is the next lever on a phone,
and untested.

### What the CDD says

The CDD's definitions (§5.6) are the ones this doc uses. *Output latency* is "the interval between
when an application writes a frame of PCM-coded data and when the corresponding sound is
presented" — which is exactly what `latencyMs` measures from our own writes via
`AudioTrack.getTimestamp`, a timestamp the CDD requires to be "accurate to +/- 2 ms" (C-1-1), so
the sheet's "Audio output" row is a measurement, not an estimate. *Continuous output latency* is
strongly recommended (C-SR) to be ≤ 45 ms — but that recommendation is stated for the low-latency
path ("when using both the OpenSL ES PCM buffer queue and AAudio native audio APIs"), which a
plain Java `AudioTrack` on the normal mixer is not on; the emulator's 120 ms is that path. The
CDD also pins `PROPERTY_OUTPUT_FRAMES_PER_BUFFER` as an *upper* bound on a low-latency stream's
burst, so on a device that grants a fast track the burst — and our two-burst cap — would be
smaller still.

Two CDD numbers we do not yet control for: *cold output latency* — up to 500 ms allowed
(C-1-2), ≤ 100 ms recommended — applies whenever "the audio output system has been idle and
powered down", which AudioFlinger does after a few seconds without writes. The loop deliberately
does not zero-fill between spurts, because with a 91 ms track that ratcheted latency up after a
burst; with the track capped at two bursts that reason is gone, and keeping the track warm
through short pauses (the latency guide's "minimize warm-up latency") would spare the first
syllable after a pause its cold start. Untested; recorded in TODO.

## Measured

Emulator (burst 1088 samples = 22.7 ms; track 4360 samples granted, capped to 2176), a paced sender in 4 s
spurts with uniform per-packet jitter, 150 s per build. Numbers are from the playout summary
line; "concealed" is the per-spurt gap count summed across spurts.

| jitter  | build  | latency | concealed / spurt | underruns  |
|---------|--------|---------|-------------------|------------|
| 0–30 ms | before | 142 ms  | 2.05              | 0          |
| 0–30 ms | after  | 120 ms  | 1.76              | 0          |
| 0–60 ms | before | 140 ms  | 2.15              | 0          |
| 0–60 ms | after  | 119 ms  | 1.86              | 1 in 150 s |

On a continuous jittered tone the queue held the estimator's target in 100% of samples (target
44 ms, depth 60–120 ms); before, it sat at 0–60 ms against a 50 ms target and refilled only by
concealing. The ~1.7 gaps per spurt that remain on both builds are the test sender's own spurt
start and end (pymumble sends no terminator), not jitter.

A measurement trap worth recording: a sender that paces itself with `sleep` *after* each packet
runs a few percent slow, and a slow source drains any jitter buffer regardless of its depth — it
produced hundreds of conceals per spurt on both builds and looked exactly like missing margin.
Pace test senders on an absolute schedule.

## What the numbers mean

`PlayoutStats`, sampled once a second per spurt and shown per speaker on the user sheet:

- **depth** (`bufferedSamples`, the sheet's "Jitter buffer") — the queue, i.e. the margin.
- **latencyMs** (the sheet's "Audio output") — samples written minus samples presented, from
  `AudioTrack.getTimestamp`: the track's fill plus the HAL below it. One output stream, so shared
  by every speaker.
- **target** — the estimator's figure *plus* the write-ahead, so it reads beside depth.
- `depth + latencyMs` is the standing delay for that speaker; neither alone is.

Invariants are pinned by `PlayoutEngineTest.cpp`, `PacketQueueTest.cpp`, `JitterEstimatorTest.cpp`,
`VoiceReceiverTest.kt`, `NativePlayoutTest.kt` and `PlayoutLoopDeviceTest.kt`.
