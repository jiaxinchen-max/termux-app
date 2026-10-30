#!/usr/bin/env python3
"""Filesystem safety regression fixtures only; no device or native compilation."""
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_install_gio.py'
spec = importlib.util.spec_from_file_location('desktop_install_gio_test', SCRIPT)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


@unittest.skipUnless(hasattr(os, 'O_PATH'), 'Linux O_PATH is required; run the full suite on Linux')
class GioInstallTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='gio-install-')
        self.base = Path(self.folder.name).resolve()
        self.root = self.base / 'rootfs'; self.root.mkdir()
        self.payloads = {name: ('test payload:' + name).encode() for name in installer.PAYLOADS}
        self.manifest = dict(schemaVersion=1, architecture='aarch64', libc='glibc', codename='trixie',
            bridgeVersion='0.4.1', outputSha256={name: hashlib.sha256(data).hexdigest() for name, data in self.payloads.items()})
        self.archive = self.base / 'gio.tar.gz'

    def tearDown(self):
        self.folder.cleanup()

    def write(self, manifest=None, extra=None):
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode='w', format=tarfile.USTAR_FORMAT) as tar:
            for name, data in dict(self.payloads, **{installer.MANIFEST: json.dumps(manifest or self.manifest).encode()}).items():
                info = tarfile.TarInfo(name); info.size = len(data); info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
            if extra is not None:
                tar.addfile(extra, io.BytesIO(b'X') if extra.size else None)
        self.archive.write_bytes(gzip.compress(out.getvalue(), mtime=0))

    def install(self):
        installer.install(str(self.archive), str(self.root), 'trixie', '0.4.1')

    def test_valid_repeat_install_has_exact_content_and_fixed_modes(self):
        self.write(); self.install(); self.install()
        for name, mode in installer.PAYLOADS.items():
            self.assertEqual(self.payloads[name], (self.root / name).read_bytes())
            self.assertEqual(mode, (self.root / name).stat().st_mode & 0o777)
        self.assertFalse(list(self.root.rglob('.tusb-gio-*.tmp')))

    def test_ancestor_read_denied_but_path_search_and_final_fsync_are_allowed(self):
        original_open = os.open
        calls = []
        def search_only_ancestors(path, flags, *args, **kwargs):
            if flags & os.O_DIRECTORY:
                calls.append((path, flags))
                if path != '.' and not flags & os.O_PATH:
                    raise PermissionError('Android policy denies ancestor directory reads')
            return original_open(path, flags, *args, **kwargs)
        with patch.object(installer.os, 'open', side_effect=search_only_ancestors):
            directory = installer.open_directory(str(self.root))
            try:
                self.assertEqual(self.root.stat().st_ino, os.fstat(directory).st_ino)
                os.fsync(directory)
            finally:
                os.close(directory)
        self.assertEqual('/', calls[0][0])
        self.assertTrue(all(flags & os.O_PATH and flags & os.O_NOFOLLOW for _, flags in calls[:-1]))
        self.assertEqual('.', calls[-1][0])
        self.assertFalse(calls[-1][1] & os.O_PATH)

    def test_symlink_in_rootfs_absolute_ancestry_is_rejected(self):
        alias = self.base / 'root-alias'; alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError): installer.open_directory(str(alias))

    def test_missing_opath_is_an_explicit_error_without_another_open_strategy(self):
        with patch.object(installer, 'DIRECTORY_PATH_FLAGS', None), patch.object(installer.os, 'open') as opened:
            with self.assertRaisesRegex(RuntimeError, 'Linux O_PATH'):
                installer.open_directory(str(self.root))
            opened.assert_not_called()

    def test_extra_paths_links_and_duplicates_are_rejected_before_any_install(self):
        for name, kind in [('../escape', tarfile.REGTYPE),
                           ('/absolute', tarfile.REGTYPE),
                           ('usr/local/alias', tarfile.SYMTYPE),
                           (next(iter(installer.PAYLOADS)), tarfile.REGTYPE)]:
            with self.subTest(name=name):
                info = tarfile.TarInfo(name); info.type = kind; info.linkname = '/outside' if kind == tarfile.SYMTYPE else ''
                self.write(extra=info)
                with self.assertRaises(ValueError): self.install()
                self.assertFalse((self.root / 'usr').exists())

    def test_payload_hash_and_architecture_mismatch_are_rejected(self):
        for changed in [dict(self.manifest, architecture='x86_64'),
                        dict(self.manifest, outputSha256={name: '0' * 64 for name in self.payloads}),
                        dict(self.manifest, schemaVersion=True)]:
            self.write(manifest=changed)
            with self.assertRaises(ValueError): self.install()
            self.assertFalse((self.root / 'usr').exists())

    def test_truncated_or_concatenated_gzip_and_trailing_tar_data_are_rejected(self):
        self.write(); original = self.archive.read_bytes()
        for damaged in [original[:-1], original + gzip.compress(b'junk'),
                        gzip.compress(gzip.decompress(original) + b'not-padding')]:
            self.archive.write_bytes(damaged)
            with self.assertRaises((ValueError, OSError)): self.install()
            self.assertFalse((self.root / 'usr').exists())

    def test_rootfs_parent_and_target_symlinks_never_escape(self):
        self.write(); outside = self.base / 'outside'; outside.mkdir()
        (outside / 'keep').write_bytes(b'original')
        (self.root / 'usr').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError): self.install()
        self.assertEqual(b'original', (outside / 'keep').read_bytes())
        self.assertEqual(['keep'], [path.name for path in outside.iterdir()])
        (self.root / 'usr').unlink()
        destination = self.root / next(iter(installer.PAYLOADS))
        destination.parent.mkdir(parents=True); destination.symlink_to(outside / 'keep')
        with self.assertRaises(ValueError): self.install()
        self.assertTrue(destination.is_symlink()); self.assertEqual(b'original', (outside / 'keep').read_bytes())

    def test_archive_symlink_and_oversized_expansion_are_rejected(self):
        self.write(); real = self.base / 'real.gz'; self.archive.rename(real); self.archive.symlink_to(real)
        with self.assertRaises(OSError): self.install()
        self.archive.unlink()
        self.archive.write_bytes(gzip.compress(bytes(installer.LIMIT + 1), mtime=0))
        with self.assertRaisesRegex(ValueError, 'exceeds'): self.install()
        self.assertFalse((self.root / 'usr').exists())

    def test_replace_failure_preserves_old_target_and_cleans_only_owned_temporary(self):
        self.write(); target = self.root / next(iter(installer.PAYLOADS))
        target.parent.mkdir(parents=True); target.write_bytes(b'old')
        with patch.object(installer.os, 'replace', side_effect=OSError('replace failed')):
            with self.assertRaisesRegex(OSError, 'replace failed'): self.install()
        self.assertEqual(b'old', target.read_bytes())
        self.assertFalse(list(self.root.rglob('.tusb-gio-*.tmp')))


if __name__ == '__main__':
    unittest.main()
