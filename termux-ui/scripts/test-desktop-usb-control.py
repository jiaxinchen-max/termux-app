#!/usr/bin/env python3
"""Policy/health unit tests with a stub coordinator; does not claim physical USB validation."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import ModuleType
import unittest
from unittest.mock import patch, Mock

HELPER = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_usb_storage.py'


class StoragePolicyTest(unittest.TestCase):
    def load(self):
        spec = importlib.util.spec_from_file_location('desktop_usb_app_test', HELPER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def run_helper(self, test, mode='native', gio=None):
        with tempfile.TemporaryDirectory(prefix='usb-policy-') as folder:
            root = Path(folder)
            desktop = ModuleType('desktop')
            class Manager:
                def __init__(self, args):
                    self.args = args; self.lock = threading.RLock(); self.records = {}; self.broker_process = None
                    self.fail_poll = False; self.environment = dict(os.environ)
                def poll(self):
                    if self.fail_poll: raise PermissionError('Termux:API background permission denied')
                def action(self, command, key):
                    if command == 'status': return json.dumps(list(self.records.values()))
                    raise ValueError('unexpected operation')
                def publish(self): pass
                def classify(self, record, authorize=False):
                    if authorize: raise AssertionError("external API authorization must not run")
                    return True
                def unmount(self, record, disconnected):
                    if disconnected: raise AssertionError('shutdown must not pretend physical unplug')
                    if record.get('fail_cleanup'): raise OSError('GVfs unmount failed')
            desktop.Manager = Manager
            desktop.private_directory = lambda path: path.mkdir(mode=0o700, parents=True, exist_ok=True)
            desktop.serve = lambda settings: test(desktop.Manager(settings), settings, root)
            client = root / 'prefix/libexec/termux-usb-app'
            client.parent.mkdir(parents=True); client.write_text('#!/bin/sh\nexit 0\n'); client.chmod(0o700)
            argv = [str(HELPER), '--mode', 'read-only', '--run-mode', mode,
                '--runtime', str(root / 'desktop'), '--broker-socket', str(root / 'broker.sock'),
                '--termux-usb', str(client)]
            if gio is not None: argv += ['--gio-command-json', json.dumps(gio)]
            with patch.dict(sys.modules, {'desktop': desktop}), patch.object(sys, 'argv', argv), patch.dict(os.environ,
                PREFIX=str(root / 'prefix'), DBUS_SESSION_BUS_ADDRESS='unix:path=' + str(root / 'bus'),
                XDG_RUNTIME_DIR=str(root), PATH=os.environ.get('PATH', '')):
                self.load().main()

    def test_only_storage_read_only_and_real_permission_are_selected(self):
        def check(manager, settings, root):
            self.assertTrue(settings.no_camera); self.assertTrue(settings.no_gamepad)
            self.assertFalse(settings.read_write); self.assertIsNone(settings.test_manifest)
            self.assertTrue(settings.request_permission)
            self.assertEqual('read-only', json.loads(manager.action('health', ''))['storageMode'])
        self.run_helper(check)

    def test_permission_failure_is_preserved_and_not_reported_as_empty_success(self):
        def check(manager, settings, root):
            manager.records['one'] = {'path': '/dev/bus/usb/001/002', 'status': 'mounted'}
            manager.fail_poll = True
            with self.assertRaises(PermissionError): manager.poll()
            health = json.loads(manager.action('health', ''))
            self.assertIn('background permission denied', health['enumerationError'])
            self.assertEqual(1, len(health['devices']))
            self.assertEqual(0, health['lastPollMillis'])
            manager.fail_poll = False; manager.poll()
            self.assertIsNone(json.loads(manager.action('health', ''))['enumerationError'])
        self.run_helper(check)

    def test_proot_uses_the_exact_explicit_gio_command(self):
        command = ['/usr/bin/proot', '--rootfs=/private/container', '/usr/local/libexec/tusb-gio-mount']
        def check(manager, settings, root):
            stored = root / 'desktop/host-wrappers/gio-command.json'
            self.assertEqual(command, json.loads(stored.read_text()))
            self.assertEqual(0o600, stored.stat().st_mode & 0o777)
            self.assertEqual('proot', json.loads(manager.action('health', ''))['runMode'])
        self.run_helper(check, mode='proot', gio=command)

    def test_authorization_uses_only_the_explicit_embedded_client(self):
        def check(manager, settings, root):
            with patch('subprocess.run') as run:
                self.assertTrue(manager.classify({'path': '/dev/bus/usb/001/002'}, authorize=True))
                self.assertEqual([str(root / 'prefix/libexec/termux-usb-app'), '-r', '/dev/bus/usb/001/002'],
                                 run.call_args.args[0])
                run.assert_called_once()
        self.run_helper(check)

    def test_broker_launch_uses_embedded_backend_and_rejects_existing_socket(self):
        def check(manager, settings, root):
            process = Mock(); process.poll.return_value = None
            def launch(command, name):
                self.assertEqual([str(root / 'prefix/bin/termux-usb-bridge'), '--socket',
                    str(root / 'broker.sock'), 'serve', '--termux-usb',
                    str(root / 'prefix/libexec/termux-usb-app')], command)
                import socket
                listener = socket.socket(socket.AF_UNIX); listener.bind(str(root / 'broker.sock'))
                listener.close()
                return process
            manager.launch = launch
            manager.start_broker()
            with self.assertRaisesRegex(RuntimeError, 'already exists'): manager.start_broker()
        self.run_helper(check)

    def test_cleanup_failure_is_not_reported_as_success(self):
        def check(manager, settings, root):
            manager.records['one'] = {'fail_cleanup': True}
            with self.assertRaisesRegex(RuntimeError, 'GVfs unmount failed'): manager.cleanup()
            self.assertEqual(1, len(manager.records))
        self.run_helper(check)


if __name__ == '__main__':
    unittest.main()
