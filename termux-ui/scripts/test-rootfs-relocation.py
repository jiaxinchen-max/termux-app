#!/usr/bin/env python3
"""Regression tests for the actual PRoot link graph and RootFS directory move."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import sys

sys.dont_write_bytecode = True

ASSET = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/relocate_rootfs_links.py'
spec = importlib.util.spec_from_file_location('relocation', ASSET)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Relocation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='rootfs move $ = ')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'containers/tmpl-build/rootfs'
        (self.root / '.l2s').mkdir(parents=True)
        (self.root / 'usr/lib/locale').mkdir(parents=True)
        self.locale = self.root / 'usr/lib/locale/locale-archive'
        self.entity = self.root / '.l2s/locale.0001'
        self.entity.write_bytes(b'locale contents')
        self.intermediate = self.root / '.l2s/locale'
        self.intermediate.symlink_to(self.entity)
        self.locale.symlink_to(self.intermediate)

    def test_double_chain_survives_move_and_is_idempotent(self):
        guest = self.root / 'usr/guest-link'
        guest.symlink_to('/usr/bin')
        self.assertEqual(2, module.relocate(self.root)['normalized'])
        live = self.base / 'games-shared-rootfs/rootfs'
        live.parent.mkdir()
        self.root.rename(live)
        self.assertEqual(b'locale contents', (live / 'usr/lib/locale/locale-archive').read_bytes())
        self.assertEqual('/usr/bin', os.readlink(live / 'usr/guest-link'))
        self.assertEqual(0, module.relocate(live)['normalized'])
        self.assertEqual(2, module.relocate(live, verify=True)['verifiedChains'])

    def test_android_style_host_alias(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.root, target_is_directory=True)
        self.locale.unlink()
        self.locale.symlink_to(alias / '.l2s/locale')
        module.relocate(alias)
        self.assertEqual(b'locale contents', self.locale.read_bytes())

    def test_verify_rejects_absolute_links(self):
        with self.assertRaises(ValueError):
            module.relocate(self.root, verify=True)

    def assert_rejected_without_mutation(self):
        before = os.readlink(self.locale)
        with self.assertRaises((ValueError, OSError)):
            module.relocate(self.root)
        self.assertEqual(before, os.readlink(self.locale))

    def test_missing_entity(self):
        self.entity.unlink()
        self.assert_rejected_without_mutation()

    def test_loop(self):
        self.entity.unlink()
        self.entity.symlink_to(self.intermediate)
        self.assert_rejected_without_mutation()

    def test_outside_host_target(self):
        self.intermediate.unlink()
        self.intermediate.symlink_to('/outside/.l2s/file')
        self.assert_rejected_without_mutation()

    def test_relative_escape(self):
        self.intermediate.unlink()
        self.intermediate.symlink_to('../../outside')
        self.assert_rejected_without_mutation()

    def test_parent_traversal_in_absolute_target(self):
        self.intermediate.unlink()
        self.intermediate.symlink_to(str(self.root) + '/.l2s/../outside')
        self.assert_rejected_without_mutation()

    def test_symlink_backing_directory(self):
        self.entity.unlink()
        self.intermediate.unlink()
        (self.root / '.l2s').rmdir()
        (self.root / '.l2s').symlink_to(self.base)
        self.assert_rejected_without_mutation()

    def test_special_entity(self):
        self.entity.unlink()
        os.mkfifo(self.entity)
        self.assert_rejected_without_mutation()


if __name__ == '__main__':
    unittest.main()
