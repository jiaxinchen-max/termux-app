#!/usr/bin/env python3
"""Exercise old/new renderer specs without devices, package managers or GPU assumptions."""
import itertools
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
PAIRS = set('''native:xfce4:system:none native:xfce4:zink:none native:xfce4:llvmpipe:none
native:kwin_plasma:system:none native:kwin_plasma:zink:none native:kwin_plasma:llvmpipe:none
native:gnome:system:none native:ubuntu_gnome:system:none native:hyprland:system:none
proot:xfce4:system:default proot:xfce4:zink:default proot:xfce4:llvmpipe:default
proot:kwin_plasma:system:llvmpipe proot:kwin_plasma:system:zink proot:kwin_plasma:zink:llvmpipe proot:kwin_plasma:zink:zink proot:kwin_plasma:llvmpipe:llvmpipe
proot:gnome:system:llvmpipe proot:gnome:zink:zink proot:ubuntu_gnome:system:llvmpipe
proot:hyprland:system:default proot:hyprland:system:zink'''.split())


class DesktopRenderingTest(unittest.TestCase):
    def resolve(self, **values):
        command = '. ' + shlex.quote(str(ASSETS / 'desktop_rendering.sh')) + '; '
        command += '; '.join(k+'='+shlex.quote(v) for k, v in values.items()) + '; '
        command += ('desktop_resolve_rendering || exit $?; '
                    'printf "%s:%s" "$DESKTOP_HOST_GL_MODE" "$DESKTOP_GUEST_GL_MODE"')
        env = {k: v for k, v in os.environ.items() if not k.startswith('DESKTOP_')}
        return subprocess.run(['sh', '-uc', command], env=env, capture_output=True, text=True, timeout=3)

    def testAllVersion3PairsAreExplicitAndRejectUnsupportedLayers(self):
        for mode, de, host, guest in itertools.product(
            ('native', 'proot'), ('xfce4', 'kwin_plasma', 'gnome', 'hyprland', 'ubuntu_gnome'),
            ('system', 'zink', 'llvmpipe', 'auto'), ('none', 'default', 'system', 'zink', 'llvmpipe', 'unresolved')):
            pair = ':'.join((mode, de, host, guest))
            extra = dict(DESKTOP_CONTAINER_BASE="", DESKTOP_CONTAINER_CODENAME="",
                         DESKTOP_GNOME_SESSION_PROFILE="native-ubuntu", DESKTOP_REFRESH_RATE="60",
                         DESKTOP_GNOME_BACKEND="android", DESKTOP_GNOME_RENDERER="system",
                         DESKTOP_GNOME_DISPLAY_GL_MODE="", DESKTOP_GNOME_DISPLAY_BUFFER_MODE="") if mode == 'native' and de == 'ubuntu_gnome' else {}
            values = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_MODE=mode, DESKTOP_DE=de,
                                  DESKTOP_HOST_GL_MODE=host, DESKTOP_GUEST_GL_MODE=guest,
                                  DESKTOP_CONTAINER_BASE="ubuntu:noble", DESKTOP_CONTAINER_CODENAME="noble",
                                  DESKTOP_GNOME_SESSION_PROFILE="ubuntu", DESKTOP_USB_STORAGE="off",
                                  DESKTOP_GNOME_BACKEND="x11", DESKTOP_GNOME_RENDERER="llvmpipe",
                                  DESKTOP_GNOME_SESSION_STARTUP="direct", DESKTOP_GNOME_DISPLAY_GL_MODE="system",
                                  DESKTOP_GNOME_DISPLAY_BUFFER_MODE="native")
            result = self.resolve(**dict(values, **extra))
            self.assertEqual(0 if pair in PAIRS else 64, result.returncode, pair + result.stderr)
            if pair in PAIRS:
                self.assertEqual(host+':'+guest, result.stdout)

    def testOldAndMissingSpecVersionsAreRejected(self):
        for version in ('', '1', '2'):
            result = self.resolve(DESKTOP_SPEC_VERSION=version, DESKTOP_MODE='native',
                                  DESKTOP_DE='xfce4', DESKTOP_GL_MODE='system')
            self.assertEqual(64, result.returncode)

    def testConflictingMissingOrFutureVersionIsRejected(self):
        good = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_MODE='proot', DESKTOP_DE='hyprland',
                    DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='zink')
        for patch in (dict(DESKTOP_GL_MODE='zink'), dict(DESKTOP_SPEC_VERSION='99'),
                      dict(DESKTOP_GUEST_GL_MODE=''), dict(DESKTOP_HOST_GL_MODE=''), dict(DESKTOP_SPEC_VERSION='2')):
            self.assertEqual(64, self.resolve(**dict(good, **patch)).returncode)

    def testInvalidPairDoesNotStopAnExistingDesktop(self):
        with tempfile.TemporaryDirectory(prefix='desktop-pair-') as directory:
            base = Path(directory)
            state = base/'state'; state.mkdir()
            other = subprocess.Popen(['sleep', '60'])
            self.addCleanup(lambda: other.poll() is None and other.kill())
            try:
                (state/'other.pid').write_text(str(other.pid))
                spec = base/'spec'
                values = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_ID='new', DESKTOP_MODE='proot',
                              DESKTOP_DE='kwin_plasma', DESKTOP_HOST_GL_MODE='llvmpipe', DESKTOP_GUEST_GL_MODE='zink',
                              DESKTOP_PID_FILE=str(state/'new.pid'), DESKTOP_STATUS_FILE=str(state/'new.status'),
                              DESKTOP_LOG_FILE=str(base/'log'))
                spec.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in values.items()))
                result = subprocess.run(['sh', str(ASSETS/'desktop_start.sh'), str(spec)],
                                        capture_output=True, text=True, timeout=3)
                self.assertEqual(64, result.returncode, result.stderr)
                self.assertIsNone(other.poll())
                self.assertEqual('exit:64\n', (state/'new.status').read_text())
            finally:
                other.terminate(); other.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
