#!/usr/bin/env python3
"""Generate committed Silero probability traces with ONNX Runtime.

The C++ forward pass is asserted against these by SileroVadTest, which is why onnxruntime is a
tool dependency here and not a dependency of the project. Re-run after any change to the
vendored .onnx, then update the blob sha256 pinned in the test.

Usage: python3 tools/silero/make_reference.py
"""
import pathlib
import sys
import wave

import numpy as np
import onnxruntime as ort

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODEL = ROOT / "third_party/silero-vad/silero_vad_16k_op15.onnx"
CORPUS = ROOT / "app/src/test/resources/LibriSpeech-ASR-corpus"
OUT = ROOT / "app/src/test/resources/silero-reference"

# TAPS/CUTOFF_HZ must match core/Decimator.h exactly — see the plan's Global Constraints.
SAMPLE_RATE, RATIO, TAPS, CUTOFF_HZ = 48000, 3, 75, 7000.0
WINDOW, CONTEXT = 512, 64
FRAME = 480          # 10 ms at 48 kHz, the unit the C++ decimates in


def taps():
    """Matches to within one tap ulp (measured): windowed sinc, Hann, unity DC gain. The C++
    divides an already-float32-rounded tap by a double sum; this divides float64 by float64 and
    rounds once — 19 of 75 taps differ by <=1 ulp. Absorbed by the parity test's 1e-4 tolerance."""
    fc = CUTOFF_HZ / SAMPLE_RATE
    mid = (TAPS - 1) / 2.0
    h = np.empty(TAPS, dtype=np.float64)
    for k in range(TAPS):
        x = k - mid
        sinc = 2 * fc if x == 0 else np.sin(2 * np.pi * fc * x) / (np.pi * x)
        h[k] = sinc * (0.5 - 0.5 * np.cos(2 * np.pi * k / (TAPS - 1)))
    return (h / h.sum()).astype(np.float32)


def decimate(pcm):
    """Frame-by-frame, exactly as VoiceActivity drives it, so tap history matches the C++."""
    h = taps()
    history = np.zeros(TAPS, dtype=np.float32)
    pos = 0
    out = []
    for start in range(0, len(pcm) - FRAME + 1, FRAME):
        for i in range(FRAME):
            history[pos] = np.float32(pcm[start + i] / 32768.0)
            pos = (pos + 1) % TAPS
            if i % RATIO == 0:
                acc = np.float32(0)
                idx = pos - 1
                for k in range(TAPS):
                    if idx < 0:
                        idx += TAPS
                    acc = np.float32(acc + h[k] * history[idx])
                    idx -= 1
                out.append(acc)
    return np.asarray(out, dtype=np.float32)


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, path
        assert w.getframerate() == SAMPLE_RATE, f"{path}: {w.getframerate()} Hz"
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float64)


def synthetic():
    """Silence, noise, a 4 Hz-modulated harmonic stack, then a tone — full probability range."""
    rng = np.random.default_rng(7)
    n = SAMPLE_RATE * 4
    t = np.arange(n) / SAMPLE_RATE
    sig = np.zeros(n)
    seg = n // 4
    sig[seg:2 * seg] = 0.05 * rng.standard_normal(seg)
    band = slice(2 * seg, 3 * seg)
    tt = t[band] - t[band][0]
    env = 0.5 * (1 + np.sin(2 * np.pi * 4 * tt))
    sig[band] = 0.3 * env * sum(np.sin(2 * np.pi * (120 * k) * tt) / k for k in range(1, 12))
    sig[3 * seg:] = 0.4 * np.sin(2 * np.pi * 1000 * t[3 * seg:])
    return np.clip(sig, -1, 1) * 32767


def probabilities(session, decimated):
    state = np.zeros((2, 1, 128), dtype=np.float32)
    context = np.zeros(CONTEXT, dtype=np.float32)
    has_sr = any(i.name == "sr" for i in session.get_inputs())
    out = []
    for start in range(0, len(decimated) - WINDOW + 1, WINDOW):
        window = decimated[start:start + WINDOW]
        feeds = {"input": np.concatenate([context, window])[None, :].astype(np.float32),
                 "state": state}
        if has_sr:
            feeds["sr"] = np.array(16000, dtype=np.int64)
        prob, state = session.run(None, feeds)
        out.append(float(prob.ravel()[0]))
        context = window[-CONTEXT:].copy()
    return out


def write_wav(path, pcm):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(np.clip(pcm, -32768, 32767).astype("<i2").tobytes())


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    session = ort.InferenceSession(str(MODEL))
    clips = [(p.stem, read_wav(p)) for p in sorted(CORPUS.glob("*.wav"))]
    # The synthetic signal is written out as a WAV too, so the C++ parity test can run the exact
    # same audio. Read it BACK rather than reusing the float array: write_wav truncates to int16,
    # so the in-memory floats and the file differ by up to 1 LSB. Tracing the floats would describe
    # a signal that was never on disk — measured at 3.5e-3 probability divergence, 35x the parity
    # tolerance, and it fails only on this clip because every other clip's samples are already
    # integers.
    write_wav(OUT / "synthetic.wav", synthetic())
    clips.append(("synthetic", read_wav(OUT / "synthetic.wav")))
    if len(clips) != 4:
        raise SystemExit(f"expected 3 corpus clips plus synthetic, found {len(clips)}")
    for name, pcm in clips:
        probs = probabilities(session, decimate(pcm))
        (OUT / f"{name}.txt").write_text("".join(f"{p:.9g}\n" for p in probs))
        print(f"{name:<36} {len(probs):4d} windows  {min(probs):.4f}..{max(probs):.4f}")


if __name__ == "__main__":
    sys.exit(main())
