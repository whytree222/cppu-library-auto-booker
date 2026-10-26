"""Prepare pinned public ddddocr assets. Never imports upstream Python code.

Run once before an Android build; the shipped APK performs inference offline.
User authentication images/credentials are not inputs to this script.
"""
import ast
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "c40f56f95412e10bcb9bd0bd24411e92f896d238"
BASE = f"https://raw.githubusercontent.com/sml2h3/ddddocr/{REVISION}/ddddocr/"
MODEL_GIT_SHA = "8ce4807e1e68c3fa5c1344d281cc7d1623a020cc"
CHARSETS_GIT_SHA = "4c93ed1c513c76b28253b8de2772fc4d82a471bb"
MODEL_BYTES = 13_606_051
CHARSETS_BYTES = 130_968
MODEL_SHA256 = "b8f2ad9cbc1f2e3922a6cb9459e30824e7e2467f3fb4fd61420640e34ea0bf68"
CHARSETS_SHA256 = "a3796391808f5bce011d659be1d822c2733dcb73da3e09d5aa39ef31e499cee3"


def blob_sha(data):
    return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()


def fetch_verified(name, length, expected, expected_sha256):
    cache = ROOT / ".ocr-cache" / name
    if cache.is_file():
        data = cache.read_bytes()
        if len(data) == length and blob_sha(data) == expected and hashlib.sha256(data).hexdigest() == expected_sha256:
            return data
    request = urllib.request.Request(BASE + name, headers={"User-Agent": "cppu-library-ocr-build"})
    with urllib.request.urlopen(request, timeout=45) as response:
        data = response.read(length + 1)
    if len(data) != length or blob_sha(data) != expected or hashlib.sha256(data).hexdigest() != expected_sha256:
        raise RuntimeError(f"Upstream asset integrity mismatch: {name}")
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(data)
    return data


def main():
    model = fetch_verified("common_old.onnx", MODEL_BYTES, MODEL_GIT_SHA, MODEL_SHA256)
    source = fetch_verified("charsets.py", CHARSETS_BYTES, CHARSETS_GIT_SHA, CHARSETS_SHA256)
    tree = ast.parse(source.decode("utf-8"))
    alphabet = None
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and
                target.id == "CHARSET_OLD" for target in node.targets):
            alphabet = ast.literal_eval(node.value)
            break
    if not isinstance(alphabet, list) or not 11 <= len(alphabet) <= 20_000 or alphabet[0] != "":
        raise RuntimeError("Old alphabet missing or invalid")
    if any(not isinstance(char, str) or not 1 <= len(char) <= 16 for char in alphabet[1:]):
        raise RuntimeError("Old alphabet has invalid entries")
    target = ROOT / "app" / "src" / "main" / "assets" / "ocr"
    target.mkdir(parents=True, exist_ok=True)
    (target / "common_old.onnx").write_bytes(model)
    (target / "charsets.json").write_text(json.dumps({"charset": alphabet}, ensure_ascii=False), encoding="utf-8")
    print(f"Pinned ddddocr assets prepared: {len(model)} bytes, {len(alphabet)} classes")
    print("Model SHA256:", hashlib.sha256(model).hexdigest())


if __name__ == "__main__":
    main()
