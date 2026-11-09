#!/usr/bin/env python3
"""Native restoration and hostile-input checks using real files and archives."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import time
import signal
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('restore_native', ASSETS / 'restore_wine_prefix.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class NativeRestoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve()
        self.glibc = self.base / 'files/usr/glibc'
        self.wine = self.glibc / 'glibc_wine_10.0'
        self.resource = self.base / 'component'
        self.resource.mkdir()
        self.target = self.base / 'prefix'
        self.data = {
            'system.reg': b'WINE REGISTRY Version 2\n"Tahoma"="Noto Sans CJK SC"\n'
                          b'"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"\n',
            'user.reg': b'WINE REGISTRY Version 2\n"prefix"="@NATIVE_PREFIX@"\n',
            'userdef.reg': b'WINE REGISTRY Version 2\n', '.update-timestamp': b'disable\n'}
        self.sources = {
            'drive_c/windows/system32/ntdll.dll': ('wine', 'lib/wine/x86_64-windows/ntdll.dll', b'pe64'),
            'drive_c/windows/syswow64/ntdll.dll': ('wine', 'lib/wine/i386-windows/ntdll.dll', b'pe32'),
            'drive_c/windows/Fonts/NotoSansCJK-Regular.ttc': ('rootfs', 'share/fonts/NotoSansCJK-Regular.ttc', b'font'),
            'drive_c/windows/Fonts/NotoSerifCJK-Regular.ttc': ('rootfs', 'share/fonts/NotoSerifCJK-Regular.ttc', b'serif')}
        for _, (root, name, data) in self.sources.items():
            path = (self.wine if root == 'wine' else self.glibc) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.records = []
        for name, data in self.data.items():
            self.records.append(dict(path=name, bytes=len(data), mode=0o600, mtime=0,
                                     sha256=hashlib.sha256(data).hexdigest()))
        for name, (root, source, data) in self.sources.items():
            self.records.append(dict(path=name, bytes=len(data), mode=0o600, mtime=0,
                                     sha256=hashlib.sha256(data).hexdigest(), source=source, sourceRoot=root))
        self.meta = dict(schemaVersion=3, backend='glibc_termux_box', hostAbi='aarch64',
                         prefixArch='wow64', peMachine=0x8664, translator='box64',
                         winePackage='glibc_wine_10.0', componentVersion=1,
                         sourceComponentSha256='b' * 64, prefixComponentId='native_prefix_wine_10.0',
                         windowsUser='root', runtimeFiles={}, wineRuntimeFiles={})
        (self.resource / '.games-component.properties').write_text(
            'schemaVersion=1\npackageName=native_prefix_wine_10.0\nversion=1\nsha256=' + 'c' * 64 + '\n')
        self.pack()

    def pack(self, extra=None):
        with tarfile.open(self.resource / 'delta.tgz', 'w:gz') as archive:
            for name, data in self.data.items():
                member = tarfile.TarInfo(name); member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            for name, link in m.LINKS.items():
                member = tarfile.TarInfo(name); member.type = tarfile.SYMTYPE; member.linkname = link
                archive.addfile(member)
            if extra is not None: archive.addfile(extra)
        self.pin()

    def pin(self):
        (self.resource / 'manifest.json').write_text(json.dumps(self.records))
        self.meta['expandedBytes'] = sum(r['bytes'] for r in self.records)
        self.meta['assets'] = {name: m.sha(self.resource / name) for name in ('delta.tgz', 'manifest.json')}
        (self.resource / 'bundle.json').write_text(json.dumps(self.meta))
        self.bundle_sha = m.sha(self.resource / 'bundle.json')

    def restore(self, **kwargs):
        args = dict(wine_root=self.wine, backend='glibc_termux_box', bundle_sha256=self.bundle_sha)
        args.update(kwargs)
        with contextlib.redirect_stdout(io.StringIO()):
            m.restore(self.glibc, self.resource, self.target, 'a' * 64,
                      str(self.glibc), 'glibc_wine_10.0', **args)

    def reject(self, message, **kwargs):
        with self.assertRaisesRegex((ValueError, OSError), message): self.restore(**kwargs)
        self.assertFalse(self.target.exists())
        self.assertFalse(self.target.with_name('prefix.prebuilt-stage').exists())

    def test_native_independent_files_maps_fonts_user_and_path(self):
        self.restore()
        self.assertEqual(os.readlink(self.target / 'dosdevices/z:'), str(self.base / 'files'))
        self.assertEqual(os.readlink(self.target / 'dosdevices/d:'), '/sdcard/Download')
        self.assertEqual((self.target / '.games-runtime/native-user').read_text(), 'root\n')
        self.assertTrue((self.target / 'termux-boxmeta/games-wine-fonts-v2').is_file())
        self.assertNotIn('@NATIVE_PREFIX@', (self.target / 'user.reg').read_text())
        source = self.wine / 'lib/wine/x86_64-windows/ntdll.dll'
        target = self.target / 'drive_c/windows/system32/ntdll.dll'
        self.assertNotEqual(source.stat().st_ino, target.stat().st_ino)
        target.write_bytes(b'game override')
        self.assertEqual(source.read_bytes(), b'pe64')

    def test_bundle_must_match_apk(self): self.reject('bundle_digest_mismatch', bundle_sha256='f' * 64)
    def test_bundle_cannot_be_omitted(self): self.reject('bundle_digest_mismatch', bundle_sha256=None)
    def test_proot_bundle_cannot_cross_backend(self):
        self.meta['backend'] = 'rootfs_proot'; self.pin(); self.reject('identity_mismatch')
    def test_native_bundle_cannot_enter_proot(self): self.reject('identity_mismatch', backend='rootfs_proot')
    def test_adjacent_wine_root_rejected(self): self.reject('native_wine_root_mismatch', wine_root=self.glibc)
    def test_wrong_prefix_resource_receipt_rejected(self):
        (self.resource / '.games-component.properties').write_text('schemaVersion=1\npackageName=wrong\nversion=1\n')
        self.reject('component_mismatch')
    def test_native_runtime_guard_checked(self):
        self.meta['wineRuntimeFiles'] = {'lib/wine/x86_64-windows/ntdll.dll': 'd' * 64}
        self.pin(); self.reject('runtime_mismatch')
    def test_changed_dll_rejected(self):
        (self.wine / 'lib/wine/x86_64-windows/ntdll.dll').write_bytes(b'changed')
        self.reject('source_size_mismatch')
    def test_wrong_pe_architecture_metadata_rejected(self):
        self.meta['peMachine'] = 0xaa64; self.pin(); self.reject('identity_mismatch')
    def test_unsafe_source_rejected(self):
        self.records[-1]['source'] = '../outside'; self.pin(); self.reject('path_invalid')
    def test_proot_font_source_rejected(self):
        self.records[-1]['source'] = 'usr/share/fonts/font'; self.pin(); self.reject('source_invalid')
    def test_symlink_escape_rejected(self):
        source = self.wine / 'lib/wine/x86_64-windows/ntdll.dll'
        outside = self.base / 'outside'; outside.write_bytes(b'pe64')
        source.unlink(); source.symlink_to(outside); self.reject('source_invalid')
    def test_nonempty_prefix_is_preserved(self):
        self.target.mkdir(); (self.target / 'save.dat').write_text('keep')
        with self.assertRaisesRegex(ValueError, 'destination_not_empty'): self.restore()
        self.assertEqual((self.target / 'save.dat').read_text(), 'keep')
    def test_unsafe_link_rejected(self):
        member = tarfile.TarInfo('drive_c/escape'); member.type = tarfile.SYMTYPE; member.linkname = '/'
        self.pack(member); self.reject('link_invalid')
    def test_missing_space_rejected(self):
        with patch.object(m.shutil, 'disk_usage', return_value=type('Usage', (), {'free': 0})()):
            self.reject('insufficient_storage')
    def test_publish_failure_cleans_stage(self):
        with patch.object(m.os, 'rename', side_effect=OSError('publication failure')):
            self.reject('publication failure')
    def test_invalid_font_registry_rejected(self):
        self.data['system.reg'] = b'WINE REGISTRY Version 2';
        self.records[0].update(bytes=len(self.data['system.reg']), sha256=hashlib.sha256(self.data['system.reg']).hexdigest())
        self.pack(); self.reject('native_fonts_invalid')
    def test_user_cannot_inject_account(self):
        self.meta['windowsUser'] = '../bad'; self.pin(); self.reject('native_user_invalid')

    def bootstrap(self, asynchronous=False, locale_ready=True):
        runtime = self.base / 'runtime'
        runtime.mkdir(exist_ok=True)
        root = ASSETS.parents[4]
        # Relocate Android's fixed private directory only; execute the real bootstrap/helpers.
        script = root / 'app/src/main/assets/bootstrap_termux_box.sh'
        (runtime / script.name).write_text(script.read_text().replace('/data/data/com.termux/files',
                                                                   str(self.base / 'files')))
        for name in ('native_runtime_components.sh', 'glibc_prefix_extras.sh', 'restore_wine_prefix.py'):
            (runtime / name).write_bytes((ASSETS / name).read_bytes())
        if asynchronous:
            helper = runtime / 'restore_wine_prefix.py'
            # Hold inside the real restoration transaction so SIGTERM exercises cleanup.
            helper.write_text(helper.read_text().replace('    count = 0\n',
                '    import time\n    time.sleep(30)\n    count = 0\n', 1))
        for path in ('lib/ld-linux-aarch64.so.1', 'bin/box64', 'opt/virgl/libvirgl_test_server.so',
                     'glibc_wine_10.0/bin/wine', 'glibc_wine_10.0/bin/wineserver'):
            p = self.glibc / path; p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('#!/bin/sh\necho forbidden-wine-execution >&2\nexit 99\n'); p.chmod(0o700)
        python = self.glibc.parent / 'bin/python'
        python.parent.mkdir(exist_ok=True); python.symlink_to(sys.executable) if not python.exists() else None
        locale = self.glibc / 'termux-box/run/games-zh-cn-locale-v1'
        if locale_ready:
            locale.parent.mkdir(parents=True, exist_ok=True); locale.touch()
            for name in ('zh_CN.utf8', 'zh_CN.gbk'):
                data=self.glibc/'lib/locale'/name/'LC_CTYPE'
                data.parent.mkdir(parents=True,exist_ok=True);data.write_bytes(b'locale')
        generator=self.glibc/'bin/locale-gen'
        generator.write_text('#!/bin/sh\necho forbidden-locale-generation >&2\nexit 99\n')
        generator.chmod(0o700)
        config = self.base / 'native.conf'
        config.write_text("TERMUX_BOX_CONTAINER_PREFIX='" + str(self.target) + "'\n"
                          "TERMUX_BOX_WINE_PACKAGE=glibc_wine_10.0\n"
                          "TERMUX_BOX_GRAPHICS_DRIVER=virgl\nTERMUX_BOX_DXWRAPPER=wined3d\n"
                          "NATIVE_PREFIX_ASSETS='" + str(self.resource) + "'\n"
                          "NATIVE_PREFIX_IDENTITY=" + 'a' * 64 + '\n'
                          "NATIVE_PREFIX_BUNDLE_SHA256=" + self.bundle_sha + '\n')
        if asynchronous:
            return subprocess.Popen(['sh', str(runtime / script.name), str(config)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return subprocess.run(['sh', str(runtime / script.name), str(config)],
                              capture_output=True, text=True, timeout=15)

    def test_real_bootstrap_never_executes_wine_and_reuses_prepared_prefix(self):
        result = self.bootstrap()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.target / '.termux-box-bootstrap-done').is_file())
        (self.target / 'save.dat').write_text('keep')
        result = self.bootstrap()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.target / 'save.dat').read_text(), 'keep')
        self.assertNotIn('forbidden-wine-execution', result.stderr)

    def test_bootstrap_missing_locale_stops_without_generation_or_prefix_write(self):
        result=self.bootstrap(locale_ready=False)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('glibc_cjk_locale_missing',result.stdout)
        self.assertNotIn('forbidden-locale-generation',result.stderr)
        self.assertFalse(self.target.exists())

    def test_real_bootstrap_stops_on_asset_failure_without_recovery(self):
        (self.resource / 'delta.tgz').write_bytes(b'corrupt')
        result = self.bootstrap()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.target.exists())
        self.assertNotIn('forbidden-wine-execution', result.stderr)

    def test_cancelled_bootstrap_removes_unpublished_stage_and_lock(self):
        process = self.bootstrap(asynchronous=True)
        try:
            stage = self.target.with_name('prefix.prebuilt-stage')
            deadline = time.monotonic() + 5
            while not stage.exists() and time.monotonic() < deadline: time.sleep(0.02)
            self.assertTrue(stage.exists())
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(self.target.exists())
            self.assertFalse(stage.exists())
            self.assertFalse(self.target.with_name('prefix.bootstrap.lock').exists())
            self.assertNotIn('forbidden-wine-execution', stderr)
        finally:
            if process.poll() is None:
                process.kill(); process.communicate(timeout=5)


if __name__ == '__main__': unittest.main()
