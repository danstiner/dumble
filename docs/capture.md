# Audio capture

Microphone to Mumble UDP-tunnel packets on the TCP transport. Three layers with a single owner for
the lifecycle. Transmit is push-to-talk today; the voice activity detector (below) is wired into
`CaptureEngine`, but no app surface sets the transmit mode to use it yet. Details live in the
code's comments; constants and their whys in `CaptureConstants.h`.

```
mic ─► OboeCapture ─► PcmRing ─► assemble packet ─► AudioEncoder ─► pollPacket ─► VoiceSender ─► transport
       (android/)     └────────── CaptureEngine (core/) ─────────┘                (Kotlin pump)
```

**CaptureEngine** (`core/`, platform-free) owns everything between "PCM arrived" and "an Opus
packet is ready" — packet assembly is `PacketAssembler` under push-to-talk, or the frame-native
preroll queue's `popPacket` under voice activity; `PacketAssembler::takePacket` never runs in the
latter. `OboeCapture` (`android/`) owns the input stream and its reopen backoff. The transmit gate
lives in `onPcm`: while closed, samples advance the frame clock but are never captured, so the
stream stays open and warm across presses.

**VoiceSender** (Kotlin pump): one daemon thread parked in `pollPacket`, wrapping each packet for
the transport. Its exit callback is the only signal the pump is gone; nothing may destroy the
engine while the pump can still touch it.

**Lifecycle** (`MumbleConnection`): four producers demand transitions concurrently — the Talk
button, telecom hold/resume, disconnect/reconnect, and the pump's own exit — so every transition
is a command on one channel with a single consumer, and state is levels, not events: `reconcile()`
compares them and is the only place a session opens or closes. Session and transmit are separate
levels — a Talk press both re-asks for a session and opens the gate, which is what brings a
session back after a hold or a terminal failure, still transmitting if the button is still down.
The mirrored-write argument that makes that race-free is KDoc'd at `setTransmitting`.

**Platform call** (`TelecomCall`, behind the `VoiceCall` seam): registering a self-managed telecom
call is what grants audio focus, communication routing, and the microphone foreground service. A
hold — an incoming cellular call is the case that matters — releases the capture session entirely
rather than gating it, because the platform owns the input device for the duration; a Talk press
while held doubles as the resume request, since core-telecom sends no unsolicited resume.

The invariants — never two open input streams, the engine freed only after its pump exits (a
wedged pump leaks deliberately rather than risk a use-after-free), no auto-reopen after a terminal
failure, generation-keyed hold callbacks — are pinned by `CaptureLifecycleTest`,
`CaptureLifecycleChaosTest`, and `VoiceSenderTest`.

## Voice activity detection

Silero VAD v6.2 as a hand-written forward pass in the portable core (`core/VoiceActivity.{h,cpp}`,
`core/SileroVad.{h,cpp}`, `core/Decimator.{h,cpp}`), so no inference runtime ships. The units are
built and pinned against ONNX Runtime reference traces, and `CaptureEngine` drives them: the
transmit mode selects the path, and no app surface sets that mode yet.

This section is detailed because the implementation deliberately diverges from how Silero is
normally run — no ONNX Runtime, a different STFT, a 48 kHz front end the upstream model has never
seen — and a reader who assumes "it's just Silero" will draw wrong conclusions from the code.

### Two gates, deliberately not one

Capture has two gates. The **arming** gate in `onPcm` decides whether the
microphone reaches the ring at all; it is push-to-talk's gate today, and under voice activity it
becomes an arming level that stays open for the session — a detector cannot judge audio the gate
already discarded. The **speech** gate is `SpeechGate`, downstream of the arming gate: it renders a
transmit/closing decision per frame, before those frames are assembled into a packet.

### The path a frame takes

```
480 × int16 @ 48 kHz          one capture frame (kFrameSamples)
  ├─ Decimator                75-coefficient FIR low-pass (windowed sinc), then take every 3rd sample
160 × float @ 16 kHz          appended to a 512-sample staging window
  ├─ SileroVad::process       triggered when 512 samples have accumulated
speech probability            held between inferences, so every frame has a level
  ├─ SpeechGate               two-threshold hysteresis + a hangover counted in frames
Decision{transmit, closing}
```

Previously, `CaptureEngine` OR-folded two decided frames into one packet verdict before
acting on it — a packet transmitted if either frame was speech — so this diagram described a
decision the pipeline computed and then discarded. That fold is gone: a packet is now assembled
from already-decided frames only at the encode boundary, so the diagram above is the literal path,
not an approximation of it. The eval corpus shows the effect directly: frames the engine transmits
that the model never predicted fell from 7 to 1 across the three clips; the survivor is a different
mechanism — an odd-length spurt's closing packet zero-padding its unfilled half — not a fold
artifact.

160 does not divide 512, so a window completes on a fixed **4, 3, 3, 3, 3** cycle — five windows
per sixteen frames, exactly, with no drift. `VoiceActivity` runs the inference *before* handing
the level to the gate, so a frame that completes a window decides on that window.

`SpeechGate` is the whole gate policy and none of the signal path, which is why it is a separate
type its tests can drive with levels directly.

### Preroll

Two different numbers describe a spurt's onset, and conflating them has produced a wrong preroll
argument twice. **40 ms** is the structural bound: the longest gap between inferences, so the
earliest any decision *could* reflect the onset, by enumeration over the cadence. **~62-70 ms** is what
preroll actually has to cover, because a window containing a few samples of speech does not fire —
the probability rises only as the window fills, so the gate opens roughly a gap plus a fill after
onset — longer still if a nearly-full window fails to fire and the following gap is a long one.

`kPrerollFrames` = 6 backfills 60 ms at gate-open. The queue holds 7 frames (`kHistorySlots` =
`kPrerollFrames + 1`); the extra slot exists so the onset frame has somewhere to land on its way
into the burst. Onset adequacy is what `MeetsThePinnedBars` scores against labelled speech.

The burst is sized from the detector and only *checked* against the receiver — never derived from
a receive-side constant. (An earlier draft justified 3 as "exactly the receiver's prebuffer",
which stopped being true the moment the fixed prebuffer went adaptive.) The check:
a gate-open that starts a spurt does not run the adaptive jitter buffer's catch-up trim, and 60 ms
sits far below that trim's 100 ms threshold.

### Why the Silero runtime is hand-written

`onnxruntime-android` 1.29.0 ships 32.1 MB for arm64-v8a, 22.7 MB for armeabi-v7a and 38.5 MB for
x86_64, and release builds carry all three — roughly 100× every other native artifact in the
project (Oboe, for scale, is 0.33 MB), to evaluate 309,633 parameters. (AAR sizes measured during
design; not reproducible from this repo.)

What ships instead is a 1,238,532-byte fp32 weight blob in `assets/` and ~290 lines of C++. ONNX
Runtime appears nowhere in the build — not as an app dependency, not as a test dependency. Its
role is taken by committed reference traces plus `tools/silero/` to regenerate them.

### Where this diverges from upstream Silero

Each of these is a place where reading the upstream Python or the ONNX graph will mislead you.

- **The STFT is an FFT, not the model's own matmul.** Silero's exported graph performs its STFT as
  a `Conv` against `forward_basis_buffer`, a dense 258 × 256 DFT basis carried in the weights.
  That basis is exactly `window * cos` and `-window * sin` — verified against the shipped blob to
  5.7e-8 — so a 256-point radix-2 FFT reproduces its magnitude output, replacing 264,192 MAC per
  window with four transforms. Row 0 of the basis *is* the analysis window and is kept; the other
  65,792 floats are read at construction and dropped. A general runtime cannot make this
  substitution: it sees an opaque weight tensor, not a transform.
- **The blob is not the ONNX file.** `tools/silero/extract_weights.py` writes the 15 initializers
  in a fixed order and refuses to write if the initializer set or shapes differ from what
  `SileroVad.cpp` assumes. `kWeightFloats` is `static_assert`ed against the layout the constructor
  walks, so a shape table edited without the count is a compile error rather than a garbage model.
- **The reflect pad is asymmetric**: 0 samples left, 64 right, applied after the 64 carried
  context samples are prepended. Symmetric padding — the obvious guess — costs max |delta| 0.109
  against the reference traces.
- **Encoder strides are 1, 2, 2, 1**, so four STFT steps collapse to one and the LSTM steps
  **once** per window rather than four times.
- **The LSTM weights are in PyTorch gate order** (i, f, g, o); the ONNX graph reorders them
  internally. Using ONNX order yields mean |delta| 0.27 — confidently wrong rather than obviously
  broken.
- **Shapes are fixed, so there is no graph.** ORT dispatches 58 kernel invocations per window
  through a general executor; here that is straight-line code.
- **The input is 48 kHz.** Upstream takes 16 kHz. `Decimator` bridges it and sits *inside* the
  parity assertion, so changing the filter without the model, or the model without the filter,
  fails loudly.

The extraction script asserts initializer names and shapes, nothing more. Strides, pad geometry
and step count are graph properties it cannot see — those claims are anchored by the parity traces
alone.

Being ~1.8× faster than ORT on a Pixel 7a is not better engineering than a mature runtime: it is
the FFT substitution and the fixed shapes, both of which need knowledge of what the weights
*mean*. The rest is ordinary optimisation; the code comments in `SileroVad.cpp`, `Decimator.h` and
`Dot.h` carry the specifics.

### The decimator's fold band

3:1, 48 kHz to 16 kHz, 75 taps, Hann-windowed sinc at 7 kHz, unity DC gain. Everything above the
8 kHz output Nyquist folds into the band the detector reads, so the requirement is a floor across
the whole fold band, not attenuation at one tone: **43.94 dB worst case over 8.2–23 kHz**, at
8297 Hz. Two properties of that guarantee cost real measurement to establish — do not relitigate
them from a single number:

- **A single probe is worthless.** Stopband ripple is not monotonic in tap count — 9 kHz measures
  30.72 dB at 33 taps but 70.20 dB at 41 — so a lucky null passes a filter that does not work.
  `DecimatorTest` sweeps ten probes; one sits at 8300 Hz because without it the set bottoms out at
  45.62 dB, flattering the filter by 1.68 dB.
- **The guarantee starts at 8.2 kHz, not 8.0.** An 8 kHz input lands exactly on the output
  Nyquist, where measured attenuation ranges from about 39 dB to a near-perfect null depending on
  the input's phase alone — no single number describes that point. Content in 8.0–8.2 kHz folds
  only to 7.8–8.0 kHz, the top edge of the band.

### The gate constants

`kOpenLevel` 0.60, `kCloseLevel` 0.45, `kHangoverFrames` 20 (200 ms). `CaptureConstants.h` says
why the shape is two thresholds plus a frame-counted hangover; this is where the values came from.

They were swept against the labelled corpus: open in {0.30…0.70}, close = open − {0.10, 0.15,
0.25}, hangover in {10, 15, 20, 30} — 60 candidates. **The sweep saturated**: all 60 scored zero
false openings, zero missed regions, zero dropout. The only movement was the quiet talker
(`dev-other-700-122866-0000`, −31.4 dBFS) dipping to 0.998 coverage / 10 ms onset for open
0.40–0.60 (perfect at 0.30), joined by a second clip at 0.70. So the data discriminated exactly
one thing — degradation spreads at open 0.70 — and could not rank open within {0.40, 0.50, 0.60},
any close gap, or any hangover. The inherited 0.60 stands as the highest open the corpus does not
penalise; 0.30's marginal win (0.002 coverage on one clip) is not worth sitting at the plateau's
low edge.

The high-edge tie-break applies to `open` only. A longer hangover swallows spurious re-triggers
into an existing window where no opening edge is recorded, so the corpus would flatter it without
measuring it — maximising the hangover would deepen the corpus's blind spot, not hedge against it.
(`nearSpeech` in `EvalCorpus.h` also derives its exemption from `kHangoverFrames`; the recorded
sweep held it fixed at 200 ms.) Re-ranking the plateau needs negative material: noise-only and
noisy-speech clips.

The sweep harness is not committed — only its conclusion, and the fixed point at the shipped
constants, which `VoiceActivityEvalTest` reproduces on every run (coverage 1.000 / 0.998 / 1.000,
onset 0 / 10 / 0 ms, zero dropout, zero false openings).

### Correctness

`tools/silero/make_reference.py` runs the vendored ONNX under ONNX Runtime offline and commits one
probability per window per clip; `SileroVadTest` asserts the C++ against those traces at a 1e-4
tolerance. Measured max |delta|:

| clip | Debug (−O0) | RelWithDebInfo (−O2) |
|---|---|---|
| `dev-other-116-288045-0000-trim` | 9.76e-07 | 9.69e-07 |
| `dev-other-700-122866-0000` | 3.58e-07 | 4.17e-07 |
| `dev-other-1255-138279-0002` | 7.75e-07 | 7.15e-07 |
| `synthetic` (spans 0.0017–0.9999) | 5.36e-07 | 5.96e-07 |

The deltas are optimisation-level dependent — `-O2` contracts multiply-adds differently — so quote
them with the build that produced them. The pin also held across toolchains: re-run on a Pixel 7a
with the Android NDK, max delta 3.58e-07.

The blob is pinned by SHA-256 computed in-process (`5afe9645…`), not by size alone: a swapped blob
of the right length would otherwise sail past `create()` and produce plausible wrong
probabilities.

### Cost

Pixel 7a (Tensor G2), single-threaded, standalone binaries built from these sources. The bench
harness is not committed, so these are recorded measurements, not reproducible ones:

| | Cortex-X1 (big) | Cortex-A55 (little) |
|---|---|---|
| model | **63.1 µs** | 628.5 µs |
| model + decimator | **68.6 µs** | 672.7 µs |
| ONNX Runtime, best config | 113.5 µs | 889.2 µs |
| ONNX Runtime, default | 133.9 µs | — |

68.6 µs against a 32 ms window is **0.21% of realtime**; pinned to a little core it is 2.1%, so
scheduler placement is not a risk. "Best config" is `ORT_ENABLE_ALL` with free dimensions pinned
to the shapes we hard-code — ORT given every advantage we give ourselves.

The forward pass executes **353,664 MAC per window, ≈11 MMAC/s**, counting only the taps it runs.
Beware the figure 679,552: that counts the dense STFT basis the FFT replaced *and* the
zero-padding taps `Conv::apply` skips, so it describes a model this code deliberately does not
run. The decimator's 1.2 MMAC/s is small against either. Live weights are 243,072 floats
(~950 KiB) — the blob's 309,633 minus the dropped DFT basis, a folded bias vector, and the head's
scalar bias. The forward pass allocates nothing; its working set is ~13 KB of stack.

### Known blind spots

All three are in `TODO.md` with what would close them.

- **The eval corpus cannot measure false-activation risk.** Three clean LibriSpeech clips with
  room tone contain nothing that should trigger the gate, so the false-opening rate reads 0.00 at
  every threshold. Closing it needs noise-only and noisy-speech material.
- **That metric also undercounts by construction.** A re-trigger inside an existing hangover
  records no opening edge, so it is invisible to the rate — and it extends the spurt: one
  re-trigger on the last hangover frame adds up to 190 ms of live mic, and chained re-triggers are
  unbounded.
- **The engine-vs-model excess count is a lower bound, not an exact count, in general.**
  `pollPacket` floor-clamps each emitted frame number upward-only for strict monotonicity, so a pad
  in one spurt can leave the floor permanently ahead of true position; a later spurt's shifted range
  can then land back inside the model's predicted run and hide a real excess frame instead of
  reporting it. Nothing in the eval corpus sits downstream of a clamp shift — only clip 2 pads, on
  its one spurt's closing packet, and clips 1 and 3 have none — so the pinned 0/1/0 excess counts
  are exact today, but the check does not guarantee that in general.

### Regenerating

Any change to the vendored `.onnx`: re-run `extract_weights.py`, then `make_reference.py`, then
update the blob SHA-256 pinned in `SileroVadTest.cpp`. A model bump that skips the reference
regeneration fails the parity test — intended. `tools/silero/README.md` has the details; neither
script runs during a build or a test.
