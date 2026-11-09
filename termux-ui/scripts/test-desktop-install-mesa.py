#!/usr/bin/env python3
"""Pinned Mesa overlay safety tests. Linux O_PATH cases must run on Linux."""
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(os.environ.get('MESA_INSTALLER', str(Path(__file__).resolve().parents[1] /
    'src/main/assets/local-games/desktop_install_mesa.py')))
spec = importlib.util.spec_from_file_location('desktop_install_mesa_test', SCRIPT)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class Fixture:
    def fixture(self, extra=None, link='libEGL_mesa.so.0.0.0'):
        entries = {}
        for name in installer.REQUIRED:
            entries[name] = ('file', b'\x7fELF\x02\x01' + bytes(12) + b'\xb7\x00fixture', 0o755)
        entries[installer.ICD] = ('file', json.dumps({'ICD': {'library_path':
            '/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so'}}).encode(), 0o644)
        entries[installer.LIB + 'libEGL_mesa.so.0'] = ('symlink', link, 0o777)
        entries[installer.LIB + 'dri/zink_drv_video.so'] = ('symlink', '../libgallium-27.0.0-devel.so', 0o777)
        for name in list(entries):
            for i in range(1, len(name.split('/'))):
                entries.setdefault('/'.join(name.split('/')[:i]), ('directory', None, 0o755))
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            for name, (kind, content, mode) in sorted(entries.items()):
                member = tarfile.TarInfo('./' + name); member.mode = mode
                if kind == 'file':
                    member.size = len(content); archive.addfile(member, io.BytesIO(content))
                else:
                    member.type = tarfile.DIRTYPE if kind == 'directory' else tarfile.SYMTYPE
                    member.linkname = content or ''; archive.addfile(member)
            if extra:
                archive.addfile(extra, io.BytesIO(b'x' * extra.size) if extra.size else None)
        return gzip.compress(out.getvalue(), mtime=0)


class ArchiveTest(Fixture, unittest.TestCase):
    def test_relative_parent_link_stays_inside_usr_and_resolves_to_payload(self):
        entries = installer.decode_archive(self.fixture())
        self.assertEqual('../libgallium-27.0.0-devel.so', entries[installer.LIB + 'dri/zink_drv_video.so'][1])

    def test_path_traversal_absolute_paths_duplicates_and_special_types_rejected(self):
        cases = [('usr/../escape', tarfile.REGTYPE), ('/usr/escape', tarfile.REGTYPE),
                 ('etc/escape', tarfile.REGTYPE), (installer.ICD, tarfile.REGTYPE),
                 ('usr/hard', tarfile.LNKTYPE), ('usr/fifo', tarfile.FIFOTYPE),
                 ('usr/device', tarfile.CHRTYPE)]
        for name, kind in cases:
            with self.subTest(name=name, kind=kind):
                member = tarfile.TarInfo(name); member.type = kind
                with self.assertRaises(ValueError): installer.decode_archive(self.fixture(extra=member))

    def test_escaping_absolute_dangling_and_cyclic_symlinks_rejected(self):
        for target in ['/outside', '../../../../outside', 'missing.so', 'libEGL_mesa.so.0']:
            with self.subTest(target=target), self.assertRaises(ValueError):
                installer.decode_archive(self.fixture(link=target))

    def test_archive_file_cannot_be_ancestor(self):
        member = tarfile.TarInfo(installer.ICD + '/child'); member.mode = 0o644
        with self.assertRaises(ValueError): installer.decode_archive(self.fixture(extra=member))

    def test_elf_identity_size_limit_and_special_modes_rejected(self):
        data = self.fixture()
        with patch.object(installer, 'REQUIRED', ('usr/missing.so',)):
            with self.assertRaisesRegex(ValueError, 'identity'): installer.decode_archive(data)
        with patch.object(installer, 'EXPANDED_LIMIT', 4):
            with self.assertRaisesRegex(ValueError, 'size/mode'): installer.decode_archive(self.fixture())
        member = tarfile.TarInfo('usr/suid'); member.mode = 0o4755
        with self.assertRaisesRegex(ValueError, 'size/mode'): installer.decode_archive(self.fixture(extra=member))

    def test_pinned_constants_and_actual_upstream_archive_when_supplied(self):
        self.assertEqual(12242251, installer.ARCHIVE_BYTES)
        self.assertEqual('b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687', installer.ARCHIVE_SHA256)
        path = os.environ.get('MESA_TEST_ARCHIVE')
        if not path:
            self.skipTest('set MESA_TEST_ARCHIVE to validate the actual upstream archive')
        data = Path(path).read_bytes()
        self.assertEqual(installer.ARCHIVE_BYTES, len(data))
        self.assertEqual(installer.ARCHIVE_SHA256, hashlib.sha256(data).hexdigest())
        self.assertEqual(90, len(installer.decode_archive(data)))


@unittest.skipUnless(hasattr(os, 'O_PATH'), 'Linux O_PATH required; macOS skips are not safety coverage')
class FilesystemTest(Fixture, unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='mesa-install-')
        self.base = Path(self.folder.name).resolve()
        self.root = self.base / 'rootfs'; self.root.mkdir()
        (self.root / 'etc').mkdir()
        (self.root / 'etc/os-release').write_text('ID=debian\nVERSION_CODENAME=trixie\n')
        self.archive = self.base / 'mesa.tar.gz'
        self.data = self.fixture(); self.archive.write_bytes(self.data)
        self.pin_bytes = patch.object(installer, 'ARCHIVE_BYTES', len(self.data))
        self.pin_sha = patch.object(installer, 'ARCHIVE_SHA256', hashlib.sha256(self.data).hexdigest())
        self.pin_bytes.start(); self.pin_sha.start()

    def tearDown(self):
        self.pin_sha.stop(); self.pin_bytes.stop(); self.folder.cleanup()

    def install(self):
        installer.install(str(self.archive), str(self.root), 'trixie', 'zink')

    def test_valid_repeat_install_exact_content_modes_and_symlinks(self):
        self.install(); self.install()
        root = installer.open_directory(str(self.root))
        try: installer.verify(root, installer.decode_archive(self.data))
        finally: os.close(root)
        self.assertFalse(list(self.root.rglob('.mesa-install-*.tmp')))

    def test_actual_upstream_archive_install_and_full_readback_when_supplied(self):
        path = os.environ.get('MESA_TEST_ARCHIVE')
        if not path:
            self.skipTest('set MESA_TEST_ARCHIVE for actual payload install/readback')
        with patch.object(installer, 'ARCHIVE_BYTES', 12242251), patch.object(installer, 'ARCHIVE_SHA256',
                'b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687'):
            installer.install(path, str(self.root), 'trixie', 'zink')
            installer.install(path, str(self.root), 'trixie', 'zink')
        self.assertEqual('../libgallium-27.0.0-devel.so',
            os.readlink(self.root / (installer.LIB + 'dri/zink_drv_video.so')))

    def test_explicit_modes_and_rootfs_identity_fail_before_payload_changes(self):
        for codename, mode in [('forky', 'zink'), ('trixie', 'llvmpipe'), ('trixie', '')]:
            with self.assertRaises(ValueError): installer.install(str(self.archive), str(self.root), codename, mode)
        (self.root / 'etc/os-release').write_text('ID=ubuntu\nVERSION_CODENAME=trixie\n')
        with self.assertRaisesRegex(ValueError, 'Debian trixie'): self.install()
        self.assertFalse((self.root / 'usr').exists())

    def test_standard_os_release_link_is_read_without_following_ancestors(self):
        (self.root / 'usr/lib').mkdir(parents=True)
        (self.root / 'usr/lib/os-release').write_text('ID=debian\nVERSION_CODENAME=trixie\n')
        (self.root / 'etc/os-release').unlink()
        (self.root / 'etc/os-release').symlink_to('../usr/lib/os-release')
        self.install()

    def test_root_archive_and_target_ancestor_symlinks_never_escape(self):
        alias = self.base / 'root-alias'; alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError): installer.install(str(self.archive), str(alias), 'trixie', 'zink')
        real = self.base / 'real.tar.gz'; self.archive.rename(real); self.archive.symlink_to(real)
        with self.assertRaises(OSError): self.install()
        self.archive.unlink(); real.rename(self.archive)
        outside = self.base / 'outside'; outside.mkdir(); (outside / 'keep').write_bytes(b'original')
        (self.root / 'usr').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError): self.install()
        self.assertEqual(['keep'], [p.name for p in outside.iterdir()])
        self.assertEqual(b'original', (outside / 'keep').read_bytes())

    def test_regular_destination_replaces_existing_symlink_without_following_it(self):
        target = self.root / installer.REQUIRED[0]; target.parent.mkdir(parents=True)
        outside = self.base / 'outside'; outside.write_bytes(b'keep')
        target.symlink_to(outside)
        self.install()
        self.assertFalse(target.is_symlink()); self.assertEqual(b'keep', outside.read_bytes())

    def test_archive_fifo_is_rejected_without_waiting_for_a_writer(self):
        self.archive.unlink()
        os.mkfifo(self.archive)
        with self.assertRaisesRegex(ValueError, 'regular file'): self.install()
        self.assertFalse((self.root / 'usr').exists())

    def test_bad_sha_and_truncated_archive_change_nothing(self):
        for data in [self.data[:-1], bytes(len(self.data))]:
            self.archive.write_bytes(data)
            with self.assertRaises((ValueError, OSError, tarfile.TarError)): self.install()
            self.assertFalse((self.root / 'usr').exists())

    def test_replace_failure_preserves_target_and_cleans_only_owned_temporary(self):
        target = self.root / installer.REQUIRED[0]; target.parent.mkdir(parents=True); target.write_bytes(b'old')
        with patch.object(installer.os, 'replace', side_effect=OSError('replace failed')):
            with self.assertRaisesRegex(OSError, 'replace failed'): self.install()
        self.assertEqual(b'old', target.read_bytes())
        self.assertFalse(list(self.root.rglob('.mesa-install-*.tmp')))

    def test_final_readback_detects_post_replace_tampering(self):
        replace = os.replace
        def tamper(*args, **kwargs):
            replace(*args, **kwargs)
            if args[1] == installer.REQUIRED[0].rsplit('/', 1)[-1]:
                fd = os.open(args[1], os.O_WRONLY | os.O_TRUNC, dir_fd=kwargs['dst_dir_fd'])
                os.write(fd, b'corrupted'); os.close(fd)
        with patch.object(installer.os, 'replace', side_effect=tamper):
            with self.assertRaisesRegex(ValueError, 'payload mismatch'): self.install()

    def test_missing_opath_is_error_and_ancestor_open_uses_search_only(self):
        with patch.object(installer, 'DIRECTORY_PATH_FLAGS', None), patch.object(installer.os, 'open') as opened:
            with self.assertRaisesRegex(RuntimeError, 'Linux O_PATH'): installer.open_directory(str(self.root))
            opened.assert_not_called()
        original = os.open
        def search_only(path, flags, *args, **kwargs):
            if flags & os.O_DIRECTORY and path != '.' and not flags & os.O_PATH:
                raise PermissionError('ancestor reads denied')
            return original(path, flags, *args, **kwargs)
        with patch.object(installer.os, 'open', side_effect=search_only): self.install()

    def test_cli_requires_all_explicit_parameters(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--archive', str(self.archive), '--rootfs', str(self.root)],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn('--codename', result.stderr); self.assertIn('--gl-mode', result.stderr)


if __name__ == '__main__':
    unittest.main()
