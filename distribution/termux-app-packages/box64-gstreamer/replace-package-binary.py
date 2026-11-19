#!/usr/bin/env python3
"""Replace exactly one Box64 ELF; run packaging on the component build server."""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inventory(path):
    result = []
    with tarfile.open(str(path)) as archive:
        names = set()
        for member in archive.getmembers():
            if member.name in names:
                raise ValueError('duplicate archive member: ' + member.name)
            names.add(member.name)
            entry = {key: getattr(member, key) for key in
                     ['name', 'mode', 'uid', 'gid', 'size', 'mtime', 'linkname', 'uname', 'gname']}
            entry['type'] = member.type.decode('ascii')
            if member.isfile():
                entry['sha256'] = digest(archive.extractfile(member).read())
            result.append(entry)
    return result


def replace(original, binary, member_name, output):
    if output.exists() or output.resolve() == original.resolve():
        raise ValueError('output must be new; preserve the baseline')
    data = binary.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01' or int.from_bytes(data[18:20], 'little') != 183:
        raise ValueError('replacement must be an AArch64 ELF64')
    before = inventory(original)
    matches = [m for m in before if m['name'] == member_name and 'sha256' in m]
    if len(matches) != 1 or matches[0]['sha256'] == digest(data):
        raise ValueError('expected exactly one changed binary member')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(str(original)) as source, tarfile.open(str(output), 'w:xz', preset=6) as dest:
        for member in source.getmembers():
            entry = copy.copy(member)
            if member.name == member_name:
                entry.size = len(data)
                dest.addfile(entry, io.BytesIO(data))
            elif member.isfile():
                with source.extractfile(member) as stream:
                    dest.addfile(entry, stream)
            else:
                dest.addfile(entry)
    after = inventory(output)
    expected = copy.deepcopy(before)
    for item in expected:
        if item['name'] == member_name:
            item.update(size=len(data), sha256=digest(data))
    if after != expected:
        raise ValueError('unexpected archive content or metadata change')
    receipt = {
        'original': {'name': original.name, 'size': original.stat().st_size,
                     'sha256': digest(original.read_bytes())},
        'replacement': {'member': member_name, 'size': len(data), 'sha256': digest(data)},
        'output': {'name': output.name, 'size': output.stat().st_size,
                   'sha256': digest(output.read_bytes())},
        'onlyChangedMember': member_name,
        'otherContentAndMetadata': 'Unchanged',
        'members': after,
    }
    output.with_suffix(output.suffix + '.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--member', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = replace(args.original, args.binary, args.member, args.output)
    print(json.dumps({k: v for k, v in result.items() if k != 'members'}, indent=2))
