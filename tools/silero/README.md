# Silero VAD offline tools

Two scripts that regenerate the voice activity detector's committed artifacts from the vendored
model. Neither runs during a build or a test. They exist so that ONNX Runtime does not have to be
a dependency, and so that a model change is a reviewable diff. The design is in
[docs/voice-activity.md](../../docs/voice-activity.md).

```bash
python3 -m pip install numpy onnx onnxruntime    # a virtualenv is fine
```

`extract_weights.py` needs only numpy.

| Script | Reads | Writes |
|---|---|---|
| `extract_weights.py` | `third_party/silero-vad/silero_vad_16k_op15.onnx` | `app/src/main/assets/silero_vad_weights.bin`: 309,633 little-endian fp32 in ONNX initializer order |
| `make_reference.py` | the same model, run under ONNX Runtime over each corpus clip | `app/src/test/resources/silero-reference/<clip>.txt`: one probability per 512-sample window; also the generated `synthetic.wav` |

`extract_weights.py` refuses to write if the initializer names or shapes differ from what
`core/SileroVad.cpp` assumes. A silent reordering would produce plausible wrong probabilities that
only the parity test catches.

`SileroVadTest` asserts the C++ against the reference files.

## After changing the model

1. Run `extract_weights.py`.
2. Run `make_reference.py`.
3. Update the weight file's SHA-256 pinned in `app/src/test/cpp/SileroVadTest.cpp`.

Skipping step 2 fails the parity test, as intended.
