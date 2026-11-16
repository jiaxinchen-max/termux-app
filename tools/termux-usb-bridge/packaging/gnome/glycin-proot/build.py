#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Cross-build the Resolute libglycin adapter on the existing Linux builder."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tomllib

UPSTREAM_SHA256 = '8e8e92e312b14d2c5f3a047bdc5305adcb9931ef0150cf74bf526a3741e6fb32'
CRATE_SHA256 = '923674c1f2b1a28f69ebdc0f572672e63874e17ce0e60c96b8bfaff47a8ef4e9'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--crate', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cc', type=Path, required=True)
    parser.add_argument('--pkg-config', type=Path, required=True)
    parser.add_argument('--sysroot', type=Path, required=True)
    parser.add_argument('--cargo', type=Path, required=True)
    args = parser.parse_args()
    for archive, expected in ((args.upstream, UPSTREAM_SHA256), (args.crate, CRATE_SHA256)):
        if digest(archive) != expected:
            parser.error('Upstream archive SHA256 mismatch: ' + str(archive))
    cc = str(args.cc.resolve(strict=True))
    if subprocess.check_output([cc, '-dumpmachine'], text=True).strip() != 'aarch64-linux-gnu':
        parser.error('Requires the Resolute aarch64-linux-gnu cross compiler')
    cargo = str(args.cargo.absolute())
    cargo_version = subprocess.check_output([cargo, '--version'], text=True).strip()
    work = args.work.resolve()
    # Never append another patch/manifest to a previous build's sources.
    work.mkdir(parents=True, exist_ok=False)
    for archive in (args.upstream, args.crate):
        with tarfile.open(archive) as source:
            source.extractall(work, filter='data')
    source, crate = work / 'glycin-2.1.1', work / 'glycin-3.1.0'
    patch = Path(__file__).with_name('0001-android-bwrap-detection.patch')
    subprocess.run(['patch', '--batch', '--forward', '-p1', '-i', str(patch.resolve())],
                   cwd=crate, check=True)
    lock_before = tomllib.loads((source / 'Cargo.lock').read_text())
    with (source / 'Cargo.toml').open('a') as manifest:
        manifest.write('\n[patch.crates-io]\nglycin = { path = "../glycin-3.1.0" }\n')
    env = dict(os.environ, PATH=str(args.cargo.absolute().parent) + os.pathsep + os.environ['PATH'],
               PKG_CONFIG=str(args.pkg_config.resolve(strict=True)),
               PKG_CONFIG_ALLOW_CROSS='1', CC_aarch64_unknown_linux_gnu=cc,
               CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=cc,
               CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUSTFLAGS=(
                   '-C link-arg=-Wl,-soname,libglycin-2.so.0 -C link-arg=-Wl,-rpath-link,' +
                   str(args.sysroot.resolve(strict=True) / 'usr/lib/aarch64-linux-gnu')),
               SOURCE_DATE_EPOCH='1774224000')
    command = [cargo, 'build', '--release', '--target', 'aarch64-unknown-linux-gnu',
               '-p', 'libglycin', '-j', '6']
    subprocess.run(command, cwd=source, env=env, check=True)
    # The local patch may replace glycin's source, never upgrade other crates.
    lock_after = tomllib.loads((source / 'Cargo.lock').read_text())
    unchanged = lambda lock: [p for p in lock['package'] if p['name'] != 'glycin']
    if unchanged(lock_before) != unchanged(lock_after):
        raise RuntimeError('Cargo changed unrelated dependencies; refusing to package')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    relative = 'usr/local/lib/termux-gnome/libglycin-2.so.0'
    target = output / relative
    target.parent.mkdir(parents=True)
    shutil.copyfile(source / 'target/aarch64-unknown-linux-gnu/release/libglycin.so', target)
    target.chmod(0o644)
    manifest = dict(schemaVersion=1, distribution='ubuntu', codename='resolute',
                    architecture='aarch64', libc='glibc', upstreamVersion='2.1.1',
                    upstreamSha256=UPSTREAM_SHA256, crateSha256=CRATE_SHA256,
                    patchSha256=digest(patch), cargoLockSha256=digest(source / 'Cargo.lock'),
                    cargoVersion=cargo_version, outputSha256={relative: digest(target)}, command=command)
    (output / 'glycin-build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
