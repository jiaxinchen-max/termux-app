#!/usr/bin/env python3
"""Safety boundaries for pruning from an already verified dependency graph."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prune', Path(__file__).with_name('prune-external-wine-plugins.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class PluginPrune(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'source'
        self.root.mkdir()
        self.prefix = 'usr/lib/x86_64-linux-gnu/'
        self.additions, self.records = {}, []
        self.core = self.add('libcamera-core.so.1', 'libgphoto2-6t64')
        self.shared = self.add('libshared.so.1', 'shared')
        self.driver = self.add('libgphoto2/1.0/camera.so', 'libgphoto2-6t64')
        self.exclusive = self.add('libexclusive.so.1', 'codec')
        self.python = self.add('libsamba-policy.cpython-39-x86-64-linux-gnu.so.0', 'samba-libs')
        self.pythonlib = self.add('libpython3.9.so.1.0', 'libpython3.9')
        self.edge(self.core, self.shared)
        self.edge(self.driver, self.shared)
        self.edge(self.driver, self.exclusive)
        self.edge(self.python, self.pythonlib)
        self.records.append({'owner': '0', 'path': 'lib/wine/netapi.so', 'resolved': {
            'core': 'rootfs-x86:' + self.core}})
        name = 'usr/share/doc/test/copyright'
        file = self.root / name
        file.parent.mkdir(parents=True)
        file.write_text('copyright')
        self.additions[name] = {'type': 'file', 'sha256': p.digest(file), 'bytes': file.stat().st_size}
        link = self.prefix + 'libexclusive.so'
        (self.root / link).symlink_to('libexclusive.so.1')
        self.additions[link] = {'type': 'symlink', 'link': 'libexclusive.so.1'}
        self.audit = {'rootfsRoot': str(self.root), 'status': 'PassedDirectDependencyClosure',
                      'problems': [], 'records': self.records}
        self.inputs = {'addedPaths': self.additions}

    def add(self, name, package):
        name = self.prefix + name
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b'\x7fELF' + name.encode())
        record = {'type': 'file', 'sha256': p.digest(file), 'bytes': file.stat().st_size, 'package': package}
        self.additions[name] = record
        self.records.append({'owner': 'rootfs-x86', 'path': name, 'sha256': record['sha256'], 'resolved': {}})
        return name

    def edge(self, source, target):
        next(r for r in self.records if r['path'] == source)['resolved'][target] = 'rootfs-x86:' + target

    def plan(self):
        return p.plan(self.root, self.audit, self.inputs)

    def test_optional_plugins_and_exclusive_dependencies_only(self):
        value = self.plan()
        removed = value['removedPaths']
        for name in [self.driver, self.exclusive, self.python, self.pythonlib, self.prefix + 'libexclusive.so']:
            self.assertIn(name, removed)
        self.assertNotIn(self.core, removed)
        self.assertNotIn(self.shared, removed)
        self.assertNotIn('usr/share/doc/test/copyright', removed)

    def test_excluded_target_required_by_remaining_core_blocks(self):
        self.edge(self.core, self.driver)
        with self.assertRaisesRegex(ValueError, 'excluded_still_required'):
            self.plan()

    def test_shared_python_runtime_is_not_removed(self):
        self.edge(self.core, self.pythonlib)
        self.assertNotIn(self.pythonlib, self.plan()['removedPaths'])

    def test_stale_added_digest_rejected(self):
        (self.root / self.exclusive).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'file_changed'):
            self.plan()

    def test_uncovered_graph_edge_rejected(self):
        self.edge(self.core, self.prefix + 'missing.so')
        with self.assertRaisesRegex(ValueError, 'uncovered_edge'):
            self.plan()

    def test_source_tree_never_mutated(self):
        with self.assertRaisesRegex(ValueError, 'original_root_forbidden'):
            p.apply(self.root, self.plan(), self.audit, self.inputs)
        self.assertTrue((self.root / self.driver).is_file())

    def test_tampered_plan_rejected_before_mutation(self):
        value = self.plan()
        value['removedPaths'][self.core] = copy.deepcopy(self.additions[self.core])
        target = self.root.parent / 'candidate'
        shutil.copytree(self.root, target, symlinks=True)
        with self.assertRaisesRegex(ValueError, 'plan_changed'):
            p.apply(target, value, self.audit, self.inputs)
        self.assertTrue((target / self.driver).is_file())

    def test_independent_apply_preserves_original_and_shared_files(self):
        target = self.root.parent / 'candidate'
        shutil.copytree(self.root, target, symlinks=True)
        result = p.apply(target, self.plan(), self.audit, self.inputs)
        self.assertEqual('AppliedInIndependentCandidate', result['state'])
        self.assertTrue((self.root / self.driver).is_file())
        self.assertFalse((target / self.driver).exists())
        self.assertTrue((target / self.shared).is_file())

    def test_parent_symlink_and_traversal_rejected(self):
        for name in ['../escape', '/absolute']:
            with self.assertRaises(ValueError):
                p.leaf(self.root, name)
        (self.root / 'alias').symlink_to(self.root / 'usr')
        with self.assertRaisesRegex(ValueError, 'unsafe_parent'):
            p.leaf(self.root, 'alias/lib/file')

    def test_changed_candidate_rejected_before_any_delete(self):
        target = self.root.parent / 'candidate'
        shutil.copytree(self.root, target, symlinks=True)
        (target / self.shared).write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError, 'file_changed'):
            p.apply(target, self.plan(), self.audit, self.inputs)
        self.assertTrue((target / self.driver).is_file())


if __name__ == '__main__':
    unittest.main()
