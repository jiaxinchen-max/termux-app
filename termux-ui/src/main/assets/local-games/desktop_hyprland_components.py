#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""App-trusted component identities and independent, safe static theme installation."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

ASSETS = Path(__file__).resolve().parent
CATALOG = json.loads((ASSETS / 'hyprland-components.json').read_text())
PREFIX = Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr'))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def resolve(runtime_id, theme_id):
    runtime = CATALOG['runtimes'].get(runtime_id)
    theme = CATALOG['themes'].get(theme_id)
    if runtime is None or theme is None or runtime_id not in theme['runtime_ids']:
        raise ValueError('Unsupported Hyprland runtime/theme combination')
    return runtime, theme


def theme_root(theme_id):
    if theme_id not in CATALOG['themes']:
        raise ValueError('Unknown Hyprland theme')
    return PREFIX / 'share/termux-hyprland/themes' / theme_id


def verify_theme(root, runtime_id, theme_id):
    _, entry = resolve(runtime_id, theme_id)
    if root.is_symlink() or digest(root / 'manifest.json') != entry['manifest_sha256']:
        raise RuntimeError('Hyprland theme manifest mismatch: ' + theme_id)
    manifest = json.loads((root / 'manifest.json').read_text())
    if (manifest.get('schema') != 1 or manifest.get('id') != theme_id
            or runtime_id not in manifest.get('runtime_ids', [])):
        raise RuntimeError('Hyprland theme identity mismatch')
    actual = set()
    for path in root.rglob('*'):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise RuntimeError('Unsafe installed theme resource')
        if path.is_file():
            actual.add(str(path.relative_to(root)))
    if actual - {'.installed'} != set(entry['resource_files']) or set(manifest['files']) != actual - {'manifest.json', '.installed'}:
        raise RuntimeError('Hyprland theme file inventory mismatch')
    for name, expected in manifest['files'].items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or digest(root / name) != expected:
            raise RuntimeError('Hyprland theme resource mismatch: ' + name)
    return root


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlsplit(newurl).scheme != 'https':
            raise RuntimeError('Theme redirect must use HTTPS')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def valid_archive(path, entry):
    return (not path.is_symlink() and path.is_file() and path.stat().st_size == entry['size']
            and digest(path) == entry['archive_sha256'])


def fetch_theme(entry, directory):
    cache = directory / '.cache'
    if cache.is_symlink():
        raise RuntimeError('Unsafe theme cache')
    cache.mkdir(exist_ok=True)
    target = cache / (entry['archive_sha256'] + '.tgz')
    if valid_archive(target, entry):
        return target
    if target.is_symlink():
        raise RuntimeError('Unsafe theme cache entry')
    if target.exists():
        target.unlink()
    if urlsplit(entry['url']).scheme != 'https':
        raise RuntimeError('Theme URL must use HTTPS')
    opener = urllib.request.build_opener(HttpsRedirect())
    request = urllib.request.Request(entry['url'], headers={'User-Agent': 'Termux-App-components/1'})
    descriptor, temporary = tempfile.mkstemp(prefix='.download-', dir=cache)
    partial = Path(temporary)
    try:
        deadline = time.monotonic() + 180
        with os.fdopen(descriptor, 'wb') as output, opener.open(request, timeout=30) as source:
            total = 0
            while True:
                if time.monotonic() > deadline:
                    raise RuntimeError('Theme download timed out; retry installation')
                chunk = source.read1(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > entry['size']:
                    raise RuntimeError('Theme download exceeds expected size')
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if not valid_archive(partial, entry):
            raise RuntimeError('Theme download size/SHA-256 mismatch; retry installation')
        partial.replace(target)
        return target
    finally:
        partial.unlink(missing_ok=True)


def install_theme(runtime_id, theme_id, archive=None):
    _, entry = resolve(runtime_id, theme_id)
    root = theme_root(theme_id)
    archive = None if archive is None else Path(archive)
    if archive is not None and (archive.is_symlink() or digest(archive) != entry['archive_sha256']):
        raise RuntimeError('Hyprland theme archive SHA-256 mismatch')
    # These are app-private immutable resources, never per-desktop user configuration.
    for parent in [PREFIX / 'share', PREFIX / 'share/termux-hyprland', root.parent, root]:
        if parent.is_symlink():
            raise RuntimeError('Unsafe Hyprland theme installation directory')
    root.parent.mkdir(parents=True, exist_ok=True)
    lock_path = root.parent / '.install.lock'
    if lock_path.is_symlink():
        raise RuntimeError('Unsafe theme installation lock')
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        backup = root.with_name(root.name + '.repair-backup')
        if backup.is_symlink():
            raise RuntimeError('Unsafe theme repair backup')
        if backup.exists():
            if root.exists():
                try:
                    verify_theme(root, runtime_id, theme_id)
                    if (root / '.installed').read_text().strip() != entry['manifest_sha256']:
                        raise RuntimeError('Hyprland theme receipt mismatch')
                except (OSError, RuntimeError, ValueError):
                    shutil.rmtree(root)
                    backup.rename(root)
                else:
                    shutil.rmtree(backup)
                    return root
            else:
                backup.rename(root)
        if root.exists():
            try:
                verify_theme(root, runtime_id, theme_id)
                if (root / '.installed').read_text().strip() != entry['manifest_sha256']:
                    raise RuntimeError('Hyprland theme receipt mismatch')
                return root
            except (OSError, RuntimeError, ValueError):
                pass
        # Only explicit install/repair reaches the network; valid installed resources work offline.
        if archive is None:
            archive = fetch_theme(entry, root.parent)
        staging = Path(tempfile.mkdtemp(prefix='.install-', dir=root.parent))
        try:
            seen = set(); total = 0
            with tarfile.open(archive, 'r:gz') as tar:
                for member in tar:
                    path = PurePosixPath(member.name)
                    if (not member.isfile() or path.is_absolute() or '..' in path.parts
                            or str(path) != member.name or member.name not in entry['resource_files']
                            or member.name in seen):
                        raise RuntimeError('Unsafe theme archive member: ' + member.name)
                    seen.add(member.name); total += member.size
                    if len(seen) > 128 or total > 16 * 1024 * 1024:
                        raise RuntimeError('Theme archive exceeds limits')
                    target = staging / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as source, target.open('xb') as output:
                        shutil.copyfileobj(source, output)
                    target.chmod(0o644)
            verify_theme(staging, runtime_id, theme_id)
            (staging / '.installed').write_text(entry['manifest_sha256'] + '\n')
            # Verify excludes only this installation receipt, not unlisted payload files.
            if backup.exists() or backup.is_symlink():
                raise RuntimeError('Theme repair backup exists; inspect previous installation')
            if root.exists():
                root.rename(backup)
            try:
                staging.rename(root)
            except OSError:
                if backup.exists():
                    backup.rename(root)
                raise
            if backup.exists():
                shutil.rmtree(backup)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    return root


def resources(theme_id):
    return verify_theme(theme_root(theme_id), '0.57.0-r7', theme_id)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['install', 'check'])
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--theme', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'install':
            install_theme(args.runtime, args.theme)
        else:
            verify_theme(theme_root(args.theme), args.runtime, args.theme)
    except (OSError, ValueError, RuntimeError, tarfile.TarError, urllib.error.URLError) as error:
        raise SystemExit('Hyprland components: ' + str(error))
