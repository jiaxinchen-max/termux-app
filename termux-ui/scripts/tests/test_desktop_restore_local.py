#!/usr/bin/env python3
"""Restoration must reuse installed software and recreate machine-local state offline."""
import importlib.util
import os
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[2] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('desktop_initialize', ASSETS / 'desktop_initialize.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RestoreLocalTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.prefix = Path(self.work.name) / 'usr'
        self.alias = 'import-97958826-4a9d-48ef-ab0a-5d271e9fb15a'
        self.root = self.prefix / 'var/lib/proot-distro/containers' / self.alias / 'rootfs'
        (self.root / 'etc').mkdir(parents=True)
        self.write(self.prefix / 'etc/resolv.conf', 'nameserver 1.1.1.1\nnameserver 2606:4700:4700::1111\n')

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        return path

    def test_runtime_resolver_link_is_recreated_without_touching_run(self):
        target = self.root / 'etc/resolv.conf'
        target.symlink_to('../run/systemd/resolve/stub-resolv.conf')
        module.restore_network(self.prefix, self.root)
        self.assertFalse(target.is_symlink())
        self.assertEqual(target.read_text(), (self.prefix / 'etc/resolv.conf').read_text())
        self.assertFalse((self.root / 'run').exists())

    def test_existing_temporary_file_is_not_deleted_on_exclusive_create_failure(self):
        target = self.root / 'etc/resolv.conf'
        target.symlink_to('../run/systemd/resolve/stub-resolv.conf')
        existing = self.write(target.with_name('resolv.conf.restore.' + str(os.getpid())), 'restored user file')
        with self.assertRaises(FileExistsError):
            module.restore_network(self.prefix, self.root)
        self.assertEqual(existing.read_text(), 'restored user file')
        self.assertTrue(target.is_symlink())

    def test_absolute_runtime_link_is_not_followed(self):
        (self.root / 'etc/resolv.conf').symlink_to('/run/systemd/resolve/stub-resolv.conf')
        module.restore_network(self.prefix, self.root)
        self.assertTrue((self.root / 'etc/resolv.conf').is_file())

    def test_custom_dns_is_preserved_byte_for_byte(self):
        text = '# my DNS\nnameserver 192.168.8.1\nsearch my.lan\n'
        target = self.write(self.root / 'etc/resolv.conf', text)
        module.restore_network(self.prefix, self.root)
        self.assertEqual(target.read_text(), text)

    def test_external_link_is_rejected_and_target_preserved(self):
        victim = self.write(Path(self.work.name) / 'victim', 'do not replace')
        (self.root / 'etc/resolv.conf').symlink_to(victim)
        with self.assertRaisesRegex(RuntimeError, 'symlink'):
            module.restore_network(self.prefix, self.root)
        self.assertEqual(victim.read_text(), 'do not replace')

    def test_etc_symlink_cannot_redirect_restore_writes(self):
        (self.root / 'etc').rmdir()
        outside = Path(self.work.name) / 'outside'
        outside.mkdir()
        (self.root / 'etc').symlink_to(outside)
        with self.assertRaisesRegex(RuntimeError, 'real etc'):
            module.restore_network(self.prefix, self.root)
        self.assertEqual(list(outside.iterdir()), [])

    def fixture(self, de):
        for name in ('python3', 'pulseaudio', 'pactl', 'proot-distro', 'eglinfo', 'kwin_wayland', 'dbus-launch', 'bash', 'Xlorie'):
            self.write(self.prefix / 'bin' / name, '#!/bin/sh\nexit 0\n').chmod(0o755)
        for name in ('plasmashell', 'dbus-daemon', 'dbus-send', 'xfce4-session', 'dbus-run-session'):
            self.write(self.root / 'usr/bin' / name, 'restored executable').chmod(0o755)
        self.write(self.root / 'etc/os-release', 'ID=ubuntu\nVERSION_CODENAME=resolute\n')
        self.write(self.root / 'usr/lib/polkit-1/polkitd', '').chmod(0o755)
        self.write(self.root / 'usr/libexec/packagekitd', '')
        self.write(self.root / 'usr/lib/aarch64-linux-gnu/qt6/plugins/platforms/libqwayland-egl.so', '')
        self.write(self.prefix / 'lib/libtermux-render.so', '')
        for name in ('start-plasma', 'kwin-android-rendering-env'):
            source = ASSETS.parents[4] / 'docs/desktop-testing/fixtures/plasma-r28' / name
            self.write(self.prefix / 'bin' / name, source.read_text())
        (self.root / 'etc/resolv.conf').symlink_to('../run/systemd/resolve/stub-resolv.conf')
        return SimpleNamespace(command='restore', prefix=self.prefix, alias=self.alias, de=de,
            run_mode='proot', host_gl_mode='system', guest_gl_mode='llvmpipe' if de == 'kwin_plasma' else 'default',
            usb_storage='off', rendering_output=Path(self.work.name) / 'task/rendering.env')

    def run_restore(self, args, fail_egl=False):
        calls = []
        def run(command, **kwargs):
            calls.append(command)
            self.assertNotIn(Path(command[0]).name, ('apt', 'pkg', 'dpkg', 'curl', 'wget'))
            if 'eglinfo' in command and fail_egl:
                return subprocess.CompletedProcess(command, 1, '', 'GPU unavailable')
            return subprocess.CompletedProcess(command, 0, 'OpenGL renderer string: llvmpipe\n', '')
        with patch.object(module.subprocess, 'run', side_effect=run), \
             patch.object(module.runpy, 'run_path', return_value={'host_probe': lambda: None}), \
             patch.object(module, 'is_emulator', return_value=True):
            module.initialize(args)
        return calls

    def test_plasma_restore_keeps_packages_and_publishes_only_task_owned_renderer(self):
        args = self.fixture('kwin_plasma')
        package = self.write(self.root / 'var/lib/dpkg/status', 'restored package database')
        user = self.write(self.root / 'root/my-document', 'user data')
        calls = self.run_restore(args)
        self.assertIn('DESKTOP_PLASMA_GUEST_MODE=llvmpipe', args.rendering_output.read_text())
        self.assertFalse((self.prefix / 'var/lib/termux-desktop/rendering').exists())
        self.assertTrue(any('eglinfo' in command for command in calls))
        self.assertEqual(package.read_text(), 'restored package database')
        self.assertEqual(user.read_text(), 'user data')

    def test_failed_renderer_validation_cannot_publish_success_receipt(self):
        args = self.fixture('kwin_plasma')
        with self.assertRaisesRegex(RuntimeError, 'renderer check failed'):
            self.run_restore(args, fail_egl=True)
        self.assertFalse(args.rendering_output.exists())

    def gnome_fixture(self):
        profile_spec = importlib.util.spec_from_file_location('ubuntu_fixture', ASSETS.parents[3] / 'scripts/test-desktop-ubuntu-gnome.py')
        profile_module = importlib.util.module_from_spec(profile_spec)
        profile_spec.loader.exec_module(profile_module)
        # rootfs_fixture owns etc; the network tests create it in setUp.
        (self.root / 'etc').rmdir()
        profile_module.rootfs_fixture(self.root)
        for name in ('python3', 'pulseaudio', 'pactl', 'proot-distro', 'gnome-shell'):
            self.write(self.prefix / 'bin' / name, '#!/bin/sh\nexit 0\n').chmod(0o755)
        for name in ('gnome-shell', 'gjs', 'dbus-daemon', 'dbus-run-session'):
            (self.root / 'usr/bin' / name).parent.mkdir(parents=True, exist_ok=True)
            self.write(self.root / 'usr/bin' / name, '').chmod(0o755)
        self.write(self.prefix / 'lib/mutter-18/libmutter-android-system-egl.so', '')
        self.write(self.root / 'etc/resolv.conf', 'nameserver 1.1.1.1\n')
        return SimpleNamespace(command='restore', prefix=self.prefix, alias=self.alias, de='ubuntu_gnome',
            run_mode='proot', host_gl_mode='system', guest_gl_mode='llvmpipe', usb_storage='off',
            desktop_id='new-gnome-id', ready_file=Path(self.work.name) / 'state/new-gnome-id.pid.gnome-ready',
            size='1280x720', refresh_rate=60, render_socket=Path(self.work.name) / 'render.sock')

    def test_gnome_restore_regenerates_new_identity_without_reinstalling_user_software(self):
        args = self.gnome_fixture()
        workspace = self.root / 'home/termux/.local/share/termux-gnome'
        self.write(workspace / 'prepared-session.json', '{"desktop_id":"old-gnome-id"}')
        self.write(workspace / 'session.sh', 'old-container')
        old_runtime = Path(self.work.name) / 'old-runtime'
        old_runtime.mkdir()
        marker = self.write(old_runtime / 'keep', 'old session data')
        (workspace / 'runtime').symlink_to(old_runtime)
        persistent = [self.root / relative for relative in ('home/termux/Documents/mine',
            'home/termux/.config/dconf/user', 'home/termux/.config/.termux-ubuntu-profile-v2',
            'var/lib/dpkg/status', 'usr/local/lib/termux-gnome/libglycin-2.so.0')]
        for path in persistent: self.write(path, 'original ' + str(path))
        real_run_path = module.runpy.run_path
        calls = []
        def load(path):
            if Path(path).name == 'desktop_hyprland_gpu.py': return {'host_probe': lambda: None}
            return real_run_path(path)
        def run(command, **kwargs):
            calls.append(command)
            self.assertNotIn(Path(command[0]).name, ('apt', 'pkg', 'dpkg', 'curl', 'wget'))
            if '--prepare' in command:
                self.assertFalse(workspace.exists())
                self.assertIn('--rootfs', command)
                self.assertEqual(str(self.root), command[command.index('--rootfs') + 1])
                for flag, value in (('--desktop-id', args.desktop_id), ('--ready-file', str(args.ready_file)),
                                    ('--refresh-rate', '60'), ('--work', str(workspace))):
                    self.assertEqual(value, command[command.index(flag) + 1])
                # The real launcher has its own prepare/launch tests; this boundary
                # records the configuration supplied by restoration, without a GPU.
                self.write(workspace / 'prepared-session.json', json.dumps(command))
            text = 'GNOME Shell 50.1\n' if '--version' in command else 'OpenGL renderer string: llvmpipe\n'
            return subprocess.CompletedProcess(command, 0, text, '')
        with patch.object(module.subprocess, 'run', side_effect=run), patch.object(module.runpy, 'run_path', side_effect=load):
            module.initialize(args)
        self.assertNotIn('old-gnome-id', (workspace / 'prepared-session.json').read_text())
        self.assertFalse((workspace / 'runtime').exists())
        self.assertEqual(marker.read_text(), 'old session data')
        self.assertTrue(any('--prepare' in command for command in calls))
        for path in persistent: self.assertEqual(path.read_text(), 'original ' + str(path))

    def test_gnome_workspace_cleanup_never_follows_workspace_or_nested_links(self):
        outside = Path(self.work.name) / 'outside'
        marker = self.write(outside / 'prepared-session.json', 'keep')
        parent = self.root / 'home/termux/.local/share'
        parent.mkdir(parents=True)
        workspace = parent / 'termux-gnome'
        workspace.symlink_to(outside)
        module.restore_gnome_workspace(self.root)
        self.assertFalse(workspace.exists())
        workspace.mkdir()
        (workspace / 'nested').symlink_to(outside)
        module.restore_gnome_workspace(self.root)
        self.assertFalse(workspace.exists())
        self.assertEqual(marker.read_text(), 'keep')

    def test_gnome_workspace_cleanup_rejects_user_parent_symlink(self):
        outside = Path(self.work.name) / 'outside'
        marker = self.write(outside / '.local/share/termux-gnome/prepared-session.json', 'keep')
        (self.root / 'home').mkdir()
        (self.root / 'home/termux').symlink_to(outside)
        with self.assertRaisesRegex(RuntimeError, 'real directory'):
            module.restore_gnome_workspace(self.root)
        self.assertEqual(marker.read_text(), 'keep')

    def test_xfce_restore_needs_no_plasma_state_or_package_operation(self):
        args = self.fixture('xfce4')
        self.assertEqual(self.run_restore(args), [])
        self.assertFalse(args.rendering_output.exists())


if __name__ == '__main__':
    unittest.main()
