#!/usr/bin/env python3
"""Package the pinned Hangover input with its Ubuntu-built winedmo Unix module.

All PE images and emulator packages must remain byte-identical. This produces
unpublished candidates and provenance, not a runtime-compatibility certificate.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ORIGINAL_SHA = 'ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1'
VERSION = '11.16~ubuntu26.04+idap.pdb2.1'
MODULE = 'usr/lib/wine/aarch64-unix/winedmo.so'


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def command(*args):
    return subprocess.check_output([str(v) for v in args], text=True)


def needed(path, readelf):
    require('AArch64' in command(readelf, '-h', path), 'module_machine')
    return set(re.findall(r'Shared library: \[([^\]]+)\]', command(readelf, '-d', path)))


def call_tables(path, readelf):
    tables = {}
    for line in command(readelf, '--dyn-syms', '--wide', path).splitlines():
        fields = line.split()
        if len(fields) == 8 and fields[-1].startswith('__wine_unix_call_'):
            require(fields[3:6] == ['OBJECT', 'GLOBAL', 'DEFAULT'] and fields[6] != 'UND',
                    'call_table_type')
            tables[fields[-1]] = int(fields[2])
    require(tables == {'__wine_unix_call_funcs': 72, '__wine_unix_call_wow64_funcs': 72},
            'unix_call_table_abi')
    return tables


def files(root):
    return {str(p.relative_to(root)): ('link:' + os.readlink(p) if p.is_symlink() else sha(p))
            for p in root.rglob('*') if p.is_file() or p.is_symlink()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('original', 'module', 'build-evidence', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--readelf', default='readelf')
    parser.add_argument('--objcopy', default='aarch64-linux-gnu-objcopy')
    args = parser.parse_args()
    require(sha(args.original) == ORIGINAL_SHA, 'original_digest')
    require(not args.output.exists() and not args.output.is_symlink(), 'output_exists')
    proof = json.loads(args.build_evidence.read_text())
    require(proof['sourceCommit'] == '3ea3a14941b6a122b892c84141ce9662c008081e' and
            proof['moduleSha256'] == sha(args.module) and
            proof['buildExitCode'] == 0 and proof['ubuntuRelease'] == '26.04', 'build_proof')
    args.output.mkdir(parents=True)
    original = args.output / 'original'
    original.mkdir()
    with tarfile.open(args.original) as archive:
        names = set()
        for member in archive:
            require(member.isfile() and (member.name.endswith('.deb') or member.name == 'dxvk-v2.7.1.tar.gz') and
                    re.fullmatch(r'[A-Za-z0-9._+~:-]+', member.name) and member.name not in names,
                    'original_member')
            names.add(member.name)
            (original / member.name).write_bytes(archive.extractfile(member).read())
    debs = list(original.glob('hangover-wine_*.deb'))
    require(len(debs) == 1, 'wine_deb_count')
    root = args.output / 'package'
    subprocess.run(['dpkg-deb', '-R', str(debs[0]), str(root)], check=True)
    before = files(root)
    old_module = root / MODULE
    old_needed = {'ntdll.so', 'libavcodec.so.61', 'libavformat.so.61', 'libavutil.so.59', 'libc.so.6'}
    new_needed = {'ntdll.so', 'libavcodec.so.62', 'libavformat.so.62', 'libavutil.so.60', 'libc.so.6'}
    require(needed(old_module, args.readelf) == old_needed, 'unexpected_original_edges')
    require(needed(args.module, args.readelf) == new_needed, 'unexpected_ubuntu_edges')
    require(call_tables(old_module, args.readelf) == call_tables(args.module, args.readelf), 'call_abi')
    debug = args.output / 'winedmo.so.debug'
    subprocess.run([args.objcopy, '--only-keep-debug', str(args.module), str(debug)], check=True)
    shutil.copyfile(args.module, old_module)
    subprocess.run([args.objcopy, '--strip-debug', str(old_module)], check=True)
    os.chmod(old_module, 0o644)
    control = root / 'DEBIAN/control'
    text = control.read_text()
    text = text.replace('Version: 11.16~trixie+idap.pdb2\n', 'Version: ' + VERSION + '\n')
    for old, new in [('libavcodec61', 'libavcodec62'), ('libavformat61', 'libavformat62'),
                     ('libavutil59', 'libavutil60')]:
        require(old + ' (>= 7:7.1.5)' in text, 'original_dependency_missing')
        text = text.replace(old + ' (>= 7:7.1.5)', new + ' (>= 7:8.0.1)')
    control.write_text(text)
    sums = root / 'DEBIAN/md5sums'
    if sums.exists():
        lines = sums.read_text().splitlines()
        sums.write_text('\n'.join(hashlib.md5(old_module.read_bytes()).hexdigest() + '  ' + MODULE
                                 if line.split()[-1] == MODULE else line for line in lines) + '\n')
    after = files(root)
    changed = sorted(name for name in before.keys() | after.keys() if before.get(name) != after.get(name))
    require(set(changed) <= {MODULE, 'DEBIAN/control', 'DEBIAN/md5sums'} and MODULE in changed,
            'unexpected_package_change')
    candidate = args.output / ('hangover-wine_' + VERSION + '_arm64.deb')
    env = dict(os.environ, SOURCE_DATE_EPOCH='1787940812', DPKG_DEB_THREADS_MAX='8')
    subprocess.run(['dpkg-deb', '--root-owner-group', '-Zzstd', '-z9', '-b', str(root), str(candidate)],
                   check=True, env=env)
    output = args.output / 'rootfs_hangover_11.16.tar'
    members = [candidate] + sorted(p for p in original.iterdir() if p not in debs)
    with tarfile.open(output, 'w') as archive:
        for p in sorted(members):
            info = tarfile.TarInfo(p.name)
            info.size, info.mode, info.mtime = p.stat().st_size, 0o644, 1787940812
            with p.open('rb') as stream:
                archive.addfile(info, stream)
    result = {'schemaVersion': 1, 'state': 'BuiltNotRuntimeQualified',
              'version': VERSION, 'originalComponentSha256': ORIGINAL_SHA,
              'sourceCommit': proof['sourceCommit'], 'buildEvidenceSha256': sha(args.build_evidence),
              'changedPackagePaths': changed, 'moduleSha256': sha(old_module),
              'file': output.name, 'bytes': output.stat().st_size, 'sha256': sha(output),
              'members': {p.name: sha(p) for p in members}}
    (args.output / 'source-lock.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
