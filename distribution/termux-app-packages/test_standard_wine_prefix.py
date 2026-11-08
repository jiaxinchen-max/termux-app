#!/usr/bin/env python3
"""Exercise packaging of a shared skeleton with distinct selected Wine bytes."""
import importlib.util
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('package_prefix', Path(__file__).with_name('package-standard-wine-prefix.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def pe(machine, payload=b''):
    data = bytearray(128)
    data[:2] = b'MZ'
    struct.pack_into('<I', data, 60, 64)
    data[64:68] = b'PE\0\0'
    struct.pack_into('<H', data, 68, machine)
    return bytes(data) + payload


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.reference = self.base/'reference'
        self.wine = self.base/'selected'
        self.prefix = self.base/'prefix'
        self.rootfs = self.base/'rootfs'
        self.recipe = self.base/'recipe'
        self.archive = self.base/'component.tar.xz'
        self.evidence = self.base/'evidence.json'
        self.output = self.base/'output'
        for name, data in {'bin/wine64':b'loader', 'bin/wineserver':b'server',
                           'lib/wine/x86_64-windows/ntdll.dll':pe(0x8664),
                           'lib/wine/i386-windows/ntdll.dll':pe(0x14c)}.items():
            self.write(self.reference/name, data)
            self.write(self.wine/name, data)
        for arch, folder in [('x86_64-windows','system32'), ('i386-windows','syswow64')]:
            self.write(self.prefix/('drive_c/windows/'+folder+'/ntdll.dll'),
                       (self.reference/('lib/wine/'+arch+'/ntdll.dll')).read_bytes())
        for name in ['system.reg','user.reg','userdef.reg']:
            self.write(self.prefix/name,b'WINE REGISTRY Version 2\n#arch=win64\n"LocaleName"="zh-CN"\n')
        self.write(self.prefix/'.update-timestamp',b'0\n')
        for name in ['NotoSansCJK-Regular.ttc','NotoSansCJK-Bold.ttc']:
            self.write(self.rootfs/('usr/share/fonts/opentype/noto/'+name),b'font')
        for name in ['setup-container.sh','games-runtime.properties']:
            self.write(self.recipe/name,b'recipe')
        self.evidence.write_text(json.dumps(dict(exitCode=0,serverExitCode=0,user='root',locale='zh_CN.UTF-8',sourceComponentSha256='f'*64)))
        self.archive_component()

    def write(self, path, data):
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(data)

    def archive_component(self):
        with tarfile.open(self.archive,'w:xz') as t:
            t.add(self.wine,arcname=self.wine.name)

    def build(self):
        return m.build(self.prefix,self.reference,self.wine,self.archive,'rootfs_wine_10.0',1,
                       m.digest(self.archive.read_bytes()),self.rootfs,self.recipe,self.evidence,self.output)

    def test_selected_dll_replaces_reference_and_new_builtins_are_included(self):
        source='lib/wine/x86_64-windows/ntdll.dll'
        selected=pe(0x8664,b'selected version')
        self.write(self.wine/source,selected)
        self.write(self.wine/'lib/wine/x86_64-windows/new.dll',pe(0x8664,b'new'))
        self.archive_component()
        self.build()
        records={r['path']:r for r in json.loads((self.output/'manifest.json').read_text())}
        record=records['drive_c/windows/system32/ntdll.dll']
        self.assertEqual(m.digest(selected),record['sha256'])
        self.assertEqual('wine',record['sourceRoot'])
        self.assertIn('drive_c/windows/system32/new.dll',records)
        with tarfile.open(self.output/'delta.tgz') as t:
            self.assertNotIn('drive_c/windows/system32/ntdll.dll',t.getnames())
            self.assertEqual(b'disable\n',t.extractfile('.update-timestamp').read())

    def test_wrong_selected_pe_architecture_rejected(self):
        self.write(self.wine/'lib/wine/x86_64-windows/ntdll.dll',pe(0xaa64))
        self.archive_component()
        with self.assertRaisesRegex(AssertionError,'ntdll.dll'):self.build()

    def test_renamed_winsxs_builtin_keeps_v6_source(self):
        source='lib/wine/x86_64-windows/comctl32_v6.dll'
        data=pe(0x8664,b'common controls v6')
        self.write(self.reference/source,data)
        self.write(self.wine/source,data)
        target='drive_c/windows/winsxs/amd64_common-controls/comctl32.dll'
        self.write(self.prefix/target,data)
        self.archive_component()
        self.build()
        records={r['path']:r for r in json.loads((self.output/'manifest.json').read_text())}
        self.assertEqual(source,records[target]['source'])
        self.assertEqual(m.digest(data),records[target]['sha256'])

    def test_mutated_component_tree_rejected(self):
        self.write(self.wine/'bin/wine64',b'mutated')
        with self.assertRaisesRegex(AssertionError,'bin/wine64'):self.build()

    def test_incomplete_reference_rejected(self):
        self.write(self.prefix/'system.reg',b'PendingFileRenameOperations')
        with self.assertRaisesRegex(AssertionError,'unfinished reference'):self.build()

    def test_host_registry_paths_rejected(self):
        self.write(self.prefix/'user.reg',b'"LocaleName"="zh-CN"\nprivate=/home/another-user')
        with self.assertRaisesRegex(AssertionError,'host path'):self.build()

    def test_reference_locale_mismatch_rejected(self):
        self.write(self.prefix/'user.reg',b'"LocaleName"="en-US"\n')
        with self.assertRaisesRegex(AssertionError,'reference locale mismatch'):self.build()

    def test_system_font_links_cover_menu_fonts_and_both_registry_views(self):
        self.build()
        with tarfile.open(self.output/'delta.tgz') as archive:
            system=archive.extractfile('system.reg').read()
            self.assertIn(b'"MS Shell Dlg"="Noto Sans CJK SC"',system)
            self.assertIn(b'Wow6432Node\\\\Microsoft',system)
            self.assertIn(b'"Tahoma Bold"=hex(7):',system)

    def menu_registry(self, command=r'C:\\windows\\system32\\winemenubuilder.exe -a -r'):
        return ('WINE REGISTRY Version 2\n#arch=win64\n\n'
                r'[Software\\Microsoft\\Windows\\CurrentVersion\\RunServices]' + '\n'
                '"winemenubuilder"="' + command + '"\n'
                '"OtherService"="keep.exe"\n\n'
                '[Unrelated] 123\n"winemenubuilder"="keep this value"\n').encode()

    def test_missing_menu_helper_hook_removed_in_asset_not_other_services(self):
        original = self.menu_registry()
        self.write(self.prefix/'system.reg', original)
        meta = self.build()
        with tarfile.open(self.output/'delta.tgz') as archive:
            registry = archive.extractfile('system.reg').read()
        self.assertNotIn(b'winemenubuilder.exe', registry)
        self.assertIn(b'"OtherService"="keep.exe"', registry)
        self.assertIn(b'"winemenubuilder"="keep this value"', registry)
        self.assertEqual(original, (self.prefix/'system.reg').read_bytes())
        self.assertEqual(1, len(meta['provenance']['omittedStartupEntries']))

    def test_menu_hook_preserved_when_selected_component_provides_program(self):
        original = self.menu_registry()
        self.write(self.prefix/'system.reg', original)
        self.write(self.wine/'lib/wine/x86_64-windows/winemenubuilder.exe', pe(0x8664))
        self.archive_component()
        meta = self.build()
        with tarfile.open(self.output/'delta.tgz') as archive:
            self.assertIn(b'winemenubuilder.exe', archive.extractfile('system.reg').read())
        records = {r['path'] for r in json.loads((self.output/'manifest.json').read_text())}
        self.assertIn('drive_c/windows/system32/winemenubuilder.exe', records)
        self.assertEqual([], meta['provenance']['omittedStartupEntries'])

    def test_unknown_menu_hook_command_rejected_instead_of_silently_removed(self):
        self.write(self.prefix/'system.reg', self.menu_registry('unknown.exe'))
        with self.assertRaisesRegex(AssertionError, 'unrecognized winemenubuilder'):
            self.build()

    def test_32bit_view_menu_hook_uses_32bit_component(self):
        original = self.menu_registry(r'C:\\windows\\syswow64\\winemenubuilder.exe -a -r')
        original = original.replace(b'Software\\\\Microsoft', b'Software\\\\Wow6432Node\\\\Microsoft')
        self.write(self.prefix/'system.reg', original)
        self.write(self.wine/'lib/wine/x86_64-windows/winemenubuilder.exe', pe(0x8664))
        self.archive_component()
        meta = self.build()
        self.assertEqual('lib/wine/i386-windows/winemenubuilder.exe',
                         meta['provenance']['omittedStartupEntries'][0]['missingComponentFile'])

    def test_proton_maps_reference_profile_without_reinitialization(self):
        self.write(self.prefix/'user.reg',b'"LocaleName"="zh-CN"\n"USERNAME"="root"\n"USERPROFILE"="C:\\\\users\\\\root"\n')
        self.write(self.prefix/'drive_c/users/root/Desktop/desktop.ini',b'desktop')
        self.write(self.wine/'bin/wine',b'proton loader')
        self.archive_component()
        meta=m.build(self.prefix,self.reference,self.wine,self.archive,'rootfs_proton_11.0',1,
                     m.digest(self.archive.read_bytes()),self.rootfs,self.recipe,self.evidence,self.output)
        self.assertEqual('steamuser',meta['windowsUser'])
        with tarfile.open(self.output/'delta.tgz') as archive:
            self.assertIn('drive_c/users/steamuser/Desktop/desktop.ini',archive.getnames())
            self.assertNotIn('drive_c/users/root/Desktop/desktop.ini',archive.getnames())
            registry=archive.extractfile('user.reg').read()
            self.assertIn(b'"USERNAME"="steamuser"',registry)
            self.assertNotIn(b'users\\\\root',registry)


if __name__=='__main__':unittest.main()
