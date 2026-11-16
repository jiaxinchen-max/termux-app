#!/usr/bin/env python3
"""Exercise save-time wallpaper preparation using real filesystem boundaries."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('desktop_initialize', ASSETS / 'desktop_initialize.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WallpaperTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / 'rootfs'
        self.target = self.root / 'usr/share/xfce4/backdrops/xubuntu-wallpaper.png'
        self.target.parent.mkdir(parents=True)
        self.source = self.root / 'usr/share/backgrounds/greybird.svg'
        self.source.parent.mkdir()
        self.source.write_text('<svg xmlns="http://www.w3.org/2000/svg"/>')
        self.config = self.root / 'home/termux/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('custom wallpaper settings remain byte-for-byte')

    def test_missing_default_uses_installed_artwork_and_preserves_user_configuration(self):
        module.prepare_xfce_wallpaper(self.root)
        self.assertTrue(self.target.is_symlink())
        self.assertEqual(self.target.resolve(), self.source)
        self.assertEqual(self.config.read_text(), 'custom wallpaper settings remain byte-for-byte')
        before = self.target.lstat().st_ino
        module.prepare_xfce_wallpaper(self.root)
        self.assertEqual(before, self.target.lstat().st_ino)

    def test_existing_default_is_preserved(self):
        self.target.write_bytes(b'user artwork')
        module.prepare_xfce_wallpaper(self.root)
        self.assertEqual(b'user artwork', self.target.read_bytes())

    def test_existing_container_absolute_link_is_preserved(self):
        self.target.symlink_to('/usr/share/backgrounds/greybird.svg')
        module.prepare_xfce_wallpaper(self.root)
        self.assertEqual('/usr/share/backgrounds/greybird.svg', str(self.target.readlink()))

    def test_parent_symlink_cannot_redirect_writes_outside_container(self):
        outside = self.root.parent / 'outside'
        outside.mkdir()
        self.target.parent.rmdir()
        self.target.parent.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(RuntimeError): module.prepare_xfce_wallpaper(self.root)
        self.assertEqual([], list(outside.iterdir()))

    def test_external_artwork_link_is_rejected(self):
        self.source.unlink()
        outside = self.root.parent / 'outside.svg'
        outside.write_text('external')
        self.source.symlink_to(outside)
        with self.assertRaises(RuntimeError): module.prepare_xfce_wallpaper(self.root)
        self.assertFalse(self.target.is_symlink())

    def test_missing_artwork_fails_without_publishing_a_broken_default(self):
        self.source.unlink()
        with self.assertRaises(OSError): module.prepare_xfce_wallpaper(self.root)
        self.assertFalse(self.target.is_symlink())


if __name__ == '__main__': unittest.main()
