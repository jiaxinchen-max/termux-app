#!/usr/bin/env python3
"""Exercise reversible deletion against real trees, hashes and symlink graphs."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('trim', Path(__file__).with_name('minimize-rootfs.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Minimize(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='rootfs-trim-$ ')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.source = self.base / 'source'
        self.root = self.base / 'candidate'
        self.assets = self.base / 'assets'
        self.assets.mkdir()
        self.put('etc/games-runtime.properties', 'runtimeBackend=rootfs_proot\narchitecture=aarch64\n')
        self.put('var/lib/dpkg/status', 'Package: hangover-wine\nVersion: ' + m.PACKAGE +
                 '\nStatus: install ok installed\n')
        self.put('usr/bin/wine', 'ELF-Wine-runtime')
        self.put('usr/include/wine/test.h', 'header')
        self.put('usr/lib/wine/aarch64-windows/libwine.a', 'static archive')
        self.put('usr/lib/wine/aarch64-windows/ntdll.dll', 'PE-runtime')
        self.put('usr/share/doc/wine/copyright', 'license')
        records = [{'source': 'usr/lib/wine/aarch64-windows/ntdll.dll',
                    'bytes': len('PE-runtime'), 'sha256': m.digest(self.source /
                    'usr/lib/wine/aarch64-windows/ntdll.dll')}]
        (self.assets / 'manifest.json').write_text(json.dumps(records))
        self.metadata = {'sourceComponentSha256':
                        'ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1',
                        'assets': {'manifest.json': m.digest(self.assets / 'manifest.json')},
                        'runtimeFiles': {'usr/bin/wine': m.digest(self.source / 'usr/bin/wine')}}
        self.write_metadata()
        m.clone(self.source, self.root)
        self.journal = self.base / 'journal.json'

    def put(self, name, content):
        p = self.source / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def write_metadata(self):
        (self.assets / 'bundle.json').write_text(json.dumps(self.metadata))

    def plan(self):
        return m.plan(self.root, self.assets, 'development')

    def test_external_runtime_dependencies_survive_trim_and_detect_changed_links(self):
        name = 'usr/include/wine/test.h'
        path = self.root / name
        alias = self.root / 'usr/include/wine/required.h'
        alias.symlink_to('test.h')
        receipt = {'schemaVersion': 1, 'state': 'Verified', 'backend': 'rootfs_proot',
                   'protectedPaths': {name: {'type': 'file', 'bytes': path.stat().st_size, 'sha256': m.digest(path)},
                                      'usr/include/wine/required.h': {'type': 'symlink', 'link': 'test.h'}}}
        (self.root / m.EXTERNAL_WINE_RECEIPT).write_text(json.dumps(receipt))
        plan = self.plan()
        self.assertNotIn(name, {r['path'] for r in plan['deletions']})
        self.assertNotIn('usr/include/wine/required.h', {r['path'] for r in plan['deletions']})
        m.apply(self.root, self.assets, plan, self.journal)
        self.assertTrue(path.is_file())
        alias.unlink()
        alias.symlink_to('other.h')
        with self.assertRaisesRegex(ValueError, 'external_link_changed'):
            m.protect(self.root, self.assets)

    def test_build_only_batch_preserves_runtime_and_package_identity(self):
        removed = ['usr/share/i18n/locales/zh_CN', 'usr/share/i18n/charmaps/GBK.gz',
                   'usr/bin/localedef', 'usr/sbin/locale-gen', 'usr/bin/apt-get',
                   'usr/bin/dpkg-query', 'var/lib/dpkg/info/wine.list',
                   'usr/lib/aarch64-linux-gnu/libapt-pkg.so.7.0.0',
                   'usr/lib/apt/methods/http', 'var/cache/debconf/config.dat']
        kept = ['usr/lib/locale/locale-archive', 'usr/lib/locale/C.utf8/LC_CTYPE',
                'usr/lib/aarch64-linux-gnu/gconv/GBK.so', 'usr/bin/locale',
                'usr/share/locale/locale.alias', 'usr/lib/aarch64-linux-gnu/libperl.so.5.40.1',
                'usr/share/perl/5.40.1/strict.pm', 'usr/lib/udev/hwdb.bin']
        for name in removed + kept:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        before = m.tree(self.root)
        plan = m.plan(self.root, self.assets, 'build-tools')
        self.assertEqual(set(removed), {r['path'] for r in plan['deletions']})
        identity = m.identity(self.root)
        m.apply(self.root, self.assets, plan, self.journal)
        self.assertEqual(identity, m.identity(self.root))
        for name in kept:
            self.assertEqual(name, (self.root / name).read_text())
        m.rollback(self.root, self.journal)
        self.assertEqual(before, m.tree(self.root))

    def test_gpu_batch_removes_exact_pairs_and_keeps_supported_icds(self):
        removed = []
        for driver in ('broadcom', 'nouveau', 'panfrost', 'radeon',
                       'freedreno', 'lvp', 'gfxstream', 'virtio'):
            for name in ('usr/lib/aarch64-linux-gnu/libvulkan_' + driver + '.so',
                         'usr/share/vulkan/icd.d/' + driver + '_icd.json'):
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
                if driver in ('broadcom', 'nouveau', 'panfrost', 'radeon'):
                    removed.append(name)
        plan = m.plan(self.root, self.assets, 'gpu-drivers')
        self.assertEqual(set(removed), {r['path'] for r in plan['deletions']})
        m.apply(self.root, self.assets, plan, self.journal)
        for driver in ('freedreno', 'lvp', 'gfxstream', 'virtio'):
            self.assertTrue((self.root / ('usr/lib/aarch64-linux-gnu/libvulkan_' + driver + '.so')).exists())
        m.rollback(self.root, self.journal)

    def test_independent_clone_apply_and_rollback_exact_content(self):
        original = m.tree(self.root)
        self.assertNotEqual((self.root / 'usr/bin/wine').stat().st_ino,
                            (self.source / 'usr/bin/wine').stat().st_ino)
        p = self.plan()
        self.assertEqual(2, len(p['deletions']))
        m.apply(self.root, self.assets, p, self.journal)
        self.assertFalse((self.root / 'usr/include/wine/test.h').exists())
        self.assertTrue((self.source / 'usr/include/wine/test.h').exists())
        self.assertTrue((self.root / 'usr/share/doc/wine/copyright').exists())
        self.assertEqual('AppliedPendingRuntimeValidation', json.loads(self.journal.read_text())['state'])
        m.rollback(self.root, self.journal)
        self.assertEqual(original, m.tree(self.root))

    def test_rollback_allows_a_fresh_attempt_and_does_not_report_old_output_hash(self):
        plan = self.plan()
        m.apply(self.root, self.assets, plan, self.journal)
        result = m.rollback(self.root, self.journal)
        self.assertNotIn('outputTreeSha256', result)
        self.assertIn('appliedTreeSha256', result)
        self.assertEqual(2, result['restoredFiles'])
        self.assertFalse(self.root.with_name(self.root.name + '.removed-development').exists())
        m.apply(self.root, self.assets, plan, self.base/'second-journal.json')
        self.assertFalse((self.root/'usr/include/wine/test.h').exists())

    def test_changed_quarantine_metadata_cannot_be_silently_restored(self):
        m.apply(self.root, self.assets, self.plan(), self.journal)
        q = self.root.with_name(self.root.name + '.removed-development')
        os.chmod(q/'usr/include/wine/test.h', 0o700)
        with self.assertRaisesRegex(ValueError, 'rollback_metadata_changed'):
            m.rollback(self.root, self.journal)

    def test_untracked_quarantine_file_survives_recovery(self):
        m.apply(self.root, self.assets, self.plan(), self.journal)
        q = self.root.with_name(self.root.name + '.removed-development')
        unexpected = q/'unexpected'
        unexpected.write_text('do not delete me')
        with self.assertRaisesRegex(ValueError, 'rollback_quarantine_not_empty'):
            m.rollback(self.root, self.journal)
        self.assertEqual('do not delete me', unexpected.read_text())

    def test_no_marker_or_live_path_refused(self):
        with self.assertRaises((OSError, ValueError)):
            m.plan(self.source, self.assets, 'development')
        with self.assertRaises(ValueError):
            m.clone(self.source, self.base / 'games-shared-rootfs')

    def test_changed_input_identity_refused(self):
        (self.root / 'var/lib/dpkg/status').write_text('incomplete')
        with self.assertRaises(ValueError):
            self.plan()

    def test_pending_package_transaction_refused(self):
        updates = self.root / 'var/lib/dpkg/updates'
        updates.mkdir()
        (updates / '0000').write_text('pending')
        with self.assertRaises(ValueError):
            self.plan()

    def test_prefix_source_or_runtime_guard_damage_refused(self):
        for name in ['usr/bin/wine', 'usr/lib/wine/aarch64-windows/ntdll.dll']:
            p = self.root / name
            data = p.read_bytes()
            p.write_bytes(b'corrupt')
            with self.assertRaises(ValueError):
                self.plan()
            p.write_bytes(data)

    def test_protected_static_source_cannot_be_removed(self):
        records = json.loads((self.assets / 'manifest.json').read_text())
        p = self.root / 'usr/lib/wine/aarch64-windows/libwine.a'
        records.append({'source': str(p.relative_to(self.root)), 'bytes': p.stat().st_size,
                        'sha256': m.digest(p)})
        (self.assets / 'manifest.json').write_text(json.dumps(records))
        self.metadata['assets']['manifest.json'] = m.digest(self.assets / 'manifest.json')
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, 'protected_deletion'):
            self.plan()

    def test_stale_plan_refuses_every_deletion(self):
        plan = self.plan()
        (self.root / 'usr/include/wine/test.h').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'stale_or_changed_plan'):
            m.apply(self.root, self.assets, plan, self.journal)
        self.assertTrue((self.root / 'usr/lib/wine/aarch64-windows/libwine.a').exists())

    def test_tampered_plan_refuses_traversal(self):
        plan = self.plan()
        plan['deletions'][0]['path'] = '../outside'
        with self.assertRaises(ValueError):
            m.apply(self.root, self.assets, plan, self.journal)
        for name in ['', '/etc/passwd', '../outside', 'usr/../outside', 'usr//lib']:
            with self.assertRaises(ValueError):
                m.leaf(self.root, name)

    def test_symlinked_parent_never_traversed(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (self.root / 'alias').symlink_to(outside)
        with self.assertRaises(ValueError):
            m.leaf(self.root, 'alias/file')

    def test_surviving_link_to_development_file_blocks_batch(self):
        (self.root / 'usr/bin/needed').symlink_to('../include/wine/test.h')
        with self.assertRaisesRegex(ValueError, 'surviving_link_depends'):
            self.plan()

    def test_directory_alias_cannot_hide_dependency_on_removed_header(self):
        (self.root / 'usr/header-alias').symlink_to('/usr/include/wine')
        (self.root / 'usr/bin/needed').symlink_to('/usr/header-alias/test.h')
        with self.assertRaisesRegex(ValueError, 'surviving_link_depends'):
            self.plan()

    def test_removed_directory_link_cannot_leave_surviving_child_link_dangling(self):
        (self.root / 'usr/include/wine/bin').symlink_to('/usr/bin')
        (self.root / 'usr/bin/needed').symlink_to('/usr/include/wine/bin/wine')
        with self.assertRaisesRegex(ValueError, 'surviving_link_depends'):
            self.plan()

    def test_preexisting_unrelated_broken_and_loop_links_do_not_block_batch(self):
        (self.root / 'usr/bin/broken').symlink_to('/nonexistent/old')
        (self.root / 'usr/bin/loop').symlink_to('loop')
        self.assertEqual(2, len(self.plan()['deletions']))

    def test_resources_preserve_legal_material_locale_and_wine_chinese_data(self):
        files = {
            'usr/share/doc/wine/README': 'manual',
            'usr/share/doc/wine/LICENSE.txt': 'license',
            'usr/share/doc/wine/NOTICE': 'notice',
            'usr/share/man/man1/wine.1.gz': 'man',
            'usr/share/info/test.info.gz': 'info',
            'usr/share/locale/zh_CN/LC_MESSAGES/example.mo': 'messages',
            'usr/share/locale/locale.alias': 'aliases',
            'usr/lib/locale/locale-archive': 'locale',
            'usr/lib/aarch64-linux-gnu/gconv/GBK.so': 'conversion',
            'usr/share/wine/nls/c_936.nls': 'wine codepage',
            'usr/share/X11/locale/zh_CN.UTF-8/Compose': 'X11 locale',
        }
        for name, data in files.items():
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(data)
        plan = m.plan(self.root, self.assets, 'resources')
        self.assertEqual({'usr/share/doc/wine/README', 'usr/share/man/man1/wine.1.gz',
                          'usr/share/info/test.info.gz',
                          'usr/share/locale/zh_CN/LC_MESSAGES/example.mo'},
                         {r['path'] for r in plan['deletions']})
        original = m.tree(self.root)
        m.apply(self.root, self.assets, plan, self.journal)
        for name in files:
            self.assertEqual(name not in {r['path'] for r in plan['deletions']},
                             (self.root / name).exists())
        m.rollback(self.root, self.journal)
        self.assertEqual(original, m.tree(self.root))

    def test_license_link_preserves_transitive_nonlicense_target(self):
        doc = self.root / 'usr/share/doc/wine'
        (doc / 'copyright').unlink()
        (doc / 'terms').write_text('license content')
        (doc / 'middle').symlink_to('terms')
        (doc / 'copyright').symlink_to('middle')
        (doc / 'unneeded').write_text('documentation')
        plan = m.plan(self.root, self.assets, 'resources')
        self.assertEqual(['usr/share/doc/wine/unneeded'], [r['path'] for r in plan['deletions']])
        retained = {r['path'] for r in plan['retainedByPolicy']}
        self.assertTrue({'usr/share/doc/wine/copyright', 'usr/share/doc/wine/middle',
                         'usr/share/doc/wine/terms'} <= retained)

    def test_doc_directory_alias_and_external_links_preserve_required_routes(self):
        doc = self.root / 'usr/share/doc/wine'
        (doc / 'README').write_text('needed by external link')
        (self.root / 'usr/share/doc/alias').symlink_to('wine')
        (self.root / 'usr/bin/needed').symlink_to('/usr/share/doc/alias/README')
        self.assertEqual([], m.plan(self.root, self.assets, 'resources')['deletions'])

    def test_resources_still_reject_a_selected_prefix_source(self):
        name = 'usr/share/doc/wine/prefix.data'
        (self.root / name).write_text('prefix source')
        self.metadata['runtimeFiles'][name] = m.digest(self.root / name)
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, 'protected_deletion'):
            m.plan(self.root, self.assets, 'resources')

    def build_fixture(self):
        owner = self.base / 'termux-files'
        root = owner / 'usr/var/lib/proot-distro/containers/tmpl-build/rootfs'
        shutil.copytree(self.source, root, symlinks=True)
        with (root / 'etc/games-runtime.properties').open('a') as stream:
            stream.write('recipeSha256=' + 'a' * 64 + '\n')
        return owner, root, self.base / 'build-report.json'

    def font_fixture(self):
        for name, data in {
            'usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc': 'serif regular',
            'usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc': 'serif bold',
            'usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc': 'protected sans',
            'var/cache/fontconfig/old.cache': 'old cache',
            'var/cache/fontconfig/CACHEDIR.TAG': 'old tag',
        }.items():
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(data)
        name = 'usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
        self.metadata['runtimeFiles'][name] = m.digest(self.root / name)
        self.write_metadata()

    def generate_cache_fixture(self, root, stage):
        self.assertFalse((root / 'var/cache/fontconfig/old.cache').exists())
        self.assertFalse((root / 'usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc').exists())
        (stage / 'new.cache').write_text('regenerated cache')
        (stage / 'CACHEDIR.TAG').write_text('new tag')

    def test_fonts_rebuild_cache_in_private_stage_and_rollback_exactly(self):
        self.font_fixture()
        before = m.tree(self.root)
        plan = m.plan(self.root, self.assets, 'fonts')
        with mock.patch.object(m, 'regenerate_font_cache', side_effect=self.generate_cache_fixture):
            journal = m.apply(self.root, self.assets, plan, self.journal)
        self.assertEqual(2, len(journal['generatedFiles']))
        self.assertFalse(Path(journal['generatedCacheStage']).exists())
        self.assertEqual('regenerated cache', (self.root / 'var/cache/fontconfig/new.cache').read_text())
        self.assertTrue((self.root / 'usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc').exists())
        self.assertEqual(2, journal['netRemovedFiles'])
        m.rollback(self.root, self.journal)
        self.assertEqual(before, m.tree(self.root))

    def test_cache_generator_failure_restores_old_fonts_and_cache(self):
        self.font_fixture()
        before = m.tree(self.root)
        plan = m.plan(self.root, self.assets, 'fonts')
        def fail(root, stage):
            (stage / 'incomplete.cache').write_text('partial')
            raise ValueError('fc-cache failed')
        with mock.patch.object(m, 'regenerate_font_cache', side_effect=fail):
            with self.assertRaisesRegex(ValueError, 'fc-cache failed'):
                m.apply(self.root, self.assets, plan, self.journal)
        self.assertEqual(before, m.tree(self.root))
        journal = json.loads(self.journal.read_text())
        self.assertEqual('RolledBack', journal['state'])
        self.assertTrue((Path(journal['generatedCacheStage']) / 'incomplete.cache').exists())

    def test_modified_generated_cache_blocks_rollback_instead_of_deleting_it(self):
        self.font_fixture()
        plan = m.plan(self.root, self.assets, 'fonts')
        with mock.patch.object(m, 'regenerate_font_cache', side_effect=self.generate_cache_fixture):
            m.apply(self.root, self.assets, plan, self.journal)
        p = self.root / 'var/cache/fontconfig/new.cache'
        p.write_text('unexpected change')
        with self.assertRaisesRegex(ValueError, 'rollback_generated_cache_changed'):
            m.rollback(self.root, self.journal)
        self.assertEqual('unexpected change', p.read_text())

    def test_font_recovery_resumes_after_old_cache_already_restored(self):
        self.font_fixture()
        before = m.tree(self.root)
        plan = m.plan(self.root, self.assets, 'fonts')
        with mock.patch.object(m, 'regenerate_font_cache', side_effect=self.generate_cache_fixture):
            journal = m.apply(self.root, self.assets, plan, self.journal)
        name = 'var/cache/fontconfig/CACHEDIR.TAG'
        (self.root / name).unlink()
        os.replace(Path(journal['quarantine']) / name, self.root / name)
        m.rollback(self.root, self.journal)
        self.assertEqual(before, m.tree(self.root))

    def test_prefix_referenced_serif_is_never_trimmed(self):
        self.font_fixture()
        name = 'usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc'
        self.metadata['runtimeFiles'][name] = m.digest(self.root / name)
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, 'protected_deletion'):
            m.plan(self.root, self.assets, 'fonts')

    def test_interrupted_generated_cache_install_restores_every_original(self):
        self.font_fixture()
        before = m.tree(self.root)
        plan = m.plan(self.root, self.assets, 'fonts')
        replace = os.replace
        installed = []
        def fail_second(src, dst):
            if '.font-cache-' in str(Path(src).parent):
                installed.append(str(src))
                if len(installed) == 2:
                    raise OSError('cache install interrupted')
            return replace(src, dst)
        with mock.patch.object(m, 'regenerate_font_cache', side_effect=self.generate_cache_fixture), \
                mock.patch.object(m.os, 'replace', side_effect=fail_second):
            with self.assertRaisesRegex(OSError, 'cache install interrupted'):
                m.apply(self.root, self.assets, plan, self.journal)
        self.assertEqual(2, len(installed))
        self.assertEqual(before, m.tree(self.root))

    def test_no_serif_removal_does_not_rebuild_unrelated_caches(self):
        cache = self.root / 'var/cache/fontconfig/old.cache'
        cache.parent.mkdir(parents=True)
        cache.write_text('keep cache')
        plan = m.plan(self.root, self.assets, 'fonts')
        self.assertEqual([], plan['deletions'])
        with mock.patch.object(m, 'regenerate_font_cache') as generator:
            journal = m.apply(self.root, self.assets, plan, self.journal)
        generator.assert_not_called()
        self.assertEqual(0, journal['allocatedBytesReclaimed'])
        self.assertEqual('keep cache', cache.read_text())

    def test_combined_installer_policy_matches_incremental_qualified_batches(self):
        self.font_fixture()
        doc = self.root / 'usr/share/doc/wine/README'
        doc.write_text('unneeded documentation')
        for name in ('usr/share/i18n/locales/zh_CN', 'usr/bin/apt-get',
                     'var/lib/dpkg/info/wine.list',
                     'usr/lib/aarch64-linux-gnu/libvulkan_radeon.so',
                     'usr/share/vulkan/icd.d/radeon_icd.json'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        other = self.base / 'combined-candidate'
        shutil.copytree(self.root, other, symlinks=True)
        def cache(root, stage):
            self.generate_cache_fixture(root, stage)
            for path in stage.iterdir():
                os.utime(path, ns=(123000000000, 123000000000))
        with mock.patch.object(m, 'BUILD_BATCHES', ('development', 'resources', 'fonts', 'build-tools', 'gpu-drivers')), \
                mock.patch.object(m, 'regenerate_font_cache', side_effect=cache):
            for batch in m.BUILD_BATCHES:
                plan = m.plan(self.root, self.assets, batch)
                m.apply(self.root, self.assets, plan, self.base / (batch + '.json'))
            plan = m.plan(other, self.assets, 'runtime')
            self.assertEqual(list(m.BUILD_BATCHES), plan['policyBatches'])
            m.apply(other, self.assets, plan, self.base / 'combined.json')
        self.assertEqual(m.tree(self.root), m.tree(other))
        self.assertFalse((other / 'usr/include/wine/test.h').exists())
        self.assertFalse(doc.exists())
        self.assertTrue((other / 'usr/share/doc/wine/copyright').exists())

    def test_apk_policy_assets_are_byte_identical_to_reviewed_tools(self):
        directory = Path(__file__).resolve().parent
        assets = directory.parents[1] / 'termux-ui/src/main/assets/local-games'
        for name in ('minimize-rootfs.py', 'audit-rootfs-elf.py'):
            self.assertEqual((directory / name).read_bytes(), (assets / name).read_bytes(), name)

    def test_unpublished_build_releases_only_verified_quarantines(self):
        owner, root, report = self.build_fixture()
        source_before = m.tree(self.source)
        with mock.patch.object(m, 'build_locales') as locales:
            receipt = m.build(root, owner, self.assets, 'a' * 64, report)
        locales.assert_called_once_with(root, owner)
        self.assertEqual('Verified', receipt['state'])
        self.assertEqual(2, receipt['removedFiles'])
        self.assertFalse(root.with_name('rootfs.removed-runtime').exists())
        self.assertFalse((root / m.MARKER).exists())
        self.assertEqual(receipt, m.verify_build(root, 'a' * 64))
        self.assertEqual(source_before, m.tree(self.source))
        self.assertTrue((root / 'usr/share/doc/wine/copyright').exists())
        self.assertEqual('Verified', json.loads(report.read_text())['state'])

    def test_verified_build_receipt_cannot_be_reused_via_root_or_receipt_alias(self):
        owner, root, report = self.build_fixture()
        with mock.patch.object(m, 'build_locales'):
            m.build(root, owner, self.assets, 'a' * 64, report)
        alias = root.with_name('alias')
        alias.symlink_to(root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'build_verification_alias'):
            m.verify_build(alias, 'a' * 64)
        receipt = root / m.TRIM_RECEIPT
        external = self.base / 'external-receipt'
        receipt.rename(external)
        receipt.symlink_to(external)
        with self.assertRaisesRegex(ValueError, 'build_receipt_alias'):
            m.verify_build(root, 'a' * 64)

    def test_build_refuses_live_or_game_tree_and_alias(self):
        owner, root, report = self.build_fixture()
        with self.assertRaisesRegex(ValueError, 'not_private_build_root'):
            m.build(self.root, owner, self.assets, 'a' * 64, report)
        alias = self.base / 'owner-alias'
        alias.symlink_to(owner)
        with self.assertRaisesRegex(ValueError, 'build_owner_alias'):
            m.build(root, alias, self.assets, 'a' * 64, report)
        (root / 'mnt/games/game').mkdir(parents=True)
        (root / 'mnt/games/game/user-save').write_text('user')
        with self.assertRaisesRegex(ValueError, 'nonempty_game_bind_target'):
            m.build(root, owner, self.assets, 'a' * 64, report)
        self.assertFalse((root / m.MARKER).exists())

    def test_locale_failure_never_creates_publishable_build(self):
        owner, root, report = self.build_fixture()
        with mock.patch.object(m, 'build_locales', side_effect=ValueError('locale failed')):
            with self.assertRaisesRegex(ValueError, 'locale failed'):
                m.build(root, owner, self.assets, 'a' * 64, report)
        self.assertEqual('Rejected', json.loads((root / m.MARKER).read_text())['state'])
        self.assertFalse((root / m.TRIM_RECEIPT).exists())
        self.assertTrue(root.with_name('rootfs.removed-runtime').exists())
        with self.assertRaisesRegex(ValueError, 'build_not_finalized'):
            m.verify_build(root, 'a' * 64)
        self.assertTrue((self.source / 'usr/include/wine/test.h').exists())

    def test_unexpected_quarantine_data_blocks_discard_and_receipt(self):
        owner, root, report = self.build_fixture()
        def contaminate(*_):
            (root.with_name('rootfs.removed-runtime') / 'unexpected').write_text('keep')
        with mock.patch.object(m, 'build_locales', side_effect=contaminate):
            with self.assertRaisesRegex(ValueError, 'build_quarantine_changed'):
                m.build(root, owner, self.assets, 'a' * 64, report)
        self.assertTrue((root.with_name('rootfs.removed-runtime') / 'unexpected').exists())
        self.assertFalse((root / m.TRIM_RECEIPT).exists())

    def test_cancellation_while_discarding_cannot_publish_partial_build(self):
        owner, root, report = self.build_fixture()
        unlink = Path.unlink
        moved = []
        def interrupt(path, *args, **kwargs):
            if 'rootfs.removed-runtime' in path.parts:
                moved.append(str(path))
                if len(moved) == 2:
                    raise KeyboardInterrupt()
            return unlink(path, *args, **kwargs)
        with mock.patch.object(m, 'build_locales'), mock.patch.object(Path, 'unlink', interrupt):
            with self.assertRaises(KeyboardInterrupt):
                m.build(root, owner, self.assets, 'a' * 64, report)
        self.assertEqual(2, len(moved))
        self.assertFalse((root / m.TRIM_RECEIPT).exists())
        self.assertEqual('RejectedUnpublishedBuild', json.loads(report.read_text())['state'])
        self.assertTrue((self.source / 'usr/include/wine/test.h').exists())

    def test_existing_report_is_never_overwritten_on_build_failure(self):
        owner, root, report = self.build_fixture()
        original = json.dumps({'recipeSha256': 'a' * 64, 'important': 'existing report'})
        report.write_text(original)
        with self.assertRaisesRegex(ValueError, 'build_report_exists'):
            m.build(root, owner, self.assets, 'a' * 64, report)
        self.assertEqual(original, report.read_text())

    def test_trim_receipt_requires_current_policy_and_recipe(self):
        owner, root, report = self.build_fixture()
        with mock.patch.object(m, 'build_locales'):
            m.build(root, owner, self.assets, 'a' * 64, report)
        with self.assertRaisesRegex(ValueError, 'build_trim_receipt_invalid'):
            m.verify_build(root, 'b' * 64)
        with mock.patch.object(m, 'policy_sha', return_value='0' * 64):
            with self.assertRaisesRegex(ValueError, 'build_trim_receipt_invalid'):
                m.verify_build(root, 'a' * 64)

    def test_guest_absolute_and_l2s_protection(self):
        (self.root / 'usr/bin/wine').unlink()
        (self.root / '.l2s').mkdir()
        (self.root / '.l2s/wine').write_text('ELF-Wine-runtime')
        (self.root / 'usr/bin/wine').symlink_to('/.l2s/wine')
        protected = m.protect(self.root, self.assets)
        self.assertIn('.l2s/wine', protected)
        self.assertEqual(2, len(self.plan()['deletions']))

    def test_guest_cycle_or_escape_refused(self):
        p = self.root / 'usr/bin/wine'
        p.unlink()
        for target in ['wine', '../../../../outside']:
            p.symlink_to(target)
            with self.assertRaises((OSError, ValueError)):
                self.plan()
            p.unlink()

    def test_apply_failure_automatically_restores_prior_moves(self):
        original = m.tree(self.root)
        plan = self.plan()
        replace = os.replace
        def fail_second_move(src, dst):
            if str(src).endswith('libwine.a') and '.removed-' in str(dst):
                raise OSError('injected move failure')
            return replace(src, dst)
        with mock.patch.object(m.os, 'replace', side_effect=fail_second_move):
            with self.assertRaisesRegex(OSError, 'injected'):
                m.apply(self.root, self.assets, plan, self.journal)
        self.assertEqual(original, m.tree(self.root))
        self.assertEqual('RolledBack', json.loads(self.journal.read_text())['state'])

    def test_reports_cannot_change_candidate_or_use_parent_alias(self):
        with self.assertRaises(ValueError):
            m.apply(self.root, self.assets, self.plan(), self.root / 'journal.json')
        alias = self.base / 'alias'
        alias.symlink_to(self.root)
        with self.assertRaises(ValueError):
            m.outside(self.root, alias / 'report.json')
        with self.assertRaises(ValueError):
            m.outside(self.root, self.source / 'report.json')

    def test_interrupted_move_is_recovered_from_durable_journal(self):
        original = m.tree(self.root)
        plan = self.plan()
        q = self.root.with_name(self.root.name + '.removed-development')
        q.mkdir()
        m.save(self.journal, {'state': 'Applying', 'plan': plan, 'quarantine': str(q)})
        record = plan['deletions'][0]
        dst = q / record['path']
        dst.parent.mkdir(parents=True)
        os.replace(self.root / record['path'], dst)
        m.rollback(self.root, self.journal)
        self.assertEqual(original, m.tree(self.root))

    def test_cancellation_restores_already_moved_files(self):
        original = m.tree(self.root)
        plan = self.plan()
        replace = os.replace
        def cancel_second_move(src, dst):
            if str(src).endswith('libwine.a') and '.removed-' in str(dst):
                raise SystemExit(143)
            return replace(src, dst)
        with mock.patch.object(m.os, 'replace', side_effect=cancel_second_move):
            with self.assertRaises(SystemExit):
                m.apply(self.root, self.assets, plan, self.journal)
        self.assertEqual(original, m.tree(self.root))

    def test_tampered_quarantine_not_restored(self):
        m.apply(self.root, self.assets, self.plan(), self.journal)
        q = self.root.with_name(self.root.name + '.removed-development')
        (q / 'usr/lib/wine/aarch64-windows/libwine.a').write_text('bad')
        with self.assertRaisesRegex(ValueError, 'rollback_file_changed'):
            m.rollback(self.root, self.journal)

    def test_missing_quarantine_file_cannot_be_reported_as_restored(self):
        m.apply(self.root, self.assets, self.plan(), self.journal)
        q = self.root.with_name(self.root.name + '.removed-development')
        (q / 'usr/lib/wine/aarch64-windows/libwine.a').unlink()
        with self.assertRaisesRegex(ValueError, 'rollback_file_missing'):
            m.rollback(self.root, self.journal)
        self.assertNotEqual('RolledBack', json.loads(self.journal.read_text())['state'])

    def test_recovery_can_resume_after_some_files_already_restored(self):
        original = m.tree(self.root)
        plan = self.plan()
        m.apply(self.root, self.assets, plan, self.journal)
        q = self.root.with_name(self.root.name + '.removed-development')
        record = plan['deletions'][-1]
        os.replace(q / record['path'], self.root / record['path'])
        m.rollback(self.root, self.journal)
        self.assertEqual(original, m.tree(self.root))

    def test_stale_temporary_report_after_process_death_does_not_block_recovery(self):
        original = m.tree(self.root)
        m.apply(self.root, self.assets, self.plan(), self.journal)
        stale = self.journal.with_name('.' + self.journal.name + '.tmp-dead-process')
        stale.write_text('{"state":')
        m.rollback(self.root, self.journal)
        self.assertEqual(original, m.tree(self.root))
        self.assertEqual('RolledBack', json.loads(self.journal.read_text())['state'])
        self.assertEqual('{"state":', stale.read_text())

    def test_clone_refuses_insufficient_space(self):
        with mock.patch.object(m.shutil, 'disk_usage', return_value=type('Space', (), {'free': 0})()):
            with self.assertRaisesRegex(ValueError, 'insufficient_space'):
                m.clone(self.source, self.base / 'other')
        self.assertFalse((self.base / 'other').exists())

    def test_clone_refuses_embedded_game_prefix(self):
        self.put('mnt/games/prefix/user.reg', 'private game data')
        with self.assertRaisesRegex(ValueError, 'nonempty_game_bind_target'):
            m.clone(self.source, self.base / 'other')
        self.assertFalse((self.base / 'other').exists())

    def test_clone_preserves_top_level_usrmerge_link_metadata(self):
        (self.source / 'bin').symlink_to('usr/bin')
        os.utime(self.source / 'bin', ns=(123456789, 123456789), follow_symlinks=False)
        other = self.base / 'other'
        m.clone(self.source, other)
        self.assertEqual('usr/bin', os.readlink(other / 'bin'))
        self.assertEqual((self.source / 'bin').lstat().st_mtime_ns,
                         (other / 'bin').lstat().st_mtime_ns)

    def test_source_change_during_clone_leaves_unready_candidate(self):
        other = self.base / 'other'
        copy = m.shutil.copytree
        def mutate_after_copy(src, dst, *args, **kwargs):
            result = copy(src, dst, *args, **kwargs)
            if str(src) == str(self.source / 'usr'):
                (self.source / 'usr/include/wine/test.h').write_text('concurrent writer changed source')
            return result
        with mock.patch.object(m.shutil, 'copytree', side_effect=mutate_after_copy):
            with self.assertRaisesRegex(ValueError, 'input_changed'):
                m.clone(self.source, other)
        self.assertEqual('Copying', json.loads((other / m.MARKER).read_text())['state'])
        with self.assertRaises(ValueError):
            m.plan(other, self.assets, 'development')

    def test_special_file_in_input_is_rejected(self):
        os.mkfifo(self.source / 'untrusted-pipe')
        with self.assertRaisesRegex(ValueError, 'special_file'):
            m.clone(self.source, self.base / 'other')

    def test_snapshot_protection_still_checks_all_runtime_bytes(self):
        snapshot = m.tree(self.root)
        snapshot['usr/bin/wine']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'runtime_guard_mismatch'):
            m.protect(self.root, self.assets, snapshot)

    def test_android_bind_alias_cannot_put_clone_or_report_in_original(self):
        alias = self.base / 'android-bind-alias'
        alias.mkdir()
        samefile = os.path.samefile
        def alias_identity(a, b):
            if Path(a) == alias and Path(b) == self.source:
                return True
            return samefile(a, b)
        with mock.patch.object(m.os.path, 'samefile', side_effect=alias_identity):
            with self.assertRaisesRegex(ValueError, 'candidate_destination'):
                m.clone(self.source, alias / 'candidate')
            with self.assertRaisesRegex(ValueError, 'report_inside_original_input'):
                m.outside(self.root, alias / 'report.json')


if __name__ == '__main__':
    unittest.main()
