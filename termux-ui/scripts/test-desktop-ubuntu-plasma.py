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
        ca = self.base / 'usr/etc/tls/cert.pem'
        ca.parent.mkdir(parents=True, exist_ok=True)
        ca.write_bytes(Path('/etc/ssl/certs/ca-certificates.crt').read_bytes())
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
            'plasma-workspace', 'dolphin', 'konsole', 'python3', 'appstream')) for c in package_calls), package_calls)
        self.assertTrue((self.base / 'usr/var/lib/proot-distro/containers/ubuntu-kwin-plasma-1/.desktop-de-kwin_plasma').exists())
        ca = self.base / 'usr/var/lib/proot-distro/containers/ubuntu-kwin-plasma-1/rootfs/etc/ssl/certs/ca-certificates.crt'
        self.assertEqual((self.base / 'usr/etc/tls/cert.pem').read_bytes(), ca.read_bytes())

    def test_catalog_is_prepared_after_packages_before_initialization(self):
        result, calls = self.install()
        self.assertEqual(result.returncode, 0, result.stdout)
        catalog = next(i for i, c in enumerate(calls) if '/tmp/desktop_container_discover_catalog.sh' in c)
        packages = [i for i, c in enumerate(calls) if '/tmp/desktop_container_plasma_apt.sh' in c]
        initialize = next(i for i, c in enumerate(calls) if any('desktop_initialize.py' in a for a in c))
        self.assertLess(max(packages), catalog)
        self.assertLess(catalog, initialize)
        register = next(i for i, c in enumerate(calls) if c[:2] == ['dpkg', '-i']
                        and c[-1].endswith('/termux-api.deb'))
        self.assertLess(max(i for i, c in enumerate(calls) if c[:2] == ['apt', 'install']), register)
        self.assertLess(register, initialize)
        self.assertIn(['apt-mark', 'hold', 'termux-api'], calls)
        self.assertTrue((self.base / 'usr/tmp/desktop_container_discover_catalog.sh').is_file())

    def test_account_is_provisioned_as_root_after_packages_before_initialization(self):
        result, calls = self.install()
        self.assertEqual(0, result.returncode, result.stdout)
        account = next(i for i, c in enumerate(calls) if '/tmp/desktop_container_user.sh' in c)
        self.assertEqual(['--user', 'root'], calls[account][3:5])
        packages = [i for i, c in enumerate(calls) if '/tmp/desktop_container_plasma_apt.sh' in c]
        self.assertLess(max(packages), account)
        self.assertTrue(any('sudo' in calls[i] and 'passwd' in calls[i] for i in packages))
        self.assertLess(account, next(i for i,c in enumerate(calls) if any('desktop_initialize.py' in a for a in c)))

    def test_failed_account_setup_prevents_success_marker(self):
        result, calls = self.install(container_user_error=77)
        self.assertEqual(77, result.returncode, result.stdout)
        self.assertFalse((self.base / 'usr/var/lib/proot-distro/containers/ubuntu-kwin-plasma-1/.desktop-de-kwin_plasma').exists())
        self.assertFalse(any(any('desktop_initialize.py' in a for a in c) for c in calls))

    def test_catalog_failure_preserves_error_and_blocks_success(self):
        marker = self.base / 'usr/var/lib/proot-distro/containers/ubuntu-kwin-plasma-1/.desktop-de-kwin_plasma'
        marker.parent.mkdir(parents=True)
        release = marker.parent / 'rootfs/etc/os-release'
        release.parent.mkdir(parents=True)
        release.write_text('ID=ubuntu\nVERSION_CODENAME=resolute\n')
        marker.touch()  # A retry must not retain an earlier success marker.
        result, calls = self.install(container_catalog_error=73)
        self.assertEqual(result.returncode, 73, result.stdout)
        self.assertEqual((self.base / 'state/status').read_text(), 'exit:73\n')
        self.assertFalse(marker.exists())
        self.assertFalse(any(any('desktop_initialize.py' in a for a in c) for c in calls))

    def test_package_failure_does_not_prepare_catalog(self):
        result, calls = self.install(container_apt_error=100)
        self.assertEqual(result.returncode, 100, result.stdout)
        self.assertFalse(any('/tmp/desktop_container_discover_catalog.sh' in c for c in calls))

    def test_native_plasma_does_not_prepare_discover_catalog(self):
        result, calls = self.fixture.run_install(de='kwin_plasma', mode='native', overrides={
            'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
            'DESKTOP_HOST_GL_MODE': 'system', 'DESKTOP_GUEST_GL_MODE': 'none',
            'DESKTOP_CONTAINER_CODENAME': None})
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertFalse(any('/tmp/desktop_container_discover_catalog.sh' in c for c in calls))

    def test_xfce_does_not_prepare_discover_catalog(self):
        result, calls = self.fixture.run_install(de='xfce4', mode='proot', overrides={
            'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
            'DESKTOP_HOST_GL_MODE': 'system', 'DESKTOP_GUEST_GL_MODE': 'default'})
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertFalse(any('/tmp/desktop_container_discover_catalog.sh' in c for c in calls))

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
