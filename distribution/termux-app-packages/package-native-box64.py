#!/usr/bin/env python3
"""Combine the Native 0.4.4 binary with existing Native x86-64 support libraries.

Build-time ELF checks and hashes are evidence only; no App runtime gate is added.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile


LIBRARIES = (
    'libcrypto.so.1.1', 'libgcc_s.so.1', 'libpng12.so.0', 'libssl.so.1.1',
    'libstdc++.so.5', 'libstdc++.so.6', 'libunwind.so.8',
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_elf(archive, name, machine):
    matches = [m for m in archive.getmembers() if m.name.removeprefix('./') == name]
    if len(matches) != 1 or not matches[0].isfile():
        raise ValueError('expected one regular file: ' + name)
    data = archive.extractfile(matches[0]).read()
    if (data[:6] != b'\x7fELF\x02\x01' or len(data) < 64
            or int.from_bytes(data[18:20], 'little') != machine):
        raise ValueError('unexpected ELF architecture: ' + name)
    return data


def package(binary_archive, libraries_archive, output):
    with tarfile.open(binary_archive) as binary, tarfile.open(libraries_archive) as libraries:
        payload = {'glibc/bin/box64': read_elf(binary, 'glibc/bin/box64', 183)}
        for name in LIBRARIES:
            path = 'glibc/lib/x86_64-linux-gnu/' + name
            payload[path] = read_elf(libraries, path, 62)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:xz', format=tarfile.USTAR_FORMAT, preset=6) as archive:
        for name, data in sorted(payload.items()):
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            entry.mode = 0o755
            entry.mtime = 0
            archive.addfile(entry, io.BytesIO(data))
    evidence = {
        'sources': {p.name: sha(p.read_bytes()) for p in (binary_archive, libraries_archive)},
        'files': {name: {'size': len(data), 'sha256': sha(data),
                         'machine': 'AArch64' if name.endswith('/box64') else 'X86-64'}
                  for name, data in sorted(payload.items())},
        'output': {'name': output.name, 'size': output.stat().st_size,
                   'sha256': sha(output.read_bytes())},
    }
    output.with_suffix(output.suffix + '.json').write_text(json.dumps(evidence, indent=2) + '\n')
    return evidence


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary-archive', type=Path, required=True)
    parser.add_argument('--libraries-archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.binary_archive, args.libraries_archive, args.output), indent=2))
