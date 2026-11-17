#!/usr/bin/env python3
"""Component integrity, extraction safety, and failed-session cleanup boundaries."""
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import os
import signal
import struct
import subprocess
import tarfile
import tempfile
import types
import unittest
import sys
sys.dont_write_bytecode = True
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_hyprland.py'
spec = importlib.util.spec_from_file_location('hyprland', source)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


class ComponentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.prefix = patch.object(h, 'PREFIX', self.root / 'usr'); self.prefix.start()
        self.home = patch.object(h, 'HOME', self.root / 'home'); self.home.start()
        h.COMPONENTS['install_theme'].__globals__['PREFIX'] = h.PREFIX
        h.COMPONENTS['install_theme'](h.VERSION, 'm3ww-v1', Path(__file__).resolve().parents[2] / 'distribution/hyprland-themes/build/hyprland-theme-m3ww-v1.tgz')

    def tearDown(self):
        self.home.stop(); self.prefix.stop(); self.temp.cleanup()

    def testMissingGtkLayerShellFailsPreflightBeforeSessionStartup(self):
        failed = subprocess.CompletedProcess([], 1, '', 'GtkLayerShell typelib missing')
        with patch.object(Path, 'is_file', return_value=True), patch.object(h.subprocess, 'run', return_value=failed):
            with self.assertRaisesRegex(RuntimeError, 'requires PyGObject and GtkLayerShell'):
                h.verify_shell()
        self.assertFalse(h.work_for('desktop-test').exists())

    def archive(self, extra=None, missing=False):
        elf = bytearray(20); elf[:4] = b'\x7fELF'; elf[18:20] = struct.pack('<H',183)
        contents = {'bin/Hyprland': bytes(elf)}
        manifest = json.dumps({'needed': {'bin/Hyprland':[]},
                              'files': {name:hashlib.sha256(data).hexdigest() for name,data in contents.items()}}).encode()
        self.manifest_digest = hashlib.sha256(manifest).hexdigest()
        contents['manifest.json'] = manifest
        archive = self.root / 'candidate.tar.gz'
        with tarfile.open(archive,'w:gz') as tar:
            for name,data in contents.items():
                if missing and name=='bin/Hyprland': continue
                info=tarfile.TarInfo(name); info.size=len(data); info.mode=0o755
                tar.addfile(info,io.BytesIO(data))
            if extra is not None:
                tar.addfile(extra,io.BytesIO(b'x') if extra.isfile() else None)
        return archive

    def install(self,archive, **kwargs):
        with patch.object(h,'ARCHIVE_SHA256',h.digest(archive)), patch.object(h,'MANIFEST_SHA256',self.manifest_digest):
            h.install(archive, **kwargs)

    def testExplicitReinstallReplacesVerifiedRuntimeAndPreservesUserFiles(self):
        archive = self.archive(); self.install(archive)
        extra = h.runtime_root() / 'old-runtime-file'; extra.write_text('old')
        user = h.HOME / 'documents/keep'; user.parent.mkdir(parents=True); user.write_text('user data')
        self.install(archive)
        self.assertTrue(extra.exists())
        self.install(archive, reinstall=True)
        self.assertFalse(extra.exists())
        self.assertEqual('user data', user.read_text())
        self.assertEqual(self.manifest_digest, (h.runtime_root()/'.installed').read_text().strip())

    def testVerifiedInstallIsIdempotentAndDoesNotTouchSharedLibraries(self):
        shared = h.PREFIX/'lib/existing.so'; shared.parent.mkdir(parents=True);shared.write_text('preserve')
        archive=self.archive(); self.install(archive); self.install(archive)
        self.assertEqual('preserve',shared.read_text())
        self.assertEqual(self.manifest_digest,(h.runtime_root()/'.installed').read_text().strip())

    def testArchiveChecksumMismatchPublishesNothing(self):
        archive=self.archive()
        with patch.object(h,'ARCHIVE_SHA256','0'*64), self.assertRaisesRegex(RuntimeError,'SHA-256'):
            h.install(archive)
        self.assertFalse(h.runtime_root().exists())

    def testTraversalIsRejectedBeforePublishing(self):
        info=tarfile.TarInfo('../escaped');info.size=1
        archive=self.archive(info)
        with self.assertRaisesRegex(RuntimeError,'Unsafe'):self.install(archive)
        self.assertFalse(h.runtime_root().exists());self.assertFalse((self.root/'escaped').exists())

    def testSymlinkArchiveMemberIsRejected(self):
        info=tarfile.TarInfo('lib/link.so');info.type=tarfile.SYMTYPE;info.linkname='/etc/passwd'
        archive=self.archive(info)
        with self.assertRaisesRegex(RuntimeError,'Unsafe'):self.install(archive)
        self.assertFalse(h.runtime_root().exists())

    def testIncompletePayloadNeverGetsInstalledMarker(self):
        archive=self.archive(missing=True)
        with self.assertRaises(OSError):self.install(archive)
        self.assertFalse(h.runtime_root().exists())

    def testVerifiedRetryRepairsCorruptedRuntimeWithoutChangingUserConfig(self):
        archive=self.archive();self.install(archive)
        (h.runtime_root()/'bin/Hyprland').write_text('corrupted')
        self.install(archive)
        self.assertTrue((h.runtime_root()/'bin/Hyprland').read_bytes().startswith(b'\x7fELF'))

    def testAndroidIpcPathsFitUnixSocketLimitForBothAppPathAliases(self):
        import socket
        for prefix in ['/data/data/com.termux/files/usr','/data/user/0/com.termux/files/usr']:
            with patch.object(h,'PREFIX',Path(prefix)):
                path=h.session_runtime(h.work_for('desktop-2764280e'))/'hypr/a7fffffff_7fffffff/.socket2.sock'
                self.assertLessEqual(len(str(path).encode()),107)
        path=h.session_runtime(h.work_for('desktop-2764280e'))/'hypr/a7fffffff_7fffffff/.socket2.sock'
        path.parent.mkdir(parents=True)
        with socket.socket(socket.AF_UNIX) as endpoint:
            endpoint.bind(str(path))
        self.assertTrue(path.is_socket())

    def testReadyFixtureUsesItsOwnSocketAndCleansItOnCompositorExit(self):
        self.runReadyFixture()

    def testActivationEnvironmentFailureNeverPublishesReadinessAndCleansSession(self):
        self.runReadyFixture(activation_error=True)

    def testPreparedCompositorActuallyReceivesNewNativeOutputPixels(self):
        self.runReadyFixture(output_size=(2400, 1080))

    def runReadyFixture(self, activation_error=False, output_size=(1280, 720)):
        import threading
        root=h.runtime_root();(root/'bin').mkdir(parents=True)
        (h.PREFIX/'bin').mkdir()
        for name,target in [('env','/usr/bin/env'),('sh','/bin/sh'),('dbus-daemon','/usr/bin/dbus-daemon'),
                            ('dbus-update-activation-environment','/usr/bin/dbus-update-activation-environment')]:
            (h.PREFIX/'bin'/name).symlink_to(target)
        compositor=root/'bin/Hyprland'
        compositor.write_text("#!/usr/bin/python3\nimport os,socket,time\nfrom pathlib import Path\nr=Path(os.environ['XDG_RUNTIME_DIR'])\n(r/'fake.pid').write_text(str(os.getpid()))\ns=socket.socket(socket.AF_UNIX);s.bind(str(r/'wayland-1'));s.listen(4)\ntime.sleep(1.5)\n")
        compositor.write_text(compositor.read_text().replace('r=Path(os.environ',
            'Path(' + repr(str(self.root / 'output-environment')) + ').write_text(repr((os.environ["AQ_ANDROID_WIDTH"], os.environ["AQ_ANDROID_HEIGHT"], os.environ["AQ_ANDROID_REFRESH"])))\nr=Path(os.environ'))
        compositor.chmod(0o755)
        for binary in [root/'bin/hyprland-smoke-client',h.PREFIX/'bin/xfce4-terminal']:
            binary.write_text('#!/bin/sh\nexec /bin/sleep 30\n');binary.chmod(0o755)
        terminal=h.PREFIX/'bin/xfce4-terminal'
        terminal.write_text('#!/usr/bin/python3\nimport os,time\nfrom pathlib import Path\nPath('+repr(str(self.root/'client-environment'))+').write_text(repr((os.getenv("LD_LIBRARY_PATH"),os.getenv("LD_PRELOAD"))))\ntime.sleep(30)\n')
        ready=self.root/'ready';work=h.work_for('desktop-ready')
        args=types.SimpleNamespace(runtime_id=h.VERSION,theme_id='m3ww-v1',desktop_id='desktop-ready',ready_file=ready,run_mode='native',usb_storage='off',
                                   width=1280,height=720,refresh=60,alias=None,client_gl_mode='default')
        observed=[]
        def watch():
            for _ in range(100):
                if ready.exists():
                    observed.append(ready.read_text().strip()==str(os.getpid())
                                    and (work/'runtime/wayland-hyprland').is_socket())
                    return
                import time
                time.sleep(.03)
        def ipc(env,*arguments):
            if arguments[-1]=='instances':
                pid=int((work/'runtime/fake.pid').read_text())
                return json.dumps([{'pid':pid,'instance':'own-instance','wl_socket':'wayland-1'}])
            return json.dumps([{'name':'ANDROID-1','width':args.width,'height':args.height,'refreshRate':args.refresh}])
        watcher=threading.Thread(target=watch);watcher.start()
        try:
            with patch.object(h,'verify'),patch.object(h,'verify_shell'),patch.object(h,'prepare_shell',return_value=[]),patch.object(h,'ipc',side_effect=ipc):
                h.prepare(args)
                args.width, args.height = output_size
                with patch.object(h, 'prepare_shell', side_effect=AssertionError('startup initialization')), patch.dict(h.COMPONENTS, verify_theme=lambda *a: self.fail('startup theme scan')):
                    if activation_error:
                        with patch.object(h, 'update_activation_environment', side_effect=RuntimeError('activation denied')):
                            with self.assertRaisesRegex(RuntimeError, 'activation denied'):
                                h.session(args)
                    else:
                        self.assertEqual(0,h.session(args))
        finally:
            watcher.join()
        self.assertEqual([] if activation_error else [True],observed)
        if activation_error:
            self.assertFalse((self.root/'client-environment').exists())
        else:
            self.assertEqual('(None, None)',(self.root/'client-environment').read_text())
        self.assertEqual(repr((str(output_size[0]), str(output_size[1]), '60')), (self.root / 'output-environment').read_text())
        self.assertFalse(ready.exists());self.assertFalse((work/'session.json').exists())
        self.assertEqual([],list((work/'runtime').iterdir()))

    def testFailedBackendRemovesOldReadinessAndPreservesConfigAndOtherProcesses(self):
        binary=h.runtime_root()/'bin/Hyprland';binary.parent.mkdir(parents=True)
        binary.write_text('#!/bin/sh\nexit 42\n');binary.chmod(0o755)
        (h.PREFIX/'bin').mkdir()
        (h.PREFIX/'bin/env').symlink_to('/usr/bin/env')
        (h.PREFIX/'bin/dbus-daemon').symlink_to('/usr/bin/dbus-daemon')
        work=h.work_for('desktop-test');(work/'config').mkdir(parents=True)
        config=work/'config/hyprland.lua';config.write_text('-- keep my config\n')
        ready=self.root/'ready';ready.write_text('99999\n')
        unrelated=subprocess.Popen(['/bin/sleep','30'],start_new_session=True)
        args=types.SimpleNamespace(runtime_id=h.VERSION,theme_id='m3ww-v1',desktop_id='desktop-test',ready_file=ready,run_mode='native',usb_storage='off',
                                   width=1280,height=720,refresh=60,alias=None,client_gl_mode='default')
        try:
            with patch.object(h,'verify'),patch.object(h,'verify_shell'),patch.object(h,'prepare_shell',return_value=[]):
                h.prepare(args)
            with patch.object(h,'prepare_shell',side_effect=AssertionError('startup initialization')), self.assertRaisesRegex(RuntimeError,'before readiness'):
                h.session(args)
            self.assertFalse(ready.exists());self.assertFalse((work/'session.json').exists())
            self.assertEqual('-- keep my config\n',config.read_text())
            self.assertIsNone(unrelated.poll())
            self.assertEqual([],list((work/'runtime').iterdir()))
        finally:
            unrelated.terminate();unrelated.wait()

    def testFailedSaveRevokesPreparedSession(self):
        ready = self.root / 'ready'; ready.write_text('123\n')
        args = types.SimpleNamespace(runtime_id=h.VERSION, theme_id='m3ww-v1',
            desktop_id='desktop-missing', ready_file=ready, run_mode='native',usb_storage='off', alias=None, client_gl_mode='default', width=1280,height=720,refresh=60)
        work = h.work_for(args.desktop_id); work.mkdir(parents=True)
        (work / 'prepared-session.json').write_text('{}')
        (h.COMPONENTS['theme_root']('m3ww-v1') / 'eww.scss').write_text('damaged')
        with self.assertRaisesRegex(RuntimeError, 'resource mismatch'):
            h.prepare(args)
        self.assertFalse((work / 'prepared-session.json').exists())
        with patch.object(h,'prepare_shell',side_effect=AssertionError('startup initialization')), self.assertRaises(FileNotFoundError):
            h.session(args)
        self.assertFalse(ready.exists())

    def testSavedGuestWrappersUseEachLiveSessionTokenWithoutRewriting(self):
        bindir = h.PREFIX / 'bin'; bindir.mkdir()
        (bindir / 'env').symlink_to('/usr/bin/env')
        (bindir / 'sh').symlink_to('/bin/sh')
        executable = bindir / 'proot-distro'
        executable.write_text('#!/usr/bin/python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
        executable.chmod(0o700)
        # This wrapper test now prepares the real software-center dependencies too.
        guest = h.PREFIX / 'var/lib/proot-distro/containers/ubuntu-test/rootfs'
        status = guest / 'var/lib/dpkg/status'; status.parent.mkdir(parents=True)
        status.write_text('\n\n'.join('Package: '+name+'\nStatus: install ok installed' for name in (
            'gnome-software','gnome-software-plugin-deb','packagekit','appstream','apt-config-icons',
            'polkitd','python3-gi','gir1.2-polkit-1.0','zenity')))
        for name in ('usr/bin/gnome-software','usr/bin/zenity','usr/bin/python3','usr/bin/dbus-daemon',
                     'usr/bin/appstreamcli','usr/lib/polkit-1/polkitd','usr/libexec/packagekitd','usr/bin/xfce4-terminal','usr/bin/thunar','usr/bin/swaybg',
                     'usr/bin/foot','usr/bin/fish','usr/bin/starship',
                     'usr/local/lib/termux-hyprland/bin/eww','usr/lib/aarch64-linux-gnu/xfce4/notifyd/xfce4-notifyd'):
            target = guest / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.touch(); target.chmod(0o755)
        args = types.SimpleNamespace(runtime_id=h.VERSION, theme_id='m3ww-v1',
            desktop_id='guest-token', ready_file=self.root/'ready', run_mode='proot',usb_storage='off',
            alias='ubuntu-test', client_gl_mode='default', width=1280,height=720,refresh=60)
        original_install = h.COMPONENTS['install_theme']
        archive = Path(__file__).resolve().parents[2] / 'distribution/hyprland-themes/build/hyprland-theme-m3ww-v1.tgz'
        with patch.dict(h.COMPONENTS, install_theme=lambda runtime, theme: original_install(runtime, theme, archive)), patch.object(h, 'verify_guest_images'):
            h.prepare(args)
        wrapper = h.work_for(args.desktop_id) / 'bin/termux-hyprland-terminal'
        content = wrapper.read_bytes()
        for token in ('first-session', 'second-session'):
            command = json.loads(subprocess.check_output([str(wrapper)],
                env=dict(os.environ, TERMUX_HYPRLAND_SESSION=token), text=True))
            self.assertIn('TERMUX_HYPRLAND_SESSION=' + token, command)
            self.assertIn('DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/hyprland-runtime/guest-session-bus', command)
            self.assertNotIn('dbus-run-session', command)
            self.assertIn('/usr/local/lib/termux-hyprland/desktop_hyprland_guest.py', command)
            self.assertNotIn(token.encode(), content)
            self.assertEqual(content, wrapper.read_bytes())

    def testServiceCleanupSelectsOnlyItsOwnSessionToken(self):
        token = 'focused-cleanup-test-' + str(os.getpid())
        owned = subprocess.Popen(['/bin/sleep', '30'], env=dict(os.environ, TERMUX_HYPRLAND_SESSION=token))
        unrelated = subprocess.Popen(['/bin/sleep', '30'])
        try:
            self.assertIn(owned.pid, h.owned_service_pids(token))
            self.assertNotIn(unrelated.pid, h.owned_service_pids(token))
        finally:
            for process in [owned, unrelated]:
                process.terminate(); process.wait()


if __name__ == '__main__':
    unittest.main()
