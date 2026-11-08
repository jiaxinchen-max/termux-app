#!/usr/bin/env python3
"""Compiler-produced ELF graphs; machine fields model two nonexecuted ABIs."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('external', Path(__file__).with_name('audit-external-wine.py'))
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)


@unittest.skipUnless(shutil.which('cc'), 'C compiler required')
class ExternalAudit(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.root = self.base / 'root'
        self.lib = self.root / 'usr/lib/aarch64-linux-gnu'
        self.lib.mkdir(parents=True)
        (self.root / 'etc').mkdir()
        self.box = self.base / 'box'
        (self.box / 'bin').mkdir(parents=True)
        self.wine = self.base / 'wine'
        (self.wine / 'bin').mkdir(parents=True)
        self.source = self.base / 'source.c'
        self.source.write_text('int symbol(void) { return 42; }\n')
        self.compile(self.box / 'bin/box64', 'box64.so', arm=True)
        self.compile(self.lib / 'libdep.so.1.0', 'libdep.so.1')
        (self.lib / 'libdep.so.1').symlink_to('libdep.so.1.0')
        self.compile(self.wine / 'bin/wine', 'wine.so', needed=self.lib / 'libdep.so.1.0')
        data = bytearray((self.lib / 'libdep.so.1.0').read_bytes())
        data[18:20] = (183).to_bytes(2, 'little')
        (self.lib / 'libdep.so.1.0').write_bytes(data)
        self.wrappers = {'backend': 'rootfs_proot', 'box64Commit': 'a'*40,
                         'box64Sha256': e.m.digest(self.box / 'bin/box64'),
                         'wrappers': {'libdep.so.1': {'native': 'libdep.so.1'}}}
        modules = self.wine / 'lib/wine/x86_64-unix'
        modules.mkdir(parents=True)
        self.compile(modules / 'feature.so', 'feature.so')

    def compile(self, output, soname, arm=False, needed=None, rpath=None):
        # No fixture is executed. Only e_machine is changed after the real
        # linker builds the ELF/program/dynamic headers and dependency edges.
        command = ['cc', '-shared', '-fPIC', '-nostdlib', str(self.source), '-Wl,-soname,' + soname]
        if needed:
            command += ['-Wl,--no-as-needed', str(needed)]
        if rpath:
            command += ['-Wl,-rpath,' + rpath]
        subprocess.run([*command, '-o', str(output)], check=True, capture_output=True)
        if arm:
            data = bytearray(output.read_bytes())
            data[18:20] = (183).to_bytes(2, 'little')
            output.write_bytes(data)

    def scan(self):
        return e.scan(self.root, {'wine': self.wine}, self.box, self.wrappers)

    def test_native_closure_records_exact_soname_link_and_content(self):
        result = self.scan()
        self.assertEqual([], result['problems'])
        self.assertIn('usr/lib/aarch64-linux-gnu/libdep.so.1', result['protectedPaths'])
        self.assertIn('usr/lib/aarch64-linux-gnu/libdep.so.1.0', result['protectedPaths'])
        result['capabilityComparison'] = e.compare_capabilities(self.wine, self.wine)
        e.seal(self.root, result)
        self.assertTrue(e.m.external_wine_protected(self.root))
        (self.lib / 'libdep.so.1.0').write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError, 'external_source_changed'):
            e.m.external_wine_protected(self.root)

    def test_missing_native_provider_is_not_satisfied_by_host(self):
        (self.lib / 'libdep.so.1.0').unlink()
        result = self.scan()
        self.assertTrue(result['problems'])
        with self.assertRaisesRegex(ValueError, 'cannot_seal_failed_audit'):
            e.seal(self.root, result)

    def test_native_transitive_edge_is_checked(self):
        extra = self.lib / 'libextra.so.1'
        self.compile(extra, extra.name)
        self.compile(self.lib / 'libdep.so.1.0', 'libdep.so.1', arm=True, needed=extra)
        extra.unlink()
        self.assertTrue(any(p.get('needed') == extra.name for p in self.scan()['problems']))

    def test_declared_box64_native_alias_is_resolved(self):
        self.wrappers['wrappers']['libdep.so.1'] = {'native': 'unavailable.so.0', 'nativeAliases': ['libdep.so.1']}
        self.assertEqual([], self.scan()['problems'])

    def test_native_soname_cannot_be_impersonated_by_same_filename(self):
        self.compile(self.lib / 'libdep.so.1.0', 'wrong.so.1', arm=True)
        self.assertTrue(self.scan()['problems'])

    def test_removed_baseline_module_blocks_capability_acceptance(self):
        baseline = self.base / 'baseline'
        shutil.copytree(self.wine, baseline)
        (self.wine / 'lib/wine/x86_64-unix/feature.so').unlink()
        self.compile(self.wine / 'lib/wine/x86_64-unix/other.so', 'other.so')
        result = e.compare_capabilities(self.wine, baseline)
        self.assertEqual('ReviewRequired', result['status'])
        self.assertEqual(['feature.so'], result['missingUnixModules'])

    def test_missing_capability_comparison_cannot_seal_candidate(self):
        with self.assertRaisesRegex(ValueError, 'capability_preservation_not_checked'):
            e.seal(self.root, self.scan())

    def test_lost_dynamic_library_name_requires_review_without_needed_edge(self):
        feature = self.wine / 'lib/wine/x86_64-unix/feature.so'
        self.source.write_text('const char *runtime_name = "libvulkan.so.1";\n')
        self.compile(feature, 'feature.so')
        baseline = self.base / 'baseline'
        shutil.copytree(self.wine, baseline)
        self.source.write_text('int symbol(void) { return 42; }\n')
        self.compile(feature, 'feature.so')
        result = e.compare_capabilities(self.wine, baseline)
        self.assertEqual('ReviewRequired', result['status'])
        self.assertEqual([], result['unreviewedRemovedDependencies'])
        self.assertEqual(['libvulkan.so.1'], result['unreviewedRemovedLibraryNames'][0]['removedLibraryNames'])

    def test_wrong_provider_architecture_rejected(self):
        self.compile(self.lib / 'libdep.so.1.0', 'libdep.so.1')
        self.assertTrue(self.scan()['problems'])

    def test_native_arm64_presence_does_not_invent_a_box64_wrapper(self):
        self.wrappers['wrappers'] = {}
        self.assertTrue(any('unmapped_or_missing' in p['reason'] for p in self.scan()['problems']))

    def test_x86_emulated_library_is_distinct_from_native_provider(self):
        self.wrappers['wrappers'] = {}
        libraries = self.box / 'lib/x86_64-linux-gnu'
        libraries.mkdir(parents=True)
        self.compile(libraries / 'libdep.so.1', 'libdep.so.1')
        result = self.scan()
        self.assertEqual([], result['problems'])
        record = next(r for r in result['records'] if r['owner'] == 'wine')
        self.assertTrue(record['resolved']['libdep.so.1'].startswith('box64:'))

    def test_guest_x86_provider_and_transitive_closure_are_retained(self):
        self.wrappers['wrappers'] = {}
        libs = self.root / 'usr/lib/x86_64-linux-gnu'
        libs.mkdir()
        self.compile(libs / 'libdep.so.1.0', 'libdep.so.1')
        (libs / 'libdep.so.1').symlink_to('libdep.so.1.0')
        result = self.scan()
        self.assertEqual([], result['problems'])
        self.assertIn('usr/lib/x86_64-linux-gnu/libdep.so.1.0', result['protectedPaths'])
        self.assertIn('usr/lib/x86_64-linux-gnu/libdep.so.1', result['protectedPaths'])
        coverage = e.a.emulated_coverage(self.root, result)
        self.assertIn('usr/lib/x86_64-linux-gnu/libdep.so.1.0', coverage)
        (libs / 'libdep.so.1.0').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'external_emulated_changed'):
            e.a.emulated_coverage(self.root, result)

    def test_guest_x86_origin_path_and_unreachable_plugin_are_audited(self):
        self.wrappers['wrappers'] = {}
        libs = self.root / 'usr/lib/x86_64-linux-gnu'
        private = libs / 'private'
        private.mkdir(parents=True)
        self.compile(private / 'libextra.so.1', 'libextra.so.1')
        self.compile(libs / 'libdep.so.1', 'libdep.so.1',
                     needed=private / 'libextra.so.1', rpath='$ORIGIN/private')
        self.assertEqual([], self.scan()['problems'])
        (private / 'libextra.so.1').unlink()
        self.assertTrue(any(p.get('needed') == 'libextra.so.1' for p in self.scan()['problems']))

    def test_usrmerge_guest_x86_alias_is_canonicalized_before_sealing(self):
        self.wrappers['wrappers'] = {}
        libs = self.root / 'usr/lib/x86_64-linux-gnu'
        libs.mkdir()
        self.compile(libs / 'libdep.so.1', 'libdep.so.1')
        (self.root / 'lib').symlink_to('usr/lib')
        result = self.scan()
        self.assertEqual([], result['problems'])
        records = [r for r in result['records'] if r['owner'] == 'rootfs-x86']
        self.assertEqual(['usr/lib/x86_64-linux-gnu/libdep.so.1'], [r['path'] for r in records])
        e.a.emulated_coverage(self.root, result)
        result['capabilityComparison'] = e.compare_capabilities(self.wine, self.wine)
        e.seal(self.root, result)
        self.assertTrue(e.m.external_wine_protected(self.root))

    def test_native_file_in_guest_x86_directory_is_rejected(self):
        libs = self.root / 'usr/lib/x86_64-linux-gnu'
        libs.mkdir()
        self.compile(libs / 'wrong.so', 'wrong.so', arm=True)
        with self.assertRaisesRegex(ValueError, 'emulated_directory_architecture'):
            self.scan()

    def test_wine_literal_dlopen_name_is_a_closure_requirement(self):
        self.source.write_text('const char *runtime_name = "libunavailable.so.9";\n')
        self.compile(self.wine / 'lib/wine/x86_64-unix/feature.so', 'feature.so')
        self.assertTrue(any(p.get('needed') == 'libunavailable.so.9' for p in self.scan()['problems']))

    def reviewed_removal(self):
        feature = self.wine / 'lib/wine/x86_64-unix/feature.so'
        self.source.write_text('const char *old_name = "libold.so.1";\n')
        self.compile(feature, 'feature.so')
        baseline = self.base / 'baseline'
        shutil.copytree(self.wine, baseline)
        self.source.write_text('int symbol(void) { return 42; }\n')
        self.compile(feature, 'feature.so')
        comparison = e.compare_capabilities(self.wine, baseline)
        path = self.base / 'review.json'
        value = {'schemaVersion': 1, 'state': 'SourceReviewedNoFeatureRemoval',
                 'baselineElfs': e.capability_inventory(baseline),
                 'candidateElfs': e.capability_inventory(self.wine), 'changes': [
                     {'module': 'feature.so', 'removedDependencies': [],
                      'removedLibraryNames': ['libold.so.1'],
                      'reason': 'Fixture replacement is reviewed against the bound source and exact binaries.',
                      'evidence': [{'path': self.source.name, 'sha256': e.m.digest(self.source)}]}]}
        path.write_text(json.dumps(value))
        return comparison, baseline, path, value

    def test_capability_review_binds_exact_differences_and_source(self):
        comparison, baseline, path, value = self.reviewed_removal()
        reviewed = e.apply_capability_review(comparison, self.wine, baseline, path)
        self.assertEqual('SourceReviewedNoFeatureRemoval', reviewed['status'])
        value['changes'][0]['removedLibraryNames'] = ['libdifferent.so.2']
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'capability_review_differences_changed'):
            e.apply_capability_review(comparison, self.wine, baseline, path)

    def test_capability_review_rejects_changed_binary_and_missing_module(self):
        comparison, baseline, path, _ = self.reviewed_removal()
        feature = self.wine / 'lib/wine/x86_64-unix/feature.so'
        feature.write_bytes(feature.read_bytes()+b'changed')
        with self.assertRaisesRegex(ValueError, 'capability_review_stale'):
            e.apply_capability_review(comparison, self.wine, baseline, path)
        comparison['missingUnixModules'] = ['feature.so']
        with self.assertRaisesRegex(ValueError, 'cannot_waive_missing_modules'):
            e.apply_capability_review(comparison, self.wine, baseline, path)

    def test_capability_review_rejects_changed_source_evidence(self):
        comparison, baseline, path, _ = self.reviewed_removal()
        self.source.write_text('changed review evidence')
        with self.assertRaisesRegex(ValueError, 'capability_review_evidence_changed'):
            e.apply_capability_review(comparison, self.wine, baseline, path)

    def test_termux_path_and_box64_identity_rejected(self):
        self.compile(self.wine / 'bin/wine', 'wine.so', rpath='/data/data/com.termux/files/usr/glibc/lib')
        self.assertTrue(any(p['reason'] == 'wrong_backend_search_path' for p in self.scan()['problems']))
        self.wrappers['box64Sha256'] = '0'*64
        with self.assertRaisesRegex(ValueError, 'box64_digest'):
            self.scan()

    def test_wrapper_source_digest_and_declared_mapping_required(self):
        source = self.base / 'wrapper.c'
        source.write_text('const char* depName = "libdep.so.1";\n')
        table = self.base / 'list.h'
        table.write_text('GO("libdep.so.1", dep)\n')
        lock = self.base / 'lock.json'
        value = dict(self.wrappers, nativeMachine=183, libraryList='list.h',
                     sourceFiles={p.name: e.m.digest(p) for p in [source, table]},
                     wrappers={'libdep.so.1': {'id': 'dep', 'source': 'wrapper.c', 'native': 'libdep.so.1'}})
        lock.write_text(json.dumps(value))
        self.assertTrue(e.load_wrappers(lock))
        source.write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'wrapper_source_digest'):
            e.load_wrappers(lock)


if __name__ == '__main__':
    unittest.main()
