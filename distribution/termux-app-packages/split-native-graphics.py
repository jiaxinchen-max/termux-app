#!/usr/bin/env python3
"""Split existing Native graphics bundles into one unchanged selectable 7z per package."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

VARIANTS = {
    'turnip': ['turnip-'+v for v in ('v2', 'v3.5', 'v4', 'v5', 'v6.5')],
    'dxvk': ['dxvk-'+v for v in ('0.96', '1.6.1', 'async-1.10.3', 'async-2.0', 'dev', 'gplasync-2.2', 'gplasync-2.3')] + ['vkd3d'],
}


def split(source, family, output):
    entries = []
    with tarfile.open(source) as original:
        for variant in VARIANTS[family]:
            name = 'glibc/opt/libs/' + ('mesa' if family == 'turnip' else 'd3d') + '/' + variant + '.7z'
            members = [m for m in original.getmembers() if m.name.removeprefix('./') == name]
            if len(members) != 1 or not members[0].isfile():
                raise ValueError('expected one regular graphics archive: ' + name)
            content = original.extractfile(members[0]).read()
            if not content.startswith(b'7z\xbc\xaf\x27\x1c'):
                raise ValueError('expected 7z payload: ' + name)
            identifier = 'native-' + variant
            target = output / (identifier + '.tar.xz')
            target.parent.mkdir(parents=True, exist_ok=True)
            with tarfile.open(target, 'w:xz', format=tarfile.USTAR_FORMAT) as archive:
                member = tarfile.TarInfo(name)
                member.size, member.mode, member.mtime = len(content), 0o644, 0
                archive.addfile(member, io.BytesIO(content))
            entries.append({'id': identifier, 'file': target.name, 'size': target.stat().st_size,
                            'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                            'payload': name, 'payloadSha256': hashlib.sha256(content).hexdigest()})
    return entries


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--turnip', type=Path, required=True)
    parser.add_argument('--dxvk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    entries = split(args.turnip, 'turnip', args.output) + split(args.dxvk, 'dxvk', args.output)
    (args.output / 'inventory.json').write_text(json.dumps(entries, indent=2)+'\n')
    print(json.dumps(entries, indent=2))
