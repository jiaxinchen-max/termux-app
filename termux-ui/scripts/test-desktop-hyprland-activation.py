#!/usr/bin/env python3
"""Verify environment received by a real D-Bus activated process."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_hyprland.py'
spec = importlib.util.spec_from_file_location('hyprland', source)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)

class ActivationTests(unittest.TestCase):
    def testActivatedApplicationReceivesFinalDisplayWithoutCompositorLibraries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            services = root / 'services'; services.mkdir()
            output = root / 'activated.json'
            app = root / 'app.py'
            app.write_text('import os,json,time\nfrom gi.repository import Gio,GLib\nbus=Gio.bus_get_sync(Gio.BusType.SESSION,None)\nbus.call_sync("org.freedesktop.DBus","/org/freedesktop/DBus","org.freedesktop.DBus","RequestName",GLib.Variant("(su)",("org.example.HyprlandProbe",0)),None,Gio.DBusCallFlags.NONE,3000,None)\nfrom pathlib import Path\nPath(' + repr(str(output)) + ').write_text(json.dumps(dict(os.environ)))\ntime.sleep(.2)\n')
            (services / 'org.example.HyprlandProbe.service').write_text(
                '[D-BUS Service]\nName=org.example.HyprlandProbe\nExec=/usr/bin/python3 ' + str(app) + '\n')
            address = 'unix:path=' + str(root / 'bus')
            config = root / 'bus.conf'
            config.write_text('<busconfig><type>session</type><listen>' + address +
                '</listen><servicedir>' + str(services) + '</servicedir>'
                '<policy context="default"><allow own="*"/><allow send_destination="*"/>'
                '<allow receive_sender="*"/></policy></busconfig>')
            # Simulate the bus created before the compositor determines its display.
            old = dict(os.environ, DBUS_SESSION_BUS_ADDRESS=address,
                       LD_LIBRARY_PATH='/compositor/private/lib')
            for key in ('WAYLAND_DISPLAY', 'GDK_BACKEND', 'HYPRLAND_INSTANCE_SIGNATURE'):
                old.pop(key, None)
            bus = subprocess.Popen(['/usr/bin/dbus-daemon', '--nofork', '--config-file=' + str(config)],
                env=old, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 5
                while not (root / 'bus').exists():
                    self.assertIsNone(bus.poll())
                    self.assertLess(time.monotonic(), deadline)
                    time.sleep(.02)
                env = dict(old, WAYLAND_DISPLAY='wayland-test', XDG_RUNTIME_DIR=str(root),
                           GDK_BACKEND='wayland', HYPRLAND_INSTANCE_SIGNATURE='test-instance',
                           TERMUX_HYPRLAND_SESSION='owned-test-session')
                env.pop('LD_LIBRARY_PATH', None)
                with patch.object(h, 'PREFIX', Path('/usr')):
                    h.update_activation_environment(env)
                subprocess.run(['/usr/bin/gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus',
                    '--object-path', '/org/freedesktop/DBus', '--method',
                    'org.freedesktop.DBus.StartServiceByName', 'org.example.HyprlandProbe', '0'],
                    env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
                deadline = time.monotonic() + 3
                while not output.exists():
                    self.assertLess(time.monotonic(), deadline)
                    time.sleep(.02)
                actual = json.loads(output.read_text())
                for key in ('WAYLAND_DISPLAY','XDG_RUNTIME_DIR','GDK_BACKEND',
                            'HYPRLAND_INSTANCE_SIGNATURE','TERMUX_HYPRLAND_SESSION'):
                    self.assertEqual(env[key], actual.get(key), key)
                self.assertEqual(address, actual['DBUS_SESSION_BUS_ADDRESS'].split(',guid=')[0])
                self.assertFalse(actual.get('LD_LIBRARY_PATH'))
                self.assertFalse(actual.get('LD_PRELOAD'))
            finally:
                bus.terminate(); bus.wait(timeout=5)

    def testActivationUpdateFailureIsReported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'bin').mkdir()
            binary = root / 'bin/dbus-update-activation-environment'
            binary.write_text('#!/bin/sh\necho "activation denied" >&2\nexit 1\n'); binary.chmod(0o700)
            with patch.object(h, 'PREFIX', root), self.assertRaisesRegex(RuntimeError, 'activation denied'):
                h.update_activation_environment(dict(os.environ))

if __name__ == '__main__':
    unittest.main()
