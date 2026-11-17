#!/usr/bin/env python3
"""Install boundaries for the Ubuntu EWW source builder; no network or compiler."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('install_eww', ASSETS / 'desktop_install_eww.py')
builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)


class EwwInstallTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.dest = Path(self.temp.name) / 'bin/eww'
        self.destination = patch.object(builder, 'DEST', self.dest); self.destination.start(); self.addCleanup(self.destination.stop)

    def archive(self, name='eww-0.6.0/Cargo.toml'):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as bundle:
            entry = tarfile.TarInfo(name); entry.size = 4
            bundle.addfile(entry, io.BytesIO(b'test'))
        return data.getvalue()

    def testBuildLocksInputsAndPublishesReceiptThenSkipsNetwork(self):
        data = self.archive(); calls = []
        def run(command, **kwargs):
            calls.append((command, kwargs))
            if command[0] == 'cargo':
                built = kwargs['cwd'] / 'target/release/eww'; built.parent.mkdir(parents=True); built.write_bytes(b'candidate')
        with patch.object(builder, 'SHA256', hashlib.sha256(data).hexdigest()), patch.object(builder.urllib.request, 'urlopen', return_value=io.BytesIO(data)), patch.object(builder.subprocess, 'run', side_effect=run):
            builder.install()
            self.assertEqual(['cargo', 'build', '--locked', '--release', '--no-default-features', '--features', 'wayland'], calls[0][0])
            self.assertEqual('2', calls[0][1]['env']['CARGO_BUILD_JOBS'])
            receipt = json.loads(self.dest.with_suffix('.json').read_text())
            self.assertEqual(hashlib.sha256(b'candidate').hexdigest(), receipt['binary_sha256'])
            self.assertEqual(builder.LOCK_SHA256, receipt['lock_sha256'])
            self.assertTrue(self.dest.stat().st_mode & 0o111)
            with patch.object(builder.urllib.request, 'urlopen', side_effect=AssertionError('unexpected network')):
                builder.install()
            self.assertEqual([str(self.dest), '--version'], calls[-1][0])

    def testBadSourceHashNeverBuildsOrPublishes(self):
        with patch.object(builder.urllib.request, 'urlopen', return_value=io.BytesIO(b'changed')), patch.object(builder.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'source SHA-256'): builder.install()
            run.assert_not_called()
        self.assertFalse(self.dest.exists())

    def testArchiveTraversalIsRejectedBeforeCargo(self):
        data = self.archive('../escape')
        with patch.object(builder, 'SHA256', hashlib.sha256(data).hexdigest()), patch.object(builder.urllib.request, 'urlopen', return_value=io.BytesIO(data)), patch.object(builder.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Unsafe EWW'): builder.install()
            run.assert_not_called()

    def testBadLockNeverBuildsOrPublishes(self):
        data = self.archive()
        with patch.object(builder, 'SHA256', hashlib.sha256(data).hexdigest()), patch.object(builder, 'LOCK_SHA256', '0'*64), patch.object(builder.urllib.request, 'urlopen', return_value=io.BytesIO(data)), patch.object(builder.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Cargo.lock SHA-256'): builder.install()
            run.assert_not_called()
        self.assertFalse(self.dest.exists())


if __name__ == '__main__': unittest.main()
