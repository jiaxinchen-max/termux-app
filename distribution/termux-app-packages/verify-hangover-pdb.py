#!/usr/bin/env python3
"""Verify source-built Wine PE images against separately linked PDB identities.

Read-only: never strips or rewrites an image. A report is published only after
all checks pass; this is a symbol gate, not a Wine runtime compatibility test.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import uuid

from runtime_binary import BinaryError, checked, cstring, pe_snapshot, unpack


def codeview(data):
    """Return RSDS GUID/age records using the PE debug directory's raw pointers."""
    pe, = unpack('<I', data, 60)
    count, = unpack('<H', data, pe + 6)
    optional_size, = unpack('<H', data, pe + 20)
    op = pe + 24
    magic, = unpack('<H', data, op)
    directory_offset = {0x10b: 96, 0x20b: 112}.get(magic)
    if directory_offset is None:
        raise BinaryError('unsupported PE optional header')
    directory_count, = unpack('<I', data, op + directory_offset - 4)
    if directory_count <= 6:
        return []
    rva, size = unpack('<II', data, op + directory_offset + 6 * 8)
    if not size:
        return []
    if size % 28:
        raise BinaryError('invalid debug directory size')
    sections = []
    for i in range(count):
        section = op + optional_size + i * 40
        virtual_size, section_rva, raw_size, raw_pointer = unpack('<IIII', data, section + 8)
        sections.append((section_rva, min(virtual_size, raw_size), raw_pointer))

    def map_rva(address, length):
        matches = [pointer + address - start for start, extent, pointer in sections
                   if start <= address and address + length <= start + extent]
        if len(matches) != 1:
            raise BinaryError('debug data outside unique runtime section')
        checked(data, matches[0], length)
        return matches[0]

    offset = map_rva(rva, size)
    records = []
    for at in range(offset, offset + size, 28):
        characteristics, timestamp, major, minor, kind, length, address, pointer = unpack(
            '<IIHHIIII', data, at)
        if kind != 2:
            continue
        if map_rva(address, length) != pointer:
            raise BinaryError('CodeView RVA and raw pointer disagree')
        record = checked(data, pointer, length)
        if not record.startswith(b'RSDS') or len(record) < 25:
            raise BinaryError('unsupported or truncated CodeView record')
        age, = unpack('<I', record, 20)
        name = record[24:].split(b'\0', 1)
        if len(name) != 2 or not name[0]:
            raise BinaryError('invalid CodeView PDB filename')
        records.append({'guid': str(uuid.UUID(bytes_le=record[4:20])), 'age': age,
                        'pdb_name': name[0].decode('utf-8')})
    return records


def pdb_identity(path, tool):
    result = subprocess.run([tool, 'dump', '--summary', str(path)],
                            capture_output=True, text=True)
    if result.returncode:
        raise BinaryError('PDB tool failed: ' + result.stderr)
    guid = re.findall(r'(?m)^\s*GUID:\s*\{?([0-9A-Fa-f-]{36})\}?\s*$', result.stdout)
    age = re.findall(r'(?m)^\s*Age:\s*(\d+)\s*$', result.stdout)
    if len(guid) != 1 or len(age) != 1:
        raise BinaryError('unrecognized PDB summary: ' + str(path))
    return str(uuid.UUID(guid[0])), int(age[0])


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def export_inventory(data):
    """Decode the physical PE export view, including ordinal-only tables.

    RVAs are bounded against unique sections; exported code addresses may move
    across source builds, so the comparison uses ordinals/names/forwarders.
    This does not apply ARM64X dynamic relocations to synthesize a hybrid view.
    """
    pe_snapshot(data)  # Validate headers/sections before following any RVAs.
    pe, = unpack('<I', data, 60)
    count, = unpack('<H', data, pe + 6)
    optional_size, = unpack('<H', data, pe + 20)
    op = pe + 24
    magic, = unpack('<H', data, op)
    directory = {0x10b: 96, 0x20b: 112}[magic]
    directory_count, = unpack('<I', data, op + directory - 4)
    if not directory_count:
        return {'module': None, 'base': 0, 'slots': [], 'named_count': 0}
    rva, size = unpack('<II', data, op + directory)
    if not rva and not size:
        return {'module': None, 'base': 0, 'slots': [], 'named_count': 0}
    if not rva or size < 40:
        raise BinaryError('invalid export directory')
    sections = [unpack('<IIII', data, op + optional_size + 40 * i + 8)
                for i in range(count)]

    def section(address, length, backed=True):
        matches = [(start, raw, pointer) for virtual, start, raw, pointer in sections
                   if start <= address and address + length <= start +
                   (raw if backed else max(virtual, raw))]
        if len(matches) != 1 or not length:
            raise BinaryError('export RVA outside unique section')
        start, raw, pointer = matches[0]
        return pointer + address - start, raw - (address - start)

    def read(address, length):
        offset, _ = section(address, length)
        return checked(data, offset, length)

    def name(address, maximum=None):
        offset, remaining = section(address, 1)
        if maximum is not None:
            remaining = min(remaining, maximum)
        value = cstring(checked(data, offset, remaining), 0)
        if not value:
            raise BinaryError('empty export name')
        return value

    header = unpack('<IIHHIIIIIII', read(rva, 40), 0)
    _, _, _, _, module, base, functions, names, eat, npt, ot = header
    if functions > 1000000 or names > 1000000 or base + functions > 0x100000000:
        raise BinaryError('invalid export table count')
    # Require the full declared directory to be backed, including forwarders.
    read(rva, size)
    addresses = unpack('<' + 'I' * functions, read(eat, functions * 4), 0) if functions else ()
    slots = [{'ordinal': base + i, 'names': [],
              'kind': 'hole' if not address else 'code', 'forwarder': None}
             for i, address in enumerate(addresses)]
    for item, address in zip(slots, addresses):
        if not address:
            continue
        if rva <= address < rva + size:
            item['kind'] = 'forwarder'
            item['forwarder'] = name(address, rva + size - address)
            if '.' not in item['forwarder']:
                raise BinaryError('invalid export forwarder')
        else:
            section(address, 1, backed=False)
    if names:
        name_rvas = unpack('<' + 'I' * names, read(npt, names * 4), 0)
        ordinals = unpack('<' + 'H' * names, read(ot, names * 2), 0)
        previous = None
        for address, ordinal in zip(name_rvas, ordinals):
            value = name(address)
            if ordinal >= functions or slots[ordinal]['kind'] == 'hole':
                raise BinaryError('export name references invalid ordinal')
            if previous is not None and value <= previous:
                raise BinaryError('export names unsorted or duplicated')
            previous = value
            slots[ordinal]['names'].append(value)
    return {'module': name(module), 'base': base, 'slots': slots, 'named_count': names}


def loader_metadata(path, tool):
    exports = export_inventory(path.read_bytes())
    options = ['--file-headers', '--coff-load-config', '--coff-imports', '--coff-basereloc']
    # LLVM 22/23 erroneously resolves RVA=0 name-ordinal tables when a valid
    # export directory has NumberOfNames=0. The physical view is fully decoded
    # above. Hybrid export decoding is explicitly not claimed for that case.
    if exports['named_count']:
        options.append('--coff-exports')
    result = subprocess.run([tool, *options, str(path)],
                            capture_output=True, text=True)
    if result.returncode or result.stderr.strip():
        raise BinaryError('PE loader metadata tool failed: ' + result.stderr)
    formats = re.findall(r'(?m)^Format: (COFF-[A-Za-z0-9_-]+)\s*$', result.stdout)
    if len(formats) != 1:
        raise BinaryError('unrecognized PE loader format')
    family = formats[0]
    # ARM64X images can have raw Machine=ARM64. LLVM synthesizes their format
    # by decoding dynamic relocations and exposes the relocated ARM64EC view.
    # A raw 0xa64e constant alone is not evidence of a valid hybrid image.
    if family == 'COFF-ARM64X' and not (
            'HybridObject {' in result.stdout and 'Format: COFF-ARM64EC' in result.stdout):
        raise BinaryError('ARM64X image lacks decoded ARM64EC hybrid view')
    normalized = '\n'.join(line for line in result.stdout.splitlines()
                           if not line.lstrip().startswith('File: '))
    return family, hashlib.sha256(normalized.encode()).hexdigest()


def verify_tree(runtime, pdb_root, tool, readobj='llvm-readobj'):
    symbols = {}
    for pdb in sorted(pdb_root.rglob('*.pdb')):
        if pdb.is_symlink() or not pdb.is_file():
            raise BinaryError('nonregular PDB input')
        identity = pdb_identity(pdb, tool)
        info = {'path': pdb.relative_to(pdb_root).as_posix(), 'sha256': sha(pdb),
                'bytes': pdb.stat().st_size}
        if identity in symbols and symbols[identity]['sha256'] != info['sha256']:
            raise BinaryError('conflicting PDB contents for the same GUID/age')
        symbols[identity] = info
    if not symbols:
        raise BinaryError('no PDB files')
    images = []
    for path in sorted(runtime.rglob('*')):
        if path.is_symlink() or not path.is_file():
            continue
        with path.open('rb') as stream:
            magic = stream.read(2)
        if magic != b'MZ':
            continue
        data = path.read_bytes()
        image = pe_snapshot(data)
        family, metadata_hash = loader_metadata(path, readobj)
        if image['debug_raw_bytes']:
            raise BinaryError('embedded DWARF remains: ' + str(path))
        if not image['wine_builtin']:
            raise BinaryError('Wine builtin marker missing: ' + str(path))
        records = codeview(data)
        executable = any(s['attributes'] & 0x20000000 and s['virtual_size']
                         for s in image['sections'])
        if executable and not records:
            raise BinaryError('executable PE has no PDB identity: ' + str(path))
        matches = []
        for record in records:
            identity = record['guid'], record['age']
            if identity not in symbols:
                raise BinaryError('matching PDB missing: ' + str(path))
            matches.append(dict(record, symbol=symbols[identity]))
        exports = export_inventory(data)
        images.append({'path': path.relative_to(runtime).as_posix(),
                       'image': image, 'symbols': matches, 'loader_format': family,
                       'loader_metadata_sha256': metadata_hash,
                       'exports': exports,
                       'export_kind': ('named' if exports['named_count'] else
                                       'ordinal-only' if exports['slots'] else 'none'),
                       'export_coverage': ('physical-and-llvm-exports' if exports['named_count']
                                           else 'physical-only-no-named-exports')})
    if not images:
        raise BinaryError('no runtime PE files')
    machines = {i['image']['machine'] for i in images}
    families = {i['loader_format'] for i in images}
    if not (0x14c in machines and 'COFF-ARM64X' in families):
        raise BinaryError('required i386/ARM64X architecture missing')
    return {'schemaVersion': 1, 'state': 'SymbolsVerifiedNotRuntimeTested',
            'images': images, 'pdb_count': len(symbols),
            'runtime_pe_bytes': sum(i['image']['bytes'] for i in images)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--pdb-root', type=Path, required=True)
    parser.add_argument('--pdbutil', default='llvm-pdbutil')
    parser.add_argument('--readobj', default='llvm-readobj')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify_tree(args.runtime.resolve(strict=True),
                             args.pdb_root.resolve(strict=True), args.pdbutil, args.readobj)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', dir=args.output.parent,
                                             prefix='.pdb-report-', delete=False) as stream:
                temporary = Path(stream.name)
                json.dump(report, stream, indent=2)
                stream.write('\n')
            os.link(temporary, args.output)  # atomic, fails if the output exists
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    except (BinaryError, OSError, ValueError, UnicodeError) as error:
        print('FAILED:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
