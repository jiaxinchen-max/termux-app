#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build only the glibc GIO volume module/helper in an aarch64 Debian rootfs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source-root', type=Path, required=True)
    ap.add_argument('--output-root', type=Path, required=True)
    ap.add_argument('--cc', type=Path, required=True)
    ap.add_argument('--pkg-config', type=Path, required=True)
    ap.add_argument('--codename', choices=('trixie',), required=True)
    args = ap.parse_args()
    fields = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    if fields.get('ID', '').strip('"') != 'debian' or fields.get('VERSION_CODENAME', '').strip('"') != args.codename:
        ap.error('build inside the explicitly selected Debian trixie runtime')
    cc, pkg = str(args.cc.resolve(strict=True)), str(args.pkg_config.resolve(strict=True))
    triple = subprocess.check_output([cc, '-dumpmachine'], text=True).strip()
    if triple != 'aarch64-linux-gnu':
        ap.error('compiler must target aarch64-linux-gnu, got ' + triple)
    source = args.source_root.resolve(strict=True)
    output = args.output_root.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, SOURCE_DATE_EPOCH='1761868800')
    import shlex
    flags = shlex.split(subprocess.check_output([pkg, '--cflags', '--libs',
        'gio-2.0', 'gio-unix-2.0', 'gmodule-2.0'], text=True, env=env))
    definitions = [
        ('src/desktop/volume_monitor.c', 'usr/local/lib/termux-usb-bridge/gio/modules/libtusb-volume-monitor.so', ['-shared', '-fPIC']),
        ('src/adapters/gio_mount.c', 'usr/local/libexec/tusb-gio-mount', []),
    ]
    commands, inputs, outputs = [], {}, {}
    for relative, destination, extra in definitions:
        source_file = source / relative
        inputs[relative] = digest(source_file)
        target = output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        command = [cc, '-std=gnu11', '-O2', '-Wall', '-Wextra', '-Werror',
            '-ffile-prefix-map=' + str(source) + '=.', '-Wl,--build-id=sha1'] + extra + [str(source_file), '-o', str(target)] + flags
        subprocess.run(command, check=True, env=env)
        target.chmod(0o755 if not extra else 0o644)
        commands.append(command)
        outputs[destination] = digest(target)
    manifest = dict(schemaVersion=1, architecture='aarch64', libc='glibc',
        codename=args.codename, bridgeVersion='0.4.1', sourceSha256=inputs, outputSha256=outputs,
        compiler=subprocess.check_output([cc, '--version'], text=True).splitlines()[0],
        glibVersion=subprocess.check_output([pkg, '--modversion', 'gio-2.0'], text=True).strip(),
        sourceDateEpoch=env['SOURCE_DATE_EPOCH'], commands=commands)
    (output / 'gio-build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
