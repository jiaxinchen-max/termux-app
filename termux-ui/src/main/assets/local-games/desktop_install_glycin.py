#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Install/verify the pinned Resolute image decoder without replacing distro files."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path

LIBRARY = 'usr/local/lib/termux-gnome/libglycin-2.so.0'
MANIFEST = 'glycin-build-manifest.json'
RECEIPT = 'usr/local/lib/termux-gnome/' + MANIFEST
OUTPUT_SHA256 = 'b2ea4a4a87d0fbc9746fe686e84d5848c682498418ded9585eb12f93f073a1be'
IDENTITY = {
    'distribution': 'ubuntu', 'codename': 'resolute', 'architecture': 'aarch64',
    'libc': 'glibc', 'upstreamVersion': '2.1.1',
    'upstreamSha256': '8e8e92e312b14d2c5f3a047bdc5305adcb9931ef0150cf74bf526a3741e6fb32',
    'crateSha256': '923674c1f2b1a28f69ebdc0f572672e63874e17ce0e60c96b8bfaff47a8ef4e9',
    'patchSha256': 'bf5d62a51cbe1bd5f12b8b296833eb5e4bc03bc9135c826c05304dd717a5c791',
}


def archive_tools():
    # -I excludes this directory; load only the adjacent shipped helper.
    spec = importlib.util.spec_from_file_location(
        'gnome_component_archive', Path(__file__).with_name('desktop_install_gio.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate(library, raw_manifest):
    tools = archive_tools()
    manifest = json.loads(raw_manifest.decode('utf-8'), object_pairs_hook=tools.unique_object)
    if (not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int
            or manifest['schemaVersion'] != 1):
        raise ValueError('Unsupported Glycin manifest schema')
    for key, expected in IDENTITY.items():
        if manifest.get(key) != expected:
            raise ValueError('Glycin component identity mismatch: ' + key)
    if manifest.get('outputSha256') != {LIBRARY: OUTPUT_SHA256}:
        raise ValueError('Glycin component output identity mismatch')
    if hashlib.sha256(library).hexdigest() != OUTPUT_SHA256:
        raise ValueError('Glycin component checksum mismatch')
    # ELF64 little-endian AArch64 shared object, checked in addition to its digest.
    if (len(library) < 64 or library[:6] != b'\x7fELF\x02\x01'
            or library[16:20] != b'\x03\x00\xb7\x00'):
        raise ValueError('Glycin component must be an AArch64 shared library')


def install(archive, rootfs):
    tools = archive_tools()
    files = tools.decode_tar(tools.read_archive(archive), {LIBRARY}, MANIFEST)
    validate(files[LIBRARY], files[MANIFEST])
    root = tools.open_directory(rootfs)
    try:
        # Package-managed /usr/lib is untouched; the launcher binds this copy
        # only into its own PRoot view after verifying both library and receipt.
        tools.install_one(root, LIBRARY, files[LIBRARY], 0o644)
        tools.install_one(root, RECEIPT, files[MANIFEST], 0o644)
        os.fsync(root)
    finally:
        os.close(root)


def verify_installed(rootfs):
    root = Path(rootfs).resolve(strict=True)
    paths = [root / LIBRARY, root / RECEIPT]
    for path in paths:
        if not path.resolve(strict=True).is_relative_to(root):
            raise ValueError('Glycin component escapes the container rootfs')
    validate(paths[0].read_bytes(), paths[1].read_bytes())
    return paths[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', required=True)
    parser.add_argument('--archive')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if bool(args.archive) == args.verify:
        parser.error('Choose exactly one of --archive or --verify')
    if args.archive:
        install(args.archive, args.rootfs)
    verify_installed(args.rootfs)
    print('Resolute Glycin component verified')


if __name__ == '__main__':
    main()
