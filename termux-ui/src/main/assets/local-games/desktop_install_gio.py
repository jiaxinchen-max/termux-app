#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Install the pinned glibc GIO artifact into one existing PRoot rootfs; never compile."""
import argparse
import hashlib
import json
import os
import re
import stat
import sys
import uuid
import zlib

LIMIT = 16 * 1024 * 1024
PAYLOADS = {
    'usr/local/lib/termux-usb-bridge/gio/modules/libtusb-volume-monitor.so': 0o644,
    'usr/local/libexec/tusb-gio-mount': 0o755,
}
MANIFEST = 'gio-build-manifest.json'
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
DIRECTORY_PATH_FLAGS = (os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
                        if hasattr(os, 'O_PATH') else None)


def components(path):
    if not os.path.isabs(path) or '\0' in path:
        raise ValueError('absolute path required')
    parts = [part for part in path.split('/') if part]
    if any(part in ('.', '..') for part in parts):
        raise ValueError('dot path components are forbidden')
    return parts


def open_directory(path):
    if DIRECTORY_PATH_FLAGS is None:
        raise RuntimeError('Linux O_PATH directory traversal is required')
    # Android permits searching system ancestors without permitting directory reads.
    # Keep an O_PATH capability at every step; no component can resolve a symlink.
    fd = os.open('/', DIRECTORY_PATH_FLAGS)
    try:
        for part in components(path):
            child = os.open(part, DIRECTORY_PATH_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
        # Only the selected app-owned final directory needs a readable FD for fsync.
        return os.open('.', DIRECTORY_FLAGS, dir_fd=fd)
    finally:
        os.close(fd)


def read_archive(path):
    parts = components(path)
    if not parts:
        raise ValueError('archive must be a regular file')
    parent = open_directory('/' + '/'.join(parts[:-1]))
    try:
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent)
    finally:
        os.close(parent)
    with os.fdopen(fd, 'rb') as source:
        before = os.fstat(source.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > LIMIT:
            raise ValueError('archive must be a regular file no larger than 16 MiB')
        compressed = source.read(LIMIT + 1)
        after = os.fstat(source.fileno())
        if len(compressed) > LIMIT or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise ValueError('archive changed while reading')
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    raw = decoder.decompress(compressed, LIMIT + 1)
    if len(raw) > LIMIT or decoder.unconsumed_tail:
        raise ValueError('expanded archive exceeds 16 MiB')
    if not decoder.eof or decoder.unused_data:
        raise ValueError('truncated gzip or trailing compressed data')
    return raw


def octal(field):
    value = field.strip(b'\0 ')
    if value and any(byte not in b'01234567' for byte in value):
        raise ValueError('non-octal tar metadata')
    return int(value or b'0', 8)


def decode_tar(raw):
    files = {}
    position = 0
    while position + 512 <= len(raw):
        header = raw[position:position + 512]
        if header == bytes(512):
            if len(raw) - position < 1024 or len(raw) % 512 or any(raw[position:]):
                raise ValueError('invalid tar termination or trailing data')
            break
        if len(files) >= 3 or header[156:157] not in (b'0', b'\0') or any(header[157:257]) or any(header[345:500]):
            raise ValueError('only three fixed regular USTAR entries are allowed')
        if header[257:263] not in (b'ustar\0', b'ustar '):
            raise ValueError('USTAR format required')
        expected = octal(header[148:156])
        if sum(header[:148]) + 8 * 32 + sum(header[156:]) != expected:
            raise ValueError('tar header checksum mismatch')
        name = header[:100].split(b'\0', 1)[0].decode('utf-8', 'strict')
        if name not in set(PAYLOADS) | {MANIFEST} or name in files:
            raise ValueError('unexpected or duplicate artifact path: ' + name)
        size = octal(header[124:136])
        if size <= 0 or size > LIMIT or (name == MANIFEST and size > 65536):
            raise ValueError('invalid artifact entry size')
        start = position + 512
        end = start + size
        padded = start + ((size + 511) // 512) * 512
        if padded > len(raw) or any(raw[end:padded]):
            raise ValueError('truncated artifact or invalid entry padding')
        files[name] = raw[start:end]
        position = padded
    else:
        raise ValueError('tar terminator missing')
    if set(files) != set(PAYLOADS) | {MANIFEST}:
        raise ValueError('artifact must contain exactly both GIO files and its manifest')
    return files


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate manifest key: ' + key)
        result[key] = value
    return result


def validate(files, codename, bridge_version):
    manifest = json.loads(files[MANIFEST].decode('utf-8'), object_pairs_hook=unique_object)
    if not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1:
        raise ValueError('unsupported GIO manifest schema')
    for key, expected in {'architecture': 'aarch64', 'libc': 'glibc', 'codename': codename,
                          'bridgeVersion': bridge_version}.items():
        if manifest.get(key) != expected:
            raise ValueError('GIO artifact identity mismatch: ' + key)
    checksums = manifest.get('outputSha256')
    if not isinstance(checksums, dict) or set(checksums) != set(PAYLOADS):
        raise ValueError('GIO manifest must identify exactly the two payloads')
    for name in PAYLOADS:
        checksum = checksums[name]
        if not isinstance(checksum, str) or not re.fullmatch('[0-9a-f]{64}', checksum):
            raise ValueError('GIO payload checksum mismatch: ' + name)


def destination_parent(root_fd, relative):
    fd = os.dup(root_fd)
    try:
        for part in relative.split('/')[:-1]:
            try:
                child = os.open(part, DIRECTORY_FLAGS, dir_fd=fd)
            except FileNotFoundError:
                os.mkdir(part, 0o755, dir_fd=fd)
                child = os.open(part, DIRECTORY_FLAGS, dir_fd=fd)
                os.fchmod(child, 0o755)
                os.fsync(fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def install_one(root_fd, name, data, mode):
    parent = destination_parent(root_fd, name)
    temporary = '.tusb-gio-' + uuid.uuid4().hex + '.tmp'
    identity = None
    try:
        target = name.rsplit('/', 1)[-1]
        try:
            old = os.stat(target, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            old = None
        if old is not None and not stat.S_ISREG(old.st_mode):
            raise ValueError('GIO destination is not a regular file: ' + name)
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                     mode, dir_fd=parent)
        identity = os.fstat(fd)
        with os.fdopen(fd, 'wb') as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target, src_dir_fd=parent, dst_dir_fd=parent)
        os.fsync(parent)
    finally:
        try:
            if identity is not None:
                try:
                    current = os.stat(temporary, dir_fd=parent, follow_symlinks=False)
                except FileNotFoundError:
                    current = None
                if current is not None and stat.S_ISREG(current.st_mode) and (
                        current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                    os.unlink(temporary, dir_fd=parent)
        finally:
            os.close(parent)


def install(archive, rootfs, codename, bridge_version):
    files = decode_tar(read_archive(archive))
    validate(files, codename, bridge_version)
    root = open_directory(rootfs)
    try:
        for name, mode in PAYLOADS.items():
            install_one(root, name, files[name], mode)
        os.fsync(root)
    finally:
        os.close(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True)
    parser.add_argument('--rootfs', required=True)
    parser.add_argument('--codename', choices=('trixie',), required=True)
    parser.add_argument('--bridge-version', choices=('0.4.1',), required=True)
    args = parser.parse_args()
    install(args.archive, args.rootfs, args.codename, args.bridge_version)
    print('Installed verified aarch64 glibc GIO module and helper (Debian trixie, bridge 0.4.1).')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, zlib.error) as error:
        sys.exit('GIO artifact installation failed: ' + str(error))
