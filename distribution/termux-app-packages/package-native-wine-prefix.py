#!/usr/bin/env python3
"""Bind a verified PRoot WoW64 skeleton to exact Native component archive bytes.

No Wine execution, network access, guest Unix libraries or mutable game inputs.
"""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import re
from pathlib import Path, PurePosixPath
import tarfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def archive_files(path, expected, prefix):
    with path.open('rb') as stream:
        require(hashlib.file_digest(stream, 'sha256').hexdigest() == expected, 'archive_digest_mismatch')
    result = {}
    with tarfile.open(path) as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            require(not name.startswith('/') and '..' not in PurePosixPath(name).parts, 'archive_path_invalid')
            if member.isfile() and name.startswith(prefix) and '/._' not in name:
                relative = name[len(prefix):]
                require(relative not in result, 'archive_duplicate')
                result[relative] = archive.extractfile(member).read()
    return result


def registry_overlay(data, hive):
    """Translate only the fixed, additive REGEDIT5 defaults into Wine hive syntax."""
    text = data.decode('utf-16') if data.startswith((b'\xff\xfe', b'\xfe\xff')) else data.decode('utf-8')
    require(text.splitlines()[0] == 'Windows Registry Editor Version 5.00', 'overlay_registry_header')
    prefix = {'system.reg': 'HKEY_LOCAL_MACHINE\\', 'user.reg': 'HKEY_CURRENT_USER\\'}[hive]
    result, in_section = [], False
    for line in text.splitlines()[1:]:
        value = line.strip()
        if not value: continue
        if value.startswith('['):
            require(value.startswith('[' + prefix) and value.endswith(']'), 'overlay_registry_scope')
            key = value[len(prefix) + 1:-1]
            result.append('\n[' + key.replace('\\', '\\\\') + ']\n')
            in_section = True
        else:
            require(in_section and (re.fullmatch(r'(?:"(?:[^"\\]|\\.)*"|@)=(?:".*"|dword:[0-9a-fA-F]{8}|hex(?:\([0-9a-fA-F]+\))?:[0-9a-fA-F,\\]*)', value)
                                    or re.fullmatch(r'[0-9a-fA-F,]+\\?', value)), 'overlay_registry_syntax')
            result.append(value + '\n')
    return ''.join(result)


def build(skeleton, wine_archive, descriptor, fonts_archive, fonts_descriptor, output,
          scripts_archive, scripts_descriptor, apps_archive, apps_descriptor):
    standard = module('package-standard-wine-prefix')
    package = descriptor['id']
    require(package in ('glibc_wine_8.18-staging', 'glibc_wine_9.0-staging',
                       'glibc_wine_9.3-vanilla', 'glibc_wine_10.0', 'glibc_proton_11.0'), 'unsupported_native_wine')
    base = json.loads((skeleton / 'bundle.json').read_text())
    require(base['schemaVersion'] == 2 and base['backend'] == 'rootfs_proot' and
            base['translator'] == 'box64' and base['prefixArch'] == 'wow64', 'skeleton_invalid')
    require(base['winePackage'] == ('rootfs_proton_11.0' if package.startswith('glibc_proton_')
                                  else 'rootfs_wine_10.0'), 'skeleton_runtime_mismatch')
    for name in ('delta.tgz', 'manifest.json'):
        require(digest((skeleton / name).read_bytes()) == base['assets'][name], 'skeleton_digest_mismatch')
    selected = archive_files(wine_archive, descriptor['sha256'], 'glibc/' + package + '/')
    fonts = archive_files(fonts_archive, fonts_descriptor['sha256'], 'glibc/')
    scripts = archive_files(scripts_archive, scripts_descriptor['sha256'], 'glibc/')
    apps = archive_files(apps_archive, apps_descriptor['sha256'], 'glibc/')
    require(standard.machine(selected['lib/wine/x86_64-windows/ntdll.dll']) == 0x8664 and
            standard.machine(selected['lib/wine/i386-windows/ntdll.dll']) == 0x14c, 'native_pe_machine_mismatch')
    delta, directories, links = {}, set(), {}
    with tarfile.open(skeleton / 'delta.tgz') as archive:
        for member in archive:
            require(not member.name.startswith('/') and '..' not in PurePosixPath(member.name).parts,
                    'skeleton_path_invalid')
            if member.isfile():
                require(member.name not in delta, 'skeleton_duplicate')
                delta[member.name] = archive.extractfile(member).read()
            elif member.isdir():
                directories.add(member.name.rstrip('/'))
            elif member.issym():
                links[member.name] = member.linkname
            else:
                raise ValueError('skeleton_member_invalid')
    require(links == {'dosdevices/c:': '../drive_c', 'dosdevices/z:': '/',
                      'dosdevices/com1': '/dev/ttyS0'}, 'skeleton_links_invalid')
    records = {}

    def add(name, data, source=None, source_root=None):
        record = dict(path=name, bytes=len(data), mode=0o600, mtime=0, sha256=digest(data))
        if source:
            record.update(source=source, sourceRoot=source_root)
        else:
            delta[name] = data
        records[name] = record

    omitted = []
    for record in json.loads((skeleton / 'manifest.json').read_text()):
        name = record['path']
        if 'source' not in record:
            require(name in delta and digest(delta[name]) == record['sha256'], 'skeleton_file_mismatch')
            data = delta[name]
            if name.endswith('.reg'):
                # No PRoot Unix paths may leak into the Native registry.
                text = data.decode().replace('/mnt/games/prefix', '@NATIVE_PREFIX@')
                require('/mnt/games/' not in text and '/home/' not in text and
                        'pendingfilerenameoperations' not in text.lower(), 'skeleton_registry_invalid')
                if name == 'system.reg':
                    text, omitted = standard.normalize_menu_startup(text, selected)
                    for overlay in ('system.reg', 'fix-services.reg'):
                        text += registry_overlay(scripts['opt/prefix/' + overlay], 'system.reg')
                    for view in ('', 'Wow6432Node\\\\'):
                        key = 'Software\\\\' + view + 'Microsoft\\\\Windows NT\\\\CurrentVersion\\\\'
                        text += '\n[' + key + 'Fonts]\n"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"\n'
                        text += '\n[' + key + 'FontSubstitutes]\n"Tahoma"="Noto Sans CJK SC"\n'
                if name == 'user.reg':
                    text += registry_overlay(scripts['opt/prefix/user.reg'], 'user.reg')
                data = text.encode()
            add(name, data)
        elif record['sourceRoot'] == 'wine':
            source = record['source']
            if source in selected:
                add(name, selected[source], source, 'wine')
        else:
            require(record['sourceRoot'] == 'rootfs' and name.startswith('drive_c/windows/Fonts/'),
                    'skeleton_source_invalid')
    for arch, target, machine in [('x86_64-windows', 'system32', 0x8664),
                                  ('i386-windows', 'syswow64', 0x14c)]:
        prefix = 'lib/wine/' + arch + '/'
        for source, data in selected.items():
            if source.startswith(prefix):
                require(standard.machine(data) in (None, machine), 'native_pe_machine_mismatch:' + source)
                add('drive_c/windows/' + target + '/' + source[len(prefix):], data, source, 'wine')
    for font in ('NotoSansCJK-Regular.ttc', 'NotoSerifCJK-Regular.ttc'):
        source = 'share/fonts/' + font
        add('drive_c/windows/Fonts/' + font, fonts[source], source, 'rootfs')
    # Preserve the existing Native base/DirectX overlays and convenience apps,
    # but bind every source byte instead of performing unverified device extraction.
    for tree in (scripts, apps):
        for source, data in tree.items():
            for start in ('opt/prefix-expanded/drive_c/', 'opt/prefix-expanded/directx/'):
                if source.startswith(start):
                    add('drive_c/' + source[len(start):], data, source, 'rootfs')
            if source.startswith('opt/prefix/start/'):
                add('drive_c/ProgramData/Microsoft/Windows/Start Menu/' + source[len('opt/prefix/start/'):],
                    data, source, 'rootfs')
    if 'opt/prefix/marlett.ttf' in scripts:
        add('drive_c/windows/Fonts/marlett.ttf', scripts['opt/prefix/marlett.ttf'],
            'opt/prefix/marlett.ttf', 'rootfs')
    with tarfile.open(fileobj=io.BytesIO(scripts['opt/prefix/fix-fonts.tar.xz'])) as archive:
        for member in archive:
            if member.isdir(): continue
            require(member.isfile() and member.name.startswith('Fonts/') and
                    '..' not in PurePosixPath(member.name).parts, 'font_overlay_invalid')
            add('drive_c/windows/' + member.name, archive.extractfile(member).read())
    if package == 'glibc_proton_11.0':
        font_defaults = module('proton_prefix_fonts')
        for hive in ('system.reg', 'user.reg'):
            add(hive, font_defaults.normalize(delta[hive].decode(), hive, records).encode())
    for name in ('system.reg', 'user.reg', 'userdef.reg', '.update-timestamp',
                 'drive_c/windows/system32/ntdll.dll', 'drive_c/windows/syswow64/ntdll.dll'):
        require(name in records, 'native_core_file_missing')
    delta = {name: data for name, data in delta.items() if name in records and 'source' not in records[name]}
    output.mkdir(parents=True, exist_ok=False)
    for name in set(records) | set(links):
        directories.update(str(p) for p in PurePosixPath(name).parents if str(p) != '.')
    with (output / 'delta.tgz').open('wb') as raw:
        with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode='w') as archive:
                for name in sorted(directories):
                    m = tarfile.TarInfo(name); m.type = tarfile.DIRTYPE; m.mode = 0o700
                    archive.addfile(m)
                for name, data in sorted(delta.items()):
                    m = tarfile.TarInfo(name); m.size = len(data); m.mode = 0o600
                    archive.addfile(m, io.BytesIO(data))
                for name, target in sorted(links.items()):
                    m = tarfile.TarInfo(name); m.type = tarfile.SYMTYPE; m.linkname = target
                    archive.addfile(m)
    (output / 'manifest.json').write_text(json.dumps(sorted(records.values(), key=lambda r: r['path']),
                                                    separators=(',', ':')) + '\n')
    guards = ['bin/wine' if 'bin/wine' in selected else 'bin/wine64', 'bin/wineserver',
              'lib/wine/x86_64-windows/ntdll.dll', 'lib/wine/i386-windows/ntdll.dll']
    metadata = dict(schemaVersion=3, backend='glibc_termux_box', hostAbi='aarch64', prefixArch='wow64',
                    peMachine=0x8664, translator='box64', winePackage=package,
                    windowsUser=base['windowsUser'], componentVersion=descriptor['version'],
                    sourceComponentSha256=descriptor['sha256'], prefixComponentId='native_prefix_' + package[6:],
                    runtimeFiles={}, wineRuntimeFiles={name: digest(selected[name]) for name in guards},
                    expandedBytes=sum(r['bytes'] for r in records.values()),
                    assets={name: digest((output / name).read_bytes()) for name in ('manifest.json', 'delta.tgz')},
                    provenance=dict(skeletonBundleSha256=digest((skeleton / 'bundle.json').read_bytes()),
                                    skeletonWinePackage=base['winePackage'],
                                    fontsComponentSha256=fonts_descriptor['sha256'],
                                    scriptsComponentSha256=scripts_descriptor['sha256'],
                                    appsComponentSha256=apps_descriptor['sha256'],
                                    omittedStartupEntries=omitted, nativeAcceptance='Pending'))
    if package == 'glibc_proton_11.0':
        metadata['prefixComponentVersion'] = 2
        metadata['provenance']['fontConfigurationVersion'] = 2
    (output / 'bundle.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('skeleton', 'wine-archive', 'fonts-archive', 'scripts-archive', 'apps-archive', 'index', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--component', required=True)
    a = p.parse_args()
    index = {d['id']: d for d in json.loads(a.index.read_text())['packages']}
    result = build(a.skeleton, a.wine_archive, index[a.component], a.fonts_archive,
                   index['glibc_fonts_1'], a.output, a.scripts_archive, index['glibc_scripts_29'],
                   a.apps_archive, index['glibc_prefix-apps_3'])
    print(json.dumps(dict(package=a.component, expandedBytes=result['expandedBytes'], assets=result['assets'])))
