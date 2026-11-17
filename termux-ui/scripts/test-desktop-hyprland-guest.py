#!/usr/bin/env python3
"""Real GIO discovery/launch and guest IPC boundaries, without an Android GUI."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import signal
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('hyprland_guest', ASSETS / 'desktop_hyprland_guest.py')
guest = importlib.util.module_from_spec(spec); spec.loader.exec_module(guest)


class GuestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hg-'); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.user = self.root / 'user'; self.system = self.root / 'system'
        for directory in (self.user, self.system):
            (directory / 'applications').mkdir(parents=True)
        self.env = dict(os.environ, HOME=str(self.root), XDG_DATA_HOME=str(self.user),
                        XDG_DATA_DIRS=str(self.system), XDG_CONFIG_HOME=str(self.root / 'config'),
                        XDG_CURRENT_DESKTOP='Hyprland', LANG='C.UTF-8', TERMUX_HYPRLAND_SESSION='test-token')

    def desktop(self, name, extra='', user=False, executable='/bin/true', title='Editor'):
        path = (self.user if user else self.system) / 'applications' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('[Desktop Entry]\nType=Application\nName=' + title + '\nExec=' + executable + '\n' + extra)
        return path

    def evaluate(self, code):
        prefix = 'import runpy,json,os,time\nfrom pathlib import Path\ng=runpy.run_path(' + repr(str(ASSETS / 'desktop_hyprland_guest.py')) + ')\n'
        result = subprocess.run(['/usr/bin/dbus-run-session', '--', '/usr/bin/python3', '-I', '-B', '-c', prefix + code],
                                env=self.env, capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def testDesktopRulesUserOverrideNestedIdsAndSameNames(self):
        self.desktop('same.desktop'); self.desktop('same.desktop', 'Hidden=true\n', user=True)
        self.desktop('hidden.desktop', 'NoDisplay=true\n')
        self.desktop('xfce.desktop', 'OnlyShowIn=XFCE;\n')
        self.desktop('not.desktop', 'NotShowIn=Hyprland;\n')
        self.desktop('try.desktop', 'TryExec=definitely-uninstalled-hyprland-test\n')
        self.desktop('absent.desktop', executable='definitely-uninstalled-hyprland-test')
        self.desktop('nested/editor.desktop', 'OnlyShowIn=Hyprland;\n')
        self.desktop('second.desktop')
        result = self.evaluate("print(json.dumps([i['desktop_id'] for i in g['apps']()]))")
        self.assertEqual(['nested-editor.desktop', 'second.desktop'], result)

    def testLaunchUsesGioArgumentsWorkingDirectoryAndCurrentSession(self):
        output = self.root / 'result.json'; target = self.root / 'record.py'
        target.write_text('import os,sys,json\nfrom pathlib import Path\nPath(' + repr(str(output)) + ').write_text(json.dumps([sys.argv[1:],os.getcwd(),os.environ.get("TERMUX_HYPRLAND_SESSION")]))\n')
        self.desktop('record.desktop', 'Path=' + str(self.root) + '\n',
                     executable='/usr/bin/python3 ' + str(target) + ' "literal; touch unwanted"')
        result = self.evaluate("g['launch']('record.desktop')\np=Path(" + repr(str(output)) + ")\nfor _ in range(100):\n if p.exists():break\n time.sleep(.02)\nprint(p.read_text())")
        self.assertEqual([['literal; touch unwanted'], str(self.root), 'test-token'], result)
        self.assertFalse((self.root / 'unwanted').exists())

    def testScanDoesNotRunTargetsAndLaunchRechecksUnavailableEntry(self):
        marker = self.root / 'unwanted'
        self.desktop('inert.desktop', executable='/usr/bin/touch ' + str(marker))
        result = self.evaluate("items=g['apps']()\np=Path(" + repr(str(self.system / 'applications/inert.desktop')) + ")\np.unlink()\ntry:g['launch']('inert.desktop')\nexcept RuntimeError:print(json.dumps(len(items)))")
        self.assertEqual(1, result); self.assertFalse(marker.exists())

    def testDbusOnlyEntryUsesExistingSessionBus(self):
        path = self.desktop('org.example.HyprlandTest.desktop', 'DBusActivatable=true\n')
        path.write_text(path.read_text().replace('Exec=/bin/true\n', ''))
        result = self.evaluate("from gi.repository import Gio,GLib\n"
            "app=Gio.Application(application_id='org.example.HyprlandTest',flags=Gio.ApplicationFlags.DEFAULT_FLAGS)\n"
            "activated=[]\napp.connect('activate',lambda *_:activated.append(True))\napp.register(None)\n"
            "items=g['apps']()\ng['launch']('org.example.HyprlandTest.desktop')\n"
            "for _ in range(100):\n while GLib.MainContext.default().iteration(False):pass\n if activated:break\n time.sleep(.02)\n"
            "print(json.dumps([[i['desktop_id'] for i in items],activated]))")
        self.assertEqual([['org.example.HyprlandTest.desktop'], [True]], result)

    def testTerminalEntryPreservesLiteralArguments(self):
        output = self.root / 'terminal.json'; terminal = self.root / 'bin'
        terminal.mkdir()
        script = '#!/usr/bin/python3\nimport json,sys\nfrom pathlib import Path\nPath(' + repr(str(output)) + ').write_text(json.dumps(sys.argv[1:]))\n'
        for name in ('xdg-terminal-exec', 'gnome-terminal', 'x-terminal-emulator', 'rxvt', 'xterm'):
            path = terminal / name; path.write_text(script); path.chmod(0o755)
        self.env['PATH'] = str(terminal) + ':/usr/bin:/bin'
        self.desktop('terminal.desktop', 'Terminal=true\n', executable='/bin/echo "literal; no-shell"')
        result = self.evaluate("g['launch']('terminal.desktop')\np=Path(" + repr(str(output)) + ")\nfor _ in range(100):\n if p.exists():break\n time.sleep(.02)\nprint(p.read_text())")
        self.assertEqual(['/bin/echo', 'literal; no-shell'], result[-2:])

    def testLocalizedNamesAndInstalledSoftwareEntryNeedNoSpecialList(self):
        self.env['LANGUAGE'] = 'zh_CN'
        self.desktop('org.gnome.Software.desktop', 'Name[zh_CN]=软件中心\n', title='Software')
        result = self.evaluate("print(json.dumps([[i['name'],i['desktop_id']] for i in g['apps']()]))")
        self.assertEqual([['软件中心', 'org.gnome.Software.desktop']], result)

    def testInaccessibleApplicationDirectoryIsAnExplicitError(self):
        directory = self.system / 'applications'; directory.chmod(0)
        try:
            result = self.evaluate("try:g['apps']()\nexcept RuntimeError as e:print(json.dumps(str(e)))")
            self.assertIn('Application directory unavailable', result)
        finally:
            directory.chmod(0o755)

    def testDirectoryChangesBecomeVisibleInTheSameGioProcess(self):
        path = self.system / 'applications/new.desktop'
        text = '[Desktop Entry]\nType=Application\nName=New\nExec=/bin/true\n'
        result = self.evaluate("from gi.repository import Gio,GLib\nmonitor=Gio.AppInfoMonitor.get()\ninitial=g['apps']()\np=Path(" + repr(str(path)) + ")\np.write_text(" + repr(text) + ")\nfor _ in range(100):\n while GLib.MainContext.default().iteration(False):pass\n if g['apps']():break\n time.sleep(.03)\nadded=g['apps']()\np.unlink()\nfor _ in range(100):\n while GLib.MainContext.default().iteration(False):pass\n if not g['apps']():break\n time.sleep(.03)\nprint(json.dumps([len(initial),len(added),len(g['apps']())]))")
        self.assertEqual([0, 1, 0], result)

    def testIpcUsesOnlyCurrentUnixSocketAndNoHostExecutable(self):
        endpoint = self.root / 'hypr/test-instance/.socket.sock'; endpoint.parent.mkdir(parents=True)
        requests = []
        with socket.socket(socket.AF_UNIX) as server:
            server.bind(str(endpoint)); server.listen(1)
            def respond():
                connection, _ = server.accept()
                with connection:
                    requests.append(connection.recv(8192)); connection.sendall(b'{"id":2}')
            thread = threading.Thread(target=respond); thread.start()
            with patch.object(guest, 'RUNTIME', self.root), patch.dict(os.environ, HYPRLAND_INSTANCE_SIGNATURE='test-instance'):
                self.assertEqual('{"id":2}', guest.ipc('-j', 'activeworkspace'))
            thread.join(timeout=2)
        self.assertEqual([b'j/activeworkspace'], requests)
        with patch.dict(os.environ, HYPRLAND_INSTANCE_SIGNATURE='../other'):
            with self.assertRaises(RuntimeError): guest.ipc('-j', 'clients')

    def testGuestEnvironmentNeverUsesHostThemePathsAndHasOneBus(self):
        env = guest.environment(guest.guest_work('desktop-test'))
        self.assertEqual('unix:path=/tmp/hyprland-runtime/guest-session-bus', env['DBUS_SESSION_BUS_ADDRESS'])
        self.assertEqual('/usr', env['PREFIX'])
        self.assertIn('/home/termux/.local/share', env['XDG_DATA_DIRS'])
        self.assertNotIn('/data/data/', env['XDG_DATA_DIRS'])
        with self.assertRaises(ValueError): guest.guest_work('../other')

    def sessionFixture(self, fail):
        runtime = self.root / 'run'; runtime.mkdir()
        work = self.root / 'work'; work.mkdir()
        endpoint = runtime / 'hypr/test-instance/.socket.sock'; endpoint.parent.mkdir(parents=True)
        component = self.root / 'component.py'; trigger = self.root / 'fail'
        component.write_text('import os,time\nfrom pathlib import Path\nPath(' + repr(str(self.root / 'component.pid')) + ').write_text(str(os.getpid()))\nwhile not Path(' + repr(str(trigger)) + ').exists():time.sleep(.05)\n')
        notifier = self.root / 'notifier.py'
        notifier.write_text("from gi.repository import Gio,GLib\nowners=[Gio.bus_own_name(Gio.BusType.SESSION,n,Gio.BusNameOwnerFlags.NONE,None,None,None) for n in ('org.freedesktop.Notifications','org.xfce.Panel')]\nGLib.MainLoop().run()\n")
        (work / 'prepared-guest.json').write_text(json.dumps(dict(schema=1, theme='classic-r7', commands=[
            ['/usr/bin/python3', '-B', str(notifier)], ['/usr/bin/python3', '-B', str(component)]])))
        marker = runtime / 'guest-desktop.ready'; marker.write_text('stale-token\n')
        done = threading.Event()
        with socket.socket(socket.AF_UNIX) as server:
            server.bind(str(endpoint)); server.listen(10); server.settimeout(.1)
            def reply():
                while not done.is_set():
                    try: connection, _ = server.accept()
                    except socket.timeout: continue
                    with connection:
                        connection.recv(8192)
                        connection.sendall(b'{"output":{"levels":{"0":[{"namespace":"wallpaper"},{"namespace":"gtk-layer-shell"}]}}}')
            thread = threading.Thread(target=reply); thread.start()
            code = ('import runpy\nfrom pathlib import Path\ng=runpy.run_path(' + repr(str(ASSETS / 'desktop_hyprland_guest.py')) + ')\n'
                    + "g['run'].__globals__['RUNTIME']=Path(" + repr(str(runtime)) + ")\nraise SystemExit(g['run'](Path(" + repr(str(work)) + ')))')
            env = dict(self.env, HYPRLAND_INSTANCE_SIGNATURE='test-instance')
            process = subprocess.Popen(['/usr/bin/python3', '-I', '-B', '-c', code], env=env,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline and process.poll() is None:
                    if marker.exists() and marker.read_text().strip() == 'test-token': break
                    time.sleep(.03)
                self.assertTrue(marker.exists(), process.poll())
                self.assertEqual('test-token', marker.read_text().strip())
                state = json.loads((work / 'session.json').read_text())
                self.assertEqual('unix:path=' + str(runtime / 'guest-session-bus'), state['env']['DBUS_SESSION_BUS_ADDRESS'])
                child_pid = int((self.root / 'component.pid').read_text())
                if fail: trigger.touch()
                else: process.send_signal(signal.SIGTERM)
                output = process.communicate(timeout=10)[0].decode()
                self.assertEqual(1 if fail else 0, process.returncode, output)
                if fail: self.assertIn('Container desktop service exited', output)
                self.assertFalse(marker.exists()); self.assertFalse((work / 'session.json').exists())
                self.assertFalse(Path('/proc', str(child_pid)).exists())
                self.assertFalse((runtime / 'guest-session-bus').exists())
            finally:
                if process.poll() is None: process.kill(); process.communicate()
                done.set(); thread.join(timeout=2)

    def testSessionUsesRealBusAndCleansChildrenAndReadinessOnStop(self):
        self.sessionFixture(False)

    def testCriticalGuestClientExitRevokesReadyAndStopsTheBus(self):
        self.sessionFixture(True)


if __name__ == '__main__': unittest.main()
