"""Offline model contract smoke test using a synthetic blank image, not user data."""
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

root = Path(__file__).resolve().parents[1]
assets = root / "app" / "src" / "main" / "assets" / "ocr"
alphabet = json.loads((assets / "charsets.json").read_text(encoding="utf-8"))["charset"]
options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
options.log_severity_level = 3
session = ort.InferenceSession(str(assets / "common_old.onnx"), options, providers=["CPUExecutionProvider"])
inputs = session.get_inputs()
assert len(inputs) == 1 and inputs[0].type == "tensor(float)"
assert len(inputs[0].shape) == 4 and inputs[0].shape[1:3] == [1, 64]
scores = session.run(None, {inputs[0].name: np.ones((1, 1, 64, 192), dtype=np.float32)})[0]
assert scores.ndim == 3 and scores.shape[1] == 1 and scores.shape[-1] == len(alphabet)
assert np.isfinite(scores).all()
assert alphabet[0] == "" and {str(value) for value in range(10)}.issubset(alphabet)
print(f"Offline OCR model contract passed: input H=64; output classes={len(alphabet)}")
