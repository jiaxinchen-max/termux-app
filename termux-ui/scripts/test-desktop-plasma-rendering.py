#!/usr/bin/env python3
"""Execute the real container renderer setup without starting a desktop."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'


class PlasmaRendererTest(unittest.TestCase):
    def setup_renderer(self, mode, icd='/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json'):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            identity = base / 'id'
            identity.write_text('#!/bin/sh\ncase "$1" in -un) echo termux;; -u) echo 1000;; *) exit 99;; esac\n')
            identity.chmod(0o755)
            # Stop before diagnostics/services; execute production selection and environment cleanup.
            setup = (ASSETS / 'desktop_container_session.sh').read_text().split('# Optional:')[0]
            setup = setup.replace('export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
                                  'export PATH=' + directory + ':/usr/bin:/bin')
            setup += '\nenv\n'
            script = base / 'setup.sh'
            script.write_text(setup)
            env = dict(os.environ, HOME='/home/termux', DESKTOP_KDE_SERVICE_POLICY='kwin-wayland',
                       QT_QPA_PLATFORM='wayland', XDG_SESSION_TYPE='wayland', PULSE_SERVER='127.0.0.1',
                       DESKTOP_GUEST_GL_MODE=mode, DESKTOP_GL_MODE='',
                       DESKTOP_PLASMA_GUEST_ICD=icd, DESKTOP_PLASMA_GUEST_SOFTWARE='0',
                       QSG_RHI_BACKEND='opengl', QT_QUICK_BACKEND='software',
                       GALLIUM_DRIVER='llvmpipe', LIBGL_ALWAYS_SOFTWARE='1',
                       VK_ICD_FILENAMES='/wrong/icd.json', VK_DRIVER_FILES='/other/icd.json')
            return subprocess.run(['sh', str(script), '--user-session'], env=env,
                                  capture_output=True, text=True, timeout=5)

    def test_gpu_uses_vulkan_presentation_and_keeps_zink_for_gl_clients(self):
        result = self.setup_renderer('zink')
        self.assertEqual(result.returncode, 0, result.stderr)
        env = dict(line.split('=', 1) for line in result.stdout.splitlines())
        self.assertEqual(env['QSG_RHI_BACKEND'], 'vulkan')
        self.assertNotIn('QT_QUICK_BACKEND', env)
        self.assertEqual(env['GALLIUM_DRIVER'], 'zink')
        self.assertEqual(env['MESA_LOADER_DRIVER_OVERRIDE'], 'zink')
        self.assertEqual(env['LIBGL_ALWAYS_SOFTWARE'], '0')
        self.assertEqual(env['VK_DRIVER_FILES'], '/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json')
        self.assertEqual(env['VK_ICD_FILENAMES'], env['VK_DRIVER_FILES'])

    def test_software_does_not_inherit_a_vulkan_backend(self):
        result = self.setup_renderer('llvmpipe')
        self.assertEqual(result.returncode, 0, result.stderr)
        env = dict(line.split('=', 1) for line in result.stdout.splitlines())
        self.assertEqual(env['QT_QUICK_BACKEND'], 'software')
        self.assertEqual(env['GALLIUM_DRIVER'], 'llvmpipe')
        self.assertEqual(env['LIBGL_ALWAYS_SOFTWARE'], '1')
        for name in ('QSG_RHI_BACKEND', 'VK_DRIVER_FILES', 'VK_ICD_FILENAMES'):
            self.assertNotIn(name, env)

    def test_gpu_rejects_missing_initialized_icd(self):
        self.assertEqual(self.setup_renderer('zink', '').returncode, 66)


if __name__ == '__main__':
    unittest.main()
