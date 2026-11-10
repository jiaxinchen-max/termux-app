#!/usr/bin/env python3
"""Run the shipped PRoot selectors and command builders against isolated version fixtures."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
LAUNCH = (ASSETS / 'start_rootfs_game.sh').read_text()
START = LAUNCH.index('\nrun_rootfs_command() {')
END = LAUNCH.index('\n}\n', START) + 3
LAUNCH_FUNCTION = LAUNCH[START:END]


class SelectedRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='box64 selected ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path in ['opt/box64/rootfs_box64_0.2.8/bin/box64',
                     'opt/box64/rootfs_box64_0.3.8/bin/box64',
                     'opt/box64/rootfs_box64_0.4.5/bin/box64',
                     'opt/box64-wine/bin/wine64', 'opt/box64-proton/bin/wine']:
            binary = self.root / path
            binary.parent.mkdir(parents=True, exist_ok=True)
            binary.write_text('#!/bin/sh\nexit 0\n')
            binary.chmod(0o755)

    def run_command(self, wine, selected, pulse, maintenance=False):
        script = r'''
            . "$1"
            warmup_fail() { printf '%s\n' "$1" >&2; exit 91; }
            proot_exec_bound() { printf '%s\n' "$@"; }
            resolve_rootfs_translator
            printf 'SELECTED:%s\n' "$GUEST_COMMAND"
        ''' + LAUNCH_FUNCTION + ('\nrun_rootfs_maintenance_command test.exe' if maintenance else '\nrun_rootfs_command test.exe')
        env = dict(os.environ, ROOTFS_CANONICAL=str(self.root), TERMUX_FILES_DIR=str(self.root),
                   HOME_PATH=str(self.root / 'home'), PREFIX_PATH=str(self.root / 'prefix'),
                   ROOT_CANONICAL=str(self.root / 'game'), GUEST_GAME_ROOT='/mnt/games/game',
                   GUEST_PREFIX='/mnt/games/prefix', GUEST_WORKING_DIRECTORY='/mnt/games/game',
                   GUEST_LOCALE='C.UTF-8', GUEST_PULSE_SERVER=str(pulse),
                   GAMES_LAUNCH_TOKEN='test', WINE_PACKAGE=wine,
                   GAMES_CUSTOM_BOX64_ID=selected, GAMES_RUNTIME_TRANSLATOR='box64')
        return subprocess.run(['sh', '-eu', '-c', script, 'test', str(ASSETS / 'rootfs_prefix_warmup.sh')],
                              env=env, text=True, capture_output=True, timeout=10)

    def test_game_and_maintenance_use_only_selected_box64_libraries(self):
        for wine in ['rootfs_wine_10.0', 'rootfs_proton_11.0']:
            for selected in ['', 'rootfs_box64_0.2.8', 'rootfs_box64_0.3.8', 'rootfs_box64_0.4.5']:
                expected = selected or 'rootfs_box64_0.4.5'
                for pulse, maintenance in [(0, False), (1, False), (0, True)]:
                    with self.subTest(wine=wine, selected=selected, pulse=pulse, maintenance=maintenance):
                        p = self.run_command(wine, selected, pulse, maintenance)
                        self.assertEqual(p.returncode, 0, p.stderr)
                        self.assertEqual(p.stdout.splitlines()[0], f'SELECTED:/opt/box64/{expected}/bin/box64')
                        paths = [x for x in p.stdout.splitlines() if x.startswith('BOX64_LD_LIBRARY_PATH=')]
                        self.assertEqual(paths, [f'BOX64_LD_LIBRARY_PATH=/opt/box64/{expected}/lib/x86_64-linux-gnu'])

    def test_missing_selected_build_never_invokes_proot_or_available_default(self):
        for component in ['rootfs_box64_0.2.8', 'rootfs_box64_0.3.8']:
            (self.root / f'opt/box64/{component}/bin/box64').unlink()
            p = self.run_command('rootfs_wine_10.0', component, 0)
            self.assertEqual(p.returncode, 91)
            self.assertIn('custom_box64_id_invalid', p.stderr)
            self.assertEqual(p.stdout, '')


if __name__ == '__main__':
    unittest.main()
