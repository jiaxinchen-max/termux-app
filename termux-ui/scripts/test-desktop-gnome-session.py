#!/usr/bin/env python3
"""Exercise GNOME session probes against a real private D-Bus."""
import importlib.util
import os
from pathlib import Path
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('gnome_launcher', ASSETS / 'desktop_gnome_launch.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


@unittest.skipUnless(shutil.which('dbus-daemon') and shutil.which('dbus-send'), 'requires real D-Bus tools')
class SessionProbeTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='gnome-session-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.bus = subprocess.Popen(
            ['dbus-daemon', '--session', '--nofork', '--print-address=1'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(self.stop_bus)
        self.address = self.bus.stdout.readline().strip()
        self.assertTrue(self.address.startswith('unix:'))
        self.session_env = self.root / "session ' env"
        self.session_env.write_text('export DBUS_SESSION_BUS_ADDRESS=' + shlex.quote(self.address) + '\n')
        self.parent_env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS='unix:path=/nonexistent/parent-bus')
        self.bus_id = ['dbus-send', '--session', '--print-reply', '--dest=org.freedesktop.DBus',
                       '/org/freedesktop/DBus', 'org.freedesktop.DBus.GetId']

    def stop_bus(self):
        self.bus.terminate()
        self.bus.communicate(timeout=5)

    def test_probe_uses_session_bus_instead_of_inherited_host_bus(self):
        wrong = subprocess.run(self.bus_id, env=self.parent_env, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, wrong.returncode)
        expected = subprocess.run(self.bus_id, env=dict(self.parent_env, DBUS_SESSION_BUS_ADDRESS=self.address),
                                  capture_output=True, text=True, check=True, timeout=5)
        for execution in ([], ['env', '-i', 'PATH=' + os.environ['PATH']]):
            with self.subTest(execution=execution):
                result = subprocess.run(launcher.session_command(execution, '/bin/sh', self.session_env,
                                        self.bus_id), env=self.parent_env, capture_output=True,
                                        text=True, check=True, timeout=5)
                self.assertEqual(expected.stdout.split('string ')[1], result.stdout.split('string ')[1])

    def test_unavailable_session_bus_fails_even_if_parent_bus_is_healthy(self):
        self.session_env.write_text('export DBUS_SESSION_BUS_ADDRESS=unix:path=/nonexistent/session-bus\n')
        result = subprocess.run(launcher.session_command([], '/bin/sh', self.session_env, self.bus_id),
                                env=dict(self.parent_env, DBUS_SESSION_BUS_ADDRESS=self.address),
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)

    def test_probe_arguments_are_not_interpreted_as_shell_commands(self):
        marker = self.root / 'injected'
        argument = 'org.invalid; touch ' + str(marker)
        result = subprocess.run(launcher.session_command([], '/bin/sh', self.session_env,
                                ['dbus-send', '--session', '--print-reply', '--dest=org.freedesktop.DBus',
                                 '/org/freedesktop/DBus', argument]), env=self.parent_env,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(marker.exists())

    def test_slow_attempt_does_not_abort_subsequent_session_probe(self):
        timed_out = launcher.probe_session(
            [sys.executable, '-c', 'import time; time.sleep(10)'],
            self.parent_env, timeout=.05)
        self.assertEqual(124, timed_out.returncode)
        healthy = launcher.probe_session(
            launcher.session_command([], '/bin/sh', self.session_env, self.bus_id), self.parent_env)
        self.assertEqual(0, healthy.returncode, healthy.stdout)
        self.assertIn('string ', healthy.stdout)


@unittest.skipUnless(shutil.which('dbus-daemon') and shutil.which('dbus-run-session'), 'requires real D-Bus tools')
class LauncherLifecycleTests(unittest.TestCase):
    """Real launcher/buses with an explicit compositor fixture, without a GPU."""

    ubuntu_fixture = False

    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='gnome-life-', dir='/tmp')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        prefix = self.root / 'usr'
        self.prefix = prefix
        binaries = prefix / 'bin'
        binaries.mkdir(parents=True)
        home = self.root / 'home'
        home.mkdir()
        services = prefix / 'share/dbus-1/services'
        services.mkdir(parents=True)
        for name in ('ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gnome.Nautilus',
                     'org.gnome.Terminal', 'org.gnome.TextEditor', 'org.gnome.Settings', 'org.gnome.clocks'):
            (services / (name + '.service')).write_text('[D-BUS Service]\nName=' + name + '\nExec=/bin/false\n')
        self.write_executable(binaries / 'gsettings', '#!/bin/sh\n[ "$FORBID_STATIC_PROBES" != 1 ] || exit 99\nexit 0\n')
        # Slow the actual bootstrap's printf so direct writes to the final
        # filenames expose an empty file to the real launcher's polling loop.
        self.write_executable(binaries / 'sh', '''#!/bin/sh
case "$1" in -c|-ec) exec /bin/sh "$@" ;; esac
exec /bin/sh -c 'printf() { sleep .3; command printf "$@"; }; . "$1"' gnome-bootstrap "$@"
''')
        self.write_executable(binaries / 'gnome-shell', '#!' + sys.executable + '''
import ctypes, ctypes.util, os, socket, sys
from pathlib import Path
if '--version' in sys.argv:
    if os.environ.get('FORBID_STATIC_PROBES') == '1': raise SystemExit(99)
    print('GNOME Shell 50.1')
    raise SystemExit(0)
display = next(arg.split('=', 1)[1] for arg in sys.argv if arg.startswith('--wayland-display='))
wayland = socket.socket(socket.AF_UNIX)
wayland.bind(os.environ['XDG_RUNTIME_DIR'] + '/' + display)
dbus = ctypes.CDLL(ctypes.util.find_library('dbus-1'))
dbus.dbus_bus_get.argtypes = [ctypes.c_int, ctypes.c_void_p]
dbus.dbus_bus_get.restype = ctypes.c_void_p
dbus.dbus_bus_request_name.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint, ctypes.c_void_p]
dbus.dbus_connection_read_write_dispatch.argtypes = [ctypes.c_void_p, ctypes.c_int]
connection = dbus.dbus_bus_get(0, None)
assert connection
assert dbus.dbus_bus_request_name(connection, b'org.gnome.Shell', 0, None) == 1
assert dbus.dbus_bus_request_name(connection, b'com.rastersoft.ding', 0, None) == 1
marker = Path(os.environ['HOME']).parent.parent / 'allow-window'
print('FIXTURE: DING name owned before windows', flush=True)
print('Android first frame submitted (explicit test compositor fixture)', flush=True)
announced = False
while dbus.dbus_connection_read_write_dispatch(connection, 100):
    if marker.exists() and not announced:
        print('Received notification for window. 0 notifications remaining.', flush=True)
        announced = True
''')
        self.work = self.root / 'work'
        self.ready = self.root / 'ready'
        render = socket.socket(socket.AF_UNIX)
        render.bind(str(self.root / 'render.sock'))
        self.addCleanup(render.close)
        self.log = (self.root / 'launcher.log').open('w+')
        self.addCleanup(self.log.close)
        script = ASSETS / 'desktop_gnome_launch.py'
        if self.ubuntu_fixture:
            # Adjacent explicit profile fixture exercises the real Ubuntu
            # readiness branch without claiming extension/GPU integration.
            script = self.root / 'desktop_gnome_launch.py'
            shutil.copyfile(ASSETS / script.name, script)
            (self.root / 'desktop_native_ubuntu.py').write_text(
                "MODE = 'explicit-ubuntu-fixture'\nEXTENSIONS = ()\n"
                "def session_setup(prefix): return ''\n"
                "def validate(prefix): pass\n"
                "def runtime_probe(prefix, env):\n"
                " import os\n"
                " if os.environ.get('FORBID_STATIC_PROBES') == '1': raise ValueError('fixture runtime failure')\n")
        self.command = [
            sys.executable, '-B', str(script),
            '--session-profile', 'native-ubuntu' if self.ubuntu_fixture else 'gnome',
            '--desktop-id', 'fixture', '--ready-file', str(self.ready),
            '--run-mode', 'native', '--backend', 'android', '--session-startup', 'direct',
            '--gl-mode', 'system', '--size', '1280x720', '--refresh-rate', '60',
            '--render-socket', str(self.root / 'render.sock'), '--work', str(self.work),
            '--wayland-display', 'wayland-fixture', '--usb-storage', 'off',
            '--display', ':0', '--service-policy', 'core']
        self.env = dict(os.environ, PREFIX=str(prefix), HOME=str(home),
                        PATH=str(binaries) + ':' + os.environ['PATH'], PULSE_SERVER='tcp:127.0.0.1:4713',
                        FORBID_STATIC_PROBES='0')
        prepared = subprocess.run(self.command + ['--prepare'], env=self.env,
                                  capture_output=True, text=True, timeout=15)
        self.assertEqual(0, prepared.returncode, prepared.stdout + prepared.stderr)
        self.assertTrue((self.work / 'prepared-session.json').is_file())
        # Every launch in this suite must succeed with static probes/default
        # writers disabled. They were allowed only in the save phase above.
        self.env['FORBID_STATIC_PROBES'] = '1'
        self.process = subprocess.Popen(self.command, env=self.env, stdout=self.log, stderr=self.log)
        self.addCleanup(self.stop_launcher)

    @staticmethod
    def write_executable(path, contents):
        path.write_text(contents)
        path.chmod(0o755)

    def stop_launcher(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)

    def wait_ready(self):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.fail((self.root / 'launcher.log').read_text())
            for name, pattern in (('shell.pid', r'[1-9][0-9]*\n'),
                                  ('session.env', r'export DBUS_SESSION_BUS_ADDRESS="unix:[^"\n]+"\n')):
                path = self.work / name
                if path.exists():
                    self.assertRegex(path.read_text(), '^' + pattern + '$')
            if self.ready.exists():
                self.assertEqual(str(self.process.pid) + '\n', self.ready.read_text())
                return
            time.sleep(.005)
        self.fail('Launcher did not become ready: ' + (self.root / 'launcher.log').read_text())

    def test_missing_save_result_exits_without_initializing_or_repairing(self):
        self.stop_launcher()
        prepared = self.work / 'prepared-session.json'
        prepared.unlink()
        start = time.monotonic()
        result = subprocess.run(self.command, env=self.env, capture_output=True, text=True, timeout=3)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(prepared.exists())
        self.assertFalse(self.ready.exists())
        self.assertLess(time.monotonic() - start, 2)

    def test_failed_save_revokes_preparation_instead_of_retaining_stale_success(self):
        self.stop_launcher()
        result = subprocess.run(self.command + ['--prepare'], env=self.env,
                                capture_output=True, text=True, timeout=10)
        # Real profile fixture's explicit runtime double is intentionally empty;
        # generic GNOME uses the --version fixture disabled after initial save.
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.work / 'prepared-session.json').exists())

    def test_bootstrap_never_publishes_partial_identity_and_stop_removes_ready(self):
        self.wait_ready()
        self.process.terminate()
        self.assertEqual(130, self.process.wait(timeout=10))
        self.assertFalse(self.ready.exists())
        self.assertFalse((self.work / 'shell.pid').exists())

    def test_compositor_exit_invalidates_readiness_without_killing_unrelated_process(self):
        unrelated = subprocess.Popen(['sleep', '30'])
        self.addCleanup(lambda: (unrelated.terminate(), unrelated.wait(timeout=5)))
        self.wait_ready()
        shell_pid = int((self.work / 'shell.pid').read_text())
        os.kill(shell_pid, signal.SIGTERM)
        self.assertNotEqual(0, self.process.wait(timeout=10))
        self.assertFalse(self.ready.exists())
        self.assertFalse((self.work / 'shell.pid').exists())
        self.assertIsNone(unrelated.poll())

    def test_session_bus_and_wayland_socket_survive_termux_tmp_cleanup(self):
        self.wait_ready()
        shutil.rmtree(self.prefix / 'tmp', ignore_errors=True)
        result = subprocess.run(launcher.session_command([], '/bin/sh', self.work / 'session.env',
                                ['dbus-send', '--session', '--print-reply', '--dest=org.gnome.Shell',
                                 '/org/gnome/Shell', 'org.freedesktop.DBus.Peer.Ping']),
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.work / 'runtime/wayland-fixture').is_socket())
        self.assertTrue(self.ready.exists())


class UbuntuWindowReadinessTests(LauncherLifecycleTests):
    ubuntu_fixture = True

    def test_bus_name_without_desktop_windows_is_not_ready(self):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            self.assertIsNone(self.process.poll(), (self.root / 'launcher.log').read_text())
            log = self.work / 'session.log'
            if log.exists() and 'FIXTURE: DING name owned before windows' in log.read_text():
                break
            time.sleep(.02)
        else:
            self.fail('Fixture never acquired its DING bus name')
        # Give the real launcher time to pass both real D-Bus probes. A bus-only
        # readiness regression would publish ready during this interval.
        time.sleep(.8)
        self.assertFalse(self.ready.exists())
        (self.root / 'allow-window').touch()
        self.wait_ready()

    def wait_ready(self):
        # Other inherited lifecycle tests still need a window-ready event.
        (self.root / 'allow-window').touch()
        super().wait_ready()


if __name__ == '__main__':
    unittest.main(verbosity=2)
