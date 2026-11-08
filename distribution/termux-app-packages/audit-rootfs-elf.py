#!/usr/bin/env python3
"""Read-only direct ELF dependency audit of an idle private RootFS candidate.

This does not execute ELF files and does not prove dlopen or hardware features.
Every interpreted executable and DSO is checked, including private Wine modules.
"""
import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import stat
import struct

spec = importlib.util.spec_from_file_location('minimize', Path(__file__).with_name('minimize-rootfs.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# These namespaces are loaded by their owners before their private modules.
# Providers must still exist, be ABI-compatible and declare the exact SONAME.
# systemd callers list both private libraries in DT_NEEDED; Wine preloads its
# guarded ntdll/win32u Unix modules. This is not a global same-directory search.
OWNER_CONTEXTS = {
    'usr/lib/wine/aarch64-unix/': {
        'ntdll.so': 'usr/lib/wine/aarch64-unix/ntdll.so',
        'win32u.so': 'usr/lib/wine/aarch64-unix/win32u.so',
    },
    'usr/lib/aarch64-linux-gnu/systemd/': {
        'libsystemd-shared-257.so': 'usr/lib/aarch64-linux-gnu/systemd/libsystemd-shared-257.so',
    },
}


def elf(path):
    with path.open('rb') as stream:
        head = stream.read(64)
        if head[:4] != b'\x7fELF':
            return None
        m.require(len(head) == 64 and head[4] in (1, 2) and head[5] in (1, 2), 'elf_header')
        wide = head[4] == 2
        endian = '<' if head[5] == 1 else '>'
        header = struct.unpack_from(endian + ('HHIQQQIHHHHHH' if wide else 'HHIIIIIHHHHHH'), head, 16)
        kind, machine = header[:2]
        offset, stride, count = header[4], header[8], header[9]
        fmt = endian + ('IIQQQQQQ' if wide else 'IIIIIIII')
        m.require(stride >= struct.calcsize(fmt) or count == 0, 'elf_program_header_size')
        m.require(count < 65535 and offset + stride * count <= path.stat().st_size, 'elf_program_header_bounds')
        segments = []
        for index in range(count):
            stream.seek(offset + stride * index)
            p = struct.unpack(fmt, stream.read(struct.calcsize(fmt)))
            segments.append((p[0], p[2], p[3], p[5]) if wide else (p[0], p[1], p[2], p[4]))
        def read(offset, size):
            m.require(0 <= size <= 16 * 1024**2 and offset + size <= path.stat().st_size, 'elf_segment_bounds')
            stream.seek(offset)
            data = stream.read(size)
            m.require(len(data) == size, 'elf_segment_truncated')
            return data
        result = {'machine': machine, 'type': kind, 'class': 64 if wide else 32,
                  'endian': head[5], 'needed': [], 'rpath': [], 'runpath': []}
        tags = []
        for typ, pos, _, size in segments:
            if typ == 3:
                result['interpreter'] = read(pos, size).split(b'\0')[0].decode()
            if typ == 2:
                data = read(pos, size)
                step = 16 if wide else 8
                m.require(size % step == 0, 'elf_dynamic_size')
                for j in range(0, size, step):
                    tag, value = struct.unpack_from(endian + ('qQ' if wide else 'iI'), data, j)
                    if tag == 0:
                        break
                    tags.append((tag, value))
        if tags:
            values = dict(tags)
            address, length = values.get(5), values.get(10)
            m.require(address is not None and length is not None, 'elf_string_table_missing')
            offsets = [pos + address - virtual for typ, pos, virtual, size in segments
                       if typ == 1 and virtual <= address and address + length <= virtual + size]
            m.require(len(offsets) == 1, 'elf_string_table_mapping')
            strings = read(offsets[0], length)
            for tag, value in tags:
                if tag not in (1, 14, 15, 29):
                    continue
                m.require(value < len(strings) and b'\0' in strings[value:], 'elf_string_bounds')
                text = strings[value:strings.index(b'\0', value)].decode()
                if tag == 14:
                    result['soname'] = text
                    continue
                key = {1: 'needed', 15: 'rpath', 29: 'runpath'}[tag]
                result[key].extend([text] if tag == 1 else text.split(':'))
        return result


def scan(root, machine=183, contexts=None):
    contexts = OWNER_CONTEXTS if contexts is None else contexts
    standard = ['usr/local/lib/aarch64-linux-gnu', 'usr/local/lib', 'lib/aarch64-linux-gnu',
                'usr/lib/aarch64-linux-gnu', 'lib', 'usr/lib']
    records, problems, cache = [], [], {}
    def decode(path):
        # The candidate is idle and held under the sibling audit lock. Reuse
        # parsed metadata rather than rereading libc/LLVM for every inbound edge.
        if path not in cache:
            cache[path] = elf(path)
        return cache[path]
    for directory, _, files in os.walk(root, followlinks=False):
        for name in sorted(files):
            path = Path(directory) / name
            if not stat.S_ISREG(path.lstat().st_mode):
                continue
            record = decode(path)
            if record is None or record['type'] not in (2, 3):
                continue
            record = dict(record)
            relative = path.relative_to(root).as_posix()
            record['path'] = relative
            record['resolved'] = {}
            record['ownerContextResolutions'] = {}
            records.append(record)
            if record['machine'] != machine:
                problems.append({'path': relative, 'reason': 'unexpected_machine', 'machine': record['machine']})
            origin = '/' + path.parent.relative_to(root).as_posix()
            search = []
            for entry in record['runpath'] or record['rpath']:
                entry = entry.replace('${ORIGIN}', origin).replace('$ORIGIN', origin)
                entry = entry.replace('${LIB}', 'lib/aarch64-linux-gnu').replace('$LIB', 'lib/aarch64-linux-gnu')
                entry = entry.replace('${PLATFORM}', 'aarch64').replace('$PLATFORM', 'aarch64')
                if not entry.startswith('/') or '$' in entry:
                    problems.append({'path': relative, 'reason': 'unsupported_search_path', 'value': entry})
                else:
                    search.append(entry.lstrip('/'))
            for needed in record['needed'] + ([record['interpreter']] if 'interpreter' in record else []):
                if needed.startswith('/'):
                    options = [(needed.lstrip('/'), False)]
                elif '/' in needed:
                    options = []
                else:
                    options = [(p + '/' + needed, False) for p in search + standard]
                for prefix, providers in contexts.items():
                    if relative.startswith(prefix) and needed in providers:
                        options.append((providers[needed], True))
                for option, owner_context in options:
                    try:
                        resolved = m.guest_file(root, os.path.normpath('/' + option).lstrip('/'))
                        dependency = decode(resolved)
                        if (dependency is not None and dependency['machine'] == machine
                                and dependency['type'] == 3 and dependency['class'] == record['class']
                                and dependency['endian'] == record['endian']
                                and (not owner_context or dependency.get('soname') == needed)):
                            record['resolved'][needed] = resolved.relative_to(root).as_posix()
                            if owner_context:
                                record['ownerContextResolutions'][needed] = record['resolved'][needed]
                            break
                    except (OSError, ValueError):
                        continue
                else:
                    problems.append({'path': relative, 'reason': 'dependency_unresolved', 'needed': needed})
    return {'status': 'NoMissingELFDependencyInDeclaredContexts' if not problems else 'MissingOrUnsupportedDependency',
            'elfCount': len(records), 'records': records, 'problems': problems,
            'declaredOwnerContexts': contexts,
            'limitations': ['Direct ELF edges only; dlopen, driver selection and inherited RPATH require separate review.',
                            'Owner preloads are explicit contexts; systemd execution is not tested.',
                            'Fixed Debian aarch64 default search paths; unsupported paths fail closed.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.absolute()
    fd = os.open(str(root) + '.minimize-lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        marker = m.candidate_root(root)
        output = m.outside(root, args.output)
        m.require(not output.exists(), 'audit_output_exists')
        result = scan(root)
        result['identity'] = marker['identity']
        m.save(output, result)
    print(json.dumps({k: v for k, v in result.items() if k not in ('records', 'identity')}))
    return 0 if not result['problems'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
