#!/usr/bin/env python3
"""Execute configuration-only installs; package processes are isolated fixtures."""
from pathlib import Path
import runpy
import shutil
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'termux-ui/src/main/assets/local-games'
CONTRACT = runpy.run_path(str(ROOT / 'termux-ui/scripts/test-desktop-ubuntu-plasma.py'))


class UsbInstallTests(unittest.TestCase):
    def test_each_proot_desktop_prepares_glibc_usb_without_reinstalling_or_invalidating_desktop(self):
        for de in ('xfce4', 'kwin_plasma', 'ubuntu_gnome', 'hyprland'):
            for mode in ('off', 'read-only'):
                with self.subTest(de=de, mode=mode):
                    fixture = CONTRACT['UbuntuPlasmaInstallTests'](); fixture.setUp()
                    try:
                        base = fixture.base
                        shutil.copy2(ASSETS / 'hyprland-components.sh', base)
                        fake = base / 'usr/bin/python3'
                        fake.write_text(fake.read_text().replace("elif command=='python3':",
                            "elif command=='python3':\n"
                            "    if any('desktop_install_gio.py' in a for a in args): sys.exit(0)"))
                        digest = base / 'usr/bin/sha256sum'
                        digest.write_text(digest.read_text().replace("elif command=='sha256sum':",
                            "elif command=='sha256sum':\n"
                            "    if args == ['-c', '-']:\n"
                            "        expected,target=sys.stdin.read().strip().split(None,1)\n"
                            "        sys.exit(0 if state['digests'].get(pathlib.Path(target).name)==expected else 1)"))
                        alias = 'ubuntu-' + de.replace('_', '-') + '-1'
                        container = base / 'usr/var/lib/proot-distro/containers' / alias
                        (container / 'rootfs').mkdir(parents=True)
                        marker = container / ('.desktop-de-' + de); marker.write_text('installed')
                        receipt = base / 'usr/var/lib/termux-desktop/rendering' / (alias + '.system.llvmpipe.env')
                        receipt.parent.mkdir(parents=True, exist_ok=True); receipt.write_text('saved renderer')
                        overrides = dict(DESKTOP_GL_MODE=None, DESKTOP_SPEC_VERSION='3',
                            DESKTOP_PROOT_DISTRO=alias, DESKTOP_CONTAINER_BASE='ubuntu:resolute',
                            DESKTOP_CONTAINER_CODENAME='resolute', DESKTOP_HOST_GL_MODE='system',
                            DESKTOP_GUEST_GL_MODE='default' if de in ('xfce4', 'hyprland') else 'llvmpipe',
                            DESKTOP_USB_CONFIGURE_ONLY='1', DESKTOP_WIDTH='1280', DESKTOP_HEIGHT='720',
                            DESKTOP_RENDER_SOCKET=str(base / 'render.sock'),
                            DESKTOP_GNOME_SESSION_PROFILE='ubuntu', DESKTOP_GNOME_BACKEND='android',
                            DESKTOP_GNOME_RENDERER='system', DESKTOP_GNOME_SESSION_STARTUP='direct',
                            DESKTOP_GNOME_APPLICATION_RENDERER='llvmpipe',
                            DESKTOP_HYPRLAND_BACKEND='android', DESKTOP_HYPRLAND_RUNTIME_ID='0.57.0-r7',
                            DESKTOP_HYPRLAND_THEME_ID='classic-r7')
                        result, calls = fixture.fixture.run_install(de=de, mode='proot', usb=mode, overrides=overrides)
                        self.assertEqual(0, result.returncode, result.stdout)
                        self.assertEqual('installed', marker.read_text())
                        self.assertEqual('saved renderer', receipt.read_text())
                        self.assertFalse(any(c[:2] == ['proot-distro', 'install'] for c in calls))
                        self.assertFalse(any('/tmp/desktop_container_discover_catalog.sh' in c for c in calls))
                        self.assertTrue(any(any('desktop_initialize.py' in arg for arg in c) for c in calls))
                        guest = [c for c in calls if '/tmp/desktop_container_plasma_apt.sh' in c]
                        if mode == 'read-only':
                            self.assertEqual(1, len(guest), calls)
                            self.assertIn('gvfs-backends', guest[0])
                            self.assertEqual(de == 'kwin_plasma', 'thunar' in guest[0])
                            self.assertTrue(any(any('desktop_install_gio.py' in a for a in c) for c in calls))
                            self.assertTrue(any('gio-querymodules' in c for c in calls))
                        else:
                            self.assertEqual([], guest)
                            self.assertFalse(any(c[0] == 'apt' for c in calls))
                    finally:
                        fixture.doCleanups()


if __name__ == '__main__':
    unittest.main(verbosity=2)
