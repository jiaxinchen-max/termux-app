#!/usr/bin/env python3
"""Session transport/lifecycle tests; fake hardware never stands in for OTG acceptance."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'


def load(name):
    spec = importlib.util.spec_from_file_location(name, ASSETS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SessionTests(unittest.TestCase):
    def test_save_preserves_masked_prefix_and_rejects_redirected_guest_tmp(self):
        usb = load('desktop_usb_storage')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / 'rootfs'; root.mkdir()
            mask = root / 'data/data/com.termux/files/usr'; mask.mkdir(parents=True); mask.chmod(0)
            usb.prepare_guest_mount(root, Path('/tmp/tusb-example'))
            self.assertTrue((root / 'tmp/tusb-example').is_dir())
            self.assertEqual(0, mask.stat().st_mode & 0o777)
            mask.chmod(0o700)
            self.assertEqual([], list(mask.iterdir()))
            (root / 'tmp/tusb-example').rmdir(); (root / 'tmp').rmdir()
            outside = Path(folder) / 'outside'; outside.mkdir(); (root / 'tmp').symlink_to(outside)
            with self.assertRaises(OSError):
                usb.prepare_guest_mount(root, Path('/tmp/tusb-example'))
            self.assertEqual([], list(outside.iterdir()))
            mask.chmod(0o700)

    def test_service_readiness_does_not_wait_for_device_enumeration_and_stop_is_owned(self):
        usb = load('desktop_usb_storage')
        with tempfile.TemporaryDirectory(prefix='usb-run-', dir='/tmp') as folder:
            prefix = Path(folder)
            session = usb.Session(prefix, 'desktop-test', {'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/guest-bus'},
                                  ['/guest/gio'], prefix / 'service.log')
            runtime = session.runtime
            program = '''import pathlib,signal,socket,time,sys
p=pathlib.Path(sys.argv[1]);p.mkdir(parents=True)
s=socket.socket(socket.AF_UNIX);s.bind(str(p/'control.sock'));s.listen()
def stop(*args):
 (p/'cleaned').write_text('unmounted');sys.exit(0)
signal.signal(signal.SIGTERM,stop)
time.sleep(90) # represents an unavailable device; IPC must become ready first
'''
            session.command = [sys.executable, '-c', program, str(runtime)]
            unrelated = subprocess.Popen(['sleep', '20'])
            try:
                session.start()
                self.assertIsNone(unrelated.poll())
                session.require_alive()
                session.stop()
                self.assertEqual('unmounted', (runtime / 'cleaned').read_text())
                self.assertIsNone(unrelated.poll())
            finally:
                if session.process is not None and session.process.poll() is None:
                    session.process.kill(); session.process.wait()
                unrelated.terminate(); unrelated.wait()

    def test_failed_service_exit_is_not_reported_as_clean_stop(self):
        usb = load('desktop_usb_storage')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            session = usb.Session(root, 'failure', {}, ['/guest/gio'], root / 'log')
            session.process = subprocess.Popen([sys.executable, '-c', 'raise SystemExit(23)'])
            session.process.wait()
            with self.assertRaisesRegex(RuntimeError, 'exited'):
                session.require_alive()
            with self.assertRaisesRegex(RuntimeError, '23'):
                session.stop()

    def test_hyprland_clients_and_helper_share_guest_bus_with_separate_host_paths(self):
        hypr = load('desktop_hyprland')
        with tempfile.TemporaryDirectory() as folder, patch.object(hypr, 'PREFIX', Path(folder)):
            work = Path(folder) / 'home/.termux/hyprland-desktop-usb'
            commands = [hypr.container_command(work, 'ubuntu-hyprland-1', 'default', program,
                        'owned-session', 'read-only') for program in (['thunar'], ['/usr/local/libexec/tusb-gio-mount'])]
            for command in commands:
                self.assertIn('DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/hyprland-runtime/guest-session-bus', command)
                self.assertIn('GIO_EXTRA_MODULES=/usr/local/lib/termux-usb-bridge/gio/modules', command)
                state = next(v for v in command if v.startswith('TERMUX_USB_DESKTOP_DIR='))
                self.assertTrue(state.startswith('TERMUX_USB_DESKTOP_DIR=/tmp/tusb-'))
                self.assertNotIn('dbus-run-session', command)
                self.assertIn('termux', command)
            off = hypr.container_command(work, 'ubuntu-hyprland-1', 'default', ['thunar'], 'token')
            self.assertFalse(any('=unix:path=/tmp/hyprland-runtime/guest-session-bus' in v for v in off))


if __name__ == '__main__':
    unittest.main(verbosity=2)
