#!/usr/bin/env python3
"""Run a real D-Bus using the launcher's generated service activation policy."""
from pathlib import Path
import os
import shlex
import shutil
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'

@unittest.skipUnless(shutil.which('dbus-daemon') and shutil.which('dbus-send'), 'requires D-Bus tools')
class SystemBusPolicy(unittest.TestCase):
    def test_supported_services_are_activatable_and_hardware_services_fail_promptly(self):
        with tempfile.TemporaryDirectory(prefix='plasma-system-bus-') as folder:
            root = Path(folder)
            services = root / 'distro-services'
            services.mkdir()
            names = ['org.freedesktop.PackageKit', 'org.freedesktop.PolicyKit1',
                     'org.freedesktop.UPower', 'org.freedesktop.UDisks2',
                     'org.freedesktop.ModemManager1', 'org.freedesktop.fwupd',
                     'fi.w1.wpa_supplicant1']
            for name in names:
                (services / (name + '.service')).write_text(
                    '[D-BUS Service]\nName=' + name + '\nExec=/bin/false\nUser=' + str(os.getuid()) + '\n')
            source = (ASSETS / 'desktop_container_session.sh').read_text()
            setup = source[source.index('# Only publish system services'):source.index('/usr/bin/dbus-daemon --config-file=')]
            setup = setup.replace('/usr/share/dbus-1/system-services', str(services))
            subprocess.run(['sh', '-eu', '-c', 'BUS_DIR=' + shlex.quote(str(root)) + '\n' + setup], check=True)
            daemon = subprocess.Popen(['dbus-daemon', '--nofork', '--print-address=1',
                '--config-file=' + str(root / 'system.conf')], stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True)
            try:
                address = daemon.stdout.readline().strip()
                self.assertTrue(address)
                def call(method, *args):
                    return subprocess.run(['dbus-send', '--bus=' + address, '--print-reply',
                        '--reply-timeout=1500', '--dest=org.freedesktop.DBus',
                        '/org/freedesktop/DBus', 'org.freedesktop.DBus.' + method, *args],
                        capture_output=True, text=True, timeout=3)
                listing = call('ListActivatableNames')
                self.assertEqual(0, listing.returncode, listing.stderr)
                for name in names[:2]:
                    self.assertIn('"' + name + '"', listing.stdout)
                for name in names[2:]:
                    self.assertNotIn('"' + name + '"', listing.stdout)
                    result = call('StartServiceByName', 'string:' + name, 'uint32:0')
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('org.freedesktop.DBus.Error.ServiceUnknown', result.stderr)
            finally:
                daemon.terminate()
                daemon.communicate(timeout=3)

if __name__ == '__main__':
    unittest.main()
