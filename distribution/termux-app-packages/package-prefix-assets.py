#!/usr/bin/env python3
"""Package one already generated prefix resource; never execute Wine or publish it."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def package(source, output):
    names = ['bundle.json', 'manifest.json', 'delta.tgz']
    data = {}
    for name in names:
        p = source / name
        if p.is_symlink() or not p.is_file():
            raise ValueError('invalid prefix input: ' + name)
        data[name] = p.read_bytes()
    bundle = json.loads(data['bundle.json'])
    for name in names[1:]:
        if sha(data[name]) != bundle['assets'][name]:
            raise ValueError('prefix input digest mismatch: ' + name)
    # Atomic publication; caller must provide a new output filename.
    temporary = output.with_name(output.name + '.pending')
    if output.exists() or temporary.exists():
        raise ValueError('output already exists')
    try:
        with temporary.open('xb') as raw:
            with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as g:
                with tarfile.open(fileobj=g, mode='w') as t:
                    for name in sorted(names):
                        m = tarfile.TarInfo(name)
                        m.mode, m.size, m.mtime = 0o600, len(data[name]), 0
                        t.addfile(m, io.BytesIO(data[name]))
        with tarfile.open(temporary) as t:
            if sorted(t.getnames()) != sorted(names):
                raise ValueError('unexpected archive entries')
            for m in t:
                if not m.isfile() or t.extractfile(m).read() != data[m.name]:
                    raise ValueError('archive roundtrip mismatch')
        temporary.rename(output)
    finally:
        temporary.unlink(missing_ok=True)
    return dict(file=output.name, bytes=output.stat().st_size,
                sha256=sha(output.read_bytes()))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    print(json.dumps(package(args.source, args.output), indent=2))
