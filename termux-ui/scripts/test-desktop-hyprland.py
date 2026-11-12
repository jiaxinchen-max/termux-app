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

    def install(self,archive):
        with patch.object(h,'ARCHIVE_SHA256',h.digest(archive)), patch.object(h,'MANIFEST_SHA256',self.manifest_digest):
            h.install(archive)

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
        import threading
        root=h.runtime_root();(root/'bin').mkdir(parents=True)
        (h.PREFIX/'bin').mkdir()
        for name,target in [('env','/usr/bin/env'),('sh','/bin/sh'),('dbus-daemon','/usr/bin/dbus-daemon')]:
            (h.PREFIX/'bin'/name).symlink_to(target)
        compositor=root/'bin/Hyprland'
        compositor.write_text("#!/usr/bin/python3\nimport os,socket,time\nfrom pathlib import Path\nr=Path(os.environ['XDG_RUNTIME_DIR'])\n(r/'fake.pid').write_text(str(os.getpid()))\ns=socket.socket(socket.AF_UNIX);s.bind(str(r/'wayland-1'));s.listen(4)\ntime.sleep(1.5)\n")
        compositor.chmod(0o755)
        for binary in [root/'bin/hyprland-smoke-client',h.PREFIX/'bin/xfce4-terminal']:
            binary.write_text('#!/bin/sh\nexec /bin/sleep 30\n');binary.chmod(0o755)
        terminal=h.PREFIX/'bin/xfce4-terminal'
        terminal.write_text('#!/usr/bin/python3\nimport os,time\nfrom pathlib import Path\nPath('+repr(str(self.root/'client-environment'))+').write_text(repr((os.getenv("LD_LIBRARY_PATH"),os.getenv("LD_PRELOAD"))))\ntime.sleep(30)\n')
        ready=self.root/'ready';work=h.work_for('desktop-ready')
        args=types.SimpleNamespace(desktop_id='desktop-ready',ready_file=ready,run_mode='native',
                                   width=1280,height=720,refresh=60,alias=None)
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
            return json.dumps([{'name':'ANDROID-1','width':1280,'height':720,'refreshRate':60}])
        watcher=threading.Thread(target=watch);watcher.start()
        try:
            with patch.object(h,'verify'),patch.object(h,'verify_shell'),patch.object(h,'prepare_shell',return_value=[]),patch.object(h,'ipc',side_effect=ipc):
                self.assertEqual(0,h.session(args))
        finally:
            watcher.join()
        self.assertEqual([True],observed)
        self.assertEqual('(None, None)',(self.root/'client-environment').read_text())
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
        args=types.SimpleNamespace(desktop_id='desktop-test',ready_file=ready,run_mode='native',
                                   width=1280,height=720,refresh=60,alias=None)
        try:
            with patch.object(h,'verify'),patch.object(h,'verify_shell'),patch.object(h,'prepare_shell',return_value=[]), self.assertRaisesRegex(RuntimeError,'before readiness'):
                h.session(args)
            self.assertFalse(ready.exists());self.assertFalse((work/'session.json').exists())
            self.assertEqual('-- keep my config\n',config.read_text())
            self.assertIsNone(unrelated.poll())
            self.assertEqual([],list((work/'runtime').iterdir()))
        finally:
            unrelated.terminate();unrelated.wait()

    def testMissingShellDependencyCannotKeepAnOldReadyMarker(self):
        ready = self.root / 'ready'; ready.write_text('123\n')
        args = types.SimpleNamespace(desktop_id='desktop-missing', ready_file=ready)
        work = h.work_for(args.desktop_id); work.mkdir(parents=True)
        (work / 'session.json').write_text('{"supervisor":123}')
        with patch.object(h, 'verify'), patch.object(h, 'verify_shell', side_effect=RuntimeError('dependency missing')):
            with self.assertRaisesRegex(RuntimeError, 'dependency missing'):
                h.session(args)
        self.assertFalse(ready.exists())
        self.assertFalse((work / 'session.json').exists())

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
