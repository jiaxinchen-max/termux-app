#!/usr/bin/env python3
"""Restore the bundled, version-bound prefix without executing Wine.

All regular files are independently copied and hashed. Links are created last,
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


def copy_verified(source, target, record):
    h = hashlib.sha256()
    count = 0
    with target.open('xb') as output:
        for data in iter(lambda: source.read(1024 * 1024), b''):
            count += len(data)
            require(count <= record['bytes'], 'file_size_mismatch')
            h.update(data)
            output.write(data)
    require(count == record['bytes'] and h.hexdigest() == record['sha256'],
            'file_digest_mismatch:' + record['path'])
    os.chmod(target, record['mode'])
    os.utime(target, (record['mtime'], record['mtime']))


def restore(rootfs, assets, destination, identity, runtime, package):
    rootfs = Path(rootfs).resolve(strict=True)
    assets = Path(assets)
    destination = Path(os.path.abspath(destination))
    require(destination.parent.resolve(strict=True) == destination.parent and
            not destination.is_symlink(), 'destination_invalid')
    require(len(identity) == 64 and all(c in '0123456789abcdef' for c in identity), 'identity_invalid')
    metadata = json.loads((assets / 'bundle.json').read_text())
    require(metadata['schemaVersion'] == 1 and metadata['backend'] == 'rootfs_proot' and
            metadata['hostAbi'] == 'aarch64' and metadata['prefixArch'] == 'wow64' and
            package == metadata['winePackage'], 'identity_mismatch')
    for name in ['manifest.json', 'delta.tgz']:
        require(sha(assets / name) == metadata['assets'][name], 'asset_digest_mismatch')
    recipe = hashlib.sha256()
    for name in ['setup-container.sh', 'games-runtime.properties']:
        recipe.update((assets.parent / name).read_bytes())
    require(recipe.hexdigest() == metadata['baseRecipeSha256'], 'recipe_mismatch')
    parents = {}
    for relative, expected in metadata['runtimeFiles'].items():
        require(sha(checked_source(rootfs, relative, parents)) == expected, 'runtime_mismatch:' + relative)
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
                isinstance(record['mtime'], (int, float)) and 0 <= record['mtime'] <= 2**40 and
                len(record['sha256']) == 64 and
                all(c in '0123456789abcdef' for c in record['sha256']), 'manifest_invalid')
        if 'source' in record:
            relative = safe_name(record['source'])
            require(relative.startswith(SOURCE_PREFIXES), 'source_invalid')
        entries[name] = record
        total += record['bytes']
    require(total == metadata['expandedBytes'] and total <= MAX_BYTES, 'expanded_size_invalid')
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
                require(name in entries and 'source' not in entries[name] and
                        member.size == entries[name]['bytes'], 'archive_file_invalid')
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
                        sourcepath = checked_source(rootfs, record['source'], parents)
                        require(sourcepath.stat().st_size == record['bytes'], 'source_size_mismatch')
                        with sourcepath.open('rb') as source:
                            copy_verified(source, target, record)
                    else:
                        with archive.extractfile(name) as source:
                            copy_verified(source, target, record)
                require((stage / '.update-timestamp').read_text() == 'disable\n', 'timestamp_invalid')
                require('pendingfilerenameoperations' not in (stage / 'system.reg').read_text().lower() and
                        not any(stage.rglob('*.tmp')), 'initialization_incomplete')
                for name, target in links.items():
                    (stage / name).parent.mkdir(parents=True, exist_ok=True)
                    (stage / name).symlink_to(target)
                markers = stage / '.games-runtime'
                markers.mkdir()
                for name, value in {'runtime': runtime + '|' + package, 'wine-dlls': package,
                                    'translator': 'hangover', 'prepared-identity': identity,
                                    'cjk-fonts': runtime + '|' + package + '|Noto Sans CJK SC|v4'}.items():
                    (markers / name).write_text(value + '\n')
                # rename replaces only an empty directory; an intervening nonempty target fails.
                require(not destination.is_symlink(), 'destination_invalid')
                os.rename(stage, destination)
            finally:
                if stage.exists():
                    shutil.rmtree(stage)
    print('Prebuilt Wine prefix restored: %d independent files, %d bytes; no wineboot.' % (len(entries), total), flush=True)


def main():
    p = argparse.ArgumentParser()
    for name in ['rootfs', 'assets', 'destination', 'identity', 'runtime', 'package']:
        p.add_argument('--' + name, required=True)
    args = p.parse_args()
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    try:
        restore(args.rootfs, args.assets, args.destination, args.identity, args.runtime, args.package)
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as error:
        p.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
