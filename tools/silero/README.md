# Silero VAD offline tools

Neither script runs during a build or a test. They exist so the committed artifacts are
reproducible from the vendored model, and so a model bump is a reviewable diff.

Requirements: `python3 -m pip install numpy onnx onnxruntime` (a virtualenv is fine).
`extract_weights.py` needs only numpy; `make_reference.py` needs all three.

## extract_weights.py

Reads `third_party/silero-vad/silero_vad_16k_op15.onnx` and writes
`app/src/main/assets/silero_vad_weights.bin` — 309,633 little-endian fp32 in ONNX initializer
order. It refuses to write if the initializer set or shapes differ from what
`core/SileroVad.cpp` assumes, because a silent reordering produces plausible-looking wrong
probabilities that only the parity test catches.

## make_reference.py

Runs the ONNX under ONNX Runtime over each corpus clip and writes
`app/src/test/resources/silero-reference/<clip>.txt`, one probability per 512-sample window.
`SileroVadTest` asserts the C++ against these, which is why ONNX Runtime is not a dependency
of this project in any form.

## When you must re-run both

Any change to the vendored `.onnx`. Re-run `extract_weights.py`, then `make_reference.py`,
then update the blob sha256 pinned in `app/src/test/cpp/SileroVadTest.cpp`. A model bump that
skips the reference regeneration will fail the parity test, which is the intended behaviour.
