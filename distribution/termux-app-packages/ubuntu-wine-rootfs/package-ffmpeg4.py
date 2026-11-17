#!/usr/bin/env python3
"""Package Ubuntu-built versioned FFmpeg libraries without replacing FFmpeg 8."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile

SOURCE_SHA = 'c73848c4ae283d9eaee7be3b276affbc3543380483555500d0dd2c9b7e1c39c3'
LIBRARIES = {'libavcodec.so.58': '58.134.100', 'libavformat.so.58': '58.76.100',
             'libavutil.so.56': '56.70.100', 'libswresample.so.3': '3.9.100',
             'libswscale.so.5': '5.9.100'}


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-root', type=Path, required=True)
    parser.add_argument('--source-archive', type=Path, required=True)
    parser.add_argument('--build-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    require(sha(args.source_archive.read_bytes()) == SOURCE_SHA, 'ffmpeg_source_digest')
    require(not args.output.exists(), 'output_exists')
    root = args.build_root.resolve(strict=True)
    release = (root / 'etc/os-release').read_text()
    require('ID=ubuntu\n' in release and 'VERSION_ID="26.04"' in release, 'ubuntu_builder_identity')
    source = root / 'work/ffmpeg-4.4.8'
    config = (source / 'config.h').read_text()
    require(all('#define CONFIG_' + name + ' 0\n' in config
                for name in ('GPL', 'NONFREE', 'VERSION3')), 'unexpected_license_configuration')
    spec = importlib.util.spec_from_file_location(
        'audit', Path(__file__).resolve().parents[1] / 'audit-rootfs-elf.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    payload, records = {}, {}
    for soname, version in LIBRARIES.items():
        filename = soname.split('.so.')[0] + '.so.' + version
        path = root / 'work/ffmpeg4-install/usr/local/lib' / filename
        require(path.is_file() and not path.is_symlink(), 'runtime_library_invalid')
        data = audit.elf(path)
        require(data['machine'] == 183 and data['class'] == 64 and data['type'] == 3
                and data['soname'] == soname and not data['rpath'] and not data['runpath'],
                'runtime_library_abi')
        require(set(data['needed']) <= set(LIBRARIES) | {'libc.so.6', 'libm.so.6',
                                                       'ld-linux-aarch64.so.1'},
                'unexpected_runtime_dependency')
        name = 'usr/local/lib/' + filename
        payload[name] = path.read_bytes()
        records[name] = dict(type='file', bytes=len(payload[name]), sha256=sha(payload[name]),
                             soname=soname, needed=data['needed'])
        name = 'usr/local/lib/' + soname
        payload[name] = filename
        records[name] = dict(type='symlink', link=filename)
    for name in ('COPYING.LGPLv2.1', 'LICENSE.md'):
        target = 'usr/local/share/doc/idap-ffmpeg4/' + name
        payload[target] = (source / name).read_bytes()
        records[target] = dict(type='file', bytes=len(payload[target]), sha256=sha(payload[target]))
    args.output.mkdir(parents=True)
    archive = args.output / 'ffmpeg4-ubuntu2604-arm64-v1.tar.xz'
    with tarfile.open(archive, 'w:xz', preset=6) as tar:
        for name, value in sorted(payload.items()):
            member = tarfile.TarInfo(name)
            member.mtime = 1787940812
            member.mode = 0o644
            if isinstance(value, str):
                member.type, member.linkname = tarfile.SYMTYPE, value
                tar.addfile(member)
            else:
                member.size = len(value)
                tar.addfile(member, io.BytesIO(value))
    lock = dict(schemaVersion=1, state='BuiltNotRuntimeQualified', version='4.4.8',
                sourceSha256=SOURCE_SHA, buildLogSha256=sha(args.build_log.read_bytes()),
                file=archive.name, bytes=archive.stat().st_size, sha256=sha(archive.read_bytes()),
                members=records)
    (args.output / 'compatibility-lock.json').write_text(json.dumps(lock, indent=2) + '\n')
    print(json.dumps({k: v for k, v in lock.items() if k != 'members'}))


if __name__ == '__main__':
    main()
