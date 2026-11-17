#!/usr/bin/env python3
"""Private Ubuntu image production must not follow aliases or discard data."""
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    'ubuntu_builder', Path(__file__).with_name('build-ubuntu-wine-rootfs.py'))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class BuildBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.prefix = self.base / 'files/usr'
        self.prefix.mkdir(parents=True)

    def test_existing_container_parent_alias_is_rejected(self):
        live = self.base / 'existing-environment'
        live.mkdir()
        marker = live / 'user-data'
        marker.write_bytes(b'keep')
        parent = self.prefix / 'var/lib/proot-distro/containers'
        parent.mkdir(parents=True)
        (parent / builder.NAME).symlink_to(live, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'build_root_symlink'):
            builder.build_container_root(self.prefix)
        self.assertEqual(marker.read_bytes(), b'keep')

    def test_empty_android_targets_are_removed(self):
        root = self.base / 'scratch-root'
        target = root / 'apex/empty/nested'
        target.mkdir(parents=True)
        target.chmod(0)
        (root / 'system').mkdir()
        (root / 'usr').mkdir()
        builder.remove_host_scaffolding(root)
        self.assertFalse((root / 'apex').exists())
        self.assertFalse((root / 'system').exists())
        self.assertTrue((root / 'usr').is_dir())

    def test_data_anywhere_blocks_all_directory_removal(self):
        root = self.base / 'scratch-root'
        empty = root / 'apex/empty'
        empty.mkdir(parents=True)
        empty.chmod(0)
        unknown = root / 'vendor/unknown-file'
        unknown.parent.mkdir()
        unknown.write_bytes(b'unknown data')
        with self.assertRaisesRegex(ValueError, 'host_scaffolding_contains_data'):
            builder.remove_host_scaffolding(root)
        self.assertTrue(empty.is_dir())
        self.assertEqual(stat.S_IMODE(empty.stat().st_mode), 0)
        self.assertEqual(unknown.read_bytes(), b'unknown data')
        empty.chmod(0o700)

    def test_linked_android_target_cannot_delete_its_destination(self):
        root = self.base / 'scratch-root'
        root.mkdir()
        outside = self.base / 'outside'
        outside.mkdir()
        (outside / 'keep').write_bytes(b'keep')
        (root / 'data').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'host_scaffolding_contains_data'):
            builder.remove_host_scaffolding(root)
        self.assertTrue((root / 'data').is_symlink())
        self.assertEqual((outside / 'keep').read_bytes(), b'keep')


class CompatibilityArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'root'
        self.root.mkdir()
        self.archive = self.base / 'compat.tar.xz'
        self.lock = self.base / 'lock.json'
        self.library = 'usr/local/lib/libavcodec.so.58.1'
        self.link = 'usr/local/lib/libavcodec.so.58'
        self.pack()

    def pack(self, extra=None):
        data = b'locked compatibility library'
        with tarfile.open(self.archive, 'w:xz') as tar:
            member = tarfile.TarInfo(self.library)
            member.size = len(data)
            tar.addfile(member, io.BytesIO(data))
            member = tarfile.TarInfo(self.link)
            member.type = tarfile.SYMTYPE
            member.linkname = Path(self.library).name
            tar.addfile(member)
            if extra:
                member = tarfile.TarInfo(extra)
                member.size = 1
                tar.addfile(member, io.BytesIO(b'x'))
        self.lock.write_text(json.dumps({
            'bytes': self.archive.stat().st_size, 'sha256': builder.digest(self.archive),
            'members': {self.library: {'type': 'file', 'bytes': len(data),
                                     'sha256': hashlib.sha256(data).hexdigest()},
                        self.link: {'type': 'symlink', 'link': Path(self.library).name}}}))

    def install(self):
        return builder.install_ffmpeg4(self.root, self.archive, self.lock)

    def test_install_is_repeatable_and_preserves_system_ffmpeg(self):
        system = self.root / 'usr/lib/libavcodec.so.62'
        system.parent.mkdir(parents=True)
        system.write_bytes(b'Ubuntu library')
        self.install()
        self.install()
        self.assertEqual((self.root / self.link).read_bytes(), b'locked compatibility library')
        self.assertEqual(system.read_bytes(), b'Ubuntu library')

    def test_corrupt_archive_is_rejected_without_writing(self):
        self.archive.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'ffmpeg4_archive_digest'):
            self.install()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_unlisted_and_escaping_members_are_rejected(self):
        for name in ('usr/local/lib/unlisted.so', '../escaped'):
            with self.subTest(name=name):
                self.pack(extra=name)
                with self.assertRaisesRegex(ValueError, 'ffmpeg4_archive_member'):
                    self.install()
                self.assertEqual(list(self.root.iterdir()), [])
                self.assertFalse((self.base / 'escaped').exists())

    def test_linked_parent_cannot_write_outside_build_root(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (self.root / 'usr').mkdir()
        (self.root / 'usr/local').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'ffmpeg4_parent_alias'):
            self.install()
        self.assertEqual(list(outside.iterdir()), [])

    def test_conflict_is_rejected_before_other_members_are_written(self):
        conflict = self.root / self.link
        conflict.parent.mkdir(parents=True)
        conflict.write_bytes(b'existing system library')
        with self.assertRaisesRegex(ValueError, 'ffmpeg4_existing_file_changed'):
            self.install()
        self.assertEqual(conflict.read_bytes(), b'existing system library')
        self.assertFalse((self.root / self.library).exists())

    def test_ubuntu_plugins_use_the_default_scanner_directory(self):
        self.library = 'usr/lib/aarch64-linux-gnu/gstreamer-1.0/libgstplayback.so'
        self.link = 'usr/local/lib/libcompat.so'
        self.pack()
        # Use a standalone plugin, rather than an alias across directories.
        with tarfile.open(self.archive, 'w:xz') as tar:
            data = b'locked compatibility library'
            member = tarfile.TarInfo(self.library)
            member.size = len(data)
            tar.addfile(member, io.BytesIO(data))
        lock = json.loads(self.lock.read_text())
        del lock['members'][self.link]
        lock.update(bytes=self.archive.stat().st_size, sha256=builder.digest(self.archive))
        self.lock.write_text(json.dumps(lock))
        builder.install_ubuntu_media(self.root, self.archive, self.lock)
        self.assertEqual((self.root / self.library).read_bytes(), data)
        with self.assertRaisesRegex(ValueError, 'ffmpeg4_archive_member'):
            self.install()

    def test_ubuntu_supplement_cannot_replace_system_libraries(self):
        self.library = 'usr/lib/aarch64-linux-gnu/libc.so.6'
        self.pack()
        with self.assertRaisesRegex(ValueError, 'ubuntu_media_archive_member'):
            builder.install_ubuntu_media(self.root, self.archive, self.lock)
        self.assertEqual(list(self.root.iterdir()), [])


class DefaultHomeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root, self.work = self.base / 'root', self.base / 'work'
        self.work.mkdir()
        for name in ('.bash_logout', '.bashrc', '.profile'):
            for directory in ('home/ubuntu', 'etc/skel'):
                path = self.root / directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('official ' + name)

    def test_official_home_and_cache_are_preserved_outside_image(self):
        cache = self.root / 'root/.cache/box64/library.box64'
        cache.parent.mkdir(parents=True)
        cache.write_bytes(b'build cache')
        records = builder.prepare_image_homes(self.root, self.work)
        self.assertEqual(set(records), {'ubuntu', 'box64'})
        self.assertFalse((self.root / 'home/ubuntu').exists())
        self.assertEqual((self.work / 'build-home-scaffolding/box64/library.box64').read_bytes(),
                         b'build cache')

    def test_changed_or_additional_home_data_is_not_moved(self):
        for name in ('.profile', 'save-data'):
            with self.subTest(name=name):
                target = self.root / 'home/ubuntu' / name
                original = target.read_bytes() if target.exists() else None
                target.write_bytes(b'personal data')
                with self.assertRaisesRegex(ValueError, 'default_home_data'):
                    builder.prepare_image_homes(self.root, self.work)
                self.assertEqual(target.read_bytes(), b'personal data')
                self.assertFalse((self.work / 'build-home-scaffolding').exists())
                if original is None: target.unlink()
                else: target.write_bytes(original)

    def test_cache_alias_blocks_all_moves(self):
        cache = self.root / 'root/.cache/box64'
        cache.parent.mkdir(parents=True)
        cache.symlink_to(self.base, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'unexpected_box64_cache_data'):
            builder.prepare_image_homes(self.root, self.work)
        self.assertTrue((self.root / 'home/ubuntu/.profile').is_file())


if __name__ == '__main__':
    unittest.main()
