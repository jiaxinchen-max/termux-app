#!/usr/bin/env python3
"""Run the real installer with isolated commands; rootfs identity is checked for real."""
from pathlib import Path
import runpy
import unittest

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = runpy.run_path(str(ROOT / 'docs/desktop-testing/test-gnome-usb-install.py'))


class UbuntuPlasmaInstallTests(unittest.TestCase):
    def setUp(self):
        self.fixture = CONTRACT['InstallContract']()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.base = self.fixture.base
        embedded = self.base / 'usr/share/embedded-api/termux-api.deb'
        embedded.parent.mkdir(parents=True)
        embedded.touch()
        script = self.base / 'desktop_install.sh'
        script.write_text(script.read_text().replace(
            '$(/system/bin/getprop ro.kernel.qemu 2>/dev/null)', '1'))
        # Initialization has its own executable tests; this fixture covers the shell entry point.
        fake = self.base / 'usr/bin/python3'
        fake.write_text(fake.read_text().replace("elif command=='python3':",
            "elif command=='python3':\n"
            "    if any('desktop_initialize.py' in a for a in args): sys.exit(0)"))

    def install(self, **state):
        return self.fixture.run_install(de='kwin_plasma', mode='proot', overrides={
            'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
            'DESKTOP_CONTAINER_BASE': 'ubuntu:resolute', 'DESKTOP_CONTAINER_CODENAME': 'resolute',
            'DESKTOP_HOST_GL_MODE': 'system', 'DESKTOP_GUEST_GL_MODE': 'llvmpipe'}, **state)

    def test_fresh_install_uses_ubuntu_and_kubuntu_packages(self):
        result, calls = self.install()
        self.assertEqual(result.returncode, 0, result.stdout)
        install = next(c for c in calls if c[:2] == ['proot-distro', 'install'] and '-n' in c)
        self.assertEqual(install[-1], 'ubuntu:resolute')
        self.assertIn('ubuntu-kwin-plasma-1', install)
        package_calls = [c for c in calls if '/tmp/desktop_container_plasma_apt.sh' in c]
        self.assertTrue(any(all(p in c for p in ('kubuntu-settings-desktop', 'plasma-desktop',
            'plasma-workspace', 'dolphin', 'konsole', 'python3')) for c in package_calls), package_calls)
        self.assertTrue((self.base / 'usr/var/lib/proot-distro/containers/ubuntu-kwin-plasma-1/.desktop-de-kwin_plasma').exists())

    def test_missing_optional_base_uses_ubuntu_default(self):
        result, calls = self.fixture.run_install(de='kwin_plasma', mode='proot', overrides={
            'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
            'DESKTOP_HOST_GL_MODE': 'system', 'DESKTOP_GUEST_GL_MODE': 'llvmpipe'})
        self.assertEqual(result.returncode, 0, result.stdout)
        install = next(c for c in calls if c[:2] == ['proot-distro', 'install'] and '-n' in c)
        self.assertEqual(install[-1], 'ubuntu:resolute')

    def test_debian_with_matching_codename_is_rejected(self):
        result, calls = self.install(actual_distro_id='debian', actual_codename='resolute')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('requires ubuntu:resolute', result.stdout)
        self.assertFalse(any('/tmp/desktop_container_plasma_apt.sh' in c for c in calls))
        self.assertFalse((self.base / 'usr/var/lib/proot-distro/containers/ubuntu-kwin-plasma-1/.desktop-de-kwin_plasma').exists())

    def test_wrong_ubuntu_release_is_rejected(self):
        result, calls = self.install(actual_distro_id='ubuntu', actual_codename='noble')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('requires ubuntu:resolute', result.stdout)
        self.assertFalse(any('/tmp/desktop_container_plasma_apt.sh' in c for c in calls))


if __name__ == '__main__':
    unittest.main()
