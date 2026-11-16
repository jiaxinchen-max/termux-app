#!/usr/bin/env python3
"""Execute host-prerequisite production entrypoint without touching any guest.

The shared fixture replaces only package/network commands, never production source.
"""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import unittest

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('install_fixture', BASE / 'test-gnome-usb-install.py')
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)
ASSETS = fixture_module.ASSETS

EXTRA_MOCK = r'''
if command=='python3' and len(args)>3:
    helper=pathlib.Path(args[2]).name
    action=args[3]
    if helper=='desktop_hyprland.py':
        if action=='check-shell': sys.exit(state.get('shell_error',0))
        if action=='check-runtime': sys.exit(state.get('runtime_error',0))
        if action=='install': sys.exit(state.get('runtime_install_error',0))
    if helper=='desktop_hyprland_components.py': sys.exit(state.get('theme_error',0))
'''


class HyprlandHostContract(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture_module.InstallContract(methodName='runTest')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.base = self.fixture.base
        for name in ('hyprland-components.sh', 'hyprland-components.json'):
            shutil.copy2(ASSETS / name, self.base / name)
        catalog = json.loads((ASSETS/'hyprland-components.json').read_text())
        for theme in catalog['themes'].values():
            for package in theme['packages']:
                self.fixture.state['versions'][package] = '1.0'
        runtime = catalog['runtimes']['0.57.0-r7']
        self.fixture.state['digests'][runtime['asset']] = runtime['archive_sha256']
        embedded = self.base/'usr/share/embedded-api'
        embedded.mkdir(parents=True)
        for package in ('termux-api', 'upower'):
            (embedded/(package+'.deb')).touch()
        # Extend the shared fake Python dispatcher. The shell under test is unmodified.
        target = self.base/'usr/bin/python3'
        mock = fixture_module.MOCK.replace("elif command=='python3':", EXTRA_MOCK+"\nelif command=='python3':")
        target.write_text('#!'+sys.executable+'\n'+mock)
        self.original = self.base/'usr/var/lib/proot-distro/containers/original/rootfs/home/termux/application.dat'
        self.original.parent.mkdir(parents=True)
        self.original.write_bytes(b'original guest must remain untouched')

    def run_host(self, **state):
        (self.base/'result').unlink(missing_ok=True)
        result, calls = self.fixture.run_install(de='hyprland', mode='proot', operation='host-prerequisites',
            overrides=dict(DESKTOP_SPEC_VERSION='3', DESKTOP_GL_MODE=None, DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='default',
                DESKTOP_AUDIO_MODE='host-pulseaudio', DESKTOP_HYPRLAND_RUNTIME_ID='0.57.0-r7',
                DESKTOP_HYPRLAND_THEME_ID='classic-r7', DESKTOP_HYPRLAND_BACKEND='android',
                DESKTOP_HYPRLAND_HOST_GL_MODE='system', DESKTOP_HYPRLAND_CLIENT_GL_MODE='default',
                DESKTOP_CONTAINER_BASE='ubuntu:resolute', DESKTOP_CONTAINER_CODENAME='resolute'), **state)
        self.assertEqual(b'original guest must remain untouched', self.original.read_bytes())
        self.assertEqual(['original'], sorted(p.name for p in self.original.parents[4].iterdir()))
        self.assertFalse(any(c[0]=='proot-distro' for c in calls), calls)
        self.assertFalse(any('desktop_initialize.py' in ' '.join(c) for c in calls), calls)
        return result, calls

    def test_healthy_host_reuses_components_without_network_or_guest_install(self):
        result, calls = self.run_host()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertFalse(any(c[0] in ('curl','pkg','apt','tar') for c in calls), calls)
        self.assertIn('state=succeeded', (self.base/'result').read_text())
        self.assertTrue(any('desktop_hyprland_components.py' in ' '.join(c) for c in calls))

    def test_missing_host_dependency_failure_cannot_report_success(self):
        del self.fixture.state['versions']['swaybg']
        result, calls = self.run_host(apt_error=74)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertTrue(any(c[0]=='apt' for c in calls))
        self.assertIn('state=failed', (self.base/'result').read_text())

    def test_missing_runtime_downloads_only_pinned_compositor_and_fails_closed(self):
        result, calls = self.run_host(runtime_error=1, runtime_install_error=75)
        self.assertNotEqual(0, result.returncode, result.stdout)
        downloads = [c for c in calls if c[0]=='curl']
        self.assertEqual(1, len(downloads), calls)
        self.assertTrue(any('hyprland-android-runtime-aarch64-0.57.0-r7.tar.gz' in x for x in downloads[0]))
        self.assertIn('state=failed', (self.base/'result').read_text())

    def test_theme_failure_and_invalid_shell_do_not_publish_ready_host(self):
        for error in ({'theme_error':76}, {'shell_error':77}):
            with self.subTest(error=error):
                result, calls = self.run_host(**({'theme_error':0, 'shell_error':0} | error))
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn('state=failed', (self.base/'result').read_text())


if __name__ == '__main__': unittest.main()
