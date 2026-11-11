#!/usr/bin/env python3
"""Offline-only, lossless runtime-preserving cleanup of an existing image archive."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

import prebuilt_rootfs as image

spec = importlib.util.spec_from_file_location('rootfs_trim', Path(__file__).with_name('minimize-rootfs.py'))
trim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trim)
POLICY = 'perl-development-and-non-zh-en-gettext-1'


def reason(name):
    if re.fullmatch(r'usr/lib/(?:aarch64-linux-gnu/)?perl/[^/]+/(?:[^/]+/)*[^/]+\.h', name):
        return 'perl_headers'
    if re.fullmatch(r'usr/(?:lib|share)/(?:aarch64-linux-gnu/)?perl[^/]*/(?:[^/]+/)*[^/]+\.pod', name):
        return 'perl_pod'
    match = re.fullmatch(r'usr/share/locale/([^/]+)/LC_MESSAGES/[^/]+\.mo', name)
    if match and not match[1].lower().startswith(('zh', 'en')):
        return 'non_zh_en_gettext'
    return None


def plan(manifest):
    snapshot = {r['path']: r for r in manifest['entries']}
    selected = {p for p, r in snapshot.items() if r['type'] == 'file' and reason(p)}
    for path, record in snapshot.items():
        match = re.fullmatch(r'usr/share/locale/([^/]+)/LC_TIME/([^/]+\.mo)', path)
        if record['type'] == 'symlink' and match:
            _, end = trim.link_route(snapshot, path)
            if end in selected and end == 'usr/share/locale/' + match[1] + '/LC_MESSAGES/' + match[2]:
                selected.add(path)
    retained = trim.resource_retained(snapshot, selected)
    targets = selected.difference(retained)
    for group in ('protectedFiles', 'runtimeFiles'):
        for path, digest in manifest[group].items():
            image.relative(path)
            route, end = trim.link_route(snapshot, path)
            image.require(not targets.intersection(route), 'cleanup_protected_deletion:' + path)
            record = snapshot.get(end, {})
            image.require(record.get('type') == 'file' and record.get('sha256') == digest,
                          'cleanup_protected_record:' + path)
    trim.check_surviving_links(snapshot, targets)
    # Validate the exported hierarchy without ever following a link on the host.
    for name in snapshot:
        for parent in Path(name).parents:
            if str(parent) != '.':
                image.require(snapshot.get(str(parent), {}).get('type') == 'directory',
                              'cleanup_non_directory_parent:' + name)
    deletions = [dict(r, reason=reason(r['path']) or 'non_zh_en_gettext_alias')
                 for r in manifest['entries'] if r['path'] in targets]
    return deletions, [{'path': p, 'reason': why} for p, why in sorted(retained.items())]


def stop(proc):
    if proc.poll() is None:
        proc.terminate()
        proc.wait()
    if proc.stdout:
        proc.stdout.close()
    if proc.stdin:
        proc.stdin.close()


def first_manifest(tar, descriptor):
    member = tar.next()
    image.require(member is not None and member.name == image.MANIFEST and member.isfile()
                  and 0 < member.size <= image.MAX_MANIFEST, 'image_manifest_first')
    return image.validate_manifest(tar.extractfile(member).read(image.MAX_MANIFEST + 1), descriptor)


def read_manifest(archive, descriptor, zstd):
    proc = subprocess.Popen([zstd, '-d', '-c', '--', str(archive)], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL)
    try:
        with tarfile.open(fileobj=proc.stdout, mode='r|') as tar:
            return first_manifest(tar, descriptor)
    finally:
        stop(proc)


class HashedReader:
    def __init__(self, source):
        self.source = source
        self.hash = hashlib.sha256()
        self.count = 0

    def read(self, size):
        block = self.source.read(size)
        self.hash.update(block)
        self.count += len(block)
        return block


def scan(archive, descriptor, zstd, output=None, removed=frozenset()):
    """Check every source byte, including members omitted from the new archive."""
    proc = subprocess.Popen([zstd, '-d', '-c', '--', str(archive)], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=proc.stdout, mode='r|') as tar:
            manifest = first_manifest(tar, descriptor)
            expected = {r['path']: r for r in manifest['entries']}
            seen = set()
            while True:
                member = tar.next()
                if member is None:
                    break
                name = image.relative(member.name.rstrip('/') if member.isdir() else member.name)
                image.require(name in expected and name not in seen, 'image_unexpected_or_duplicate_member')
                seen.add(name)
                record = expected[name]
                image.require(member.mode == record['mode'] and not member.islnk(), 'image_member_mode_or_hardlink')
                if member.isdir():
                    image.require(record['type'] == 'directory', 'image_directory_type')
                elif member.issym():
                    image.require(record['type'] == 'symlink' and member.linkname == record['link'],
                                  'image_symlink_type')
                else:
                    image.require(member.isfile() and record['type'] == 'file'
                                  and member.size == record['bytes'], 'image_file_type_or_size')
                if record['type'] == 'file':
                    reader = HashedReader(tar.extractfile(member))
                    if output is not None and name not in removed:
                        output.addfile(member, reader)
                    while reader.read(1024 * 1024):
                        pass
                    image.require(reader.count == record['bytes'] and reader.hash.hexdigest() == record['sha256'],
                                  'image_member_digest:' + name)
                elif output is not None and name not in removed:
                    output.addfile(member)
            image.require(seen == set(expected), 'image_members_missing')
            trailing_bytes = 0
            while True:
                trailing = tar.fileobj.read(65536)
                if not trailing:
                    break
                trailing_bytes += len(trailing)
                image.require(trailing_bytes <= 2 * tarfile.RECORDSIZE and not trailing.strip(b'\x00'),
                              'image_trailing_data')
        image.require(not proc.stdout.read(1), 'image_trailing_data')
        image.require(proc.wait() == 0, 'image_decoder_failed')
        return manifest
    finally:
        stop(proc)


def repack(archive, descriptor_path, destination, zstd='zstd'):
    descriptor = image.descriptor_read(descriptor_path)
    image.require(not archive.is_symlink() and archive.stat().st_size == descriptor['bytes']
                  and image.digest(archive) == descriptor['sha256'], 'image_archive_digest')
    image.require(destination.parent.resolve() == destination.parent and not destination.exists()
                  and not destination.is_symlink(), 'cleanup_output_exists_or_invalid')
    manifest = read_manifest(archive, descriptor, zstd)
    deletions, retained = plan(manifest)
    image.require(deletions, 'cleanup_no_candidates')
    targets = {r['path'] for r in deletions}
    new_manifest = dict(manifest, entries=[r for r in manifest['entries'] if r['path'] not in targets])
    data = image.raw_json(new_manifest)
    new_descriptor = dict(descriptor, entryCount=len(new_manifest['entries']),
                          expandedBytes=sum(r.get('bytes', 0) for r in new_manifest['entries']),
                          manifestSha256=image.sha_bytes(data))
    image.require(len(data) <= image.MAX_MANIFEST, 'image_manifest_limit')
    image.validate_manifest(data, new_descriptor)
    with tempfile.TemporaryDirectory(prefix='.rootfs-cleanup-', dir=destination.parent) as temporary:
        stage = Path(temporary) / 'candidate'
        stage.mkdir()
        output = stage / descriptor['file']
        with output.open('xb') as dest:
            proc = subprocess.Popen([zstd, '-T2', '-6', '-c'], stdin=subprocess.PIPE, stdout=dest)
            try:
                with tarfile.open(fileobj=proc.stdin, mode='w|', format=tarfile.PAX_FORMAT) as tar:
                    header = tarfile.TarInfo(image.MANIFEST)
                    header.mode = 0o644
                    header.size = len(data)
                    tar.addfile(header, io.BytesIO(data))
                    scan(archive, descriptor, zstd, output=tar, removed=targets)
                proc.stdin.close()
                image.require(proc.wait() == 0, 'image_compression_failed')
            finally:
                stop(proc)
        new_descriptor.update(bytes=output.stat().st_size, sha256=image.digest(output))
        image.require(scan(output, new_descriptor, zstd) == new_manifest, 'cleanup_output_mismatch')
        plan(new_manifest)  # Recheck the remaining guard and link topology.
        image.require(image.digest(archive) == descriptor['sha256'], 'cleanup_input_changed')
        groups = {}
        for entry in deletions:
            group = groups.setdefault(entry['reason'], {'files': 0, 'bytes': 0})
            group['files'] += 1
            group['bytes'] += entry.get('bytes', 0)
        report = {'schemaVersion': 1, 'policy': POLICY, 'state': 'OfflineVerified',
                  'runtimeAcceptance': 'Pending', 'source': descriptor, 'candidate': new_descriptor,
                  'deletions': deletions, 'retainedByPolicy': retained, 'groups': groups,
                  'removedFiles': len(deletions),
                  'expandedBytesSaved': descriptor['expandedBytes'] - new_descriptor['expandedBytes'],
                  'archiveBytesSaved': descriptor['bytes'] - new_descriptor['bytes'],
                  'protectedFilesUnchanged': len(manifest['protectedFiles']),
                  'runtimeFilesUnchanged': len(manifest['runtimeFiles']),
                  'retainedEntriesUnchanged': len(new_manifest['entries']),
                  'sourceUnchanged': True, 'allSourceAndCandidateMembersVerified': True,
                  'compression': ['-T2', '-6']}
        (stage / 'image.json').write_bytes(image.raw_json(new_descriptor) + b'\n')
        (stage / 'cleanup-report.json').write_bytes(image.raw_json(report) + b'\n')
        image.require(not destination.exists() and not destination.is_symlink(), 'cleanup_output_exists_or_invalid')
        stage.rename(destination)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--descriptor', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--zstd', default='zstd')
    args = parser.parse_args()
    report = repack(args.archive.absolute(), args.descriptor.absolute(), args.output_dir.absolute(), args.zstd)
    print(json.dumps({key: report[key] for key in ('state', 'removedFiles', 'expandedBytesSaved',
                                                 'archiveBytesSaved', 'runtimeAcceptance')}))


if __name__ == '__main__':
    main()
