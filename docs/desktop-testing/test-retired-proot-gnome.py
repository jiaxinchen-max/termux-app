#!/usr/bin/env python3
"""Retired desktops cannot start/install, but their containers remain removable."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'


class RetiredGnomeTest(unittest.TestCase):
    def test_shell_entries_reject_before_invoking_tools(self):
        for script, extra in [('desktop_install.sh', ['--operation=install']),
                              ('desktop_start.sh', [])]:
            with self.subTest(script=script), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                spec = root / 'desktop.spec'
                spec.write_text('DESKTOP_MODE=proot\nDESKTOP_DE=gnome\n'
                                + ''.join(f'DESKTOP_{field}_FILE={root / field.lower()}\n'
                                          for field in ('LOG', 'PID', 'STATUS')))
                result = subprocess.run(['sh', str(ASSETS / script), str(spec)] + extra,
                                        capture_output=True, text=True)
                self.assertEqual(64, result.returncode, result.stderr)
                self.assertIn('retired', result.stdout)

    def test_renderer_contract_rejects_retired_pairs(self):
        for host, guest in [('system', 'llvmpipe'), ('zink', 'zink')]:
            result = subprocess.run(['sh', '-c', '. "$1"; desktop_resolve_rendering',
                                     'check', str(ASSETS / 'desktop_rendering.sh')],
                                    env=dict(os.environ, DESKTOP_MODE='proot', DESKTOP_DE='gnome',
                                             DESKTOP_SPEC_VERSION='3', DESKTOP_HOST_GL_MODE=host,
                                             DESKTOP_GUEST_GL_MODE=guest))
            self.assertEqual(64, result.returncode)

    def test_removal_needs_no_supported_renderer_or_desktop_type(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'bin').mkdir()
            tool = root / 'bin/proot-distro'
            tool.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$PREFIX/calls"\n')
            tool.chmod(0o755)
            spec = root / 'remove.spec'
            spec.write_text('DESKTOP_MODE=proot\nDESKTOP_REMOVE_METHOD=proot-distro\n'
                            'DESKTOP_PROOT_DISTRO=debian-gnome-1\n'
                            f'DESKTOP_STATUS_FILE={root / "status"}\n')
            result = subprocess.run(['sh', str(ASSETS / 'desktop_remove.sh'), str(spec)],
                                    env=dict(os.environ, PREFIX=folder), capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['remove', 'debian-gnome-1'], (root / 'calls').read_text().splitlines())
            self.assertEqual('exit:0\n', (root / 'status').read_text())


if __name__ == '__main__':
    unittest.main()
