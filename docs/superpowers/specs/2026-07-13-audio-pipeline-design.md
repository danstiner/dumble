# Audio Pipeline Design

**Date:** 2026-07-13
**Status:** Approved (pending written-spec review)

## Overview

Replace Dumble's synthetic voice loopback (`SyntheticVoiceSource`) with a real
audio engine that captures the microphone, Opus-encodes it, and transmits it;
and receives, decodes, mixes, and plays other speakers — a real full-duplex
**two-party (design for N-speaker) conversation** against a live Mumble server.

The work sits behind the existing seam. `VoiceEngine` is the permanent
JNI-shaped boundary ("decrypted Opus frame in / encoded Opus frame out"); the
**protocol stack and net layer are untouched**, and `VoiceTransport` changes only
minimally — it is constructed with `target = 0`, and it carries an end-of-talk
terminator flag additively added to `VoiceFrame` (see below). We add a new
`AudioVoiceEngine` implementation plus its supporting units, and wire mute /
audio-routing / stats into the existing Telecom + Compose UI.

The design was adversarially reviewed; four correctness blockers and several
robustness items were folded in before approval (see
[Correctness decisions from review](#correctness-decisions-from-review)).

## Goals

- Full-duplex real voice: mic → Opus encode → send, and receive → Opus decode →
  mix → play, verified as a real conversation with another Mumble user.
- Managed Java audio I/O (`AudioRecord`/`AudioTrack`) with the platform
  `VOICE_COMMUNICATION` echo-cancellation / noise-suppression / AGC path.
- libopus via a thin JNI shim, behind a swappable `OpusCodec` interface.
- Continuous open-mic transmission with a **Mute** toggle.
- Correct interop with stock Mumble clients: a **sample/time-domain jitter
  buffer** that handles any sender's Opus packet size (10/20/40/60 ms) and the
  Mumble `frame_number` contract (10 ms units).
- A Speaker↔Earpiece routing toggle (framework auto-routes Bluetooth).
- Real per-call voice stats surfaced on the active-call screen.

## Non-goals (deferred / out of scope)

- **In-band FEC** — inert with a PLC-only decoder; real FEC needs a
  decode-next-with-`fec=1` lookahead (one frame of added delay). PLC-only in v1;
  FEC is a documented follow-up.
- **DTX (discontinuous transmission)** — off in v1 to keep `frame_number`
  contiguous and avoid comfort-noise-vs-loss ambiguity in the jitter buffer.
- **Full adaptive time-scaling (WSOLA / NetEq-style)** — v1 uses fixed
  watermarks to *cap* drift-induced latency; smooth time-stretching is later.
- **Mixer soft-limiter for N>2 simultaneous speakers** — v1 hard-sums and clips
  (fine for a single remote); a limiter is a later add.
- **Voice-activity detection / push-to-talk** — v1 is continuous + mute; VAD is a
  separate spec (tracked).
- **Oboe / AAudio low-latency path and a software (WebRTC APM) echo canceller** —
  the reliability/latency upgrade if profiling justifies it; swappable behind the
  same seam. See [Future extension points](#future-extension-points).
- **Named Bluetooth device route picker** — v1 shows only Speaker/Earpiece; a
  named picker via `CallEndpoint` is a permission-free later add.
- **Positional audio / per-packet server volume** — the `Audio` proto carries
  `positional_data` and `volume_adjustment`; v1 ignores both.

## User decisions (already made)

Quotable decisions from brainstorming, in order:

- Scope: **"Full two-party conversation"** (multi-speaker mixing in scope).
- Native surface: **"A: Java I/O + platform AEC (no NDK)"** for audio I/O.
- Codec: **"libopus JNI now"** (behind the `OpusCodec` seam).
- Transmit: **"Continuous (open mic) + mute toggle."**
- Routing: **"Bump to minSdk 34 now, but keep the auto-routing for now and only
  have a simple Speaker↔Earpiece toggle similar to the system Phone call screen."**
- FEC/DTX/WSOLA and a named-BT picker: explicitly deferred (above).

## Why managed I/O + platform AEC (not Oboe)

Verified during design:

- Android's platform AEC/NS/AGC and the low-latency **FAST/MMAP** path are
  **mutually exclusive** — enabling effects (via the `VOICE_COMMUNICATION` source
  or attached `AudioEffect`s) forces the legacy AudioFlinger mixer path — the FAST
  capture/playback path does not carry effects. Oboe's docs confirm: "Sessions and
  Effects are only supported on the Legacy data path."
- The only way to get low latency *and* echo cancellation is a **software** AEC
  (WebRTC APM) — native C++, biggest lift — which is deferred.
- For networked VoIP with a receive-side jitter buffer, the ~20–40 ms local-path
  saving from MMAP is a small fraction of a mouth-to-ear budget dominated by
  network RTT + jitter-buffer depth (60–200 ms+; G.114 target < 150 ms one-way).

So v1 uses blocking `AudioRecord`/`AudioTrack` on the processed path; Oboe buys
nothing once AEC is on. `48 kHz` is Opus-native (zero resampling) and Android's
lowest-latency rate.

## Architecture

`AudioVoiceEngine` implements the existing `VoiceEngine` and is constructed where
`SyntheticVoiceSource` is today (`MumbleManager.ActiveSession`). Two halves, three
threads, one shared structure:

| Concern | Thread | Behaviour |
|---|---|---|
| Capture → encode (outgoing) | VoiceTransport's **existing** voice-send thread | `nextOutgoingFrame(timeout)` reads one 20 ms PCM frame from `AudioRecord` (the blocking read is the capture clock), Opus-encodes, returns a `VoiceFrame`. |
| Receive enqueue (incoming) | **existing** receive thread | `onIncomingFrame(...)` copies the encoded bytes into a per-speaker queue and returns — never blocks, never allocates a decoder. |
| De-jitter → decode → mix → play | **new** engine-owned playback thread | Every 20 ms (paced by blocking `AudioTrack.write()`) pulls due audio per speaker, decodes / PLC-conceals, sums, writes to `AudioTrack`. |

**Thread affinity (keeps native handles single-owner):**

- Send thread solely owns `AudioRecord` + the Opus **encoder**.
- Playback thread solely owns the Opus **decoders** (one per speaker), the mixer,
  and `AudioTrack`. Decoder *allocation* also happens here — never on the receive
  hot path.
- The per-speaker inbound queues/jitter buffers are the only cross-thread state,
  guarded by a short internal lock that holds only for byte copies (no decode, no
  native alloc under the lock).

## Audio format

**48 kHz · mono · 16-bit PCM · 20 ms (960-sample) frames** for our own
capture/transmit. Incoming packets may be any Opus duration (see below), so the
decode path never assumes 960 samples out.

## Codec: libopus via JNI

A thin JNI shim wraps libopus behind an `OpusCodec` Kotlin interface so the
codec is swappable and JVM-unit-testable at the boundary:

```kotlin
interface OpusCodec {
    /** Encode exactly one 20ms/960-sample mono PCM16 frame → Opus bytes. */
    fun encode(pcm: ShortArray, frameSamples: Int): ByteArray
    /**
     * Decode one Opus packet → PCM16 mono. Pass null opus for PLC concealment of
     * one 20ms step. Returns the number of samples written into [out].
     * [out] MUST be sized for the Opus maximum (5760 samples / 120ms @ 48kHz).
     */
    fun decode(opus: ByteArray?, offset: Int, length: Int, out: ShortArray, plcFrameSamples: Int): Int
    /** Frames of the packet in 48kHz samples, via opus_packet_get_nb_samples. */
    fun packetSamples(opus: ByteArray, offset: Int, length: Int): Int
    fun close()
}
```

- **Encoder:** one instance, `OPUS_APPLICATION_VOIP`, 1 channel, 48 kHz,
  bitrate 24–32 kbps CVBR, complexity 5–7, `OPUS_SIGNAL_VOICE`. **DTX off, FEC
  off** in v1.
- **Decoder:** one per speaker (per session), 48 kHz mono. Decode into a
  **5760-sample** (120 ms) buffer and use `opus_decode`'s return value as the
  actual sample count. A remote 60 ms packet (2880 samples) must not overflow.
- **PLC:** `opus_decode(NULL)` conceals **one 20 ms (960-sample) step**; the
  caller repeats it across a measured hole (see jitter buffer).
- **Native lifecycle:** encoder/decoder handles created and destroyed
  (`opus_encoder_destroy` / `opus_decoder_destroy`) by their single owning thread;
  all freed only after `stop()` has joined the threads.
- **Vendoring:** libopus built from source via CMake (`externalNativeBuild`) for
  all ABIs, or a pinned prebuilt — decided in the plan's Task 1.

## Capture / encode path (send thread)

`nextOutgoingFrame(timeoutNanos)`:

1. Always **drain** `AudioRecord`: blocking `read()` of one 960-sample frame. This
   is the capture clock and must run **even while muted** — skipping the read
   overflows the mic ring buffer (stale-audio burst on unmute) and busy-loops the
   thread.
2. If **muted**, discard the PCM and return `null`. On the mute *edge*
   (unmuted→muted) emit exactly one terminator frame (a `VoiceFrame` with the new
   `isTerminator = true` flag, which `VoiceTransport` maps to `Audio.is_terminator`)
   so peers see a clean end-of-talk instead of PLC-concealing our silence.
3. Else encode PCM → Opus and return `VoiceFrame(opus, len, frameNumber)`, where
   `frameNumber` advances **+2 per 20 ms packet** (10 ms Mumble units — see
   [frame_number contract](#frame_number--variable-packet-size)).

`VoiceTransport` sends to **target 0** (normal speech). A debug `loopbackVoice`
flag sets **target 31** (server echoes you) for a real end-to-end self-test.

## Receive + jitter buffer (sample/time domain)

**The jitter buffer and playout cursor operate in the sample/time domain**, not an
integer packet index. This is the single change that makes us correct against
stock Mumble clients *and* robust to any sender packet size.

`onIncomingFrame(opus, off, len, frameNumber, senderSession, arrivalNanos)`
(receive thread, non-blocking):

- Enqueue `(timestampSamples = frameNumber × 480, opusBytes)` into `senderSession`'s
  inbound queue (create the queue entry if absent — but **not** the decoder).
- Drop if older than the playout cursor (late) or a duplicate timestamp.
- If the frame carries `is_terminator`, tag the queue's end-of-talk at that
  timestamp.

Playback thread, per speaker, each 20 ms tick:

- **Prebuffer:** hold ~2 frames (~40 ms) before starting playout of a talkspurt.
- **Initial anchor:** on the first packet of a talkspurt, set the cursor to that
  packet's `timestampSamples` plus the prebuffer (not 0).
- **Decoder (lazy):** each `SpeakerStream` starts with a **null decoder**; the
  receive thread's `computeIfAbsent` creates only the queue/`JitterBuffer`. The
  playback thread calls `opus_decoder_create` on the first due frame, keeping the
  native alloc off the receive path.
- **Decoded-PCM FIFO (variable-duration decoupling):** each `SpeakerStream` holds
  a decoded-PCM FIFO. Playout pulls **exactly 960 samples per tick**. When the FIFO
  holds < 960 samples, decode the next due packet into it — a 10/20/40/60 ms packet
  adds 480/960/1920/2880 samples — so one large packet feeds multiple ticks (a
  40 ms packet yields two ticks from a single decode). This decouples a sender's
  variable packet duration from our fixed 20 ms playout.
- **Due frame:** a packet is due when its timestamp range meets the cursor. Decode
  into the 5760-sample buffer, honoring `opus_decode`'s returned sample count
  (equivalently `opus_packet_get_nb_samples`), append to the FIFO, and advance the
  cursor by that sample count.
- **Gap:** if the next needed sample range is absent but the stream is live →
  `decode(null)` PLC in **20 ms steps across the measured hole** (cursor → next
  available packet's timestamp), advancing 960 samples per step.
- **Large forward jump** (new talkspurt after a long silence, timestamp far ahead)
  → **re-anchor** the cursor to the new packet instead of PLC-filling seconds.
- **Retire** a speaker only when the playout cursor passes its tagged terminator
  **and** the queue is drained; free its decoder then. A later talkspurt for the
  same session recreates the stream via `computeIfAbsent`.

**Clock-drift watermarks (drift compensation, v1):** independent mic/speaker
clocks drift, so a fixed buffer slowly grows latency or underruns. Cap it:

- Above a **high-water** depth → drop the oldest buffered frame (bounded latency).
- Below a **low-water** depth → PLC to avoid underrun.
- Log buffer depth in `VoiceStats.bufferMs`. (Smooth WSOLA time-stretching is
  deferred.)

## Playback / mix

Playback thread loop (paced by blocking `AudioTrack.write`):

1. Zero a 960-sample `mix` buffer.
2. For each active `SpeakerStream`, pull exactly 960 samples (20 ms) from its
   decoded-PCM FIFO — decoding the next due packet into the FIFO when it holds
   < 960, or contributing PLC/silence when nothing is due (per above).
3. `AudioMixer` sums each speaker into `mix`, clipping to int16.
4. **Always** `write()` a full 20 ms buffer — **silence when no one is talking** —
   so the DAC clock and the AEC downlink reference stay alive.

## frame_number & variable packet size

Interop-critical, verified against the Mumble protocol and Opus API:

- Mumble's **"Audio per packet"** picks 1/2/4/6 base frames of 10 ms
  (480 samples) → **10/20/40/60 ms** per packet, encoded as **one** Opus packet
  (not bundled sub-frames, in Opus mode). `opus_data` = exactly one Opus packet.
- `frame_number` is the sequence of the packet's **first 10 ms frame** and
  advances by the number of contained 10 ms frames: **+2 for 20 ms, +4 for 40 ms,
  +6 for 60 ms** — and varies per sender.
- Therefore: **timestamp = `frame_number × 480` samples**; per-packet span from
  `opus_packet_get_nb_samples`; cursor advances by decoded sample count; a "gap"
  is a missing sample *range*, not a missing index. Our own transmit stays fixed
  20 ms (`frame_number += 2`), which is the universal/most-compatible choice; no
  need to match any server-advertised value.

## Wiring & integration

**`MumbleManager` / `ActiveSession`:**

- Construct `AudioVoiceEngine` instead of `SyntheticVoiceSource`;
  `VoiceTransport(engine, target = 0, …)`.
- `loopbackVoice` flag → default **false**; `true` sets target 31 (self-echo
  test). `SyntheticVoiceSource` stays in the tree, unused by default.
- Expose `voiceStats: StateFlow<VoiceStats>` (replaces `loopbackStats`) and
  `muted: StateFlow<Boolean>`; add `setMuted(Boolean)` forwarding to the engine.

**Mute:** `AudioVoiceEngine.setMuted(b)` sets a `@Volatile muted`. UI Mute toggle
→ `MumbleManager.setMuted`.

**Audio mode / focus / AEC (Context side — Activity/CallManager):** self-managed
Telecom does **not** set the audio mode for us. Explicitly:

- `AudioManager.setMode(MODE_IN_COMMUNICATION)` at call start; restore on end.
- Request voice-communication audio focus (`AUDIOFOCUS_GAIN`); abandon on end.
- Assert `AcousticEchoCanceler.getEnabled()` (log a warning if the OEM lacks it);
  this is what makes our `AudioTrack` playback the AEC downlink reference.

**Routing (Telecom `Connection`):** implement the `CallEndpoint` callbacks
(`onAvailableCallEndpointsChanged` / `onCallEndpointChanged`), expose active +
available endpoints through the `CallManager` bridge, and
`CallManager.setSpeaker(Boolean)` → `requestCallEndpointChange()` selecting the
`TYPE_SPEAKER`/`TYPE_EARPIECE` endpoint. Framework auto-routes Bluetooth. **No
`BLUETOOTH_CONNECT`** — the app never reads a `BluetoothDevice` identity nor drives
SCO itself.

**Buffer sizing:** `AudioRecord`/`AudioTrack` buffers ≥ 2–4× `getMinBufferSize()`.
Guard 48 kHz support (query/fallback) rather than assuming it.

**`ActiveCallScreen`:** status + `VoiceStats` line + control row
**[Mute] · [Speaker] · [Hang Up]** (Mute/Speaker are filled-when-active toggles),
mirroring the system call screen.

## Concurrency & lifecycle

- **Speaker map** = `ConcurrentHashMap<Int, SpeakerStream>`; the playback thread
  iterates a snapshot; the receive thread inserts queue entries only.
- **Receive thread never blocks:** copies bytes under a bounded lock; no decode,
  no `opus_decoder_create` on that path (allocation deferred to the playback
  thread).
- **Retire vs late frame** resolved by playout-cursor-passes-terminator +
  `computeIfAbsent` recreation.
- **`stop()`** = set `running = false`, **join** the send and playback threads,
  *then* destroy all libopus handles and release `AudioRecord`/`AudioTrack`. No
  native teardown while a thread is live.

## File structure

New (all under `me.danielstiner.dumble.mumble.voice` unless noted):

| File | Responsibility |
|---|---|
| `OpusCodec.kt` | Interface (encode / decode+PLC / packetSamples / close) + `LibOpusCodec` JNI impl. |
| `AudioVoiceEngine.kt` | The `VoiceEngine` impl: `AudioRecord`/`AudioTrack`, encoder, playback thread, `@Volatile muted`, `VoiceStats` flow. |
| `JitterBuffer.kt` | Per-speaker sample/time-domain reorder buffer: enqueue (receive), timed dequeue (playback), watermarks, gap/PLC sizing, re-anchor, terminator tag. |
| `SpeakerStream.kt` | Per-speaker bundle: `JitterBuffer` + a lazily-created Opus decoder + a decoded-PCM FIFO (variable-duration → 20 ms playout decoupling) + cursor/concealment bookkeeping. |
| `AudioMixer.kt` | Sum N PCM streams into one 20 ms buffer, clip to int16. |
| `VoiceStats.kt` | `data class VoiceStats(sent, received, lost, concealed, bufferMs, activeSpeakers)`. |
| `app/src/main/cpp/CMakeLists.txt`, `opus_jni.c` | JNI shim + libopus build/link. |

Modified:

| File | Change |
|---|---|
| `MumbleManager.kt` | Construct `AudioVoiceEngine`; `voiceStats`/`muted`/`setMuted`; target 0; repurpose `loopbackVoice`. |
| `VoiceEngine.kt` | Additive: `VoiceFrame` gains an `isTerminator: Boolean = false` flag (backward-compatible; the seam contract is otherwise unchanged). |
| `VoiceTransport.kt` | Constructed with `target = 0` (existing param); set `Audio.is_terminator` from `VoiceFrame.isTerminator`. |
| `telecom/CallManager.kt` + the self-managed `Connection` | `CallEndpoint` callbacks; `setSpeaker`; expose route state. |
| `ActiveCallActivity.kt` | `MODE_IN_COMMUNICATION` + audio focus + AEC assert; thread mute/speaker callbacks. |
| `ui/ActiveCallScreen.kt` | Mute/Speaker toggles + real `VoiceStats`. |
| `ui/DumbleApp.kt` | Pass mute/speaker callbacks + route/mute state. |
| `app/build.gradle.kts` + `libs.versions.toml` | `minSdk = 34`; `externalNativeBuild { cmake }`; libopus dependency/build. |
| `AndroidManifest.xml` | No new permissions (`RECORD_AUDIO`/`POST_NOTIFICATIONS` already present; **no** `BLUETOOTH_CONNECT`). |

## Testing

- **JVM unit (primary):**
  - `OpusCodec` round-trip: encode 20 ms of PCM → decode → sample count and rough
    energy match; decode a synthesized 40/60 ms packet without overflow; `decode(null)`
    PLC returns 960 samples.
  - `JitterBuffer`: in-order playout; reordered packets; a missing range → correct
    number of 20 ms PLC steps; late/duplicate drop; **variable sizes** (a 40 ms and
    a 20 ms sender interleaved) produce a monotonic cursor; high/low watermark
    drop/PLC; large-forward-jump re-anchor; terminator retire only after drain.
  - `AudioMixer`: sum + clip correctness.
  - `SpeakerStream` decoupling: a 40 ms sender packet decodes once and yields two
    960-sample playout ticks; the decoded-PCM FIFO never under/overflows across
    interleaved 20/40/60 ms input.
  - `frame_number`: our transmit advances +2 per 20 ms packet.
- **Native smoke (Task 1 gate):** load the built `.so`, `opus_encoder_create` →
  encode → `opus_decode` round-trip from an instrumented test, proving the JNI
  toolchain before any engine work.
- **On-device (manual, user gate):** connect to a live Mumble server; confirm a
  two-way conversation with another client (both directions intelligible, no
  runaway echo with AEC), Mute silences the far end, Speaker↔Earpiece toggle
  works, Bluetooth headset auto-routes mid-call, and `frame_number` deltas
  observed on the wire are +2 for our 20 ms packets. Also run the target-31
  self-echo test.

## Build / dependency changes

- `minSdk` 33 → **34** (enables `CallEndpoint`-only routing; drops Android 13).
- First **NDK/CMake** in the project: `externalNativeBuild`, `ndkVersion`,
  `CMakeLists.txt`, vendored/prebuilt **libopus** for all ABIs.
- No new runtime permissions.

## Failure & edge handling

- **No AEC on device** (`isAvailable()` false): proceed, log a warning; software
  AEC is the documented future fix.
- **48 kHz unsupported**: query and fall back; log.
- **Foreign/garbage packet** (bad Opus): decoder error → treat as a lost frame
  (PLC), don't crash.
- **Bitstream parity**: the `OpusCodec` round-trip test guards a future libopus →
  alternative swap.

## Future extension points

- **Oboe + software WebRTC AEC (Approach C):** low latency *and* consistent echo
  cancellation, behind the same `VoiceEngine` seam — the reliability/latency
  upgrade if profiling justifies it.
- **In-band FEC** (decode-next-with-`fec=1` lookahead) and **DTX**.
- **Adaptive time-scaling** (WSOLA / NetEq) replacing the watermark cap.
- **VAD / push-to-talk** transmit modes (tracked separately).
- **Named Bluetooth route picker** via `CallEndpoint` (no `BLUETOOTH_CONNECT`).
- **Positional audio** + per-packet `volume_adjustment`.
