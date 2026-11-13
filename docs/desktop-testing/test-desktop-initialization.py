#!/usr/bin/env python3
"""Exercise installation failure gates and preflight-free startup boundaries, not desktop rendering."""
import argparse
import ast
import importlib.util
import os
from pathlib import Path
import runpy
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'termux-ui/src/main/assets/local-games'


def module(name):
    spec = importlib.util.spec_from_file_location(name, ASSETS / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class InitializationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='desk-')
        self.addCleanup(temp.cleanup)
        self.prefix = Path(temp.name) / 'usr'
        self.prefix.mkdir()
        self.trace = self.prefix / 'trace'
        self.env = patch.dict(os.environ, PREFIX=str(self.prefix), HOME=temp.name,
                              PULSE_SERVER='tcp:127.0.0.1:4713', TRACE=str(self.trace))
        self.env.start()
        self.addCleanup(self.env.stop)
        self.m = module('desktop_initialize')

    def program(self, name, body='exit 0', root=None):
        path = (root or self.prefix) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)
        return path

    def args(self, de='xfce4', mode='native', host='llvmpipe', guest='none'):
        return argparse.Namespace(prefix=self.prefix, de=de, run_mode=mode, alias='desk',
                                  host_gl_mode=host, guest_gl_mode=guest, usb_storage='off')

    def fixture(self):
        for name in ('python3', 'pulseaudio', 'pactl', 'xfce4-session', 'dbus-run-session', 'Xlorie'):
            self.program('bin/' + name)
        self.program('bin/eglinfo', 'echo "probe:$*" >> "$TRACE"; echo "OpenGL renderer string: llvmpipe"')

    def test_native_initialization_runs_headless_check(self):
        self.fixture()
        result = subprocess.run([sys.executable, str(ASSETS / 'desktop_initialize.py'), 'initialize',
            '--de', 'xfce4', '--run-mode', 'native', '--host-gl-mode', 'llvmpipe',
            '--guest-gl-mode', 'none', '--usb-storage', 'off'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.trace.read_text().strip(), 'probe:-p surfaceless -B')

    def test_missing_dependency_fails_before_gpu_probe(self):
        self.fixture()
        (self.prefix / 'bin/xfce4-session').unlink()
        with self.assertRaisesRegex(RuntimeError, 'xfce4-session'):
            self.m.initialize(self.args())
        self.assertFalse(self.trace.exists())

    def test_gpu_failure_and_wrong_renderer_are_rejected(self):
        self.fixture()
        for body in ('echo "renderer: llvmpipe"; exit 7', 'echo "renderer: zink (llvmpipe)"', 'echo no-renderer'):
            with self.subTest(body=body):
                self.program('bin/eglinfo', body)
                with self.assertRaisesRegex(RuntimeError, 'EGL renderer check failed'):
                    self.m.initialize(self.args())

    def test_zink_rejects_software_and_accepts_adreno(self):
        for renderer, passes in [('zink (Turnip Adreno 740)', True), ('zink (lavapipe)', False),
                                 ('llvmpipe', False), ('Adreno 740', False)]:
            with self.subTest(renderer=renderer):
                command = [sys.executable, '-c', 'print(' + repr('renderer: ' + renderer) + ')']
                if passes:
                    self.m.check_egl(command, 'zink', dict(os.environ))
                else:
                    with self.assertRaises(RuntimeError):
                        self.m.check_egl(command, 'zink', dict(os.environ))

    def test_wrong_rootfs_fails_before_launching_guest(self):
        root = self.prefix / 'var/lib/proot-distro/containers/desk/rootfs'
        release = root / 'etc/os-release'
        release.parent.mkdir(parents=True)
        release.write_text('ID=ubuntu\nVERSION_CODENAME=noble\n')
        self.program('bin/proot-distro', 'echo called >> "$TRACE"')
        with self.assertRaisesRegex(RuntimeError, 'debian:trixie'):
            self.m.initialize(self.args(mode='proot', guest='default'))
        self.assertFalse(self.trace.exists())

    def test_proot_gnome_initialization_does_not_need_x11(self):
        self.fixture()
        root = self.prefix / 'var/lib/proot-distro/containers/desk/rootfs'
        release = root / 'etc/os-release'
        release.parent.mkdir(parents=True)
        release.write_text('ID=debian\nVERSION_CODENAME=trixie\n')
        for name in ('gnome-shell', 'gjs', 'dbus-daemon', 'dbus-run-session'):
            self.program('usr/bin/' + name, root=root)
        for name in ('ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gnome.Nautilus'):
            path = root / 'usr/share/dbus-1/services' / (name + '.service')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        for name in ('python3', 'pulseaudio', 'pactl', 'Xlorie'):
            self.program('bin/' + name)
        self.program('bin/proot-distro', 'echo "$*" >> "$TRACE"; echo "renderer: llvmpipe"')
        self.m.initialize(self.args(de='gnome', mode='proot', host='llvmpipe', guest='llvmpipe'))
        # The native host needs its own selected EGL renderer as well.

    def plasma(self):
        fixtures = ROOT / 'docs/desktop-testing/fixtures/plasma-r28'
        for name in ('start-plasma', 'kwin-android-rendering-env'):
            target = self.prefix / 'bin' / name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(fixtures / name, target)
        (self.prefix / 'bin/bash').symlink_to(shutil.which('bash'))

    def test_plasma_conversion_preserves_supervision_without_tests_or_installs(self):
        self.plasma()
        self.m.prepare_plasma(self.prefix)
        script = (self.prefix / 'libexec/termux-desktop/start-plasma').read_text()
        self.assertNotIn('QT_CLIENT_PROBE', script)
        self.assertNotIn('pkg install', script)
        self.assertNotIn('glxinfo', script)
        self.assertIn('wait "$KWIN_PID"', script)
        self.assertIn('kwin_wayland exited during startup', script)
        self.assertIn('cleanup_session', script)

    def test_restore_host_preparation_needs_no_container(self):
        self.plasma()
        result = subprocess.run([sys.executable, str(ASSETS / 'desktop_initialize.py'),
                                 'prepare-plasma'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.prefix / 'libexec/termux-desktop/start-plasma').is_file())
        self.assertFalse((self.prefix / 'var/lib/proot-distro').exists())

    def test_plasma_runtime_sets_exact_environment_without_dependency_or_gpu_checks(self):
        self.plasma()
        self.m.prepare_plasma(self.prefix)
        # Neither driver/plugin files nor test utilities exist; runtime config still resolves.
        for mode, integration, driver in [('system', 'termux-ahardware-buffer', ''),
                                           ('zink', 'wayland-egl', 'zink'),
                                           ('llvmpipe', 'wayland-egl', 'llvmpipe')]:
            with self.subTest(mode=mode):
                result = subprocess.run(['bash', '-c',
                    'set -e; source "$PREFIX/libexec/termux-desktop/rendering-env"; '
                    'kwin_android_select_rendering; kwin_android_configure_qt_clients; '
                    'printf "RESULT=%s:%s:%s\\n" "$KWIN_ANDROID_GL_MODE" '
                    '"$QT_WAYLAND_CLIENT_BUFFER_INTEGRATION" "$GALLIUM_DRIVER"'],
                    env=dict(os.environ, KWIN_ANDROID_GL_MODE=mode), capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('RESULT=' + ':'.join((mode, integration, driver)), result.stdout)

    def test_changed_plasma_component_is_rejected_without_overwriting_previous_scripts(self):
        self.plasma()
        self.m.prepare_plasma(self.prefix)
        installed = self.prefix / 'libexec/termux-desktop/start-plasma'
        previous = installed.read_bytes()
        (self.prefix / 'bin/start-plasma').write_text('unknown upgrade')
        with self.assertRaisesRegex(RuntimeError, 'Unsupported Plasma component'):
            self.m.prepare_plasma(self.prefix)
        self.assertEqual(installed.read_bytes(), previous)

    def test_hyprland_launch_reaches_first_service_without_static_verification(self):
        m = module('desktop_hyprland')
        args = argparse.Namespace(client_gl_mode='zink', run_mode='proot', desktop_id='desk',
                                  alias='desk', width=1280, height=720, refresh=60, ready_file=self.prefix / 'ready')
        class ReachedService(Exception):
            pass
        with patch.object(m, 'verify', side_effect=AssertionError('startup hash scan')), \
             patch.object(m, 'verify_shell', side_effect=AssertionError('startup dependency probe')), \
             patch.object(m, 'verify_zink_host', side_effect=AssertionError('startup GPU probe')), \
             patch.object(m, 'prepare_shell', return_value=[]), \
             patch.object(m.subprocess, 'Popen', side_effect=ReachedService):
            with self.assertRaises(ReachedService):
                m.session(args)

    def test_proot_ubuntu_launch_reaches_real_service_without_rootfs_validation(self):
        prefix = self.prefix
        rootfs = prefix / 'rootfs'
        rootfs.mkdir()
        for name in ('ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gnome.Nautilus', 'org.gnome.Terminal',
                     'org.gnome.TextEditor', 'org.gnome.Settings', 'org.gnome.clocks'):
            path = rootfs / 'usr/share/dbus-1/services' / (name + '.service')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('[D-BUS Service]\nName=' + name + '\nExec=/usr/bin/false\n')
        render = prefix / 'render.sock'
        connection = socket.socket(socket.AF_UNIX)
        self.addCleanup(connection.close)
        connection.bind(str(render))
        argv = ['launch', '--desktop-id', 'desk', '--ready-file', str(prefix / 'ready'),
                '--run-mode', 'proot', '--backend', 'x11', '--session-startup', 'direct',
                '--gl-mode', 'llvmpipe', '--size', '1280x720', '--refresh-rate', '60',
                '--render-socket', str(render), '--display', ':0', '--wayland-display', 'wayland-0',
                '--usb-storage', 'off', '--work', str(prefix / 'work'), '--service-policy', 'core',
                '--session-profile', 'ubuntu', '--rootfs', str(rootfs),
                '--x11-display-gl-mode', 'system', '--x11-display-buffer-mode', 'native']
        class ReachedService(Exception):
            pass
        with patch.object(sys, 'argv', argv), \
             patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)), \
             patch.object(subprocess, 'Popen', side_effect=ReachedService):
            with self.assertRaises(ReachedService):
                runpy.run_path(str(ASSETS / 'desktop_gnome_launch.py'), run_name='__main__')

    def test_launchers_have_no_synthetic_probe_calls(self):
        for name, function in [('desktop_hyprland.py', 'session'), ('desktop_gnome_launch.py', 'main')]:
            tree = ast.parse((ASSETS / name).read_text())
            entry = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == function)
            calls = [n.func.id if isinstance(n.func, ast.Name) else n.func.attr
                     for n in ast.walk(entry) if isinstance(n, ast.Call)
                     and isinstance(n.func, (ast.Name, ast.Attribute))]
            self.assertFalse(set(calls) & {'verify', 'verify_shell', 'validate_rootfs', 'runtime_probe',
                                           'verify_zink_host', 'verify_zink_client'})
        container = (ASSETS / 'desktop_container_session.sh').read_text()
        self.assertNotIn('apt-get', container)
        self.assertNotIn('configure_proot_statx', container)
        self.assertNotIn('restore_legacy_postinsts', container)

    def test_installer_records_success_only_after_initialization(self):
        spec = importlib.util.spec_from_file_location('install_contract', ROOT /
            'docs/desktop-testing/test-gnome-usb-install.py')
        contract = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(contract)
        for code in (0, 73):
            with self.subTest(initialization_exit=code):
                fixture = contract.InstallContract()
                fixture.setUp()
                self.addCleanup(fixture.doCleanups)
                fake = fixture.base / 'usr/bin/python3'
                fake.write_text(fake.read_text().replace("elif command=='python3':", 
                    "elif command=='python3':\n    if any('desktop_initialize.py' in a for a in args): "
                    "sys.exit(" + str(code) + ")"))
                marker = fixture.base / 'usr/var/lib/proot-distro/containers/debian-xfce4-1/.desktop-de-xfce4'
                marker.parent.mkdir(parents=True)
                marker.write_text('previous initialization')
                release = marker.parent / 'rootfs/etc/os-release'
                release.parent.mkdir(parents=True)
                release.write_text('ID=debian\nVERSION_CODENAME=trixie\n')
                result, calls = fixture.run_install(de='xfce4', mode='proot', overrides={
                    'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
                    'DESKTOP_HOST_GL_MODE': 'system', 'DESKTOP_GUEST_GL_MODE': 'default'})
                self.assertEqual(result.returncode, code, result.stdout)
                checks = [c for c in calls if c[0] == 'python3' and any('desktop_initialize.py' in a for a in c)]
                self.assertEqual(len(checks), 1, calls)
                self.assertEqual(marker.exists(), code == 0)
                self.assertEqual((fixture.base / 'state/status').read_text(), 'exit:' + str(code) + '\n')


if __name__ == '__main__':
    unittest.main()
