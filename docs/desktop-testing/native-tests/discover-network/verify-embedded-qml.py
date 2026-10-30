#!/usr/bin/env python3
"""Verify the generated Qt resource bytes and final ELF contain the exact patched QML."""
import argparse
import ctypes
import ctypes.util
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("qrc_cpp", type=Path)
parser.add_argument("binary", type=Path)
parser.add_argument("expected_qml", type=Path)
args = parser.parse_args()
text = args.qrc_cpp.read_text().split("qt_resource_data[] = {", 1)[1].split("};", 1)[0]
decode = lambda value: bytes(int(x, 16) for x in re.findall(r"0x([0-9a-fA-F]+)", value))
all_data = decode(text)
if all_data not in args.binary.read_bytes():
    raise SystemExit("FAIL: final ELF does not contain generated resource array")
entry = text.split("// DiscoverWindow.qml", 1)[1].split("//", 1)[0]
data = decode(entry)
size = int.from_bytes(data[:4], "big")
compressed = data[4:]
if size != len(compressed):
    raise SystemExit("FAIL: resource entry size mismatch")
library = ctypes.util.find_library("zstd")
if not library:
    raise SystemExit("FAIL: libzstd required for explicit zstd resource decoding")
zstd = ctypes.CDLL(library)
zstd.ZSTD_getFrameContentSize.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
zstd.ZSTD_getFrameContentSize.restype = ctypes.c_ulonglong
zstd.ZSTD_decompress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t]
zstd.ZSTD_decompress.restype = ctypes.c_size_t
expected = args.expected_qml.read_bytes()
content_size = zstd.ZSTD_getFrameContentSize(compressed, len(compressed))
if content_size != len(expected):
    raise SystemExit("FAIL: QML uncompressed size mismatch")
output = ctypes.create_string_buffer(content_size)
actual_size = zstd.ZSTD_decompress(output, content_size, compressed, len(compressed))
if actual_size != len(expected) or output.raw != expected:
    raise SystemExit("FAIL: embedded QML differs from exact patched source")
print("PASS: generated Qt resource and final ELF embed exact patched DiscoverWindow.qml")
