#!/usr/bin/env python3
"""Exercise installation failure gates and preflight-free startup boundaries, not desktop rendering."""
import argparse
import ast
import importlib.util
import itertools
import json
import shlex
import struct
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

    def vulkan_fixture(self, root, guest=False, machine=183):
        library = root / ('usr/lib/aarch64-linux-gnu/libvulkan_lvp.so' if guest else 'lib/libvulkan_lvp.so')
        library.parent.mkdir(parents=True, exist_ok=True)
        library.write_bytes(b'\x7fELF\x02\x01' + b'\0' * 12 + struct.pack('<H', machine))
        icd = root / ('usr/share/vulkan/icd.d/lvp_icd.json' if guest else 'share/vulkan/icd.d/lvp_icd.aarch64.json')
        icd.parent.mkdir(parents=True, exist_ok=True)
        icd.write_text(json.dumps({'ICD': {'library_path': 'libvulkan_lvp.so' if guest else str(library)}}))
        return icd, library

    def test_plasma_software_zink_is_explicit_and_other_desktops_stay_strict(self):
        for renderer in ['zink Vulkan 1.4(llvmpipe (LLVM 21))', 'zink (lavapipe)']:
            command = [sys.executable, '-c', 'print(' + repr('renderer: ' + renderer) + ')']
            self.assertEqual(self.m.check_egl(command, 'zink', {}, allow_software=True), renderer.lower())
            with self.assertRaises(RuntimeError):
                self.m.check_egl(command, 'zink', {})
        with self.assertRaises(RuntimeError):
            self.m.check_egl([sys.executable, '-c', 'print("renderer: llvmpipe")'], 'zink', {}, allow_software=True)

    def test_plasma_icd_rejects_wrong_abi_path_and_missing_library(self):
        icd, library = self.vulkan_fixture(self.prefix)
        self.assertEqual(self.m.plasma_icd(self.prefix, False, True), str(icd))
        library.write_bytes(b'not an ELF')
        with self.assertRaisesRegex(RuntimeError, 'AArch64'):
            self.m.plasma_icd(self.prefix, False, True)
        self.vulkan_fixture(self.prefix, machine=62)
        with self.assertRaisesRegex(RuntimeError, 'AArch64'):
            self.m.plasma_icd(self.prefix, False, True)
        self.vulkan_fixture(self.prefix)
        icd.write_text(json.dumps({'ICD': {'library_path': '/usr/lib/aarch64-linux-gnu/libvulkan_lvp.so'}}))
        with self.assertRaisesRegex(RuntimeError, 'ABI/library'):
            self.m.plasma_icd(self.prefix, False, True)
        icd.unlink()
        with self.assertRaisesRegex(RuntimeError, 'explicit lvp'):
            self.m.plasma_icd(self.prefix, False, True)

    def test_plasma_four_pairs_keep_abis_separate_and_publish_after_both_probes(self):
        guest_root = self.prefix / 'container'
        host_icd, _ = self.vulkan_fixture(self.prefix)
        guest_icd, _ = self.vulkan_fixture(guest_root, guest=True)
        for host, guest in itertools.product(('system', 'zink'), ('llvmpipe', 'zink')):
            args = self.args('kwin_plasma', 'proot', host, guest)
            with patch.object(self.m, 'is_emulator', return_value=True), \
                 patch.object(self.m.runpy, 'run_path', return_value={'host_probe': lambda: {}}), \
                 patch.object(self.m, 'check_egl', return_value='validated') as check:
                self.m.plasma_rendering(args, guest_root, ['proot', 'login', 'desk', '--', 'env'], {})
            receipt = self.prefix / ('var/lib/termux-desktop/rendering/desk.' + host + '.' + guest + '.env')
            env = subprocess.check_output(['sh', '-c', '. "$1"; printf "%s|%s|%s|%s" "$DESKTOP_PLASMA_HOST_MODE" "$DESKTOP_PLASMA_GUEST_MODE" "$DESKTOP_PLASMA_HOST_ICD" "$DESKTOP_PLASMA_GUEST_ICD"', 'sh', str(receipt)], text=True)
            self.assertEqual(env, '|'.join((host, guest, str(host_icd) if host == 'zink' else '', '/usr/share/vulkan/icd.d/lvp_icd.json' if guest == 'zink' else '')))
            last = check.call_args_list[-1]
            self.assertIn('proot', last.args[0])
            self.assertNotIn('TERMUX_ANDROID_ZINK=1', last.args[0])
            if guest == 'zink':
                self.assertIn('VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json', last.args[0])
            self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)
            with patch.object(self.m, 'is_emulator', return_value=True), \
                 patch.object(self.m.runpy, 'run_path', return_value={'host_probe': lambda: {}}), \
                 patch.object(self.m, 'check_egl', side_effect=RuntimeError('probe failed')):
                with self.assertRaisesRegex(RuntimeError, 'probe failed'):
                    self.m.plasma_rendering(args, guest_root, ['proot', 'login', 'desk', '--', 'env'], {})
            self.assertFalse(receipt.exists())

    def test_plasma_runtime_reuses_software_identity_without_hardware_probe(self):
        self.plasma()
        self.m.prepare_plasma(self.prefix)
        command = ['bash', '-uc', 'source "$PREFIX/libexec/termux-desktop/rendering-env"; kwin_android_select_rendering; printf "%s|%s|%s|%s" "$GALLIUM_DRIVER" "$VK_DRIVER_FILES" "$LIBGL_ALWAYS_SOFTWARE" "$KWIN_ANDROID_AHB_IMPORT"']
        env = dict(os.environ, KWIN_ANDROID_GL_MODE='zink', DESKTOP_PLASMA_RENDERING='1',
                   DESKTOP_PLASMA_HOST_ICD='/explicit/lvp.json', DESKTOP_PLASMA_HOST_SOFTWARE='1')
        self.assertIn('zink|/explicit/lvp.json|1|off', subprocess.check_output(command, env=env, text=True))

    def test_plasma_container_zink_uses_gl_over_its_own_initialized_icd(self):
        script = (ASSETS / 'desktop_container_session.sh').read_text().split('# Optional:')[0]
        script += '\nprintf "%s|%s|%s|%s" "$QSG_RHI_BACKEND" "$GALLIUM_DRIVER" "$VK_DRIVER_FILES" "$LIBGL_ALWAYS_SOFTWARE"'
        env = dict(os.environ, DESKTOP_GUEST_GL_MODE='zink', DESKTOP_KDE_SERVICE_POLICY='kwin-wayland',
                   QT_QPA_PLATFORM='wayland', XDG_SESSION_TYPE='wayland',
                   DESKTOP_PLASMA_GUEST_ICD='/usr/share/vulkan/icd.d/lvp_icd.json', DESKTOP_PLASMA_GUEST_SOFTWARE='1',
                   VK_DRIVER_FILES='/wrong/host/icd.json')
        self.assertEqual(subprocess.check_output(['sh', '-c', script], env=env, text=True),
                         'opengl|zink|/usr/share/vulkan/icd.d/lvp_icd.json|1')
        del env['DESKTOP_PLASMA_GUEST_ICD']
        self.assertEqual(subprocess.run(['sh', '-c', script], env=env, capture_output=True).returncode, 66)

    def test_missing_or_mismatched_plasma_receipt_does_not_stop_another_desktop(self):
        state = self.prefix / 'state'
        state.mkdir()
        other = subprocess.Popen(['sleep', '60'])
        self.addCleanup(other.wait)
        self.addCleanup(other.terminate)
        (state / 'other.pid').write_text(str(other.pid))
        spec = self.prefix / 'spec'
        values = dict(DESKTOP_ID='desk', DESKTOP_SPEC_VERSION='3', DESKTOP_MODE='proot',
                      DESKTOP_DE='kwin_plasma', DESKTOP_PROOT_DISTRO='desk',
                      DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='llvmpipe',
                      DESKTOP_LOG_FILE=str(self.prefix / 'log'),
                      DESKTOP_PID_FILE=str(state / 'desk.pid'),
                      DESKTOP_STATUS_FILE=str(state / 'status'))
        spec.write_text(''.join(k + '=' + shlex.quote(v) + '\n' for k, v in values.items()))
        receipt = self.prefix / 'var/lib/termux-desktop/rendering/desk.system.llvmpipe.env'
        for content in (None, 'export DESKTOP_PLASMA_RENDERING=1\n'
                       'export DESKTOP_PLASMA_HOST_MODE=zink\n'):
            if content:
                receipt.parent.mkdir(parents=True)
                receipt.write_text(content)
            result = subprocess.run(['sh', str(ASSETS / 'desktop_start.sh'), str(spec)],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 66, result.stdout + result.stderr)
            self.assertIsNone(other.poll())
            self.assertTrue((state / 'other.pid').exists())

    def test_emulator_install_routes_matching_mesa_and_never_turnip_overlay(self):
        contract = runpy.run_path(str(ROOT / 'docs/desktop-testing/test-gnome-usb-install.py'))
        for host, guest in itertools.product(('system', 'zink'), ('llvmpipe', 'zink')):
            with self.subTest(host=host, guest=guest):
                fixture = contract['InstallContract']()
                fixture.setUp()
                self.addCleanup(fixture.doCleanups)
                embedded = fixture.base / 'usr/share/embedded-api/termux-api.deb'
                embedded.parent.mkdir(parents=True)
                embedded.touch()
                script = fixture.base / 'desktop_install.sh'
                script.write_text(script.read_text().replace(
                    '$(/system/bin/getprop ro.kernel.qemu 2>/dev/null)', '1'))
                fake = fixture.base / 'usr/bin/python3'
                fake.write_text(fake.read_text().replace("elif command=='python3':",
                    "elif command=='python3':\n"
                    "    if any('desktop_initialize.py' in a for a in args): sys.exit(0)"))
                result, calls = fixture.run_install(de='kwin_plasma', mode='proot', overrides={
                    'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
                    'DESKTOP_HOST_GL_MODE': host, 'DESKTOP_GUEST_GL_MODE': guest})
                self.assertEqual(result.returncode, 0, result.stdout)
                installs = [c for c in calls if c[:2] == ['apt', 'install']]
                if host == 'zink':
                    self.assertTrue(any('mesa-vulkan-icd-swrast' in c for c in installs))
                self.assertFalse(any('mesa-vulkan-icd-freedreno' in c for c in installs))
                repair = [c for c in calls if '--allow-change-held-packages' in c and c[0] == 'proot-distro']
                self.assertEqual(len(repair), 1, calls)
                self.assertIn('--reinstall', repair[0])
                self.assertEqual(repair[0][-6:], ['libgbm1', 'libegl-mesa0', 'libgl1-mesa-dri',
                                                  'libglx-mesa0', 'libgles2', 'mesa-vulkan-drivers'])
                self.assertFalse(any('mesa-for-android-container' in ' '.join(c) for c in calls))
                self.assertFalse(any(c[:2] == ['apt-mark', 'unhold'] for c in calls))

    def test_held_mesa_repair_option_is_explicit_and_preserves_hold_selection(self):
        contract = runpy.run_path(str(ROOT / 'docs/desktop-testing/test-gnome-usb-install.py'))
        fixture = contract['ContainerAptContract']()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        result, calls = fixture.run_helper('--statx-backend=proc-fdinfo',
            '--reinstall', '--allow-change-held-packages', 'mesa-vulkan-drivers',
            package_status='hold ok installed')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(any(c[:2] == ['apt-get', 'install'] and '--reinstall' in c
                            and '--allow-change-held-packages' in c for c in calls))
        self.assertFalse(any(c[0] == 'apt-mark' for c in calls))
        result, calls = fixture.run_helper('--statx-backend=proc-fdinfo',
            '--allow-change-held-packages', 'mesa-vulkan-drivers')
        self.assertEqual(result.returncode, 64)
        self.assertEqual(calls, [])

    def test_wrong_rootfs_fails_before_launching_guest(self):
        root = self.prefix / 'var/lib/proot-distro/containers/desk/rootfs'
        release = root / 'etc/os-release'
        release.parent.mkdir(parents=True)
        release.write_text('ID=ubuntu\nVERSION_CODENAME=noble\n')
        self.program('bin/proot-distro', 'echo called >> "$TRACE"')
        with self.assertRaisesRegex(RuntimeError, 'debian:trixie'):
            self.m.initialize(self.args(mode='proot', guest='default'))
        self.assertFalse(self.trace.exists())

    def test_plasma_rejects_debian_and_wrong_ubuntu_before_guest_work(self):
        root = self.prefix / 'var/lib/proot-distro/containers/desk/rootfs'
        (root / 'etc').mkdir(parents=True)
        self.program('bin/proot-distro', 'echo called >> "$TRACE"')
        for release in ('ID=debian\nVERSION_CODENAME=forky\n',
                        'ID=debian\nVERSION_CODENAME=resolute\n',
                        'ID=ubuntu\nVERSION_CODENAME=noble\n'):
            with self.subTest(release=release):
                (root / 'etc/os-release').write_text(release)
                with self.assertRaisesRegex(RuntimeError, 'ubuntu:resolute'):
                    self.m.initialize(self.args('kwin_plasma', 'proot', 'system', 'llvmpipe'))
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

    def test_plasma_cache_failure_prevents_publishing_renderer_initialization(self):
        self.plasma()
        for name in ('python3', 'pulseaudio', 'pactl', 'kwin_wayland', 'dbus-launch'):
            self.program('bin/' + name)
        self.program('bin/proot-distro', 'echo "$*" >> "$TRACE"; exit 7')
        library = self.prefix / 'lib/libtermux-render.so'
        library.parent.mkdir(parents=True, exist_ok=True)
        library.touch()
        root = self.prefix / 'var/lib/proot-distro/containers/desk/rootfs'
        (root / 'etc').mkdir(parents=True)
        (root / 'etc/os-release').write_text('ID=ubuntu\nVERSION_CODENAME=resolute\n')
        for name in ('usr/bin/plasmashell', 'usr/bin/dbus-daemon', 'usr/bin/dbus-send',
                     'usr/lib/polkit-1/polkitd', 'usr/libexec/packagekitd'):
            self.program(name, root=root)
        plugin = root / 'usr/lib/aarch64-linux-gnu/qt6/plugins/platforms/libqwayland-egl.so'
        plugin.parent.mkdir(parents=True)
        plugin.touch()
        with patch.object(self.m, 'plasma_rendering') as rendering:
            with self.assertRaises(subprocess.CalledProcessError):
                self.m.initialize(self.args('kwin_plasma', 'proot', 'system', 'llvmpipe'))
            rendering.assert_not_called()
        self.assertIn('XDG_MENU_PREFIX=plasma- kbuildsycoca6 --noincremental', self.trace.read_text())
        self.program('bin/proot-distro', 'echo "$*" >> "$TRACE"')
        with patch.object(self.m, 'plasma_rendering') as rendering:
            self.m.initialize(self.args('kwin_plasma', 'proot', 'system', 'llvmpipe'))
            rendering.assert_called_once()

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
        m.COMPONENTS['install_theme']('0.57.0-r7', 'm3ww-v1', Path(__file__).resolve().parents[2] / 'distribution/hyprland-themes/build/hyprland-theme-m3ww-v1.tgz')
        args = argparse.Namespace(runtime_id='0.57.0-r7', theme_id='m3ww-v1',
                                  client_gl_mode='zink', run_mode='proot', desktop_id='desk',
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
        for name, function in [('desktop_hyprland.py', 'session'), ('desktop_hyprland.py', '_session_locked'),
                               ('desktop_gnome_launch.py', 'main')]:
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
                embedded = fixture.base / 'usr/share/embedded-api/termux-api.deb'
                embedded.parent.mkdir(parents=True, exist_ok=True)
                embedded.write_bytes(b'fixture package; dpkg is mocked')
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
