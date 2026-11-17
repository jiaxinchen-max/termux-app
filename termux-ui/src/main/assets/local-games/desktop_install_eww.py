#!/usr/bin/python3
# SPDX-License-Identifier: MIT
"""Install-time only: build EWW for Ubuntu's libc, never reuse Termux binaries."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

SOURCE = 'https://codeload.github.com/elkowar/eww/tar.gz/refs/tags/v0.6.0'
SHA256 = 'cef361946946c566b79f8ddc6208d1a3f16b4ff9961439a3f86935e1cfa174a1'
LOCK_SHA256 = 'cc8b1d7f7b2033921d39d28613cac5fd27f1ec86445a81da09ee133e5d144f57'
DEST = Path('/usr/local/lib/termux-hyprland/bin/eww')


def install():
    receipt = DEST.with_suffix('.json')
    if DEST.is_file() and receipt.is_file():
        data = json.loads(receipt.read_text())
        if (data.get('source_sha256') == SHA256 and data.get('lock_sha256') == LOCK_SHA256 and
                data.get('binary_sha256') == hashlib.sha256(DEST.read_bytes()).hexdigest()):
            subprocess.run([str(DEST), '--version'], check=True)
            return
    print('Building Ubuntu EWW from pinned source; this first install needs Rust, RAM and time.', flush=True)
    # Root-owned, bounded extraction; Cargo.lock pins registry and git dependency identities.
    with tempfile.TemporaryDirectory(prefix='termux-eww-') as temporary:
        work = Path(temporary); archive = work / 'source.tar.gz'
        with urllib.request.urlopen(SOURCE, timeout=60) as source, archive.open('wb') as target:
            total = 0
            while chunk := source.read(1024 * 1024):
                total += len(chunk)
                if total > 32 * 1024 * 1024:
                    raise RuntimeError('EWW source exceeds limit')
                target.write(chunk)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
            raise RuntimeError('EWW source SHA-256 mismatch')
        with tarfile.open(archive) as bundle:
            members = bundle.getmembers()
            if len(members) > 10000 or sum(member.size for member in members) > 64 * 1024 * 1024:
                raise RuntimeError('EWW source archive exceeds limits')
            for member in members:
                name = Path(member.name)
                if name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir()):
                    raise RuntimeError('Unsafe EWW source archive')
            bundle.extractall(work, members=members, filter='data')
        source_root = work / 'eww-0.6.0'
        lock = Path(__file__).with_name('hyprland-eww-Cargo.lock').read_bytes()
        if hashlib.sha256(lock).hexdigest() != LOCK_SHA256:
            raise RuntimeError('EWW Cargo.lock SHA-256 mismatch')
        (source_root / 'Cargo.lock').write_bytes(lock)
        subprocess.run(['cargo', 'build', '--locked', '--release', '--no-default-features', '--features', 'wayland'],
                       cwd=source_root, env=dict(os.environ, CARGO_BUILD_JOBS='2'), check=True)
        built = source_root / 'target/release/eww'
        subprocess.run([str(built), '--version'], check=True)
        DEST.parent.mkdir(parents=True, exist_ok=True)
        candidate = DEST.with_suffix('.tmp')
        shutil.copyfile(built, candidate); candidate.chmod(0o755); candidate.replace(DEST)
        receipt.write_text(json.dumps(dict(source=SOURCE, source_sha256=SHA256, lock_sha256=LOCK_SHA256, license='MIT',
            build='cargo build --locked --release --no-default-features --features wayland',
            binary_sha256=hashlib.sha256(DEST.read_bytes()).hexdigest()), indent=2) + '\n')


if __name__ == '__main__':
    install()
