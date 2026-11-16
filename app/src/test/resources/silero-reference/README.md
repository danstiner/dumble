# Silero reference traces

The expected output of the voice activity detector: one speech probability per 512-sample window
(32 ms at 16 kHz), produced by ONNX Runtime from `third_party/silero-vad/silero_vad_16k_op15.onnx`
with `tools/silero/make_reference.py`.

`SileroVadTest` asserts the hand-written C++ forward pass against these files. They are why ONNX
Runtime is not a dependency: a runtime of about 32 MB per ABI is replaced by a few kilobytes of
committed numbers and a script to regenerate them.

`synthetic.wav` is the generated signal behind `synthetic.txt`: silence, noise, a modulated
harmonic stack and a tone. It is committed so the C++ test runs byte-identical audio. It spans the
widest range of probabilities, so it is the clip most likely to catch a gate-order or stride error.

**Do not edit by hand.**

- If the vendored model changes, re-run the tool.
- If the C++ changes and the traces stop matching, the C++ is wrong.
- The exception is a deliberate retune of the C++, such as the decimator's filter. Then the
  mismatch is expected and the traces are what should be regenerated.
