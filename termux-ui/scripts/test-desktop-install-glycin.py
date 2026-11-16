#!/usr/bin/env python3
"""Component integrity and installation boundaries; ELF data is an explicit fixture."""
import gzip
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
spec = importlib.util.spec_from_file_location('glycin_installer', ASSETS / 'desktop_install_glycin.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class GlycinInstallTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='glycin-install-')
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / 'rootfs'
        self.root.mkdir()
        elf = bytearray(64)
        elf[:6], elf[16:20] = b'\x7fELF\x02\x01', b'\x03\x00\xb7\x00'
        self.library = bytes(elf) + b'EXPLICIT TEST FIXTURE'
        self.digest = hashlib.sha256(self.library).hexdigest()
        pin = patch.object(installer, 'OUTPUT_SHA256', self.digest)
        pin.start()
        self.addCleanup(pin.stop)
        self.manifest = dict(installer.IDENTITY, schemaVersion=1,
                             outputSha256={installer.LIBRARY: self.digest})
        self.archive = self.base / 'component.tar.gz'

    def write(self, manifest=None, library=None, extra=None):
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            for name, data in ((installer.LIBRARY, self.library if library is None else library),
                               (installer.MANIFEST, json.dumps(self.manifest if manifest is None else manifest).encode())):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
            if extra:
                archive.addfile(extra)
        self.archive.write_bytes(gzip.compress(raw.getvalue(), mtime=0))

    def install(self):
        installer.install(str(self.archive), str(self.root))

    def test_repeat_install_verifies_and_preserves_package_managed_library(self):
        original = self.root / 'usr/lib/aarch64-linux-gnu/libglycin-2.so.0'
        original.parent.mkdir(parents=True)
        original.write_bytes(b'distro library')
        self.write()
        self.install()
        self.install()
        self.assertEqual(self.root / installer.LIBRARY, installer.verify_installed(self.root))
        self.assertEqual(b'distro library', original.read_bytes())
        self.assertEqual(0o644, (self.root / installer.LIBRARY).stat().st_mode & 0o777)

    def test_wrong_identity_or_payload_is_rejected_before_writing(self):
        for manifest, library in ((dict(self.manifest, libc='bionic'), self.library),
                                  (dict(self.manifest, codename='trixie'), self.library),
                                  (dict(self.manifest, schemaVersion=True), self.library),
                                  (dict(self.manifest, patchSha256='0' * 64), self.library),
                                  (self.manifest, self.library + b'corrupt')):
            with self.subTest(manifest=manifest, library=library):
                self.write(manifest, library)
                with self.assertRaises(ValueError):
                    self.install()
                self.assertEqual([], list(self.root.iterdir()))

    def test_extra_archive_entry_is_rejected_before_writing(self):
        for name in ('../escape', installer.MANIFEST, 'usr/local/bin/unexpected'):
            self.write(extra=tarfile.TarInfo(name))
            with self.assertRaises(ValueError):
                self.install()
            self.assertEqual([], list(self.root.iterdir()))

    def test_tampered_installed_library_or_receipt_fails_verification(self):
        self.write()
        self.install()
        target = self.root / installer.LIBRARY
        target.write_bytes(self.library + b'corrupt')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            installer.verify_installed(self.root)
        target.write_bytes(self.library)
        (self.root / installer.RECEIPT).write_text(json.dumps(dict(self.manifest, libc='bionic')))
        with self.assertRaisesRegex(ValueError, 'identity'):
            installer.verify_installed(self.root)

    def test_symlink_destination_cannot_write_or_verify_outside_rootfs(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (self.root / 'usr').symlink_to(outside)
        self.write()
        with self.assertRaises(OSError):
            self.install()
        self.assertEqual([], list(outside.iterdir()))
        # A pre-existing adapter outside rootfs is rejected even with matching bytes.
        for relative, data in ((installer.LIBRARY, self.library),
                               (installer.RECEIPT, json.dumps(self.manifest).encode())):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'escapes'):
            installer.verify_installed(self.root)


if __name__ == '__main__':
    unittest.main()
