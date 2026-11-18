#!/usr/bin/env python3
"""Private Ubuntu image production must not follow aliases or discard data."""
import importlib.util
import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import stat
import subprocess
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

    def test_new_private_name_does_not_reuse_default_or_shared_root(self):
        original = builder.build_container_root(self.prefix)
        original.mkdir(parents=True)
        marker = original / 'preserved'; marker.write_bytes(b'old build')
        name = 'wine-ubuntu2604-build-slim-20261118'
        candidate = builder.build_container_root(self.prefix, name)
        self.assertEqual(candidate, original.parent.parent / name / 'rootfs')
        self.assertFalse(candidate.exists())
        self.assertEqual(marker.read_bytes(), b'old build')

    def test_shared_names_and_path_traversal_cannot_select_build_root(self):
        for name in ('games-shared-rootfs', '', '../wine-ubuntu2604-build',
                     'wine-ubuntu2604-build/../../games-shared-rootfs',
                     'wine-ubuntu2604-build-', 'wine-ubuntu2604-build-A'):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, 'private_build_container_name_invalid'):
                    builder.build_container_root(self.prefix, name)

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


class OciManifestCacheTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.prefix = Path(self.temporary.name)
        self.cache = self.prefix / 'var/lib/proot-distro/cache/oci_manifests'
        self.cache.mkdir(parents=True)
        manifest = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                    'config': {'digest': 'sha256:config'}, 'layers': [{'digest': 'sha256:layer'}]}
        self.raw = json.dumps(manifest).encode()
        self.digest = 'sha256:' + hashlib.sha256(self.raw).hexdigest()
        self.lock = {'reference': 'ubuntu:26.04@' + self.digest, 'architecture': 'aarch64',
                     'manifestDigest': self.digest, 'manifest': manifest}
        self.record = {'image_ref': self.lock['reference'], 'arch': 'aarch64',
                       'manifest': copy.deepcopy(manifest),
                       'documents': {self.digest: base64.b64encode(self.raw).decode()}}

    def write(self, record):
        (self.cache / 'entry.json').write_text(json.dumps(record))

    def test_registry_bytes_accept_only_the_matching_internal_content_type(self):
        for annotated in (False, True):
            with self.subTest(annotated=annotated):
                record = copy.deepcopy(self.record)
                if annotated:
                    record['manifest']['_ct'] = self.lock['manifest']['mediaType']
                self.write(record)
                builder.validate_cached_oci_manifest(self.prefix, self.lock)

    def test_tampered_bytes_or_manifest_fields_cannot_hide_behind_internal_tag(self):
        variants = []
        for field, value in (('_ct', 'wrong/type'), ('unknown', True), ('schemaVersion', 1),
                             ('layers', [{'digest': 'sha256:other'}])):
            record = copy.deepcopy(self.record)
            record['manifest'][field] = value
            variants.append(record)
        record = copy.deepcopy(self.record)
        record['documents'][self.digest] = base64.b64encode(self.raw + b' ').decode()
        variants.append(record)
        record = copy.deepcopy(self.record); del record['documents']; variants.append(record)
        for record in variants:
            with self.subTest(record=record):
                self.write(record)
                with self.assertRaisesRegex(ValueError, 'official_base_manifest_not_found'):
                    builder.validate_cached_oci_manifest(self.prefix, self.lock)

    def test_reference_architecture_and_full_raw_document_must_match(self):
        for field, value in (('image_ref', 'ubuntu:latest'), ('arch', 'x86_64')):
            record = copy.deepcopy(self.record); record[field] = value; self.write(record)
            with self.assertRaisesRegex(ValueError, 'official_base_manifest_not_found'):
                builder.validate_cached_oci_manifest(self.prefix, self.lock)
        self.write(self.record)
        lock = copy.deepcopy(self.lock); lock['manifest']['unknown'] = True
        with self.assertRaisesRegex(ValueError, 'official_base_manifest_not_found'):
            builder.validate_cached_oci_manifest(self.prefix, lock)

    def test_unrelated_malformed_cache_and_linked_records_do_not_qualify(self):
        (self.cache / 'broken.json').write_text('{')
        (self.cache / 'array.json').write_text('[]')
        outside = self.prefix / 'outside.json'; outside.write_text(json.dumps(self.record))
        (self.cache / 'linked.json').symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'official_base_manifest_not_found'):
            builder.validate_cached_oci_manifest(self.prefix, self.lock)
        self.write(self.record)
        builder.validate_cached_oci_manifest(self.prefix, self.lock)


class CoreutilsProviderTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'root'
        self.status = self.root / 'var/lib/dpkg/status'
        self.status.parent.mkdir(parents=True)
        self.status.write_text(
            'Package: coreutils-from-gnu\nVersion: 0.0.0~ubuntu25\nStatus: install ok installed\n\n'
            'Package: gnu-coreutils\nVersion: 9.7-3ubuntu2.1\nStatus: install ok installed\n\n')
        directory = self.root / 'usr/bin'
        directory.mkdir(parents=True)
        for name in ('[', 'env', 'cat', 'ls', 'chmod', 'mkdir', 'cp', 'mv', 'rm',
                     'ln', 'readlink', 'realpath', 'stat', 'sort', 'sha256sum', 'timeout'):
            (directory / name).symlink_to('gnu' + name)
            (directory / ('gnu' + name)).write_bytes(b'GNU fixture')
            (directory / ('gnu' + name)).chmod(0o755)

    def test_official_gnu_provider_and_guest_absolute_links_are_accepted(self):
        (self.root / 'usr/bin/env').unlink()
        (self.root / 'usr/bin/env').symlink_to('/usr/bin/gnuenv')
        builder.validate_coreutils(self.root)

    def test_wrong_provider_or_version_blocks_packing(self):
        original = self.status.read_text()
        for content in (original.replace('coreutils-from-gnu', 'coreutils-from-uutils'),
                        original.replace('9.7-3ubuntu2.1', '9.8'),
                        original + 'Package: rust-coreutils\nStatus: install ok installed\n'):
            with self.subTest(content=content):
                self.status.write_text(content)
                with self.assertRaisesRegex(ValueError, 'gnu_coreutils_package_identity'):
                    builder.validate_coreutils(self.root)

    def test_missing_nonexecutable_or_wrong_command_target_is_rejected(self):
        target = self.root / 'usr/bin/gnuenv'
        target.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'gnu_coreutils_command:env'):
            builder.validate_coreutils(self.root)
        target.unlink()
        with self.assertRaisesRegex(ValueError, 'gnu_coreutils_command:env'):
            builder.validate_coreutils(self.root)
        target.write_bytes(b'GNU fixture')
        target.chmod(0o755)
        (self.root / 'usr/bin/env').unlink()
        (self.root / 'usr/bin/env').write_bytes(b'unexpected replacement')
        with self.assertRaisesRegex(ValueError, 'gnu_coreutils_command:env'):
            builder.validate_coreutils(self.root)

    def test_leftover_rust_payload_or_dangling_payload_link_is_rejected(self):
        for name in ('usr/bin/coreutils', 'usr/lib/cargo/bin/coreutils'):
            with self.subTest(name=name):
                p = self.root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.symlink_to('missing')
                with self.assertRaisesRegex(ValueError, 'rust_coreutils_payload_remains'):
                    builder.validate_coreutils(self.root)
                p.unlink()

    def check_plan(self, text):
        plan = self.base / 'plan.txt'
        plan.write_text(text)
        return subprocess.run(['sh', str(Path(__file__).parent /
                               'ubuntu-wine-rootfs/check-coreutils-plan.sh'), str(plan)],
                              capture_output=True, text=True)

    def test_only_old_rust_packages_may_be_removed(self):
        self.assertEqual(0, self.check_plan(
            'Remv coreutils-from-uutils [0.0.0~ubuntu25]\n'
            'Remv rust-coreutils:arm64 [0.10.0-1ubuntu2~26.04.1]\n'
            'Inst coreutils-from-gnu (0.0.0~ubuntu25 Ubuntu:26.04/resolute [all])\n').returncode)
        self.assertEqual(0, self.check_plan('0 upgraded, 0 newly installed, 0 to remove\n').returncode)
        for name in ('coreutils', 'libc6:arm64', 'hangover-wine', 'rust-coreutils-extra'):
            with self.subTest(name=name):
                result = self.check_plan('Remv ' + name + ' [1.0]\n')
                self.assertEqual(65, result.returncode)
                self.assertIn('unexpected package removal: ' + name, result.stderr)


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
