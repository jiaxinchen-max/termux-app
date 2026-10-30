#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Install one pinned upstream KGSL Mesa overlay; never compile or change renderer."""
import argparse
import hashlib
import io
import json
import os
import posixpath
import stat
import sys
import tarfile
import uuid

ARCHIVE_BYTES = 12242251
ARCHIVE_SHA256 = 'b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687'
EXPANDED_LIMIT = 64 * 1024 * 1024
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
DIRECTORY_PATH_FLAGS = (os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
                        if hasattr(os, 'O_PATH') else None)
ICD = 'usr/share/vulkan/icd.d/freedreno_icd.aarch64.json'
LIB = 'usr/lib/aarch64-linux-gnu/'
REQUIRED = (LIB + 'libvulkan_freedreno.so', LIB + 'libgallium-27.0.0-devel.so',
            LIB + 'libEGL_mesa.so.0.0.0', LIB + 'libGLX_mesa.so.0.0.0')


def components(path):
    if not os.path.isabs(path) or '\0' in path:
        raise ValueError('absolute path required')
    parts = [p for p in path.split('/') if p]
    if any(p in ('.', '..') for p in parts):
        raise ValueError('dot path components forbidden')
    return parts


def open_directory(path):
    if DIRECTORY_PATH_FLAGS is None:
        raise RuntimeError('Linux O_PATH directory traversal is required')
    fd = os.open('/', DIRECTORY_PATH_FLAGS)
    try:
        for part in components(path):
            child = os.open(part, DIRECTORY_PATH_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
        return os.open('.', DIRECTORY_FLAGS, dir_fd=fd)
    finally:
        os.close(fd)


def parent_directory(root, relative, create=False):
    fd = os.dup(root)
    try:
        for part in relative.split('/')[:-1]:
            try:
                child = os.open(part, DIRECTORY_PATH_FLAGS, dir_fd=fd)
            except FileNotFoundError:
                if not create:
                    raise
                # Only readable final capabilities are used for mkdir/fsync.
                readable = os.open('.', DIRECTORY_FLAGS, dir_fd=fd)
                try:
                    os.mkdir(part, 0o755, dir_fd=readable)
                    os.fsync(readable)
                finally:
                    os.close(readable)
                child = os.open(part, DIRECTORY_PATH_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
        return os.open('.', DIRECTORY_FLAGS, dir_fd=fd)
    finally:
        os.close(fd)


def read_regular(root, name, limit):
    parent = parent_directory(root, name)
    try:
        fd = os.open(name.rsplit('/', 1)[-1], os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW | os.O_CLOEXEC,
                     dir_fd=parent)
    finally:
        os.close(parent)
    with os.fdopen(fd, 'rb') as source:
        before = os.fstat(source.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise ValueError('regular file exceeds limit: ' + name)
        data = source.read(limit + 1)
        after = os.fstat(source.fileno())
        if len(data) > limit or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise ValueError('file changed while reading: ' + name)
        return data


def read_archive(path):
    parts = components(path)
    if not parts:
        raise ValueError('archive must be a regular file')
    parent = open_directory('/' + '/'.join(parts[:-1]))
    try:
        data = read_regular(parent, parts[-1], ARCHIVE_BYTES)
    finally:
        os.close(parent)
    if len(data) != ARCHIVE_BYTES or hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise ValueError('pinned Mesa archive bytes/SHA256 mismatch')
    return data


def relative_name(name):
    if name.startswith('./'):
        name = name[2:]
    if not name or name.startswith('/') or '\0' in name or any(p in ('', '.', '..') for p in name.split('/')):
        raise ValueError('unsafe archive path: ' + name)
    if name != 'usr' and not name.startswith('usr/'):
        raise ValueError('Mesa payload must remain under usr')
    return name


def decode_archive(data):
    entries = {}
    total = 0
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive:
            if member.name == '.' and member.isdir():
                continue
            name = relative_name(member.name)
            if name in entries or len(entries) >= 128:
                raise ValueError('duplicate or excessive archive entries')
            if member.isdir():
                value = ('directory', None, 0o755)
            elif member.isfile() and not member.issparse():
                total += member.size
                if member.size < 0 or total > EXPANDED_LIMIT or member.mode not in (0o644, 0o755):
                    raise ValueError('unsafe Mesa file size/mode')
                content = archive.extractfile(member).read(member.size + 1)
                if len(content) != member.size:
                    raise ValueError('truncated Mesa payload')
                value = ('file', content, member.mode)
            elif member.issym():
                link = member.linkname
                if not link or link.startswith('/') or '\0' in link:
                    raise ValueError('absolute or empty Mesa symlink')
                target = posixpath.normpath(posixpath.join(posixpath.dirname(name), link))
                relative_name(target)
                value = ('symlink', link, 0o777)
            else:
                raise ValueError('unsupported Mesa archive type')
            entries[name] = value
    for name, (kind, content, mode) in entries.items():
        for i in range(1, len(name.split('/'))):
            ancestor = '/'.join(name.split('/')[:i])
            if ancestor not in entries or entries[ancestor][0] != 'directory':
                raise ValueError('archive ancestor is not a declared directory')
        if kind == 'symlink':
            seen = {name}
            target = posixpath.normpath(posixpath.join(posixpath.dirname(name), content))
            while target in entries and entries[target][0] == 'symlink':
                if target in seen:
                    raise ValueError('cyclic Mesa symlink')
                seen.add(target)
                target = posixpath.normpath(posixpath.join(posixpath.dirname(target), entries[target][1]))
            if target not in entries or entries[target][0] != 'file':
                raise ValueError('Mesa symlink must resolve to an archived regular file')
    for name in REQUIRED:
        value = entries.get(name)
        if not value or value[0] != 'file' or value[1][:6] != b'\x7fELF\x02\x01' or value[1][18:20] != b'\xb7\x00':
            raise ValueError('Mesa aarch64 ELF identity mismatch: ' + name)
    value = entries.get(ICD)
    if not value or value[0] != 'file':
        raise ValueError('Mesa Turnip ICD missing')
    icd = json.loads(value[1])
    if icd.get('ICD', {}).get('library_path') != '/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so':
        raise ValueError('Mesa Turnip ICD identity mismatch')
    return entries


def validate_rootfs(root):
    parent = parent_directory(root, 'etc/os-release')
    try:
        info = os.stat('os-release', dir_fd=parent, follow_symlinks=False)
        if stat.S_ISLNK(info.st_mode):
            if os.readlink('os-release', dir_fd=parent) != '../usr/lib/os-release':
                raise ValueError('unexpected rootfs os-release symlink')
            name = 'usr/lib/os-release'
        else:
            name = 'etc/os-release'
    finally:
        os.close(parent)
    release = {}
    for line in read_regular(root, name, 65536).decode().splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            if key in release:
                raise ValueError('duplicate os-release key')
            release[key] = value.strip('"\'')
    if release.get('ID') != 'debian' or release.get('VERSION_CODENAME') != 'trixie':
        raise ValueError('Mesa overlay requires Debian trixie rootfs')


def install_one(root, name, kind, content, mode):
    parent = parent_directory(root, name, create=True)
    target = name.rsplit('/', 1)[-1]
    temporary = '.mesa-install-' + uuid.uuid4().hex + '.tmp'
    identity = None
    try:
        try:
            old = os.stat(target, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            old = None
        if old is not None and not (stat.S_ISREG(old.st_mode) or stat.S_ISLNK(old.st_mode)):
            raise ValueError('Mesa destination must be regular file or symlink: ' + name)
        if kind == 'file':
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                         mode, dir_fd=parent)
            identity = os.fstat(fd)
            with os.fdopen(fd, 'wb') as out:
                os.fchmod(out.fileno(), mode)
                out.write(content)
                out.flush()
                os.fsync(out.fileno())
        else:
            os.symlink(content, temporary, dir_fd=parent)
            identity = os.stat(temporary, dir_fd=parent, follow_symlinks=False)
        os.replace(temporary, target, src_dir_fd=parent, dst_dir_fd=parent)
        os.fsync(parent)
    finally:
        try:
            if identity is not None:
                try:
                    current = os.stat(temporary, dir_fd=parent, follow_symlinks=False)
                except FileNotFoundError:
                    current = None
                if current and (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                    os.unlink(temporary, dir_fd=parent)
        finally:
            os.close(parent)


def verify(root, entries):
    for name, (kind, content, mode) in entries.items():
        if kind == 'file':
            if read_regular(root, name, len(content)) != content:
                raise ValueError('installed Mesa payload mismatch: ' + name)
        parent = parent_directory(root, name)
        try:
            leaf = name.rsplit('/', 1)[-1]
            info = os.stat(leaf, dir_fd=parent, follow_symlinks=False)
            if kind == 'file' and stat.S_IMODE(info.st_mode) != mode:
                raise ValueError('installed Mesa mode mismatch: ' + name)
            if kind == 'directory' and not stat.S_ISDIR(info.st_mode):
                raise ValueError('installed Mesa directory mismatch: ' + name)
            if kind == 'symlink' and (not stat.S_ISLNK(info.st_mode) or os.readlink(leaf, dir_fd=parent) != content):
                raise ValueError('installed Mesa symlink mismatch: ' + name)
        finally:
            os.close(parent)


def install(archive, rootfs, codename, gl_mode):
    if codename != 'trixie' or gl_mode != 'zink':
        raise ValueError('explicit trixie/zink mode required')
    entries = decode_archive(read_archive(archive))
    root = open_directory(rootfs)
    try:
        validate_rootfs(root)
        # Verify existing ancestors before writing any payload. Missing directories are created below.
        for name, (kind, content, mode) in entries.items():
            try:
                parent = parent_directory(root, name)
            except FileNotFoundError:
                continue
            try:
                try:
                    old = os.stat(name.rsplit('/', 1)[-1], dir_fd=parent, follow_symlinks=False)
                except FileNotFoundError:
                    old = None
                if kind != 'directory' and old is not None and not (stat.S_ISREG(old.st_mode) or stat.S_ISLNK(old.st_mode)):
                    raise ValueError('Mesa destination must be regular file or symlink: ' + name)
                if kind == 'directory':
                    try:
                        child = os.open(name.rsplit('/', 1)[-1], DIRECTORY_PATH_FLAGS, dir_fd=parent)
                    except FileNotFoundError:
                        continue
                    os.close(child)
            finally:
                os.close(parent)
        for name, (kind, content, mode) in entries.items():
            if kind == 'directory':
                parent = parent_directory(root, name + '/.directory', create=True)
                os.close(parent)
            else:
                install_one(root, name, kind, content, mode)
        verify(root, entries)
        os.fsync(root)
    finally:
        os.close(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True)
    parser.add_argument('--rootfs', required=True)
    parser.add_argument('--codename', choices=('trixie',), required=True)
    parser.add_argument('--gl-mode', choices=('zink',), required=True)
    args = parser.parse_args()
    install(args.archive, args.rootfs, args.codename, args.gl_mode)
    print('Installed verified upstream Mesa 27.0.0-devel-20261025 (Debian trixie, aarch64, KGSL/Zink).')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, tarfile.TarError) as error:
        sys.exit('Mesa installation failed: ' + str(error))
