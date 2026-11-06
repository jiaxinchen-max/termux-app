"""End-to-end candidate wrapping with immutable emulator and symbol inputs."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import tarfile
import tempfile
import unittest

from test_pdb_symbols import GUID, export_image, image, make_readobj

SCRIPT = Path(__file__).with_name('package-hangover-source.py')


@unittest.skipUnless(shutil.which('dpkg-deb'), 'dpkg-deb required')
class SourcePackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.original = self.root / 'original.tar'
        def deb(name, version, wine=False):
            root = self.root / (name + '-' + version)
            (root / 'DEBIAN').mkdir(parents=True)
            (root / 'DEBIAN/control').write_text(
                'Package: ' + name + '\nVersion: ' + version + '\nArchitecture: arm64\n'
                'Maintainer: Test <test@example.invalid>\nDescription: fixture\n')
            if wine:
                runtime = root / 'usr/lib/wine'
                runtime.mkdir(parents=True)
                (runtime / 'arm.dll').write_bytes(image(0xaa64))
                (runtime / 'i386.dll').write_bytes(export_image(True))
            output = self.root / (name + '_' + version + '_arm64.deb')
            subprocess.run(['dpkg-deb', '--build', '--root-owner-group', str(root), str(output)],
                           check=True, capture_output=True)
            return output
        old = deb('hangover-wine', '1.0', True)
        emulators = [deb(name, '1.0') for name in
                     ['hangover-libarm64ecfex', 'hangover-libwow64fex', 'hangover-wowbox64']]
        with tarfile.open(self.original, 'w') as archive:
            for path in [old] + emulators:
                archive.add(path, arcname=path.name)
        self.wine = deb('hangover-wine', '1.0+idap.pdb1', True)
        self.debug = deb('hangover-wine-dbgsym', '1.0+idap.pdb1')
        self.symbols = self.root / 'pdb'; self.symbols.mkdir()
        (self.symbols / 'test.pdb').write_bytes(b'symbol fixture')
        self.tool = self.root / 'pdbutil'
        self.readobj = self.root / 'readobj'
        make_readobj(self.readobj)
        self.tool.write_text('#!' + sys.executable + '\nprint(' +
                             repr('GUID: {' + str(GUID) + '}\nAge: 1') + ')\n')
        self.tool.chmod(0o755)
        self.lock = self.root / 'lock.json'
        self.lock.write_text(json.dumps({'schemaVersion': 1, 'crossDebug': 'pdb',
                                        'debianRevision': '1.0+idap.pdb1', 'sourceDateEpoch': 0,
                                        'originalComponent': {'sha256': self.sha(self.original)}}))

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def invoke(self, out):
        return subprocess.run([sys.executable, str(SCRIPT), '--original', str(self.original),
                               '--wine-deb', str(self.wine), '--dbgsym-deb', str(self.debug),
                               '--pdb-root', str(self.symbols), '--pdbutil', str(self.tool),
                               '--readobj', str(self.readobj),
                               '--lock', str(self.lock), '--output', str(out)],
                              capture_output=True, text=True)

    def test_candidate_reuses_original_emulators_and_keeps_symbols_separate(self):
        outputs = [self.root / 'one', self.root / 'two']
        for out in outputs:
            result = self.invoke(out)
            self.assertEqual(result.returncode, 0, result.stderr)
        reports = [json.loads((p / 'candidate-manifest.json').read_text()) for p in outputs]
        self.assertEqual(reports[0]['artifacts'], reports[1]['artifacts'])
        self.assertEqual(len(reports[0]['reused']), 3)
        with tarfile.open(outputs[0] / 'hangover-runtime-source-candidate.tar') as archive:
            names = archive.getnames()
            self.assertEqual(len(names), 4)
            self.assertFalse(any('dbgsym' in n or n.endswith('.pdb') for n in names))
            for item in reports[0]['reused']:
                self.assertEqual(hashlib.sha256(archive.extractfile(item['name']).read()).hexdigest(),
                                 item['sha256'])

    def test_wrong_original_digest_does_not_publish(self):
        self.original.write_bytes(self.original.read_bytes() + b'changed')
        out = self.root / 'failed'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(out.exists())

    def test_missing_symbols_do_not_publish(self):
        (self.symbols / 'test.pdb').unlink()
        out = self.root / 'missing-symbols'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(out.exists())

    def rebuild_candidate(self):
        root = self.root / 'hangover-wine-1.0+idap.pdb1'
        subprocess.run(['dpkg-deb', '--build', '--root-owner-group', str(root), str(self.wine)],
                       check=True, capture_output=True)

    def test_added_runtime_module_is_rejected(self):
        root = self.root / 'hangover-wine-1.0+idap.pdb1'
        (root / 'usr/lib/wine/unplanned.dll').write_bytes(image(0xaa64))
        self.rebuild_candidate()
        out = self.root / 'added-module'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('paths added outside baseline', result.stderr)
        self.assertFalse(out.exists())

    def test_new_dependency_is_rejected(self):
        path = self.root / 'hangover-wine-1.0+idap.pdb1/DEBIAN/control'
        path.write_text(path.read_text() + 'Depends: unexpected-runtime-library\n')
        self.rebuild_candidate()
        out = self.root / 'dependency-drift'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('package contract changed: Depends', result.stderr)
        self.assertFalse(out.exists())

    def test_runtime_permissions_are_preserved(self):
        path = self.root / 'hangover-wine-1.0+idap.pdb1/usr/lib/wine/arm.dll'
        path.chmod(0o700)
        self.rebuild_candidate()
        out = self.root / 'changed-mode'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('runtime paths removed or changed', result.stderr)
        self.assertFalse(out.exists())

    def test_added_maintainer_script_is_rejected_without_executing_it(self):
        path = self.root / 'hangover-wine-1.0+idap.pdb1/DEBIAN/postinst'
        marker = self.root / 'must-not-exist'
        path.write_text('#!/bin/sh\ntouch ' + str(marker) + '\n')
        path.chmod(0o755)
        self.rebuild_candidate()
        out = self.root / 'maintainer-drift'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('maintenance metadata changed: postinst', result.stderr)
        self.assertFalse(out.exists())
        self.assertFalse(marker.exists())

    def test_changed_export_name_is_rejected_before_publication(self):
        root = self.root / 'hangover-wine-1.0+idap.pdb1'
        path = root / 'usr/lib/wine/i386.dll'
        data = bytearray(path.read_bytes())
        data[800:806] = b'OtherX'
        path.write_bytes(data)
        subprocess.run(['dpkg-deb', '--build', '--root-owner-group', str(root), str(self.wine)],
                       check=True, capture_output=True)
        out = self.root / 'changed-exports'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('export capability changed', result.stderr)
        self.assertFalse(out.exists())

    def test_recompiled_export_address_can_move(self):
        root = self.root / 'hangover-wine-1.0+idap.pdb1'
        path = root / 'usr/lib/wine/i386.dll'
        data = bytearray(path.read_bytes())
        struct.pack_into('<I', data, 680, 0x1004)
        path.write_bytes(data)
        subprocess.run(['dpkg-deb', '--build', '--root-owner-group', str(root), str(self.wine)],
                       check=True, capture_output=True)
        result = self.invoke(self.root / 'moved-address')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_removed_runtime_module_does_not_publish(self):
        root = self.root / 'hangover-wine-1.0+idap.pdb1'
        (root / 'usr/lib/wine/i386.dll').unlink()
        subprocess.run(['dpkg-deb', '--build', '--root-owner-group', str(root), str(self.wine)],
                       check=True, capture_output=True)
        out = self.root / 'missing-runtime'
        result = self.invoke(out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('runtime paths removed', result.stderr)
        self.assertFalse(out.exists())


if __name__ == '__main__':
    unittest.main()
