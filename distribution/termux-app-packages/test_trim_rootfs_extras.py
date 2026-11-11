#!/usr/bin/env python3
"""Exercise archive cleanup and failure isolation using real tar/zstd streams."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('extras', Path(__file__).with_name('trim-rootfs-extras.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
image = m.image
ZSTD = shutil.which('zstd')


@unittest.skipUnless(ZSTD, 'zstd executable required')
class Cleanup(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='rootfs extras $ ')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.archive = self.base / 'rootfs.tar.zst'
        self.descriptor = self.base / 'source.json'
        self.destination = self.base / 'candidate'
        self.files = {
            'etc/wine-runtime.properties': b'recipeVersion=12\nruntimeBackend=rootfs_proot\n',
            'usr/bin/wine': b'ELF-Wine',
            'usr/bin/perl': b'ELF-Perl',
            'usr/lib/aarch64-linux-gnu/perl/5.40/CORE/test.h': b'development header',
            'usr/share/perl/5.40/tutorial.pod': b'pure documentation',
            'usr/lib/aarch64-linux-gnu/perl/5.40/main.pod': b'architecture documentation',
            'usr/share/locale/fr/LC_MESSAGES/coreutils.mo': b'French translation',
        }
        for name in (
            'usr/share/locale/zh_CN/LC_MESSAGES/coreutils.mo',
            'usr/share/locale/en_GB/LC_MESSAGES/coreutils.mo',
            'usr/lib/locale/locale-archive', 'usr/lib/aarch64-linux-gnu/gconv/GBK.so',
            'usr/lib/wine/nls/c_936.nls', 'usr/share/perl/5.40/strict.pm',
            'usr/lib/aarch64-linux-gnu/libperl.so.5.40',
            'usr/lib/aarch64-linux-gnu/libLLVM.so.19', 'usr/lib/aarch64-linux-gnu/libgallium.so',
            'usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
            'usr/lib/wine/aarch64-windows/ntdll.dll', 'usr/lib/wine/i386-windows/ntdll.dll',
            'usr/lib/wine/fex.dll', 'usr/lib/wine/box.dll',
        ):
            self.files[name] = name.encode()
        self.links = {'bin': 'usr/bin', 'usr/share/locale/fr/LC_TIME/coreutils.mo': '../LC_MESSAGES/coreutils.mo'}
        self.protected = {'usr/bin/wine': image.sha_bytes(self.files['usr/bin/wine'])}
        self.runtime = dict(self.protected)

    def write_archive(self, mutate=None, trailing=b''):
        records = []
        directories = set()
        for name in list(self.files) + list(self.links):
            directories.update(str(p) for p in Path(name).parents if str(p) != '.')
        for name in sorted(directories):
            records.append({'path': name, 'type': 'directory', 'mode': 0o755})
        for name, data in sorted(self.files.items()):
            records.append({'path': name, 'type': 'file', 'mode': 0o644,
                            'bytes': len(data), 'sha256': image.sha_bytes(data)})
        for name, link in sorted(self.links.items()):
            records.append({'path': name, 'type': 'symlink', 'mode': 0o777, 'link': link})
        manifest = {'schemaVersion': 1, 'imageId': image.IMAGE_ID,
                    'sourceComponentSha256': image.SOURCE_SHA,
                    'entries': records, 'protectedFiles': self.protected, 'runtimeFiles': self.runtime,
                    'sourceTrimReceipt': {'state': 'Verified', 'historical': 'unchanged'}}
        data = image.raw_json(manifest)
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w', format=tarfile.PAX_FORMAT) as tar:
            header = tarfile.TarInfo(image.MANIFEST)
            header.size = len(data)
            tar.addfile(header, io.BytesIO(data))
            for record in records:
                info = tarfile.TarInfo(record['path'])
                info.mode = record['mode']
                payload = self.files.get(record['path'])
                if record['type'] == 'directory':
                    info.type = tarfile.DIRTYPE
                elif record['type'] == 'symlink':
                    info.type = tarfile.SYMTYPE
                    info.linkname = record['link']
                else:
                    info.size = len(payload)
                members = [(info, payload)] if mutate is None else mutate(info, payload)
                for member, content in members:
                    tar.addfile(member, io.BytesIO(content) if content is not None and member.isfile() else None)
        self.archive.write_bytes(subprocess.check_output([ZSTD, '-q', '-6', '-c'], input=stream.getvalue() + trailing))
        descriptor = {'schemaVersion': 1, 'imageId': image.IMAGE_ID, 'sourceComponentSha256': image.SOURCE_SHA,
                      'file': self.archive.name, 'manifestSha256': image.sha_bytes(data),
                      'sha256': image.digest(self.archive), 'bytes': self.archive.stat().st_size,
                      'entryCount': len(records), 'expandedBytes': sum(len(v) for v in self.files.values())}
        self.descriptor.write_bytes(image.raw_json(descriptor))
        return manifest, descriptor

    def run_cleanup(self):
        return m.repack(self.archive, self.descriptor, self.destination, ZSTD)

    def assert_failed_cleanly(self, pattern):
        before = self.archive.read_bytes()
        with self.assertRaisesRegex(ValueError, pattern):
            self.run_cleanup()
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.base.glob('.rootfs-cleanup-*')))
        self.assertEqual(before, self.archive.read_bytes())

    def test_real_repack_restores_exact_remaining_runtime(self):
        original, _ = self.write_archive()
        before = self.archive.read_bytes()
        report = self.run_cleanup()
        self.assertEqual(5, report['removedFiles'])
        self.assertEqual(4, sum(e['type'] == 'file' for e in report['deletions']))
        self.assertEqual('Pending', report['runtimeAcceptance'])
        descriptor = self.destination / 'image.json'
        root = self.base / 'restored'
        image.restore(self.destination / self.archive.name, root, descriptor, 'a' * 64, ZSTD)
        self.assertEqual('Verified', image.verify(root, descriptor, 'a' * 64, full=True)['state'])
        removed = {r['path'] for r in report['deletions']}
        for name, data in self.files.items():
            if name in removed:
                self.assertFalse((root / name).exists())
            elif name != 'etc/wine-runtime.properties':
                self.assertEqual(data, (root / name).read_bytes())
        result = json.loads((root / image.MANIFEST).read_text())
        self.assertEqual(original['sourceTrimReceipt'], result['sourceTrimReceipt'])
        self.assertEqual(original['protectedFiles'], result['protectedFiles'])
        self.assertEqual([e for e in original['entries'] if e['path'] not in removed], result['entries'])
        self.assertEqual(before, self.archive.read_bytes())

    def test_legal_and_surviving_alias_routes_are_kept(self):
        self.files['usr/share/perl/5.40/AUTHORS.pod'] = b'legal authors'
        self.files['usr/share/perl/5.40/copyright/license.pod'] = b'legal copyright'
        self.links['usr/share/perl-view'] = '/usr/share/perl/5.40'
        self.links['usr/share/consumer'] = 'perl-view/tutorial.pod'
        self.links['.l2s/runtime-header'] = '/usr/lib/aarch64-linux-gnu/perl/5.40/CORE/test.h'
        self.links['usr/share/translation-consumer'] = '/usr/share/locale/fr/LC_TIME/coreutils.mo'
        manifest, _ = self.write_archive()
        deletions, retained = m.plan(manifest)
        targets = {e['path'] for e in deletions}
        self.assertEqual({'usr/lib/aarch64-linux-gnu/perl/5.40/main.pod'}, targets)
        self.assertEqual(6, len(retained))
        self.assertEqual(1, self.run_cleanup()['removedFiles'])

    def test_protected_file_aborts_and_protected_alias_retains_dependency(self):
        header = 'usr/lib/aarch64-linux-gnu/perl/5.40/CORE/test.h'
        self.protected[header] = image.sha_bytes(self.files[header])
        self.write_archive()
        self.assert_failed_cleanly('cleanup_protected_deletion')
        del self.protected[header]
        self.links['usr/share/guard'] = '/' + header
        self.protected['usr/share/guard'] = image.sha_bytes(self.files[header])
        self.write_archive()
        report = self.run_cleanup()
        self.assertNotIn(header, {e['path'] for e in report['deletions']})
        self.assertIn(header, {e['path'] for e in report['retainedByPolicy']})

    def test_manifest_guard_digest_mismatch_aborts(self):
        self.runtime['usr/bin/wine'] = 'f' * 64
        self.write_archive()
        self.assert_failed_cleanly('cleanup_protected_record')

    def test_invalid_archive_members_abort_without_candidate(self):
        target = 'usr/share/perl/5.40/tutorial.pod'
        def change(kind):
            def mutate(info, data):
                if info.name != target:
                    return [(info, data)]
                info = copy.copy(info)
                if kind == 'content':
                    data = b'!' * len(data)
                elif kind == 'mode':
                    info.mode = 0o777
                elif kind == 'duplicate':
                    return [(info, data), (info, data)]
                elif kind == 'missing':
                    return []
                elif kind == 'escape':
                    info.name = '../escape'
                elif kind == 'hardlink':
                    info.type = tarfile.LNKTYPE
                    info.linkname = 'usr/bin/wine'
                return [(info, data)]
            return mutate
        for kind, error in [('content', 'image_member_digest'), ('mode', 'image_member_mode'),
                            ('duplicate', 'image_unexpected'), ('missing', 'image_members_missing'),
                            ('escape', 'image_member_path'), ('hardlink', 'image_member_mode')]:
            with self.subTest(kind=kind):
                self.write_archive(mutate=change(kind))
                self.assert_failed_cleanly(error)

    def test_extra_payload_and_bad_compressed_digest_abort(self):
        self.write_archive(trailing=b'payload')
        self.assert_failed_cleanly('image_trailing_data')
        self.write_archive()
        with self.archive.open('ab') as stream:
            stream.write(b'corrupt')
        self.assert_failed_cleanly('image_archive_digest')

    def test_existing_output_is_not_modified(self):
        self.write_archive()
        self.destination.mkdir()
        marker = self.destination / 'keep'
        marker.write_bytes(b'existing output')
        with self.assertRaisesRegex(ValueError, 'cleanup_output_exists'):
            self.run_cleanup()
        self.assertEqual(b'existing output', marker.read_bytes())

    def test_decoder_failure_removes_partial_output(self):
        self.write_archive()
        with self.archive.open('ab') as stream:
            stream.write(b'invalid zstd frame')
        descriptor = json.loads(self.descriptor.read_text())
        descriptor.update(bytes=self.archive.stat().st_size, sha256=image.digest(self.archive))
        self.descriptor.write_bytes(image.raw_json(descriptor))
        self.assert_failed_cleanly('image_decoder_failed')

    def test_compressor_failure_removes_partial_output(self):
        self.write_archive()
        wrapper = self.base / 'failing-zstd'
        wrapper.write_text('#!/bin/sh\nif [ "$1" = "-d" ]; then exec "' + ZSTD +
                           '" "$@"; else exit 18; fi\n')
        wrapper.chmod(0o755)
        before = self.archive.read_bytes()
        with self.assertRaises((ValueError, BrokenPipeError)):
            m.repack(self.archive, self.descriptor, self.destination, str(wrapper))
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.base.glob('.rootfs-cleanup-*')))
        self.assertEqual(before, self.archive.read_bytes())


if __name__ == '__main__':
    unittest.main()
