#!/usr/bin/env python3
"""Extract Silero VAD weights from the vendored ONNX into a flat little-endian fp32 blob.

The blob is what ships; the ONNX is the source of truth. Order is the ONNX initializer order
and SileroVad.cpp depends on it exactly — see that file's constructor.

Usage: python3 tools/silero/extract_weights.py
"""
import hashlib
import json
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODEL = ROOT / "third_party/silero-vad/silero_vad_16k_op15.onnx"
BLOB = ROOT / "app/src/main/assets/silero_vad_weights.bin"

EXPECTED_FLOATS = 309633
EXPECTED_SHAPES = [
    ("model.stft.forward_basis_buffer", [258, 1, 256]),
    ("model.encoder.0.reparam_conv.weight", [128, 129, 3]),
    ("model.encoder.0.reparam_conv.bias", [128]),
    ("model.encoder.1.reparam_conv.weight", [64, 128, 3]),
    ("model.encoder.1.reparam_conv.bias", [64]),
    ("model.encoder.2.reparam_conv.weight", [64, 64, 3]),
    ("model.encoder.2.reparam_conv.bias", [64]),
    ("model.encoder.3.reparam_conv.weight", [128, 64, 3]),
    ("model.encoder.3.reparam_conv.bias", [128]),
    ("model.decoder.rnn.weight_ih", [512, 128]),
    ("model.decoder.rnn.weight_hh", [512, 128]),
    ("model.decoder.rnn.bias_ih", [512]),
    ("model.decoder.rnn.bias_hh", [512]),
    ("model.decoder.decoder.2.weight", [1, 128, 1]),
    ("model.decoder.decoder.2.bias", [1]),
]


def _varint(buf, i):
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def _fields(buf, start, end):
    i = start
    while i < end:
        key, i = _varint(buf, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = _varint(buf, i)
            yield number, wire, value
        elif wire == 2:
            length, i = _varint(buf, i)
            if i + length > end:
                raise ValueError(f"length-delimited field at offset {i} claims {length} bytes but only {end - i} available")
            yield number, wire, buf[i:i + length]
            i += length
        elif wire == 5:
            if i + 4 > end:
                raise ValueError(f"32-bit field at offset {i} needs 4 bytes but only {end - i} available")
            yield number, wire, buf[i:i + 4]
            i += 4
        elif wire == 1:
            if i + 8 > end:
                raise ValueError(f"64-bit field at offset {i} needs 8 bytes but only {end - i} available")
            yield number, wire, buf[i:i + 8]
            i += 8
        else:
            raise ValueError(f"unsupported wire type {wire}")


def initializers(model_bytes):
    """Yield (name, dims, float32 array) in graph order. ModelProto.graph is field 7."""
    graph = next(v for n, w, v in _fields(model_bytes, 0, len(model_bytes)) if n == 7 and w == 2)
    for number, wire, value in _fields(graph, 0, len(graph)):
        if number != 5 or wire != 2:          # GraphProto.initializer
            continue
        dims, name, raw, dtype = [], None, None, None
        for f, w, v in _fields(value, 0, len(value)):
            if f == 1 and w == 0:             # TensorProto.dims, unpacked
                dims.append(v)
            elif f == 1 and w == 2:           # TensorProto.dims, packed
                j = 0
                while j < len(v):
                    d, j = _varint(v, j)
                    dims.append(d)
            elif f == 2 and w == 0:
                dtype = v                     # TensorProto.data_type
            elif f == 8 and w == 2:
                name = v.decode()             # TensorProto.name
            elif f == 9 and w == 2:
                raw = v                       # TensorProto.raw_data
        if dtype != 1:
            raise SystemExit(f"{name}: expected float32 (1), got {dtype}")
        if raw is None:
            raise SystemExit(f"{name}: no raw_data; this tool does not read typed data fields")
        yield name, dims, np.frombuffer(raw, dtype="<f4").reshape(dims)


def main():
    if not MODEL.exists():
        raise SystemExit(f"missing {MODEL} — see third_party/silero-vad/NOTICE")
    found = list(initializers(MODEL.read_bytes()))
    got = [(name, list(dims)) for name, dims, _ in found]
    if got != EXPECTED_SHAPES:
        raise SystemExit(
            "initializer set changed — SileroVad.cpp's layout assumptions are invalid.\n"
            f"expected: {json.dumps(EXPECTED_SHAPES)}\ngot:      {json.dumps(got)}"
        )
    blob = b"".join(a.astype("<f4").tobytes() for _, _, a in found)
    total = sum(a.size for _, _, a in found)
    if total != EXPECTED_FLOATS or len(blob) != EXPECTED_FLOATS * 4:
        raise SystemExit(f"expected {EXPECTED_FLOATS} floats, got {total} ({len(blob)} bytes)")
    BLOB.parent.mkdir(parents=True, exist_ok=True)
    BLOB.write_bytes(blob)
    print(f"{BLOB.relative_to(ROOT)}  {len(blob)} bytes  sha256 {hashlib.sha256(blob).hexdigest()}")


if __name__ == "__main__":
    sys.exit(main())
