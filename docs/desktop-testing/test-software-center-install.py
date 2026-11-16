#!/usr/bin/env python3
"""Run the actual installer with isolated host/download/guest command transports."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('hyprland_fixture', BASE/'test-hyprland-restore-host.py')
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)


class SoftwareInstall(unittest.TestCase):
    def setUp(self):
        host = fixture.HyprlandHostContract(methodName='runTest')
        host.setUp(); self.addCleanup(host.doCleanups)
        self.fixture = host.fixture
        self.base = host.base
        cert = self.base/'usr/etc/tls/cert.pem'
        cert.parent.mkdir(parents=True)
        cert.write_text('-----BEGIN CERTIFICATE-----\nfixture trust bundle\n-----END CERTIFICATE-----\n')
        # The transport logs every command before handling it; no production
        # script or dependency-selection logic is changed in this fixture.
        mock = fixture.fixture_module.MOCK.replace("elif command=='python3':", "elif command=='python3':\n    sys.exit(0)")
        (self.base/'usr/bin/python3').write_text('#!'+sys.executable+'\n'+mock)
        # This fixture downloads placeholders, so checksum -c is a transport
        # double as well. Production integrity checks remain unmodified.
        checksums = fixture.fixture_module.MOCK.replace("elif command=='sha256sum':",
            "elif command=='sha256sum':\n    if args[0]=='-c': sys.stdin.read(); sys.exit(state.get('checksum_error',0))")
        (self.base/'usr/bin/sha256sum').write_text('#!'+sys.executable+'\n'+checksums)
        for name in ('xfce4-terminal', 'thunar'):
            p = self.base/'usr/bin'/name
            p.write_text('#!/bin/sh\nexit 0\n'); p.chmod(0o755)

    def install(self, de, **state):
        values = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_GL_MODE=None,
            DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='default',
            DESKTOP_AUDIO_MODE='host-pulseaudio', DESKTOP_CONTAINER_BASE='ubuntu:resolute',
            DESKTOP_CONTAINER_CODENAME='resolute', DESKTOP_PROOT_DISTRO='ubuntu-'+de.replace('_','-')+'-1',
            DESKTOP_WIDTH='1280', DESKTOP_HEIGHT='720', DESKTOP_RENDER_SOCKET='/tmp/render',
            DESKTOP_HYPRLAND_RUNTIME_ID='0.57.0-r7', DESKTOP_HYPRLAND_THEME_ID='classic-r7',
            DESKTOP_HYPRLAND_BACKEND='android', DESKTOP_HYPRLAND_HOST_GL_MODE='system',
            DESKTOP_HYPRLAND_CLIENT_GL_MODE='default')
        if de == 'ubuntu_gnome':
            values.update(DESKTOP_GUEST_GL_MODE='llvmpipe', DESKTOP_GNOME_SESSION_PROFILE='ubuntu',
                DESKTOP_GNOME_BACKEND='android', DESKTOP_GNOME_RENDERER='system',
                DESKTOP_GNOME_SESSION_STARTUP='direct', DESKTOP_GNOME_APPLICATION_RENDERER='llvmpipe')
        return self.fixture.run_install(de=de, mode='proot', overrides=values, **state)

    def test_three_install_paths_prepare_deb_backend_and_catalog_before_success(self):
        # Separate fixtures avoid reusing completion markers across desktops.
        for de in ('xfce4', 'hyprland', 'ubuntu_gnome'):
            with self.subTest(de=de):
                result, calls = self.install(de)
                guest = [c for c in calls if c[0]=='proot-distro' and '/tmp/desktop_container_plasma_apt.sh' in c]
                self.assertTrue(guest, result.stdout)
                packages = next(c for c in guest if 'gnome-software' in c)
                for package in ('gnome-software-plugin-deb','packagekit','appstream','apt-config-icons',
                                'polkitd','python3-gi','gir1.2-polkit-1.0','zenity'):
                    self.assertIn(package, packages)
                self.assertNotIn('gnome-software-plugin-flatpak', packages)
                catalog = next(c for c in calls if '/tmp/desktop_container_discover_catalog.sh' in c)
                self.assertGreater(calls.index(catalog), calls.index(packages))
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertEqual('exit:0\n', (self.base/'state/status').read_text())

    def test_catalog_failure_cannot_publish_success_or_initialize_desktop(self):
        result, calls = self.install('xfce4', container_catalog_error=75)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any('desktop_initialize.py' in ' '.join(c) for c in calls))
        self.assertNotEqual('exit:0\n', (self.base/'state/status').read_text())

    def test_apk_runtime_extracts_every_new_service_script(self):
        repo = BASE.parents[1]
        source = (repo/'termux-ui/src/main/java/com/termux/ui/service/LaunchScriptInstaller.java').read_text()
        for name in ('desktop_software_center.py','desktop_container_software_services.py','desktop_container_polkit_agent.py'):
            self.assertIn('"'+name+'"',source)


if __name__ == '__main__': unittest.main()
