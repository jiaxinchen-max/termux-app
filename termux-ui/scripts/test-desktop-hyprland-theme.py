#!/usr/bin/env python3
"""Regression coverage for private theme migration and untrusted desktop data."""
import importlib.util
import json
from pathlib import Path
import tempfile
import runpy
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_hyprland_theme.py'
spec = importlib.util.spec_from_file_location('hyprland_theme', source)
theme = importlib.util.module_from_spec(spec)
spec.loader.exec_module(theme)


class ThemeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        prefix = Path(self.temp.name) / 'usr'
        self.prefix_patch = patch.object(theme, 'PREFIX', prefix)
        self.prefix_patch.start(); self.addCleanup(self.prefix_patch.stop)
        components = runpy.run_path(str(source.with_name('desktop_hyprland_components.py')))
        components['install_theme'].__globals__['PREFIX'] = prefix
        components['install_theme']('0.57.0-r7', 'm3ww-v1', Path(__file__).resolve().parents[2] / 'distribution/hyprland-themes/build/hyprland-theme-m3ww-v1.tgz')

    def testCustomConfigEvenWithGeneratedHeaderRequiresExplicitThemeChoice(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); (work / 'config').mkdir()
            cfg = work / 'config/hyprland.lua'
            cfg.write_text('-- User configuration. Preserved across updates and restarts.\n-- my bindings\n')
            self.assertEqual('r7', theme.selected(work))
            (work / 'config/desktop-theme').write_text('m3ww-v1\n')
            self.assertEqual('m3ww-v1', theme.selected(work))
            self.assertIn('my bindings', cfg.read_text())

    def testUntrustedWindowTitleCannotBecomeWidgetCommand(self):
        title = '\") (button :onclick "touch /tmp/injected") \\ unicode 中文'
        clients = [dict(address='0x123', mapped=True, title=title), dict(address='bad; exec x', mapped=True, title='bad')]
        value = theme.task_markup(Path('/private'), clients, '0x123')
        self.assertEqual(1, value.count(':onclick'))
        self.assertNotIn('bad; exec', value)
        self.assertIn('focus 0x123', value)
        with patch.object(theme, 'ipc') as ipc:
            with self.assertRaises(ValueError):
                theme.dispatch(Path('/private'), 'focus', '0x123;evil')
            ipc.assert_not_called()

    def testPRootLauncherContainsOnlyGuestRoutedApps(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            items = theme.apps(work, 'proot')
            self.assertEqual(['Terminal', 'Files'], [i['name'] for i in items])
            self.assertTrue(all(str(work / 'bin') in i['command'][0] for i in items))

    def testPrivateTerminalAndThemePreserveUserEditsAcrossPrepare(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); (work / 'bin').mkdir(); (work / 'config').mkdir()
            cfg = work / 'config/hyprland.lua'; cfg.write_text('-- user')
            with patch.object(theme.subprocess, 'run'):
                theme.prepare(work, 'native')
                custom = work / 'config/themes/m3ww-v1/foot.ini'; custom.write_text('# custom terminal')
                theme.prepare(work, 'native')
            self.assertEqual('-- user', cfg.read_text())
            self.assertEqual('# custom terminal', custom.read_text())
            terminal = (work / 'bin/termux-hyprland-terminal-themed').read_text()
            self.assertIn('--no-config', terminal)
            self.assertIn('themes/m3ww-v1/fish/config.fish', terminal)
            yuck = (work / 'config/themes/m3ww-v1/eww.yuck').read_text()
            # A foreground GTK dialog would be killed by EWW's three-second timeout.
            self.assertIn('launcher >> ', yuck)
            self.assertIn('2>&1 &', yuck)

    def testBatteryNeverUsesIllustrativeOrStaleValues(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / 'battery.json'
            self.assertEqual('Battery —', theme.battery(p, 1000))
            for value in [dict(percent=54, updated=1), dict(percent=True, updated=999000)]:
                p.write_text(json.dumps(value)); self.assertEqual('Battery —', theme.battery(p, 1000))
            p.write_text(json.dumps(dict(percent=71, charging=True, updated=999000)))
            self.assertIn('71%', theme.battery(p, 1000))


if __name__ == '__main__':
    unittest.main()
