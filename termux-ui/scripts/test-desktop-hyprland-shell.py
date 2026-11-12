#!/usr/bin/env python3
"""Desktop upgrade preservation, container routing and truthful Android status."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_hyprland_shell.py'
spec = importlib.util.spec_from_file_location('desktop_shell', source)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


class ShellTests(unittest.TestCase):
    def testUpgradePreservesUserPanelWallpaperAndHyprlandConfig(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            (work / 'bin').mkdir()
            originals = {
                'config/hyprland.lua': '-- user bindings',
                'config/hyprland-wallpaper.svg': '<svg>user wallpaper</svg>',
                'config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml': '<channel>user panel</channel>',
            }
            for name, text in originals.items():
                p = work / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
            h.prepare(work, 'native')
            h.prepare(work, 'native')
            for name, text in originals.items():
                self.assertEqual(text, (work / name).read_text())

    def testContainerMenuOnlyAdvertisesRoutedClientsAndDesktopControls(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            (work / 'bin').mkdir()
            h.prepare(work, 'proot')
            menu = (work / 'config/menus/hyprland-applications.menu').read_text()
            self.assertNotIn('<All', menu)
            for action in ['terminal', 'files']:
                desktop = (work / ('data/applications/termux-hyprland-' + action + '.desktop')).read_text()
                self.assertIn(str(work / ('bin/termux-hyprland-' + action)), desktop)

    def testBatteryRequiresFreshValidAndroidData(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'status.json'
            self.assertIn('unavailable', h.battery_status(path, now=1000))
            for data in [dict(percent=102, updated=1000000), dict(percent=71, updated=1),
                         dict(percent='71', updated=1000000), dict(percent=True, updated=1000000),
                         dict(percent=71, updated=1200000)]:
                path.write_text(json.dumps(data))
                self.assertIn('unavailable', h.battery_status(path, now=1000))
            path.write_text(json.dumps(dict(percent=71, charging=True, updated=990000)))
            self.assertIn('⚡ 71%', h.battery_status(path, now=1000))


if __name__ == '__main__':
    unittest.main()
