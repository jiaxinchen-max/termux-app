#!/usr/bin/env python3
"""Bind one initialized x86_64/WoW64 skeleton to a pinned portable Wine tree.

Run Wine only at the reference-build step, never here or during device restore.
DLLs/NLS come from the selected immutable component, fonts from the shared image.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import socket
import struct
import tarfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def machine(data):
    if data[:2] != b'MZ' or len(data) < 64:
        return None
    offset = struct.unpack_from('<I', data, 60)[0]
    if offset + 6 <= len(data) and data[offset:offset + 4] == b'PE\0\0':
        return struct.unpack_from('<H', data, offset + 4)[0]
    return None


def system_font_registry():
    """Wine consumes system font linking from HKLM, including its 32-bit view."""
    fontlink = 'NotoSansCJK-Regular.ttc,Noto Sans CJK SC\0\0'.encode('utf-16le')
    value = 'hex(7):' + ','.join('%02x' % b for b in fontlink)
    sections = []
    for view in ('', 'Wow6432Node\\\\'):
        key = 'Software\\\\' + view + 'Microsoft\\\\Windows NT\\\\CurrentVersion\\\\'
        sections.append('\n[' + key + 'FontSubstitutes]\n')
        for name in ('MS Shell Dlg', 'MS Shell Dlg 2', 'Microsoft Sans Serif', 'SimSun',
                     'NSimSun', 'Microsoft YaHei'):
            sections.append('"' + name + '"="Noto Sans CJK SC"\n')
        sections.append('\n[' + key + 'FontLink\\\\SystemLink]\n')
        for name in ('Tahoma', 'Tahoma Bold', 'MS Shell Dlg', 'MS Shell Dlg 2',
                     'Microsoft Sans Serif', 'MS Sans Serif', 'Lucida Sans Unicode', 'Arial', 'Arial Black'):
            sections.append('"' + name + '"=' + value + '\n')
    return ''.join(sections).encode()


def normalize_menu_startup(text, selected):
    """Discard only a known host-menu hook whose program is absent from Wine."""
    sections = {r'Software\\Microsoft\\Windows\\CurrentVersion\\RunServices',
                r'Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\RunServices'}
    section, result, omitted = '', [], []
    for line in text.splitlines(keepends=True):
        value = line.strip()
        if value.startswith('[') and ']' in value:
            section = value[1:value.index(']')]
        if section in sections and value.startswith('"winemenubuilder"='):
            targets = {r'"winemenubuilder"="C:\\windows\\' + folder +
                       r'\\winemenubuilder.exe -a -r"': arch
                       for folder, arch in [('system32', 'x86_64-windows'),
                                            ('syswow64', 'i386-windows')]}
            assert value in targets, 'unrecognized winemenubuilder startup command'
            source = 'lib/wine/' + targets[value] + '/winemenubuilder.exe'
            if source not in selected:
                omitted.append(dict(section=section, value=value, missingComponentFile=source))
                continue
        result.append(line)
    return ''.join(result), omitted


def build(prefix, reference, wine, archive, component, version, expected_sha, rootfs, recipe, evidence, output):
    assert component in ('rootfs_wine_10.0', 'rootfs_proton_11.0')
    assert digest(archive.read_bytes()) == expected_sha, 'component archive digest mismatch'
    proof = json.loads(evidence.read_text())
    assert proof['exitCode'] == proof['serverExitCode'] == 0 and proof['user'] == 'root'
    assert proof['locale'] == 'zh_CN.UTF-8'
    assert '"LocaleName"="zh-CN"' in (prefix / 'user.reg').read_text(), 'reference locale mismatch'
    # Verify selected component bytes against the published archive, not a mutable build tree.
    archive_files = {}
    with tarfile.open(archive) as tar:
        for member in tar:
            if member.isfile():
                name = member.name.removeprefix('./')
                if name.startswith(wine.name + '/'):
                    relative = name[len(wine.name) + 1:]
                    archive_files[relative] = digest(tar.extractfile(member).read())
    reference_hashes = {}
    for file in sorted(reference.rglob('*')):
        if file.is_file() and file.relative_to(reference).parts[0] in ('lib', 'share'):
            reference_hashes.setdefault(digest(file.read_bytes()), []).append(file.relative_to(reference).as_posix())
    selected = {}
    for file in sorted(wine.rglob('*')):
        if file.is_file() and not file.is_symlink():
            relative = file.relative_to(wine).as_posix()
            data = file.read_bytes()
            assert archive_files.get(relative) == digest(data), relative
            selected[relative] = data
    entries, delta = {}, {}
    # Proton intentionally uses the Windows account "steamuser", irrespective of guest UID.
    # Reuse the initialized registry/directory skeleton while mapping that profile explicitly.
    windows_user = 'steamuser' if component == 'rootfs_proton_11.0' else 'root'

    def profile_path(name):
        start = 'drive_c/users/root'
        if name == start or name.startswith(start + '/'):
            return 'drive_c/users/' + windows_user + name[len(start):]
        return name

    def add(name, data, source=None, root=None):
        name = profile_path(name)
        assert not name.startswith('/') and '..' not in Path(name).parts
        record = dict(path=name, bytes=len(data), mode=0o600, mtime=0, sha256=digest(data))
        if source:
            record.update(source=source, sourceRoot=root)
        else:
            delta[name] = data
        entries[name] = record

    skipped, omitted_startup = [], []
    for file in sorted(prefix.rglob('*')):
        name = file.relative_to(prefix).as_posix()
        if file.is_symlink():
            assert name in ('dosdevices/c:', 'dosdevices/z:'), name
            continue
        if not file.is_file() or name.startswith('.games-runtime/') or name.endswith('.tmp'):
            continue
        data = file.read_bytes()
        candidates = reference_hashes.get(digest(data), [])
        # Ambiguous identical empty files prefer the same basename.
        # INF may rename an unambiguous builtin, e.g. comctl32_v6.dll ->
        # winsxs/.../comctl32.dll. Preserve that exact source identity.
        source = next((s for s in candidates if Path(s).name == file.name),
                      candidates[0] if len(candidates) == 1 else None)
        if source is not None:
            if source not in selected:
                skipped.append(name)
                continue
            add(name, selected[source], source, 'wine')
        else:
            pe = machine(data)
            # Wine's generated 1032-byte "Wine placeholder DLL" files are portable x86 stubs.
            assert pe is None or (pe in (0x14c, 0x8664) and len(data) == 1032 and
                                     b'Wine placeholder DLL' in data), name
            if name.endswith('.reg'):
                text = data.decode()
                assert 'pendingfilerenameoperations' not in text.lower(), 'unfinished reference prefix'
                text = text.replace(str(prefix), '/mnt/games/prefix').replace(socket.gethostname(), 'wine')
                if windows_user != 'root':
                    text = text.replace(r'users\\root', r'users\\steamuser')
                    text = text.replace('"USERNAME"="root"', '"USERNAME"="steamuser"')
                assert '/home/' not in text and '/data/' not in text, 'host path in registry'
                if name == 'system.reg':
                    text, omitted_startup = normalize_menu_startup(text, selected)
                if name == 'user.reg':
                    fontlink = 'NotoSansCJK-Regular.ttc,Noto Sans CJK SC\0\0'.encode('utf-16le')
                    text += ('\n[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\FontLink\\\\SystemLink]\n'
                             '"Tahoma"=hex(7):' + ','.join('%02x' % b for b in fontlink) + '\n')
                data = text.encode()
                if name == 'system.reg':
                    data += system_font_registry()
            if name == '.update-timestamp':
                data = b'disable\n'
            add(name, data)
    # Include all selected builtins, including DLLs newer than the reference skeleton.
    # Preserve subdirectory locations used by Wine's INF setup.
    for arch, target, expected in [('x86_64-windows', 'system32', 0x8664),
                                    ('i386-windows', 'syswow64', 0x14c)]:
        start = 'lib/wine/' + arch + '/'
        for source, data in selected.items():
            if source.startswith(start):
                pe = machine(data)
                assert pe is None or pe == expected, source
                relative = source[len(start):]
                add('drive_c/windows/' + target + '/' + relative, data, source, 'wine')
    for font in ('NotoSansCJK-Regular.ttc', 'NotoSansCJK-Bold.ttc'):
        relative = 'usr/share/fonts/opentype/noto/' + font
        add('drive_c/windows/Fonts/' + font, (rootfs / relative).read_bytes(), relative, 'rootfs')
    for name in ('system.reg', 'user.reg', 'userdef.reg', '.update-timestamp',
                 'drive_c/windows/system32/ntdll.dll', 'drive_c/windows/syswow64/ntdll.dll'):
        assert name in entries, name
    assert machine(selected['lib/wine/x86_64-windows/ntdll.dll']) == 0x8664
    assert machine(selected['lib/wine/i386-windows/ntdll.dll']) == 0x14c
    output.mkdir(parents=True, exist_ok=False)
    # Remove delta entries superseded by selected DLL/NLS files.
    delta = {name: data for name, data in delta.items() if 'source' not in entries[name]}
    links = {'dosdevices/c:': '../drive_c', 'dosdevices/z:': '/', 'dosdevices/com1': '/dev/ttyS0'}
    directories = {p.relative_to(prefix).as_posix() for p in prefix.rglob('*') if p.is_dir() and
                   not p.is_symlink() and '.games-runtime' not in p.relative_to(prefix).parts}
    directories.update({'drive_c/users/root', 'drive_c/users/root/Desktop',
                        'drive_c/users/public', 'drive_c/windows/temp'})
    directories = {profile_path(name) for name in directories}
    for name in set(entries) | set(links):
        directories.update(str(parent) for parent in Path(name).parents if str(parent) != '.')
    with (output / 'delta.tgz').open('wb') as raw:
        with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode='w') as tar:
                for name in sorted(directories):
                    member = tarfile.TarInfo(name); member.type = tarfile.DIRTYPE; member.mode = 0o700
                    tar.addfile(member)
                for name, data in sorted(delta.items()):
                    member = tarfile.TarInfo(name); member.size = len(data); member.mode = 0o600
                    tar.addfile(member, io.BytesIO(data))
                for name, target in links.items():
                    member = tarfile.TarInfo(name); member.type = tarfile.SYMTYPE; member.linkname = target
                    tar.addfile(member)
    records = sorted(entries.values(), key=lambda r: r['path'])
    (output / 'manifest.json').write_text(json.dumps(records, separators=(',', ':')) + '\n')
    base = b''.join((recipe / name).read_bytes() for name in ('setup-container.sh', 'wine-runtime.properties'))
    loader = 'bin/wine64' if component == 'rootfs_wine_10.0' else 'bin/wine'
    guards = [loader, 'bin/wineserver', 'lib/wine/x86_64-windows/ntdll.dll',
              'lib/wine/i386-windows/ntdll.dll']
    metadata = dict(schemaVersion=2, backend='rootfs_proot', hostAbi='aarch64', prefixArch='wow64',
                    peMachine=0x8664, translator='box64', winePackage=component,
                    windowsUser=windows_user,
                    componentVersion=version, sourceComponentSha256=expected_sha,
                    baseRecipeSha256=digest(base), initializationRevision='standard-wow64-zh-skeleton-2',
                    runtimeFiles={}, wineRuntimeFiles={name: digest(selected[name]) for name in guards},
                    expandedBytes=sum(r['bytes'] for r in records),
                    assets={name: digest((output / name).read_bytes()) for name in ('manifest.json', 'delta.tgz')},
                    provenance=dict(kind='x86_64-wow64-reference',
                                    referenceComponentSha256=proof['sourceComponentSha256'],
                                    referenceEvidenceSha256=digest(evidence.read_bytes()),
                                    referenceRegistries={name: digest((prefix / name).read_bytes())
                                                         for name in ('system.reg', 'user.reg', 'userdef.reg')},
                                    missingReferenceFiles=skipped, omittedStartupEntries=omitted_startup,
                                    targetAcceptance='pending'))
    (output / 'bundle.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('prefix', 'reference', 'wine', 'archive', 'rootfs', 'recipe', 'evidence', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--component', required=True)
    parser.add_argument('--version', type=int, required=True)
    parser.add_argument('--sha256', required=True)
    a = parser.parse_args()
    result = build(a.prefix, a.reference, a.wine, a.archive, a.component, a.version, a.sha256,
                   a.rootfs, a.recipe, a.evidence, a.output)
    print(json.dumps(dict(package=a.component, expandedBytes=result['expandedBytes'], assets=result['assets']), indent=2))


if __name__ == '__main__':
    main()
