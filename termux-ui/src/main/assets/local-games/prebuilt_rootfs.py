#!/usr/bin/env python3
"""Pack and safely restore a pinned, preinstalled PRoot image. No guest installation."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile

IMAGE_ID = 'rootfs_debian13_hangover_pdb2_minimal'
SOURCE_SHA = 'ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1'
MANIFEST = '.idap-image-manifest.json'
RECEIPT = 'etc/wine-image-install.json'
MAX_MANIFEST = 24 * 1024 * 1024
MAX_ENTRIES = 100000
MAX_BYTES = 4 * 1024**3
RESET = {'etc/machine-id': b'', 'var/lib/dbus/machine-id': b'',
         'etc/hostname': b'idap\n', 'etc/hosts': b'127.0.0.1 localhost idap\n::1 localhost\n',
         'etc/resolv.conf': b'nameserver 1.1.1.1\n'}
EXCLUDE = {'etc/wine-rootfs-trim.json', 'var/lib/systemd/random-seed'}


def require(value, code):
    if not value:
        raise ValueError(code)


def raw_json(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode()


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def relative(name):
    require(isinstance(name, str) and name and not name.startswith('/') and
            all(p not in ('', '.', '..') for p in name.split('/')) and '\x00' not in name,
            'image_member_path')
    return name


def leaf(root, name, create=False):
    parts = relative(name).split('/')
    current = root
    for part in parts[:-1]:
        current = current / part
        require(not current.is_symlink(), 'image_symlink_parent')
        if create:
            current.mkdir(exist_ok=True)
        require(current.is_dir(), 'image_parent_not_directory')
    return current / parts[-1]


def guest(root, name):
    pending = relative(name).split('/')
    parts = []
    hops = 0
    while pending:
        item = pending.pop(0)
        if item in ('', '.'):
            continue
        if item == '..':
            require(parts, 'image_guest_escape')
            parts.pop()
            continue
        p = root.joinpath(*parts, item)
        if p.is_symlink():
            hops += 1
            require(hops <= 40, 'image_guest_link_cycle')
            edge = os.readlink(p)
            if edge.startswith('/'):
                parts = []
            pending = edge.split('/') + pending
        else:
            parts.append(item)
    return root.joinpath(*parts)


def record(path, name):
    st = path.lstat()
    result = {'path': relative(name), 'mode': stat.S_IMODE(st.st_mode)}
    result['mode'] &= 0o1777 if stat.S_ISDIR(st.st_mode) else 0o777
    if stat.S_ISDIR(st.st_mode):
        result['type'] = 'directory'
    elif stat.S_ISLNK(st.st_mode):
        result.update(type='symlink', link=os.readlink(path))
        require('\x00' not in result['link'], 'image_link_invalid')
    elif stat.S_ISREG(st.st_mode):
        result.update(type='file', bytes=st.st_size, sha256=digest(path))
    else:
        raise ValueError('image_special_file:' + name)
    return result


def clean_bytes(path, name):
    if name in RESET:
        return RESET[name]
    if name == 'etc/wine-runtime.properties':
        return ('\n'.join('recipeVersion=12' if x.startswith('recipeVersion=') else x
                          for x in path.read_text().splitlines() if not x.startswith('recipeSha256=')) + '\n').encode()
    return None


def excluded(name):
    return (name in EXCLUDE or name.startswith('var/log/') or name == 'root/.ssh' or
            name.startswith('root/.ssh/') or name.startswith(('run/', 'tmp/', 'var/tmp/')) or re.fullmatch(r'etc/ssh/ssh_host_[A-Za-z0-9_]+(?:\.pub)?', name) is not None)


def inventory(root):
    require(root.is_dir() and not root.is_symlink(), 'image_root_invalid')
    require(not (root / '.wine-minimize-candidate.json').exists(), 'image_experimental_root')
    require(not (root / RECEIPT).exists(), 'image_already_imported')
    for prefix in ('root', 'home', 'mnt/games/game', 'mnt/games/prefix'):
        p = root / prefix
        require(not p.is_symlink(), 'image_private_directory_link')
        if p.exists():
            for f in p.rglob('*'):
                allowed = prefix == 'root' and str(f.relative_to(root)) in ('root/.bashrc', 'root/.profile', 'root/.ssh')
                require(f.is_dir() and not f.is_symlink() or allowed, 'image_private_data:' + str(f.relative_to(root)))
    records = []
    for directory, dirs, files in os.walk(root, followlinks=False):
        dirs.sort(); files.sort()
        for n in dirs + files:
            p = Path(directory) / n
            name = str(p.relative_to(root))
            if excluded(name):
                continue
            require(name not in (MANIFEST, RECEIPT), 'image_reserved_member')
            r = record(p, name)
            replacement = clean_bytes(p, name)
            if replacement is not None:
                r = {'path': name, 'mode': 0o644, 'type': 'file', 'bytes': len(replacement), 'sha256': sha_bytes(replacement)}
            records.append(r)
    require(0 < len(records) <= MAX_ENTRIES, 'image_entry_limit')
    require(sum(r.get('bytes', 0) for r in records) <= MAX_BYTES, 'image_byte_limit')
    return records


def validate_manifest(data, descriptor):

    m = json.loads(data)
    require(m['schemaVersion'] == 1 and m['imageId'] == IMAGE_ID, 'image_identity')
    records = m['entries']
    require(isinstance(records, list) and 0 < len(records) == descriptor['entryCount'] <= MAX_ENTRIES, 'image_entry_limit')
    names = set()
    total = 0
    for r in records:
        name = relative(r['path'])
        require(name not in names and name not in (MANIFEST, RECEIPT), 'image_duplicate_or_reserved')
        names.add(name)
        require(r['type'] in ('file', 'directory', 'symlink') and isinstance(r['mode'], int) and
                0 <= r['mode'] <= (0o1777 if r['type'] == 'directory' else 0o777), 'image_entry_type_or_mode')
        if r['type'] == 'file':
            require(isinstance(r['bytes'], int) and 0 <= r['bytes'] <= MAX_BYTES and
                    re.fullmatch('[a-f0-9]{64}', r['sha256']), 'image_file_record')
            total += r['bytes']
        elif r['type'] == 'symlink':
            require(isinstance(r['link'], str) and r['link'] and '\x00' not in r['link'], 'image_link_record')
    require(total == descriptor['expandedBytes'] <= MAX_BYTES, 'image_expanded_size')
    return m


def pack(root, output, descriptor_path, assets, zstd):
    require(not output.exists() and not descriptor_path.exists(), 'image_output_exists')
    import importlib.util
    spec = importlib.util.spec_from_file_location('image_trim', Path(__file__).with_name('minimize-rootfs.py'))
    trim = importlib.util.module_from_spec(spec); spec.loader.exec_module(trim)
    receipt = json.loads((root / 'etc/wine-rootfs-trim.json').read_text())
    require(receipt['state'] == 'Verified' and len(receipt['batches']) == 5, 'image_trim_not_verified')
    protected = trim.protect(root, assets)
    entries = inventory(root)
    m = {'schemaVersion': 1, 'imageId': IMAGE_ID, 'sourceComponentSha256': SOURCE_SHA,
         'sourceTrimReceipt': receipt, 'protectedFiles': protected,
         'runtimeFiles': json.loads((assets / 'bundle.json').read_text())['runtimeFiles'], 'entries': entries}
    manifest = raw_json(m)
    require(len(manifest) <= MAX_MANIFEST, 'image_manifest_limit')
    descriptor = {'schemaVersion': 1, 'imageId': IMAGE_ID, 'sourceComponentSha256': SOURCE_SHA,
                  'file': output.name, 'manifestSha256': sha_bytes(manifest),
                  'expandedBytes': sum(r.get('bytes', 0) for r in entries), 'entryCount': len(entries)}
    validate_manifest(manifest, descriptor)
    with output.open('xb') as dest:
        proc = subprocess.Popen([zstd, '-T2', '-6', '-c'], stdin=subprocess.PIPE, stdout=dest)
        try:
            with tarfile.open(fileobj=proc.stdin, mode='w|', format=tarfile.PAX_FORMAT) as tar:
                info = tarfile.TarInfo(MANIFEST); info.size = len(manifest); info.mode = 0o644
                tar.addfile(info, io.BytesIO(manifest))
                for r in entries:
                    info = tarfile.TarInfo(r['path']); info.mode = r['mode']; info.uid = info.gid = 0
                    path = root / r['path']
                    if r['type'] == 'directory':
                        info.type = tarfile.DIRTYPE; tar.addfile(info)
                    elif r['type'] == 'symlink':
                        require(os.readlink(path) == r['link'], 'image_source_changed')
                        info.type = tarfile.SYMTYPE; info.linkname = r['link']; tar.addfile(info)
                    else:
                        info.size = r['bytes']
                        replacement = clean_bytes(path, r['path'])
                        with io.BytesIO(replacement) if replacement is not None else path.open('rb') as stream:
                            tar.addfile(info, stream)
            proc.stdin.close()
            require(proc.wait() == 0, 'image_compression_failed')
        finally:
            if proc.poll() is None:
                proc.terminate(); proc.wait()
    # The immutable input must stay unchanged while streaming.
    require(entries == inventory(root), 'image_source_changed')
    descriptor.update(bytes=output.stat().st_size, sha256=digest(output))
    descriptor_path.write_bytes(raw_json(descriptor) + b'\n')
    return descriptor


def descriptor_read(path):
    require(path.stat().st_size <= 65536 and not path.is_symlink(), 'image_descriptor_invalid')
    d = json.loads(path.read_text())
    require(d['schemaVersion'] == 1 and d['imageId'] == IMAGE_ID and re.fullmatch('[a-f0-9]{64}', d['sha256']) and re.fullmatch('[a-f0-9]{64}', d['manifestSha256']) and re.fullmatch('[A-Za-z0-9_.-]+\\.tar\\.zst', d['file']) and isinstance(d['bytes'], int) and (0 < d['bytes'] <= MAX_BYTES) and isinstance(d['entryCount'], int) and (0 < d['entryCount'] <= MAX_ENTRIES) and isinstance(d['expandedBytes'], int) and (0 <= d['expandedBytes'] <= MAX_BYTES), 'image_descriptor_invalid')
    return d


def verify(root, descriptor, recipe, full=False):
    d = descriptor_read(descriptor)
    require(re.fullmatch('[a-f0-9]{64}', recipe), 'image_recipe_invalid')
    require(root.is_dir() and not root.is_symlink(), 'image_root_invalid')
    p = leaf(root, RECEIPT); require(not p.is_symlink(), 'image_receipt_link')
    receipt = json.loads(p.read_text())
    require(receipt.get('schemaVersion') == 1 and set(receipt) == {'schemaVersion', 'imageSha256', 'manifestSha256', 'recipeSha256'}, 'image_install_identity')

    data = leaf(root, MANIFEST).read_bytes(); m = validate_manifest(data, d)
    props = leaf(root, 'etc/wine-runtime.properties').read_text()

    guards = m['protectedFiles'] if full else m['runtimeFiles']
    for name in guards:
        require(guest(root, name).is_file(), "image_runtime_file_missing:" + name)
    for r in m['entries']:
        if r['type'] == 'symlink':
            guest(root, r['path'])
    if full:
        for r in m['entries']:
            p = leaf(root, r['path'])
            actual = record(p, r['path'])
            if r['path'] == 'etc/wine-runtime.properties':
                data = clean_bytes(p, r['path']); actual['bytes'] = len(data); actual['sha256'] = sha_bytes(data)
            require({k:v for k,v in actual.items() if k != 'sha256'} == {k:v for k,v in r.items() if k != 'sha256'}, 'image_installed_member_mismatch:' + r['path'])
    return {'state': 'Ready', 'imageSha256': d['sha256'], 'protectedFiles': len(m['protectedFiles'])}


def discard_owned(root):
    for directory, dirs, files in os.walk(root, followlinks=False):
        os.chmod(directory, 0o700)
    shutil.rmtree(root)


def restore(archive, root, descriptor, recipe, zstd, reserve_bytes=0):
    require(re.fullmatch('[a-f0-9]{64}', recipe), 'image_recipe_invalid')
    d = descriptor_read(descriptor)
    require(not archive.is_symlink() and archive.stat().st_size == d['bytes'], 'image_archive_digest')
    require(root.parent.resolve() == root.parent and not root.exists() and not root.is_symlink(), 'image_destination_exists')
    require(type(reserve_bytes) is int and 0 <= reserve_bytes <= MAX_BYTES, 'image_reserve_invalid')
    require(shutil.disk_usage(root.parent).free >= d['expandedBytes'] + d['entryCount'] * 8192 + 128 * 1024**2 + reserve_bytes,
            'image_insufficient_storage')
    owned = False
    proc = None
    old_handlers = {}
    def interrupted(signum, frame):
        raise InterruptedError('image_cancelled')
    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            old_handlers[sig] = signal.signal(sig, interrupted)
        root.mkdir(mode=0o700); owned = True
        proc = subprocess.Popen([zstd, '-d', '-c', '--', str(archive)], stdout=subprocess.PIPE)
        seen = set(); links = []; directories = []; total = 0
        with tarfile.open(fileobj=proc.stdout, mode='r|') as tar:
            first = tar.next()
            require(first is not None and first.name == MANIFEST and first.isfile() and
                    0 < first.size <= MAX_MANIFEST, 'image_manifest_first')
            data = tar.extractfile(first).read(MAX_MANIFEST + 1)
            m = validate_manifest(data, d)
            expected = {r['path']: r for r in m['entries']}
            while True:
                item = tar.next()
                if item is None:
                    break
                name = relative(item.name.rstrip('/') if item.isdir() else item.name)
                require(name in expected and name not in seen, 'image_unexpected_or_duplicate_member')
                seen.add(name); r = expected[name]
                require(item.mode == r['mode'] and not item.islnk(), 'image_member_mode_or_hardlink')
                p = leaf(root, name, create=True)
                if item.isdir():
                    require(r['type'] == 'directory' and not p.is_symlink(), 'image_directory_type')
                    p.mkdir(exist_ok=True); directories.append((p, r['mode']))
                elif item.issym():
                    require(r['type'] == 'symlink' and item.linkname == r['link'] and
                            not p.exists() and not p.is_symlink(), 'image_symlink_type')
                    links.append((p, item.linkname))
                else:
                    require(item.isfile() and r['type'] == 'file' and item.size == r['bytes'], 'image_file_type_or_size')
                    count = 0
                    with p.open('xb') as dest:
                        source = tar.extractfile(item)
                        while True:
                            block = source.read(1024 * 1024)
                            if not block:
                                break
                            dest.write(block); count += len(block)
                    require(count == r['bytes'], 'image_member_digest')
                    total += count; os.chmod(p, r['mode'])
            # Consume decoder output through EOF; reject non-zero trailing tar data.
            trailing_bytes = 0
            while True:
                trailing = tar.fileobj.read(65536)
                if not trailing:
                    break
                trailing_bytes += len(trailing)
                require(trailing_bytes <= 2 * tarfile.RECORDSIZE and not trailing.strip(b'\x00'), 'image_trailing_data')
        while proc.stdout.read(1024 * 1024):
            raise ValueError('image_trailing_data')
        require(proc.wait() == 0, 'image_decoder_failed')
        require(seen == set(expected) and total == d['expandedBytes'], 'image_members_missing')
        # Links are created last: they can never redirect an archive write into the host.
        for p, edge in links:
            require(not p.exists() and not p.is_symlink(), 'image_symlink_parent_collision')
            p.symlink_to(edge)
        for p, mode in sorted(directories, key=lambda x: len(x[0].parts), reverse=True):
            os.chmod(p, mode)
        # Source archive names belong to the pinned image format. Publish this app's
        # filenames consistently and record the installed names for every future read.
        source_names = {
            'games-runtime.properties': 'wine-runtime.properties',
            'games-rootfs-trim.json': 'wine-rootfs-trim.json',
            'games-external-wine-dependencies.json': 'wine-external-wine-dependencies.json',
        }
        def installed_name(name):
            parent, _, base = name.rpartition('/')
            return (parent + '/' if parent else '') + source_names.get(base, base)
        for entry in m['entries']:
            if entry['type'] == 'directory':
                continue
            old_name = entry['path']
            new_name = installed_name(old_name)
            if new_name != old_name:
                target = leaf(root, new_name)
                require(not target.exists() and not target.is_symlink(), 'image_name_collision')
                leaf(root, old_name).rename(target)
                entry['path'] = new_name
        for key in ('protectedFiles', 'runtimeFiles'):
            values = m[key]
            m[key] = ({installed_name(name): value for name, value in values.items()}
                      if isinstance(values, dict) else [installed_name(name) for name in values])
        (root / MANIFEST).write_bytes(raw_json(m))
        props = leaf(root, 'etc/wine-runtime.properties')
        props.write_text(props.read_text() + 'recipeSha256=' + recipe + '\n')
        leaf(root, RECEIPT).write_bytes(raw_json({'schemaVersion': 1, 'imageSha256': d['sha256'],
            'manifestSha256': d['manifestSha256'], 'recipeSha256': recipe}))
        result = verify(root, descriptor, recipe)
        os.chmod(root, 0o755)
        return result
    except BaseException:
        if proc is not None and proc.poll() is None:
            proc.terminate(); proc.wait()
        if owned:
            discard_owned(root)
        raise
    finally:
        if proc is not None and proc.stdout is not None:
            proc.stdout.close()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='action', required=True)
    p = subs.add_parser('pack'); p.add_argument('--assets', type=Path, required=True)
    for name in ('restore', 'verify'):
        p = subs.add_parser(name)
        p.add_argument('--recipe', required=True)
        if name == 'verify':
            p.add_argument('--full', action='store_true')
        else:
            p.add_argument('--archive', type=Path, required=True)
            p.add_argument('--reserve-bytes', type=int, default=0)
    for p in subs.choices.values():
        p.add_argument('--root', type=Path, required=True)
        p.add_argument('--descriptor', type=Path, required=True)
        if p.prog.endswith('pack'):
            p.add_argument('--output', type=Path, required=True)
        p.add_argument('--zstd', default='zstd')
    args = parser.parse_args()
    try:
        if args.action == 'pack':
            result = pack(args.root, args.output, args.descriptor, args.assets, args.zstd)
        elif args.action == 'restore':
            result = restore(args.archive, args.root, args.descriptor, args.recipe, args.zstd, args.reserve_bytes)
        else:
            result = verify(args.root, args.descriptor, args.recipe, args.full)
        print(json.dumps(result, sort_keys=True), flush=True)
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, InterruptedError) as e:
        print('prebuilt_rootfs_failed: ' + str(e), file=sys.stderr)
        sys.exit(70)


if __name__ == '__main__':
    main()
