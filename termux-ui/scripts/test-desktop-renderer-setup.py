#!/usr/bin/env python3
"""Verify incremental dependency installation and reuse without a device or network."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('renderer_setup', ASSETS / 'desktop_renderer_setup.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class RendererSetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.prefix = Path(self.temp.name)
        (self.prefix / 'tmp').mkdir()
        container = self.prefix / 'var/lib/proot-distro/containers/ubuntu-plasma-1'
        container.mkdir(parents=True)
        (container / '.desktop-de-kwin_plasma').touch()

    def run_setup(self, missing=()):
        calls = []
        def run(command, **kwargs):
            calls.append(command)
            if 'dpkg-query' in command:
                absent = command[-1] in missing
                return subprocess.CompletedProcess(command, 1 if absent else 0,
                                                   '' if absent else 'installed', '')
            return subprocess.CompletedProcess(command, 0, '', '')
        with patch.object(m.subprocess, 'run', side_effect=run), \
             patch.object(m.runpy, 'run_path', return_value={'is_emulator': lambda: False}):
            m.dependencies(self.prefix, 'kwin_plasma', 'zink', 'zink', 'ubuntu-plasma-1')
        return calls

    def test_existing_backends_do_not_run_apt_or_downloads(self):
        calls = self.run_setup()
        self.assertTrue(calls)
        self.assertTrue(all('dpkg-query' in call for call in calls))

    def test_only_missing_host_driver_is_installed(self):
        calls = self.run_setup(('mesa-vulkan-icd-freedreno',))
        installs = [c for c in calls if 'dpkg-query' not in c]
        self.assertEqual(len(installs), 1)
        self.assertEqual(installs[0][-1], 'mesa-vulkan-icd-freedreno')
        self.assertIn('--no-remove', installs[0])
        self.assertNotIn('--reinstall', installs[0])
        self.assertNotIn('plasma-desktop', installs[0])

    def test_only_missing_guest_loader_is_passed_to_container_apt(self):
        calls = self.run_setup(('libvulkan-dev',))
        installs = [c for c in calls if 'dpkg-query' not in c]
        self.assertEqual(len(installs), 1)
        self.assertEqual(installs[0][-2:], ['--no-install-recommends', 'libvulkan-dev'])
        self.assertIn('/tmp/desktop_container_plasma_apt.sh', installs[0])
        self.assertNotIn('--reinstall', installs[0])

    def test_absent_container_fails_before_package_mutation(self):
        with patch.object(m.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'not installed'):
                m.dependencies(self.prefix, 'kwin_plasma', 'zink', 'zink', 'absent')
            run.assert_not_called()

    def test_package_database_error_does_not_trigger_install(self):
        with patch.object(m.subprocess, 'run', return_value=subprocess.CompletedProcess([], 2, '', 'database error')):
            with self.assertRaisesRegex(RuntimeError, 'Cannot query'):
                m.missing_packages([], ['mesa'])

    def test_invalid_renderer_flags_fail_before_erasing_existing_installation(self):
        marker = self.prefix / 'var/lib/proot-distro/containers/ubuntu-plasma-1/.desktop-de-kwin_plasma'
        spec = self.prefix / 'desktop.spec'
        spec.write_text('DESKTOP_DE=kwin_plasma\nDESKTOP_MODE=proot\n'
                        'DESKTOP_PROOT_DISTRO=ubuntu-plasma-1\n'
                        'DESKTOP_RENDERER_CONFIGURE_ONLY=1\nDESKTOP_PREPARE_ONLY=0\n'
                        'DESKTOP_STATUS_FILE=' + str(self.prefix / 'status') + '\n')
        result = subprocess.run(['sh', str(ASSETS / 'desktop_install.sh'), str(spec), '--operation=install'],
                                env={'PREFIX': str(self.prefix), 'PATH': '/usr/bin:/bin'},
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 64, result.stderr)
        self.assertTrue(marker.is_file())

    def test_renderer_branch_reuses_healthy_zink_and_exits_before_full_install(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'bin').mkdir()
            python = base / 'bin/python3'
            python.write_text('#!/bin/sh\nexit 0\n')
            python.chmod(0o755)
            script = (ASSETS / 'desktop_install.sh').read_text()
            branch = script[script.index('# Renderer changes reuse'):script.index('# Saving an existing desktop')]
            preamble = '''
run() { "$@"; }
log() { :; }
fail() { exit 99; }
ensure_turnip_icd() { exit 98; }
ensure_resolute_turnip_icd() { exit 97; }
initialize_desktop() { echo initialized; }
OPERATION=install
DESKTOP_RENDERER_CONFIGURE_ONLY=1
DESKTOP_PREPARE_ONLY=1
DESKTOP_DE=kwin_plasma
DESKTOP_HOST_GL_MODE=zink
DESKTOP_GUEST_GL_MODE=zink
DESKTOP_MODE=proot
DESKTOP_PROOT_DISTRO=ubuntu-plasma-1
PLASMA_EMULATOR=0
'''
            result = subprocess.run(['sh', '-uc', preamble + branch + '\nexit 96'],
                                    env={'PREFIX': str(base), 'PATH': '/usr/bin:/bin'},
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), 'initialized')


if __name__ == '__main__':
    unittest.main()
