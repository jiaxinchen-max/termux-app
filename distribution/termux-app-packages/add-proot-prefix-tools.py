#!/usr/bin/env python3
"""Add portable C: tools/Start Menu links to a PRoot prebuilt prefix, offline.

Uses only the standard library. Never executes Wine, changes runtime DLLs,
downloads resources, updates component versions, or publishes artifacts.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import struct
import tarfile


MENU_ROOT = 'drive_c/ProgramData/Microsoft/Windows/Start Menu/Apps/'
PORTABLE = ('pgup-f6-pgdn-f7.exe', 'tfm.exe', 'f5taskmgr.exe', 'servman.exe', 'paint.exe')
TT = 'TrueTransparency/'
LINKS = {'dosdevices/c:': '../drive_c', 'dosdevices/z:': '/', 'dosdevices/com1': '/dev/ttyS0'}
PACKAGES = {'rootfs_hangover_11.16': 1, 'rootfs_wine_10.0': 2, 'rootfs_proton_11.0': 2}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_name(name):
    require(isinstance(name, str) and name and not name.startswith('/') and
            '\\' not in name and '\x00' not in name and
            all(p not in ('', '.', '..') for p in name.split('/')), 'invalid_path')
    return name


def pe_machine(data):
    require(len(data) >= 64 and data[:2] == b'MZ', 'invalid_pe')
    offset = struct.unpack_from('<I', data, 60)[0]
    require(offset + 6 <= len(data) and data[offset:offset + 4] == b'PE\0\0', 'invalid_pe')
    return struct.unpack_from('<H', data, offset + 4)[0]


def menu_entries():
    # All targets are local to this prefix; working directories travel with it.
    builtins = [('Command Prompt', r'C:\windows\system32\cmd.exe', ''),
                ('Notepad', r'C:\windows\notepad.exe', ''),
                ('Registry Editor', r'C:\windows\regedit.exe', ''),
                ('Task Manager', r'C:\windows\system32\taskmgr.exe', ''),
                ('Wine Configuration', r'C:\windows\system32\winecfg.exe', ''),
                ('Control Panel', r'C:\windows\system32\control.exe', ''),
                ('Controllers', r'C:\windows\system32\control.exe', 'joy.cpl')]
    tools = [('TrueTransparency', r'C:\Tools\TrueTransparency\TrueTransparency.exe', '')]
    tools += [(Path(name).stem, 'C:\\Tools\\' + name, '') for name in PORTABLE]
    return [dict(name=name, target=target, arguments=args,
                 workingDirectory=str(PureWindowsPath(target).parent))
            for name, target, args in builtins + tools]


def shortcut(entry):
    """MS-SHLLINK: local LinkInfo + Unicode StringData, no host PIDL/paths."""
    target = entry['target'].encode('ascii') + b'\0'
    # VolumeID for the prefix's fixed C: drive (empty volume label).
    volume = struct.pack('<IIII', 17, 3, 0, 16) + b'\0'
    suffix_offset = 28 + len(volume) + len(target)
    info = struct.pack('<7I', suffix_offset + 1, 28, 1, 28, 28 + len(volume), 0,
                       suffix_offset) + volume + target + b'\0'
    flags = 0x02 | 0x04 | 0x10 | 0x80  # LinkInfo, Name, WorkingDir, Unicode
    if entry['arguments']:
        flags |= 0x20
    clsid = bytes.fromhex('0114020000000000c000000000000046')
    header = struct.pack('<I16sIIQQQIIIHHII', 76, clsid, flags, 0x20,
                         0, 0, 0, 0, 0, 1, 0, 0, 0, 0)

    def string(value):
        encoded = value.encode('utf-16le')
        return struct.pack('<H', len(encoded) // 2) + encoded

    strings = string(entry['name']) + string(entry['workingDirectory'])
    if entry['arguments']:
        strings += string(entry['arguments'])
    return header + info + strings + b'\0' * 4  # TerminalBlock


def portable_files(scripts_archive):
    files = {}
    with tarfile.open(scripts_archive) as archive:
        for member in archive:
            name = safe_name(member.name.removeprefix('./').rstrip('/'))
            if not name.startswith('glibc/opt/apps/'):
                continue
            relative = name[len('glibc/opt/apps/'):]
            if relative not in PORTABLE and not relative.startswith(TT):
                continue
            if member.isdir():
                continue
            require(member.isfile() and relative not in files, 'invalid_tool_member')
            data = archive.extractfile(member).read()
            if relative.lower().endswith(('.exe', '.dll')):
                require(pe_machine(data) == 0x14c, 'tool_architecture_mismatch')
            files[relative] = data
    required = set(PORTABLE) | {TT + n for n in (
        'TrueTransparency.exe', 'TrueTransparencyHook.dll', 'config.ini', 'license.txt',
        'lang/English.lng', 'skin/Aero/Normal/skin.ini', 'skin/Aero/Maximized/skin.ini')}
    require(required <= files.keys(), 'tool_resources_missing')
    return {'drive_c/Tools/' + name: data for name, data in files.items()}


def build(source, scripts_archive, output):
    require(source.resolve() != output.resolve() and not output.exists(), 'output_exists')
    metadata = json.loads((source / 'bundle.json').read_text())
    require(metadata.get('backend') == 'rootfs_proot' and metadata.get('hostAbi') == 'aarch64' and
            metadata.get('prefixArch') == 'wow64' and
            metadata.get('winePackage') in PACKAGES and
            metadata.get('schemaVersion') == PACKAGES[metadata['winePackage']], 'unsupported_bundle')
    records = {}
    for record in json.loads((source / 'manifest.json').read_text()):
        name = safe_name(record['path'])
        require(name not in records, 'duplicate_record')
        records[name] = record
    members, payload, links = {}, {}, {}
    with tarfile.open(source / 'delta.tgz') as archive:
        for member in archive:
            name = safe_name(member.name.rstrip('/') if member.isdir() else member.name)
            require(name not in members, 'duplicate_member')
            require(member.isfile() or member.isdir() or member.issym(), 'invalid_member')
            members[name] = member
            if member.isfile():
                require(name in records and 'source' not in records[name], 'unexpected_delta_file')
                payload[name] = archive.extractfile(member).read()
            elif member.issym():
                links[name] = member.linkname
    require(links == LINKS and set(payload) == {n for n, r in records.items() if 'source' not in r},
            'incomplete_delta')
    extra = portable_files(scripts_archive)
    entries = menu_entries()
    require('drive_c/windows/system32/joy.cpl' in records, 'controller_panel_missing')
    for entry in entries:
        target = 'drive_c/' + entry['target'][3:].replace('\\', '/')
        require(target in records or target in extra, 'menu_target_missing:' + target)
        extra[MENU_ROOT + entry['name'] + '.lnk'] = shortcut(entry)
    require(not (set(extra) & (set(records) | set(members))), 'overlay_collision')
    # Original records and bytes (including registries/system DLL references) are immutable.
    for name, data in extra.items():
        records[name] = dict(path=name, bytes=len(data), mode=0o600, mtime=0, sha256=sha(data))
        payload[name] = data
        member = tarfile.TarInfo(name)
        member.mode, member.size = 0o600, len(data)
        members[name] = member
        for parent in PurePosixPath(name).parents:
            if str(parent) == '.':
                continue
            if str(parent) in members:
                require(members[str(parent)].isdir(), 'parent_collision')
            else:
                require(str(parent) not in records, 'parent_collision')
                directory = tarfile.TarInfo(str(parent))
                directory.type, directory.mode = tarfile.DIRTYPE, 0o700
                members[str(parent)] = directory
    output.mkdir(parents=True)
    with (output / 'delta.tgz').open('wb') as raw:
        with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode='w') as archive:
                for name, member in sorted(members.items()):
                    archive.addfile(copy.copy(member), io.BytesIO(payload[name]) if member.isfile() else None)
    (output / 'manifest.json').write_text(json.dumps(sorted(records.values(), key=lambda r: r['path']),
                                                    separators=(',', ':')) + '\n')
    metadata['expandedBytes'] = sum(r['bytes'] for r in records.values())
    metadata['assets'] = {n: sha((output / n).read_bytes()) for n in ('manifest.json', 'delta.tgz')}
    metadata.setdefault('provenance', {})['portableTools'] = dict(
        scriptsArchiveSha256=sha(scripts_archive.read_bytes()), menu=entries,
        embeddedFiles=len(extra), addedBytes=sum(len(d) for d in extra.values()),
        acceptance='PendingAppUI')
    (output / 'bundle.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'scripts-archive', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    result = build(args.source, args.scripts_archive, args.output)
    print(json.dumps(result['provenance']['portableTools'], indent=2))
