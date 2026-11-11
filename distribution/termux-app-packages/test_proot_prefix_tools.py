#!/usr/bin/env python3
"""Test packaged menu semantics with an independent Shell Link parser/restorer.

Install prefix-tools-test-requirements.txt for the build-time-only parser.
No Wine execution or network access is used by these tests.
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest

import pylnk3

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


b = module('tools_builder', HERE / 'add-proot-prefix-tools.py')
restore = module('prefix_restore', HERE.parents[1] /
                 'termux-ui/src/main/assets/local-games/restore_wine_prefix.py')
packager = module('prefix_package', HERE / 'package-prefix-assets.py')


def pe(machine=0x14c):
    data = bytearray(70)
    data[:2] = b'MZ'
    struct.pack_into('<I', data, 60, 64)
    data[64:68] = b'PE\0\0'
    struct.pack_into('<H', data, 68, machine)
    return bytes(data)


class ToolsTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.source = self.base / 'source'
        self.source.mkdir()
        self.output = self.base / 'output'
        self.scripts = self.base / 'scripts.tar.xz'
        self.tools = {name: pe() for name in b.PORTABLE}
        self.tools.update({b.TT + 'TrueTransparency.exe': pe(), b.TT + 'TrueTransparencyHook.dll': pe(),
                           b.TT + 'config.ini': b'[General]\nSkin=Aero\n', b.TT + 'license.txt': b'license',
                           b.TT + 'lang/English.lng': b'English',
                           b.TT + 'skin/Aero/Normal/skin.ini': b'normal',
                           b.TT + 'skin/Aero/Maximized/skin.ini': b'maximized',
                           '7z.exe': pe(), 'unused-installer.exe': pe()})
        self.pack_scripts()
        self.make_source('rootfs_hangover_11.16')

    def pack_scripts(self):
        with tarfile.open(self.scripts, 'w:xz') as archive:
            for name, data in self.tools.items():
                info = tarfile.TarInfo('glibc/opt/apps/' + name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))

    def make_source(self, package):
        schema = b.PACKAGES[package]
        self.rootfs = self.base / ('rootfs-' + package)
        self.rootfs.mkdir()
        self.wine = None if schema == 1 else self.base / ('wine-' + package)
        if self.wine:
            self.wine.mkdir()
            (self.wine / '.wine-component.properties').write_text(
                'schemaVersion=1\npackageName=' + package + '\nversion=1\n')
        self.files = dict.fromkeys(('system.reg', 'user.reg', 'userdef.reg'), b'WINE REGISTRY Version 2\n')
        self.files['.update-timestamp'] = b'disable\n'
        for entry in b.menu_entries():
            if entry['target'].startswith('C:\\windows\\'):
                self.files['drive_c/' + entry['target'][3:].replace('\\', '/')] = pe(0xaa64 if schema == 1 else 0x8664)
        self.files['drive_c/windows/system32/joy.cpl'] = pe(0xaa64 if schema == 1 else 0x8664)
        self.files['drive_c/windows/system32/ntdll.dll'] = pe(0xaa64 if schema == 1 else 0x8664)
        self.files['drive_c/windows/syswow64/ntdll.dll'] = pe()
        self.records = []
        for name, data in self.files.items():
            record = dict(path=name, bytes=len(data), mode=0o600, mtime=123, sha256=b.sha(data))
            if '/windows/' in name:
                relative = ('usr/lib/wine/' if schema == 1 else 'lib/wine/') + name.split('/windows/')[1]
                record['source'] = relative
                if schema == 2:
                    record['sourceRoot'] = 'wine'
                root = self.rootfs if schema == 1 else self.wine
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                (root / relative).write_bytes(data)
            self.records.append(record)
        self.meta = dict(schemaVersion=schema, backend='rootfs_proot', hostAbi='aarch64',
                         prefixArch='wow64', peMachine=0x8664, translator='box64', winePackage=package,
                         componentVersion=1, runtimeFiles={}, wineRuntimeFiles={},
                         expandedBytes=sum(r['bytes'] for r in self.records), assets={})
        self.pack_source()

    def pack_source(self):
        with tarfile.open(self.source / 'delta.tgz', 'w:gz') as archive:
            for r in self.records:
                if 'source' not in r:
                    data = self.files[r['path']]
                    info = tarfile.TarInfo(r['path']); info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
            for name, target in b.LINKS.items():
                info = tarfile.TarInfo(name); info.type = tarfile.SYMTYPE; info.linkname = target
                archive.addfile(info)
        (self.source / 'manifest.json').write_text(json.dumps(self.records))
        (self.source / 'bundle.json').write_text(json.dumps(self.meta))

    def build(self):
        return b.build(self.source, self.scripts, self.output)

    def test_three_backends_restore_portable_menu_and_preserve_system_files(self):
        for index, package in enumerate(b.PACKAGES):
            with self.subTest(package=package):
                if index:
                    self.make_source(package)
                self.output = self.base / ('output-' + package)
                metadata = self.build()
                records = {r['path']: r for r in json.loads((self.output / 'manifest.json').read_text())}
                for original in self.records:
                    self.assertEqual(original, records[original['path']])
                self.assertEqual(metadata['componentVersion'], 1)
                target = self.base / ('restored-' + package)
                with contextlib.redirect_stdout(io.StringIO()):
                    restore.restore(self.rootfs, self.output, target, 'a' * 64, str(self.rootfs),
                                    package, self.wine)
                for name, data in self.files.items():
                    self.assertEqual(data, (target / name).read_bytes())
                for entry in b.menu_entries():
                    raw = (target / b.MENU_ROOT / (entry['name'] + '.lnk')).read_bytes()
                    link = pylnk3.parse(io.BytesIO(raw))
                    self.assertEqual(entry['target'], link.path)
                    self.assertEqual(entry['workingDirectory'], link.work_dir)
                    self.assertEqual(entry['arguments'], link.arguments or '')
                    path = target / ('drive_c/' + link.path[3:].replace('\\', '/'))
                    self.assertTrue(path.is_file())
                    self.assertNotIn(b'glibc', raw)
                self.assertTrue((target / 'drive_c/Tools/TrueTransparency/TrueTransparencyHook.dll').is_file())
                self.assertFalse((target / 'drive_c/Tools/7z.exe').exists())
                self.assertFalse((target / 'drive_c/Tools/unused-installer.exe').exists())

    def test_output_and_release_archives_are_reproducible(self):
        self.build()
        second = self.base / 'second'
        b.build(self.source, self.scripts, second)
        for name in ('manifest.json', 'bundle.json', 'delta.tgz'):
            self.assertEqual((self.output / name).read_bytes(), (second / name).read_bytes())
        files = [self.base / 'one.tar.gz', self.base / 'two.tar.gz']
        for output in files:
            packager.package(self.output, output)
        self.assertEqual(files[0].read_bytes(), files[1].read_bytes())

    def test_missing_builtin_is_rejected(self):
        self.records = [r for r in self.records if not r['path'].endswith('/winecfg.exe')]
        self.pack_source()
        with self.assertRaisesRegex(ValueError, 'menu_target_missing'):
            self.build()

    def test_missing_control_panel_argument_is_rejected(self):
        self.records = [r for r in self.records if not r['path'].endswith('/joy.cpl')]
        self.pack_source()
        with self.assertRaisesRegex(ValueError, 'controller_panel_missing'):
            self.build()

    def test_missing_tool_dependency_is_rejected(self):
        del self.tools[b.TT + 'TrueTransparencyHook.dll']
        self.pack_scripts()
        with self.assertRaisesRegex(ValueError, 'tool_resources_missing'):
            self.build()

    def test_wrong_tool_architecture_is_rejected(self):
        self.tools['paint.exe'] = pe(0xaa64)
        self.pack_scripts()
        with self.assertRaisesRegex(ValueError, 'tool_architecture_mismatch'):
            self.build()

    def test_existing_file_cannot_be_replaced(self):
        name = 'drive_c/Tools/paint.exe'
        data = b'existing customized tool'
        self.files[name] = data
        self.records.append(dict(path=name, bytes=len(data), mode=0o600, mtime=0, sha256=b.sha(data)))
        self.pack_source()
        with self.assertRaisesRegex(ValueError, 'overlay_collision'):
            self.build()

    def test_native_bundle_is_rejected(self):
        self.meta['backend'] = 'glibc_termux_box'
        self.pack_source()
        with self.assertRaisesRegex(ValueError, 'unsupported_bundle'):
            self.build()

    def test_unknown_runtime_without_schema_is_rejected(self):
        self.meta.pop('schemaVersion')
        self.meta['winePackage'] = 'unsupported'
        self.pack_source()
        with self.assertRaisesRegex(ValueError, 'unsupported_bundle'):
            self.build()

    def test_archive_escape_is_rejected(self):
        self.tools['TrueTransparency/../../escape'] = b'escape'
        self.pack_scripts()
        with self.assertRaisesRegex(ValueError, 'invalid_path'):
            self.build()
        self.assertFalse((self.base / 'escape').exists())

    def test_refuses_existing_output(self):
        self.build()
        before = (self.output / 'bundle.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'output_exists'):
            self.build()
        self.assertEqual(before, (self.output / 'bundle.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
