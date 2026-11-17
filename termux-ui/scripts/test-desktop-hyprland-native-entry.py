#!/usr/bin/env python3
"""Exercise theme and desktop-action CLIs with real managed desktop paths."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'


class NativeEntryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='hyprland-native-entry-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.home = self.root / 'home'
        self.prefix = self.root / 'usr'
        self.marker = self.root / 'eww-config'
        eww = self.root / 'eww'
        eww.write_text('#!' + sys.executable + '\n' + '''
import os
from pathlib import Path
import sys
import time
marker = Path(os.environ['EWW_TEST_CONFIG'])
if 'daemon' in sys.argv:
    marker.write_text(sys.argv[sys.argv.index('--config') + 1])
    deadline = time.monotonic() + 4
    while not marker.with_suffix('.opened').exists():
        if time.monotonic() > deadline:
            sys.exit(10)
        time.sleep(.02)
elif 'open' in sys.argv:
    marker.with_suffix('.opened').touch()
else:
    sys.exit(11)
''')
        eww.chmod(0o700)
        self.environment = dict(os.environ, HOME=str(self.home), PREFIX=str(self.prefix),
            TERMUX_HYPRLAND_GUEST='0', TERMUX_HYPRLAND_EWW=str(eww),
            EWW_TEST_CONFIG=str(self.marker), TERMUX_HYPRLAND_BATTERY=str(self.root / 'missing-battery'))

    def cli(self, helper, work, guest=False):
        action = 'serve' if helper == 'theme' else 'battery-status'
        return subprocess.run([sys.executable, '-I', '-B',
            str(ASSETS / ('desktop_hyprland_' + helper + '.py')), '--work', str(work), action],
            env=dict(self.environment, TERMUX_HYPRLAND_GUEST='1' if guest else '0'),
            capture_output=True, text=True, timeout=8)

    def test_fixed_native_entry_starts_foreground_theme_service(self):
        work = self.home / '.termux/hyprland-native-hyprland'
        result = self.cli('theme', work)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(work / 'config/themes/m3ww-v1'), self.marker.read_text())
        self.assertTrue(self.marker.with_suffix('.opened').exists())

    def test_fixed_native_entry_runs_desktop_action(self):
        result = self.cli('shell', self.home / '.termux/hyprland-native-hyprland')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Android battery status unavailable', result.stdout)

    def test_legacy_native_and_guest_entries_remain_compatible(self):
        for guest, relative in [(False, '.termux/hyprland-desktop-abc_123'),
                                (True, '.local/share/termux-hyprland/desktop-abc_123')]:
            for helper in ('theme', 'shell'):
                with self.subTest(guest=guest, helper=helper):
                    result = self.cli(helper, self.home / relative, guest)
                    self.assertEqual(0, result.returncode, result.stderr)
                    if helper == 'theme':
                        self.marker.unlink()
                        self.marker.with_suffix('.opened').unlink()

    def test_invalid_host_paths_are_rejected_before_actions(self):
        invalid = [self.home / '.other/hyprland-native-hyprland',
                   self.home / '.termux/hyprland-native-xfce4',
                   self.home / '.termux/hyprland-native-hyprland-extra',
                   self.home / '.termux/hyprland-desktop-',
                   self.home / '.termux/hyprland-desktop-bad name',
                   self.home / '.termux/hyprland-desktop-bad;command',
                   self.home / '.termux/desktop-abc_123',
                   self.home / '.termux/nested/hyprland-native-hyprland']
        for work in invalid:
            for helper in ('theme', 'shell'):
                with self.subTest(work=work, helper=helper):
                    result = self.cli(helper, work)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertIn('path', result.stderr)
                    self.assertFalse(self.marker.exists())
                    self.assertEqual('', result.stdout)

    def test_guest_rejects_native_and_outside_paths(self):
        for relative in ['.termux/hyprland-native-hyprland',
                         '.local/share/termux-hyprland/hyprland-native-hyprland',
                         '.local/share/termux-hyprland/desktop-',
                         '.local/share/termux-hyprland/desktop-bad name',
                         '.other/desktop-abc_123']:
            for helper in ('theme', 'shell'):
                with self.subTest(relative=relative, helper=helper):
                    result = self.cli(helper, self.home / relative, True)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertFalse(self.marker.exists())
                    self.assertEqual('', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
