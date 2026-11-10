#!/usr/bin/env python3
"""Restore the bundled, version-bound prefix without executing Wine.

All regular files are independently copied without content verification. Links are created last,
from a fixed Wine drive-map allowlist, never followed while writing files.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import stat
import tarfile

LINKS = {'dosdevices/c:': '../drive_c', 'dosdevices/z:': '/',
         'dosdevices/com1': '/dev/ttyS0'}
SOURCE_PREFIXES = ('usr/lib/wine/', 'usr/share/wine/', 'usr/share/fonts/')
MAX_BYTES = 2 * 1024**3
MAX_FILES = 20000


def require(condition, message):
    if not condition:
        raise ValueError('rootfs_prebuilt_prefix_' + message)


def safe_name(name):
    require(isinstance(name, str) and name and '\\' not in name and '\x00' not in name,
            'path_invalid')
    require(not name.startswith('/') and all(x not in ('', '.', '..') for x in name.split('/')),
            'path_invalid')
    return name


def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def checked_source(root, relative, parents):
    safe_name(relative)
    p = root / relative
    parent = p.parent
    if parent not in parents:
        resolved = parent.resolve(strict=True)
        require(resolved == root or root in resolved.parents, 'source_escape')
        parents[parent] = resolved
    resolved = parents[parent] / p.name
    # A leaf symlink is acceptable only for immutable RootFS inputs inside RootFS.
    resolved = resolved.resolve(strict=True)
    require(root in resolved.parents and resolved.is_file(), 'source_invalid')
    return resolved


def copy_entry(source, target, record):
    with target.open('xb') as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
    os.chmod(target, record['mode'])
    os.utime(target, (record['mtime'], record['mtime']))


def restore(rootfs, assets, destination, identity, runtime, package, wine_root=None,
            backend='rootfs_proot', bundle_sha256=None):
    rootfs = Path(rootfs).resolve(strict=True)
    assets = Path(assets)
    destination = Path(os.path.abspath(destination))
    require(destination.parent.resolve(strict=True) == destination.parent and
            not destination.is_symlink(), 'destination_invalid')
    require(len(identity) == 64 and all(c in '0123456789abcdef' for c in identity), 'identity_invalid')
    native = backend == 'glibc_termux_box'
    require(backend in ('rootfs_proot', 'glibc_termux_box'), 'identity_mismatch')
    if native:
        require(isinstance(bundle_sha256, str) and len(bundle_sha256) == 64, 'bundle_identity_invalid')

    metadata = json.loads((assets / 'bundle.json').read_text())
    require(metadata['schemaVersion'] in ((3,) if native else (1, 2)) and metadata['backend'] == backend and
            metadata['hostAbi'] == 'aarch64' and metadata['prefixArch'] == 'wow64' and
            package == metadata['winePackage'], 'identity_mismatch')
    roots = {'rootfs': rootfs}
    translator = 'hangover'
    if metadata['schemaVersion'] in (2, 3):
        packages = ('glibc_wine_8.18-staging', 'glibc_wine_9.0-staging',
                    'glibc_wine_9.3-vanilla', 'glibc_wine_10.0', 'glibc_proton_11.0') if native else (
                    'rootfs_wine_10.0', 'rootfs_proton_11.0')
        require(package in packages and
                metadata['translator'] == 'box64' and metadata['peMachine'] == 0x8664 and
                wine_root is not None, 'identity_mismatch')
        wine_root = Path(wine_root).resolve(strict=True)
        # The caller resolves the frozen PRoot binding. Also pin its install receipt here;
        # an adjacent, similarly named Wine tree is not an interchangeable input.
        receipt = wine_root / '.wine-component.properties'
        if native:
            # Bind the prefix component receipt; copy current activated Wine files as-is.
            receipt = assets / '.wine-component.properties'
        if not native and not receipt.is_file():
            receipt = wine_root.parent / '.wine-component.properties'
        if native:
            require(receipt.is_file() and not receipt.is_symlink(), 'component_receipt_invalid')
        values = {}
        for line in receipt.read_text().splitlines():
            if '=' in line:
                key, value = line.split('=', 1)
                require(key not in values, 'component_receipt_invalid')
                values[key] = value
        if native:
            require(values.get('schemaVersion') == '1' and
                    values.get('packageName') == metadata['prefixComponentId'] and
                    values.get('version') == str(metadata.get('prefixComponentVersion', 1)),
                    'component_mismatch')
            require(wine_root == rootfs / package, 'native_wine_root_mismatch')
        else:
            require(values.get('schemaVersion') == '1' and values.get('packageName') == package and (values.get('version') == str(metadata['componentVersion'])), 'component_mismatch')
        roots['wine'] = wine_root
        translator = 'box64'
    else:
        require(wine_root is None, 'identity_mismatch')
    parents = {}
    source_parents = {'rootfs': parents, 'wine': {}}
    for relative in metadata['runtimeFiles']:
        checked_source(rootfs, relative, parents)
    if translator == 'box64':
        for relative in metadata['wineRuntimeFiles']:
            require(relative.startswith(('bin/', 'lib/wine/', 'share/wine/')), 'source_invalid')
            checked_source(roots['wine'], relative, source_parents['wine'])
            checked_source(roots['wine'], relative, source_parents['wine'])

    records = json.loads((assets / 'manifest.json').read_text())
    require(isinstance(records, list) and 0 < len(records) <= MAX_FILES, 'manifest_invalid')
    entries = {}
    total = 0
    for record in records:
        name = safe_name(record['path'])
        require(name not in entries and not name.startswith('.games-runtime/') and
                name != '.games-runtime', 'duplicate_or_reserved_path')
        require(type(record['bytes']) is int and 0 <= record['bytes'] <= MAX_BYTES and
                type(record['mode']) is int and 0 <= record['mode'] <= 0o777 and
                isinstance(record['mtime'], (int, float)) and 0 <= record['mtime'] <= 2**40,
                'manifest_invalid')
        if 'source' in record:
            relative = safe_name(record['source'])
            source_root = record.get('sourceRoot', 'rootfs')
            require(source_root in roots, 'source_invalid')
            allowed = (('share/fonts/', 'opt/prefix-expanded/drive_c/', 'opt/prefix-expanded/directx/',
                        'opt/prefix/start/', 'opt/prefix/marlett.ttf') if native else SOURCE_PREFIXES
                       ) if source_root == 'rootfs' else ('lib/wine/', 'share/wine/')
            require(relative.startswith(allowed), 'source_invalid')
        else:
            require('sourceRoot' not in record, 'source_invalid')
        entries[name] = record
        total += record['bytes']
    # Manifest sizes are storage estimates, not expected content lengths.
    require(total <= MAX_BYTES, 'expanded_size_invalid')
    with tarfile.open(assets / 'delta.tgz', 'r:gz') as archive:
        members = []
        for member in archive:
            require(len(members) < MAX_FILES, 'archive_too_large')
            require(member.size <= MAX_BYTES, 'archive_member_too_large')
            members.append(member)
        names = set()
        regulars = set()
        links = {}
        for member in members:
            name = safe_name(member.name.rstrip('/') if member.isdir() else member.name)
            require(name not in names and len(name) <= 1024, 'duplicate_member')
            names.add(name)
            if member.isdir():
                require(name not in entries and name not in LINKS and
                        name != '.games-runtime' and not name.startswith('.games-runtime/'), 'directory_invalid')
            elif member.isfile():
                require(name in entries and 'source' not in entries[name], 'archive_file_invalid')
                regulars.add(name)
            elif member.issym():
                require(name not in entries and LINKS.get(name) == member.linkname, 'link_invalid')
                links[name] = member.linkname
            else:
                require(False, 'member_type_invalid')
        require(regulars == {n for n, r in entries.items() if 'source' not in r} and
                links == LINKS, 'archive_incomplete')
        # Prevent files or directories having a regular file/link as an ancestor.
        leaves = set(entries) | set(links)
        for name in names | set(entries):
            require(not any(str(p) in leaves for p in PurePosixPath(name).parents if str(p) != '.'),
                    'parent_type_invalid')
        for name in ['system.reg', 'user.reg', 'userdef.reg', '.update-timestamp',
                     'drive_c/windows/system32/ntdll.dll', 'drive_c/windows/syswow64/ntdll.dll']:
            require(name in entries, 'core_file_missing')
        lockpath = destination.with_name(destination.name + '.prebuilt-lock')
        fd = os.open(lockpath, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            require(not destination.is_symlink() and
                    (not destination.exists() or (destination.is_dir() and not any(destination.iterdir()))),
                    'destination_not_empty')
            stage = destination.with_name(destination.name + '.prebuilt-stage')
            require(not stage.is_symlink(), 'stage_invalid')
            # Only our reserved, unpublished staging directory is recoverable after process death.
            if stage.exists():
                shutil.rmtree(stage)
            require(shutil.disk_usage(destination.parent).free >= total + 256 * 1024**2,
                    'insufficient_storage')
            stage.mkdir(mode=0o700)
            try:
                for member in members:
                    if member.isdir():
                        (stage / member.name).mkdir(parents=True, exist_ok=True)
                for name, record in entries.items():
                    target = stage / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if 'source' in record:
                        source_root = record.get('sourceRoot', 'rootfs')
                        # Parent caches are scoped to an input root.
                        sourcepath = checked_source(roots[source_root], record['source'],
                                                    source_parents[source_root])
                        with sourcepath.open('rb') as source:
                            copy_entry(source, target, record)
                    else:
                        with archive.extractfile(name) as source:
                            copy_entry(source, target, record)
                require((stage / '.update-timestamp').read_text() == 'disable\n', 'timestamp_invalid')
                require('pendingfilerenameoperations' not in (stage / 'system.reg').read_text().lower() and
                        not any(stage.rglob('*.tmp')), 'initialization_incomplete')
                if native:
                    for name in ('system.reg', 'user.reg', 'userdef.reg'):
                        registry = stage / name
                        registry.write_text(registry.read_text().replace('@NATIVE_PREFIX@', str(destination)))
                for name, target in links.items():
                    (stage / name).parent.mkdir(parents=True, exist_ok=True)
                    (stage / name).symlink_to(target)
                if native:
                    # Absolute host paths are derived locally, never copied from a guest.
                    (stage / 'dosdevices/z:').unlink()
                    (stage / 'dosdevices/z:').symlink_to(rootfs.parent.parent)
                    (stage / 'dosdevices/d:').symlink_to('/sdcard/Download')
                    (stage / 'dosdevices/e:').symlink_to('/sdcard/Android/data/com.termux/files/Download')
                markers = stage / '.games-runtime'
                markers.mkdir()
                for name, value in {'runtime': runtime + '|' + package, 'wine-dlls': package,
                                    'translator': translator, 'prepared-identity': identity,
                                    'cjk-fonts': runtime + '|' + package + '|Noto Sans CJK SC|v4'}.items():
                    (markers / name).write_text(value + '\n')
                if native:
                    user = metadata['windowsUser']
                    require(user in ('root', 'steamuser'), 'native_user_invalid')
                    (markers / 'native-user').write_text(user + '\n')
                    # The font registry is already initialized in the build asset.
                    require('"Tahoma"="Noto Sans CJK SC"' in (stage / 'system.reg').read_text() and
                            '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"' in
                            (stage / 'system.reg').read_text(), 'native_fonts_invalid')
                    (stage / 'termux-boxmeta').mkdir(exist_ok=True)
                    (stage / 'termux-boxmeta/wine-fonts-v2').touch()
                # rename replaces only an empty directory; an intervening nonempty target fails.
                require(not destination.is_symlink(), 'destination_invalid')
                os.rename(stage, destination)
            finally:
                if stage.exists():
                    shutil.rmtree(stage)
    print('Prebuilt Wine prefix restored: %d independent files, %d estimated bytes; no wineboot.' % (len(entries), total), flush=True)


def main():
    p = argparse.ArgumentParser()
    for name in ['rootfs', 'assets', 'destination', 'identity', 'runtime', 'package']:
        p.add_argument('--' + name, required=True)
    p.add_argument('--wine-root')
    p.add_argument('--backend', choices=('rootfs_proot', 'glibc_termux_box'), default='rootfs_proot')
    p.add_argument('--bundle-sha256')
    args = p.parse_args()
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    try:
        restore(args.rootfs, args.assets, args.destination, args.identity, args.runtime, args.package,
                args.wine_root, args.backend, args.bundle_sha256)
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as error:
        p.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
