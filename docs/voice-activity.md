# Voice activity detection

Decides, frame by frame, whether the microphone is carrying speech, so that voice-activated
transmit sends speech and nothing else. It is Silero VAD v6.2, written by hand as a forward pass
in the portable core (`core/VoiceActivity`, `core/SileroVad`, `core/Decimator`).
[capture.md](capture.md) covers the pipeline it sits in.

It departs from how Silero is normally run: no ONNX Runtime, a different transform, and a 48 kHz
front end the model has never seen. Reading the upstream Python or the ONNX graph will mislead you
in the places listed under [Where this differs](#where-this-differs-from-upstream-silero).

## Goals

- **Send speech and only speech:** the first syllable intact, short pauses not chopped, silence
  unsent.
- **No inference runtime in the APK.**
- **Provably the same model.** The output matches ONNX Runtime's on committed traces.
- **Cheap on any core,** so scheduler placement cannot matter.

## Design

```
480 × int16 @ 48 kHz          one capture frame (kFrameSamples)
  ├─ Decimator                75-tap low-pass, then every 3rd sample
160 × float @ 16 kHz          appended to a 512-sample window
  ├─ SileroVad::process       runs when 512 samples have accumulated
speech probability            held between inferences, so every frame has a level
  ├─ SpeechGate               two thresholds plus a hangover counted in frames
Decision{transmit, closing}
```

160 does not divide 512, so a window completes every 4, 3, 3, 3, 3 frames: five windows per sixteen
frames exactly, with no drift. `VoiceActivity` runs the inference before handing the level to the
gate, so a frame that completes a window is decided on that window. Packets are assembled from
frames that have already been decided.

Capture has two gates under voice activity:

- The **arming gate**, in `CaptureEngine::onPcm`, decides whether microphone audio reaches the
  ring. Under push-to-talk it is the Talk button. Under voice activity it is open for the whole
  session while not muted.
- The **speech gate**, `SpeechGate`, sits after it and decides per frame whether to transmit. It
  holds the whole gate policy and none of the signal path, so its tests drive it with levels
  directly.

## Decisions

| Decision | Why | Cost |
|---|---|---|
| The forward pass is written by hand | ONNX Runtime is roughly 100 times the size of every other native artifact in the project. | The code is tied to one model's shapes, and every model change must be re-verified against reference traces. |
| An FFT replaces the model's own transform | The model computes its transform as a dense matrix product, 264,192 multiply-accumulates per window. Four FFTs give the same magnitudes. | It relies on the stored basis being exactly a windowed Fourier basis, which is verified, not assumed. |
| A decimator feeds the 16 kHz model from 48 kHz capture | Capture runs at 48 kHz and the model takes 16 kHz. | Everything above 8 kHz folds into the band the model reads, so the filter must hold a floor across the whole fold band. |
| Two gates, arming and speech | A detector cannot judge audio that was already discarded. | The microphone is read for the whole session while unmuted. |
| A spurt opens with 60 ms of audio from before the gate opened | The gate opens 62 to 70 ms after speech starts. | Each spurt begins with a burst of packets the receiver must absorb. |
| The gate opens at 0.60, closes at 0.45, and hangs over for 200 ms | 0.60 is the highest opening level the labelled corpus does not penalise. | The corpus cannot rank the alternatives or measure false activations. |

## Why no inference runtime

`onnxruntime-android` 1.29.0 is 32.1 MB for arm64-v8a, 22.7 MB for armeabi-v7a and 38.5 MB for
x86_64, and a release build carries all three, to evaluate 309,633 parameters. Oboe, for scale, is
0.33 MB. (Sizes measured during design; not reproducible from this repo.)

What ships is a 1,238,532-byte fp32 weight file in `assets/` and about 300 lines of C++. ONNX
Runtime is not a dependency of the app or of its tests; committed reference traces take its place.

## Preroll

Two numbers describe the start of a spurt, and they are easy to confuse.

- **40 ms** is the longest gap between inferences, so it bounds how soon a decision could reflect
  the onset.
- **62 to 70 ms** is what preroll has to cover. A window holding a few samples of speech does not
  fire; the probability rises as the window fills. So the gate opens about one gap plus one fill
  after the onset, and later if a nearly full window fails to fire before a long gap.

`kPrerollFrames` is 6, so 60 ms is sent from before the gate opened. The queue holds 7 frames
(`kHistorySlots`); the extra slot gives the onset frame somewhere to land on its way into the
burst. `MeetsThePinnedBars` scores the onset against labelled speech.

The burst is sized from the detector and only checked against the receiver. The check: a gate-open
that starts a spurt does not run the jitter buffer's catch-up trim, and 60 ms is well under that
trim's 100 ms threshold ([playout.md](playout.md)).

## Where this differs from upstream Silero

| Difference | Detail |
|---|---|
| The transform is an FFT | Silero's graph does its short-time Fourier transform as a `Conv` against `forward_basis_buffer`, a dense 258 × 256 basis stored in the weights. That basis is exactly `window * cos` and `-window * sin` (verified against the shipped weights to 5.7e-8), so a 256-point radix-2 FFT gives the same magnitudes. That replaces 264,192 multiply-accumulates per window with four transforms. Row 0 of the basis is the analysis window and is kept; the other 65,792 floats are dropped at construction. |
| The weight file is not the ONNX file | `extract_weights.py` writes the 15 initializers in a fixed order and refuses if their names or shapes differ from what `SileroVad.cpp` assumes. `kWeightFloats` is `static_assert`ed against the layout, so a mismatch is a compile error. |
| The reflect pad is asymmetric | 0 samples left and 64 right, applied after the 64 carried context samples are prepended. Symmetric padding costs a maximum difference of 0.109 against the reference. |
| The recurrent layer steps once per window | Encoder strides are 1, 2, 2, 1, so four transform steps collapse to one. |
| The recurrent weights are in PyTorch gate order (i, f, g, o) | The ONNX graph reorders them internally. ONNX order gives a mean difference of 0.27: confidently wrong, not obviously broken. |
| There is no graph | Shapes are fixed, so the 58 kernel invocations ONNX Runtime dispatches per window are straight-line code. |
| The input is 48 kHz | Upstream takes 16 kHz. `Decimator` converts, and it sits inside the parity assertion, so changing the filter or the model alone fails the test. |

The extraction script checks initializer names and shapes only. Strides, pad geometry and step
count are properties of the graph that it cannot see; only the parity traces verify them.

The speed over ONNX Runtime (about 1.8 times on a Pixel 7a) comes from the FFT substitution and
the fixed shapes, both of which need knowledge of what the weights mean. The rest is ordinary
optimisation, described in the comments of `SileroVad.cpp`, `Decimator.h` and `Dot.h`.

## The decimator

3:1, 48 kHz to 16 kHz: 75 taps, Hann-windowed sinc at 7 kHz, unity gain at DC. Everything above
the 8 kHz output Nyquist frequency folds into the band the detector reads. So the requirement is a
floor across the whole fold band: **43.94 dB worst case over 8.2 to 23 kHz**, at 8297 Hz.

Two properties took real measurement to establish:

- **One probe frequency proves nothing.** Stopband ripple is not monotonic in tap count: 9 kHz
  measures 30.72 dB at 33 taps and 70.20 dB at 41. `DecimatorTest` sweeps ten probes. One sits at
  8300 Hz because without it the set bottoms out at 45.62 dB and flatters the filter by 1.68 dB.
- **The guarantee starts at 8.2 kHz, not 8.0.** An 8 kHz input lands exactly on the output
  Nyquist frequency, where the measured attenuation runs from about 39 dB to a near-perfect null
  depending only on the input's phase. Content between 8.0 and 8.2 kHz folds only to 7.8 to
  8.0 kHz, the top edge of the band.

## The gate constants

`kOpenLevel` 0.60, `kCloseLevel` 0.45, `kHangoverFrames` 20 (200 ms). The comment on `SpeechGate` in
`VoiceActivity.h` says why the shape is two thresholds and a frame-counted hangover. The values came
from a sweep over the labelled corpus: open in {0.30…0.70}, close = open − {0.10, 0.15, 0.25},
hangover in {10, 15, 20, 30}, 60 candidates.

**The sweep saturated.** All 60 scored zero false openings, zero missed regions and zero dropout.
The only movement was the quiet talker (`dev-other-700-122866-0000`, −31.4 dBFS) dipping to 0.998
coverage and a 10 ms onset for open 0.40 to 0.60 (perfect at 0.30), joined by a second clip at
0.70. The data therefore showed one thing: results degrade at open 0.70. It could not rank open
within {0.40, 0.50, 0.60}, any close gap, or any hangover.

- **Open stays at 0.60,** the highest value the corpus does not penalise. The 0.002 of coverage
  that 0.30 gains on one clip is not worth sitting at the low edge of the plateau.
- **The same tie-break is not applied to the hangover.** A longer hangover absorbs spurious
  re-triggers into an open window, where no opening is recorded, so the corpus would reward it
  without measuring it. (`nearSpeech` in `EvalCorpus.h` derives its exemption from
  `kHangoverFrames`; the sweep held it at 200 ms.)
- **Ranking the plateau needs negative material:** noise-only and noisy-speech clips.

The sweep harness is not committed, only its conclusion. `VoiceActivityEvalTest` reproduces the
result at the shipped constants on every run: coverage 1.000 / 0.998 / 1.000, onset 0 / 10 / 0 ms,
zero dropout, zero false openings.

## Correctness

`tools/silero/make_reference.py` runs the vendored ONNX file under ONNX Runtime offline and commits
one probability per window per clip. `SileroVadTest` asserts the C++ against those traces within
1e-4. Measured maximum difference:

| clip | Debug (−O0) | RelWithDebInfo (−O2) |
|---|---|---|
| `dev-other-116-288045-0000-trim` | 9.76e-07 | 9.69e-07 |
| `dev-other-700-122866-0000` | 3.58e-07 | 4.17e-07 |
| `dev-other-1255-138279-0002` | 7.75e-07 | 7.15e-07 |
| `synthetic` (spans 0.0017–0.9999) | 5.36e-07 | 5.96e-07 |

The differences depend on optimisation level, because `-O2` contracts multiply-adds differently,
so quote them with the build that produced them. The result also held across toolchains: on a
Pixel 7a with the Android NDK the maximum was 3.58e-07.

The weight file is pinned by a SHA-256 computed in-process (`5afe9645…`), not by size. A swapped
file of the right length would otherwise load and produce plausible wrong probabilities.

## Cost

Pixel 7a (Tensor G2), single thread, standalone binaries built from these sources. The bench
harness is not committed, so these are recorded measurements, not reproducible ones.

| | Cortex-X1 (big) | Cortex-A55 (little) |
|---|---|---|
| model | **63.1 µs** | 628.5 µs |
| model + decimator | **68.6 µs** | 672.7 µs |
| ONNX Runtime, best configuration | 113.5 µs | 889.2 µs |
| ONNX Runtime, default | 133.9 µs | — |

68.6 µs per 32 ms window is 0.21% of realtime, and 2.1% on a little core, so which core the
scheduler picks does not matter. "Best configuration" is `ORT_ENABLE_ALL` with free dimensions
pinned to the shapes we hard-code.

The forward pass executes 353,664 multiply-accumulates per window, about 11 million a second. The
figure 679,552 counts the dense basis the FFT replaced and the zero-padding taps `Conv::apply`
skips, so it describes a model this code does not run. The decimator adds 1.2 million a second. Live
weights are 243,072 floats (about 950 KiB): the file's 309,633 minus the dropped basis, a folded
bias vector and the head's scalar bias. The forward pass allocates nothing and uses about 13 KB of
stack.

## Known blind spots

All three are in `TODO.md` with what would close them.

- **The corpus cannot measure false activations.** Three clean LibriSpeech clips contain nothing
  that should open the gate wrongly, so the false-opening rate is 0.00 at every threshold.
- **That rate also undercounts by construction.** A re-trigger inside a hangover records no
  opening, and it extends the spurt: one on the last hangover frame adds up to 190 ms of live
  microphone, and chained re-triggers are unbounded.
- **The count of frames sent beyond what the model predicted is a lower bound.** `pollPacket`
  only ever moves an emitted frame number forward, so padding in one spurt can leave the clock
  ahead of the true position, and a later spurt's shifted range can then hide a real extra frame.
  Nothing in the corpus is downstream of such a shift, so today's pinned counts (0 / 1 / 0) are
  exact. The one extra frame is a closing packet zero-padding its unfilled half.

## Regenerating

After any change to the vendored `.onnx`: run `extract_weights.py`, then `make_reference.py`, then
update the SHA-256 pinned in `SileroVadTest.cpp`. A model change that skips the reference
regeneration fails the parity test, as intended. Neither script runs during a build or a test.
See `tools/silero/README.md`.
