#!/usr/bin/env python3
"""Exercise real env handoff and strict experimental GPU gates without claiming a GPU."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch, Mock
sys.dont_write_bytecode = True
ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
def module(name):
    spec = importlib.util.spec_from_file_location(name, ASSETS / (name + '.py'))
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    return mod
g = module('desktop_hyprland_gpu'); h = module('desktop_hyprland')


class GpuGateTests(unittest.TestCase):
    def testSoftwareAndNonAdrenoNeverPassHardwareGate(self):
        for renderer in ['zink (llvmpipe)', 'zink (Adreno via lavapipe)', 'SwiftShader', '', 'Mali G710']:
            with self.subTest(renderer=renderer), self.assertRaises(RuntimeError):
                g.require_hardware(renderer, True)
        g.require_hardware('zink Vulkan 1.3 (Turnip Adreno 740)', True)
        with self.assertRaisesRegex(RuntimeError, 'Expected Zink'):
            g.require_hardware('Adreno 740', True)

    def testHostGateDistinguishesBasicImportAndModifierEnumeration(self):
        base = dict(kgsl_access=True, dma_buf_import=True, dma_buf_format_query=False, drm_formats=[])
        g.validate_host(base)  # Older import extensions need not expose modifier queries.
        for updates, message in [(dict(kgsl_access=False), 'KGSL'),
                                 (dict(dma_buf_import=False), 'cannot import'),
                                 (dict(dma_buf_format_query=True), 'no importable')]:
            with self.subTest(updates=updates), self.assertRaisesRegex(RuntimeError, message):
                g.validate_host(dict(base, **updates))
        g.validate_host(dict(base, dma_buf_format_query=True, drm_formats=['0x34325241']))

    def testActualEnvHandoffRemovesHostLibrariesAndUsesOnlySelectedGuestPolicy(self):
        with tempfile.TemporaryDirectory() as temp:
            prefix = Path(temp); (prefix / 'bin').mkdir()
            (prefix / 'bin/env').symlink_to('/usr/bin/env')
            login = prefix / 'bin/proot-distro'
            # Stand-in for only the login boundary; execute the real guest env command.
            login.write_text('#!' + sys.executable + '\nimport os,sys\na=sys.argv[1:];i=a.index("--");os.execvp(a[i+1],a[i+1:])\n')
            login.chmod(0o755)
            inherited = dict(os.environ, LD_LIBRARY_PATH='/host/private',
                LD_PRELOAD='', VK_DRIVER_FILES='/stale/icd', QT_QUICK_BACKEND='software',
                XDG_CONFIG_HOME='/host/config', MESA_GL_VERSION_OVERRIDE='9.9',
                QT_PLUGIN_PATH='/host/bionic/qt', LIBGL_ALWAYS_INDIRECT='1')
            program = [sys.executable, '-c', 'import os,json;print(json.dumps(dict(os.environ)))']
            for mode in ['default', 'zink']:
                with patch.object(h, 'PREFIX', prefix):
                    cmd = h.container_command(Path(temp) / 'desktop', 'debian-1', mode, program, 'session-token')
                result = subprocess.run(cmd, env=inherited, capture_output=True, text=True, check=True)
                env = json.loads(result.stdout)
                for key in ['LD_PRELOAD', 'LD_LIBRARY_PATH', 'QT_QUICK_BACKEND', 'QT_PLUGIN_PATH', 'XDG_CONFIG_HOME', 'MESA_GL_VERSION_OVERRIDE', 'LIBGL_ALWAYS_INDIRECT']:
                    self.assertNotIn(key, env)
                self.assertEqual('wayland', env['GDK_BACKEND'])
                self.assertEqual('session-token', env['TERMUX_HYPRLAND_SESSION'])
                self.assertEqual(mode == 'zink', 'VK_DRIVER_FILES' in env)
                if mode == 'zink':
                    self.assertEqual('0', env['LIBGL_ALWAYS_SOFTWARE'])
                    self.assertEqual('zink', env['GALLIUM_DRIVER'])
                    self.assertEqual('vulkan', env['QSG_RHI_BACKEND'])

    def testBadAliasAndModeCannotLaunchCommands(self):
        for alias, mode in [('debian; touch /tmp/x', 'zink'), ('valid', 'auto'), ('../escape', 'default')]:
            with self.assertRaises(ValueError):
                h.container_command(Path('/unused'), alias, mode, ['true'], 'token')

    def testDefaultModeDoesNotProbeOrRewriteSelectedMode(self):
        # Strict failure is covered below; default command has no accidental software override.
        command = h.container_command(Path('/unused'), 'debian', 'default', ['true'], 'token')
        self.assertNotIn('LIBGL_ALWAYS_SOFTWARE=1', command)
        self.assertNotIn('GALLIUM_DRIVER=llvmpipe', command)
        self.assertNotIn('MESA_LOADER_DRIVER_OVERRIDE=zink', command)

    def testHostFailureNeverStartsCompositorOrPublishesReadiness(self):
        with tempfile.TemporaryDirectory() as temp:
            prefix = Path(temp) / 'usr'; home = Path(temp) / 'home'
            ready = Path(temp) / 'ready'; ready.write_text('stale')
            args = types.SimpleNamespace(desktop_id='desktop-zink', ready_file=ready,
                run_mode='proot', client_gl_mode='zink', alias='debian-1', width=1280, height=720, refresh=30)
            with patch.object(h, 'PREFIX', prefix), patch.object(h, 'HOME', home), patch.object(h, 'verify'), patch.object(h, 'verify_shell'), patch.object(h, 'prepare_shell', return_value=[]), patch.object(h, 'verify_zink_host', side_effect=RuntimeError('no dma-buf import')), patch.object(h.subprocess, 'Popen') as start:
                with self.assertRaisesRegex(RuntimeError, 'no dma-buf import'):
                    h.session(args)
                start.assert_not_called()
                work = h.work_for(args.desktop_id)
                self.assertFalse(ready.exists())
                self.assertFalse((work / 'session.json').exists())
                self.assertFalse(any((work / 'runtime').iterdir()))
                self.assertIn('MESA_LOADER_DRIVER_OVERRIDE=zink', (work / 'bin/termux-hyprland-terminal').read_text())

    def testNativeZinkIsRejectedBeforeRuntimeAccess(self):
        with patch.object(h, 'verify') as verify:
            with self.assertRaisesRegex(ValueError, 'only for PRoot'):
                h.session(types.SimpleNamespace(run_mode='native', client_gl_mode='zink'))
            verify.assert_not_called()

    def testExitedProbeIsFailureEvenWhenItsExitCodeIsZero(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); (work / 'runtime').mkdir()
            process = Mock(); process.poll.return_value = 0
            with patch.object(h.subprocess, 'Popen', return_value=process):
                with self.assertRaisesRegex(RuntimeError, 'failed to render/map'):
                    h.verify_zink_client(work, {'TERMUX_HYPRLAND_SESSION':'token'}, 'debian', None, [])
            self.assertFalse((work / 'gpu-client-report.json').exists())

    def testHardwareAndMappedWindowBothRequiredBeforeProbePasses(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); (work / 'runtime').mkdir()
            report = work / 'runtime/gpu-client-report.json'
            report.write_text(json.dumps(dict(status='Rendered', renderer='zink (Turnip Adreno 740)', frames=1)))
            process = Mock(returncode=0)
            # The probe creates its report only after Popen, exactly as a child would.
            def start(*args, **kwargs):
                report.write_text(json.dumps(dict(status='Rendered', renderer='zink (Turnip Adreno 740)', frames=1)))
                return process
            with patch.object(h.subprocess, 'Popen', side_effect=start), patch.object(h, 'ipc', return_value=json.dumps([dict(mapped=True, title='Hyprland GPU check')])):
                children = []; h.verify_zink_client(work, {'TERMUX_HYPRLAND_SESSION':'token'}, 'debian', None, children)
            self.assertEqual([], children)
            self.assertTrue(report.with_suffix('.ack').exists())
            self.assertTrue(json.loads((work / 'gpu-client-report.json').read_text())['mapped_wayland_window'])

    def testSoftwareProbeCannotPublishSuccessDespiteMappedWindow(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); (work / 'runtime').mkdir()
            report = work / 'runtime/gpu-client-report.json'
            def start(*args, **kwargs):
                report.write_text(json.dumps(dict(status='Rendered', renderer='zink (llvmpipe)', frames=1)))
                return Mock(returncode=0)
            with patch.object(h.subprocess, 'Popen', side_effect=start), patch.object(h, 'ipc', return_value=json.dumps([dict(mapped=True, title='Hyprland GPU check')])):
                with self.assertRaisesRegex(RuntimeError, 'hardware renderer'):
                    h.verify_zink_client(work, {'TERMUX_HYPRLAND_SESSION':'token'}, 'debian', None, [])
            self.assertFalse((work / 'gpu-client-report.json').exists())


    def testNonzeroExitCannotLeavePreviousPassRecord(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); (work / 'runtime').mkdir()
            (work / 'gpu-client-report.json').write_text('{"status":"PASS"}')
            report = work / 'runtime/gpu-client-report.json'
            def start(*args, **kwargs):
                report.write_text(json.dumps(dict(status='Rendered', renderer='zink (Turnip Adreno 740)', frames=1)))
                return Mock(returncode=7)
            with patch.object(h.subprocess, 'Popen', side_effect=start), patch.object(h, 'ipc', return_value=json.dumps([dict(mapped=True, title='Hyprland GPU check')])):
                with self.assertRaisesRegex(RuntimeError, 'exited unsuccessfully'):
                    h.verify_zink_client(work, {'TERMUX_HYPRLAND_SESSION':'token'}, 'debian', None, [])
            self.assertFalse((work / 'gpu-client-report.json').exists())


class MesaBackupTests(unittest.TestCase):
    def setUp(self):
        fixture_spec = importlib.util.spec_from_file_location('mesa_fixture', Path(__file__).with_name('test-desktop-install-mesa.py'))
        fixture = importlib.util.module_from_spec(fixture_spec); fixture_spec.loader.exec_module(fixture)
        self.m = fixture.installer
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name); self.root = self.base / 'rootfs'; self.root.mkdir()
        (self.root / 'etc').mkdir(); (self.root / 'etc/os-release').write_text('ID=debian\nVERSION_CODENAME=trixie\n')
        data = fixture.Fixture().fixture(); self.archive = self.base / 'mesa.gz'; self.archive.write_bytes(data)
        import hashlib
        self.m.ARCHIVE_BYTES = len(data); self.m.ARCHIVE_SHA256 = hashlib.sha256(data).hexdigest()
        self.dest = self.base / 'backup'
        self.loader = patch.object(g.runpy, 'run_path', return_value=vars(self.m)); self.loader.start(); self.addCleanup(self.loader.stop)
        self.file = self.root / self.m.REQUIRED[0]; self.file.parent.mkdir(parents=True)
        self.file.write_bytes(b'original Mesa'); self.file.chmod(0o755)
        self.link = self.root / (self.m.LIB + 'libEGL_mesa.so.0'); self.link.symlink_to('old-mesa.so')

    def testBackupPreservesOriginalBytesLinksModesAndRetryIdentity(self):
        import tarfile
        g.backup_mesa(self.archive, self.root, self.dest)
        identity = (self.dest / 'files-before.json').read_bytes()
        with tarfile.open(self.dest / 'files-before.tar.gz') as tar:
            self.assertEqual(b'original Mesa', tar.extractfile(self.m.REQUIRED[0]).read())
            self.assertEqual(0o755, tar.getmember(self.m.REQUIRED[0]).mode)
            self.assertEqual('old-mesa.so', tar.getmember(self.m.LIB + 'libEGL_mesa.so.0').linkname)
        self.file.write_bytes(b'new overlay')
        g.backup_mesa(self.archive, self.root, self.dest)
        self.assertEqual(identity, (self.dest / 'files-before.json').read_bytes())
        (self.dest / 'files-before.tar.gz').write_bytes(b'corrupt')
        with self.assertRaisesRegex(RuntimeError, 'identity mismatch'):
            g.backup_mesa(self.archive, self.root, self.dest)

    def testSymlinkReceiptOrAncestorIsRejectedWithoutTouchingOutsideFile(self):
        victim = self.base / 'victim'; victim.write_text('untouched')
        self.dest.mkdir(); (self.dest / 'files-before.json').symlink_to(victim)
        with self.assertRaises(OSError): g.backup_mesa(self.archive, self.root, self.dest)
        self.assertEqual('untouched', victim.read_text())
        ancestor = self.base / 'redirect'; ancestor.symlink_to(self.dest, target_is_directory=True)
        with self.assertRaises(OSError): g.backup_mesa(self.archive, self.root, ancestor / 'nested')

    def testBackupSizeFailureDoesNotPublishReceiptOrChangeOriginal(self):
        self.m.EXPANDED_LIMIT = 1
        # Archive decoding is independently tested; exercise the backup size boundary here.
        entries = {self.m.REQUIRED[0]: ('file', b'new', 0o755)}
        with patch.dict(vars(self.m), decode_archive=lambda _: entries):
            with self.assertRaises(ValueError): g.backup_mesa(self.archive, self.root, self.dest)
        self.assertFalse((self.dest / 'files-before.json').exists())
        self.assertEqual(b'original Mesa', self.file.read_bytes())


if __name__ == '__main__':
    unittest.main()
