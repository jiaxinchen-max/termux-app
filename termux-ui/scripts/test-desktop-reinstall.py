#!/usr/bin/env python3
"""Execute real XFCE/Plasma reinstall branches with isolated package commands."""
from pathlib import Path
import runpy
import unittest

FIXTURES = runpy.run_path(str(Path(__file__).with_name('test-desktop-ubuntu-plasma.py')))

class ReinstallTests(unittest.TestCase):
    def setUp(self):
        self.fixture = FIXTURES['UbuntuPlasmaInstallTests']()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.base = self.fixture.base

    def install(self, de, mode, flag='1', **state):
        return self.fixture.fixture.run_install(de=de, mode=mode, overrides={
            'DESKTOP_GL_MODE': None, 'DESKTOP_SPEC_VERSION': '3',
            'DESKTOP_REINSTALL': flag, 'DESKTOP_CONTAINER_BASE': 'ubuntu:resolute',
            'DESKTOP_CONTAINER_CODENAME': 'resolute', 'DESKTOP_HOST_GL_MODE': 'system',
            'DESKTOP_GUEST_GL_MODE': 'none' if mode=='native' else 'llvmpipe' if de=='kwin_plasma' else 'default'}, **state)

    def testBothModesActuallyReinstallPackagesAndPreserveExistingContainer(self):
        for de in ['xfce4', 'kwin_plasma']:
            for mode in ['native','proot']:
                with self.subTest(de=de,mode=mode):
                    alias=('ubuntu' if de=='kwin_plasma' else 'debian')+'-'+de.replace('_','-')+'-1'
                    container=self.base/'usr/var/lib/proot-distro/containers'/alias
                    user=container/'rootfs/home/termux/keep'
                    user.parent.mkdir(parents=True,exist_ok=True);user.write_text('preserve')
                    release=container/'rootfs/etc/os-release';release.parent.mkdir(parents=True,exist_ok=True)
                    release.write_text('ID=ubuntu\nVERSION_CODENAME=resolute\n')
                    result,calls=self.install(de,mode)
                    self.assertEqual(0,result.returncode,result.stdout)
                    host=[c for c in calls if c[:2]==['apt','install'] and '--allow-downgrades' in c]
                    self.assertEqual(1,len(host));self.assertIn('--reinstall',host[0])
                    if mode=='proot':
                        desktop=[c for c in calls if '/tmp/desktop_container_plasma_apt.sh' in c and ('xfce4' in c or 'plasma-desktop' in c)]
                        self.assertEqual(1,len(desktop),calls);self.assertIn('--reinstall',desktop[0])
                    self.assertFalse(any(c[:2] in [['proot-distro','remove'],['proot-distro','install']] for c in calls))
                    self.assertEqual('preserve',user.read_text())

    def testOrdinaryXfceInstallDoesNotForceGuestReinstall(self):
        result,calls=self.install('xfce4','proot',flag='0')
        self.assertEqual(0,result.returncode,result.stdout)
        guest=next(c for c in calls if '/tmp/desktop_container_plasma_apt.sh' in c)
        self.assertNotIn('--reinstall',guest)

    def testFailedReinstallCannotKeepOldSuccessMarker(self):
        marker=self.base/'usr/var/lib/proot-distro/containers/debian-xfce4-1/.desktop-de-xfce4'
        marker.parent.mkdir(parents=True);marker.touch()
        release=marker.parent/'rootfs/etc/os-release';release.parent.mkdir(parents=True)
        release.write_text('ID=ubuntu\nVERSION_CODENAME=resolute\n')
        result,calls=self.install('xfce4','proot',container_apt_error=100)
        self.assertEqual(100,result.returncode,result.stdout)
        self.assertFalse(marker.exists());self.assertEqual('exit:100\n',(self.base/'state/status').read_text())

if __name__=='__main__':unittest.main()
