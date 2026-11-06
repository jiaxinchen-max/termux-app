#!/usr/bin/env python3
"""Read-only ELF runtime/debug Build-ID and allocated-layout correspondence gate."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile

from runtime_binary import BinaryError, checked, cstring, snapshot, unpack


def build_id(path):
    # Read the standard GNU note directly. GNU readelf emits an unrelated
    # PT_INTERP diagnostic on valid --only-keep-debug files with NOBITS sections.
    data = path.read_bytes()
    if checked(data, 0, 6) != b'\x7fELF\x02\x01':
        raise BinaryError('unsupported ELF Build-ID input')
    shoff, = unpack('<Q', data, 40)
    shsize, count, names_index = unpack('<HHH', data, 58)
    if shsize != 64 or not count or not names_index < count:
        raise BinaryError('invalid ELF note section table')
    headers = [unpack('<IIQQQQIIQQ', data, shoff + i * shsize) for i in range(count)]
    names = headers[names_index]
    table = checked(data, names[4], names[5])
    ids = []
    for header in headers:
        if cstring(table, header[0]) != '.note.gnu.build-id' or header[1] != 7:
            continue
        note = checked(data, header[4], header[5])
        at = 0
        while at < len(note):
            namesz, descsz, kind = unpack('<III', note, at)
            name = checked(note, at + 12, namesz)
            descriptor = at + 12 + ((namesz + 3) & ~3)
            value = checked(note, descriptor, descsz)
            at = descriptor + ((descsz + 3) & ~3)
            if at > len(note):
                raise BinaryError('truncated ELF note padding')
            if name == b'GNU\0' and kind == 3:
                ids.append(value.hex())
    if len(ids) != 1 or not 8 <= len(ids[0]) <= 128:
        raise BinaryError('missing or ambiguous ELF Build-ID: ' + str(path))
    return ids[0].lower()


def verify_tree(runtime, debug_root, expected_machine=None):
    mappings = []
    for path in sorted(runtime.rglob('*')):
        if path.is_symlink() or not path.is_file():
            continue
        with path.open('rb') as stream:
            magic = stream.read(4)
        if magic != b'\x7fELF':
            continue
        image = snapshot(path)
        if expected_machine is not None and image['header'][1] != expected_machine:
            raise BinaryError('ELF runtime architecture mismatch: ' + str(path))
        if image['debug_raw_bytes']:
            raise BinaryError('ELF runtime still embeds DWARF: ' + str(path))
        identity = build_id(path)
        relative = Path('usr/lib/debug/.build-id') / identity[:2] / (identity[2:] + '.debug')
        debug = debug_root / relative
        if debug.is_symlink() or not debug.is_file():
            raise BinaryError('matching ELF debug file missing: ' + str(path))
        if build_id(debug) != identity:
            raise BinaryError('ELF debug Build-ID mismatch: ' + str(path))
        symbols = snapshot(debug)
        if symbols['format'] != 'ELF' or not symbols['debug_raw_bytes']:
            raise BinaryError('ELF debugging information is empty')
        fields = ('name', 'flags', 'address', 'size', 'alignment', 'entry_size')
        allocated = lambda item: [s for s in item['sections'] if s['flags'] & 2]
        live, separate = allocated(image), allocated(symbols)
        matches = len(live) == len(separate)
        for left, right in zip(live, separate):
            expected = {key: left[key] for key in fields}
            # only-keep-debug converts relocation payloads to NOBITS and
            # removes SHF_INFO_LINK, since their sh_info no longer identifies
            # a live relocation target. Other flags/layout must still match.
            if left['type'] in (4, 9) and right['type'] == 8:
                expected['flags'] &= ~0x40
            if expected != {key: right[key] for key in fields}:
                matches = False
        if image['header'] != symbols['header'] or not matches:
            raise BinaryError('ELF runtime/debug allocated layout differs: ' + str(path))
        mappings.append({'runtime_path': path.relative_to(runtime).as_posix(),
                         'runtime_sha256': image['sha256'], 'build_id': identity,
                         'debug_path': relative.as_posix(), 'debug_sha256': symbols['sha256'],
                         'debug_bytes': symbols['bytes']})
    if not mappings:
        raise BinaryError('no runtime ELF files')
    return {'schemaVersion': 1, 'state': 'ElfDebugMappedNotRuntimeTested', 'mappings': mappings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--debug-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--machine', choices=('aarch64', 'x86_64'), default='aarch64')
    args = parser.parse_args()
    try:
        report = verify_tree(args.runtime.resolve(strict=True), args.debug_root.resolve(strict=True),
                             {'aarch64': 183, 'x86_64': 62}[args.machine])
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', dir=args.output.parent,
                                             prefix='.elf-report-', delete=False) as stream:
                temporary = Path(stream.name)
                json.dump(report, stream, indent=2)
                stream.write('\n')
            os.link(temporary, args.output)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    except (BinaryError, OSError, ValueError) as error:
        print('FAILED:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
