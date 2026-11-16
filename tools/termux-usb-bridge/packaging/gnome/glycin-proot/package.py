#!/usr/bin/env python3
"""Package the two verified build outputs in deterministic USTAR/gzip format."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile

LIBRARY = 'usr/local/lib/termux-gnome/libglycin-2.so.0'
MANIFEST = 'glycin-build-manifest.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    library = (args.directory / LIBRARY).read_bytes()
    manifest = (args.directory / MANIFEST).read_bytes()
    if json.loads(manifest)['outputSha256'] != {LIBRARY: hashlib.sha256(library).hexdigest()}:
        parser.error('Library does not match the build manifest')
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, data in ((LIBRARY, library), (MANIFEST, manifest)):
            info = tarfile.TarInfo(name)
            info.mode, info.size, info.mtime = 0o644, len(data), 0
            archive.addfile(info, io.BytesIO(data))
    result = gzip.compress(raw.getvalue(), mtime=0)
    with args.output.open('xb') as output:
        output.write(result)
    print(hashlib.sha256(result).hexdigest(), args.output)


if __name__ == '__main__':
    main()
