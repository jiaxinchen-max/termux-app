#!/usr/bin/env python3
"""Reproduce the locked supplement from unmodified Ubuntu ARM64 packages."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile


def require(value, reason):
    if not value:
        raise ValueError(reason)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deb-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(Path(__file__).with_name('ubuntu-media-lock.json').read_text())
    payload = {}
    for package in lock['packages']:
        matches = list(args.deb_directory.glob(package['package'] + '_*.deb'))
        require(len(matches) == 1, 'media_deb_selection')
        source = matches[0]
        require(not source.is_symlink() and source.stat().st_size == package['bytes']
                and hashlib.sha256(source.read_bytes()).hexdigest() == package['sha256'],
                'media_deb_digest')
        raw = subprocess.run(['dpkg-deb', '--fsys-tarfile', str(source)],
                             capture_output=True, check=True).stdout
        expected = {record['source']: record for record in package['files']}
        found = set()
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            for member in archive:
                name = member.name.removeprefix('./')
                if name not in expected:
                    continue
                require(name not in found, 'media_duplicate_member')
                found.add(name)
                record = expected[name]
                destination = record['destination']
                require(destination not in payload and destination in lock['members']
                        and not destination.startswith('/')
                        and all(part not in ('', '.', '..') for part in destination.split('/')),
                        'media_destination')
                if record['type'] == 'symlink':
                    require(member.issym() and member.linkname == record['link'], 'media_link')
                    payload[destination] = member.linkname
                else:
                    require(member.isfile() and member.size == record['bytes'], 'media_file')
                    data = archive.extractfile(member).read()
                    require(hashlib.sha256(data).hexdigest() == record['sha256'], 'media_file_digest')
                    payload[destination] = data
        require(found == expected.keys(), 'media_missing_members')
    require(payload.keys() == lock['members'].keys(), 'media_payload_members')
    with tarfile.open(args.output, 'x:xz') as archive:
        for name, data in sorted(payload.items()):
            member = tarfile.TarInfo(name)
            member.mode = 0o644
            if isinstance(data, str):
                member.type = tarfile.SYMTYPE
                member.linkname = data
                archive.addfile(member)
            else:
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
    require(args.output.stat().st_size == lock['bytes']
            and hashlib.sha256(args.output.read_bytes()).hexdigest() == lock['sha256'],
            'media_archive_not_reproduced')
    print(json.dumps({'file': str(args.output), 'bytes': lock['bytes'], 'sha256': lock['sha256']}))


if __name__ == '__main__':
    main()
