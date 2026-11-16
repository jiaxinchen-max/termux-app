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

    def run_helper(self, test, mode='native', gio=None, storage='read-only', real=False):
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
                def mount(self, record):
                    record['mount_called'] = True
                def unmount(self, record, disconnected):
                    if disconnected and not record.get('allow_disconnect'): raise AssertionError('shutdown must not pretend physical unplug')
                    if record.get('fail_cleanup'): raise OSError('GVfs unmount failed')
                    record.pop('files', None)
                    if record.get('fail_flush'): raise OSError('filesystem did not close cleanly')
            if real:
                bridge = HELPER.parents[5] / 'tools/termux-usb-bridge'
                with patch.object(sys, 'path', [str(bridge), *sys.path]):
                    spec = importlib.util.spec_from_file_location('usb_real_desktop', bridge / 'desktop.py')
                    desktop = importlib.util.module_from_spec(spec); spec.loader.exec_module(desktop)
            else:
                desktop.Manager = Manager
            desktop.private_directory = lambda path: path.mkdir(mode=0o700, parents=True, exist_ok=True)
            desktop.serve = lambda settings: test(desktop.Manager(settings), settings, root)
            client = root / 'prefix/libexec/termux-usb-app'
            client.parent.mkdir(parents=True); client.write_text('#!/bin/sh\nexit 0\n'); client.chmod(0o700)
            argv = [str(HELPER), '--mode', storage, '--run-mode', mode,
                '--runtime', str(root / 'desktop'), '--broker-socket', str(root / 'broker.sock'),
                '--termux-usb', str(client)]
            if gio is not None: argv += ['--gio-command-json', json.dumps(gio), '--guest-runtime', '/tmp/guest-usb/desktop']
            with patch.dict(sys.modules, {'desktop': desktop}), patch.object(sys, 'argv', argv), patch.dict(os.environ,
                PREFIX=str(root / 'prefix'), DBUS_SESSION_BUS_ADDRESS='unix:path=' + str(root / 'bus'),
                XDG_RUNTIME_DIR=str(root), PATH=os.environ.get('PATH', '')):
                self.load().main()

    def test_only_storage_read_only_and_real_permission_are_selected(self):
        def check(manager, settings, root):
            self.assertTrue(settings.no_camera); self.assertTrue(settings.no_gamepad)
            self.assertFalse(settings.read_write); self.assertIsNone(settings.test_manifest)
            self.assertFalse(settings.request_permission)
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
            self.assertEqual(command, json.loads(stored.read_text())["command"])
            self.assertEqual(0o600, stored.stat().st_mode & 0o777)
            self.assertEqual('proot', json.loads(manager.action('health', ''))['runMode'])
        self.run_helper(check, mode='proot', gio=command)

    def test_guest_mount_receives_guest_token_path_and_unmount_has_no_translation(self):
        import subprocess
        command = [sys.executable, '-c', 'import json,sys; print(json.dumps(sys.argv[1:]))']
        def check(manager, settings, root):
            wrapper = root / 'desktop/host-wrappers/tusb-gio-mount'
            token = root / 'desktop/0123abcdef.token'
            token.write_text('private-password')
            result = subprocess.run([str(wrapper), 'mount', 'dav://tusb@127.0.0.1:1234/', str(token)],
                capture_output=True, text=True, check=True)
            self.assertEqual(['mount', 'dav://tusb@127.0.0.1:1234/', '/tmp/guest-usb/desktop/0123abcdef.token'],
                json.loads(result.stdout))
            self.assertNotIn('private-password', result.stdout)
            for path in (root / 'outside.token', root / 'desktop/../escape.token'):
                result = subprocess.run([str(wrapper), 'mount', 'dav://host/', str(path)], capture_output=True)
                self.assertNotEqual(0, result.returncode)
            result = subprocess.run([str(wrapper), 'unmount', 'dav://host/'], capture_output=True, text=True, check=True)
            self.assertEqual(['unmount', 'dav://host/'], json.loads(result.stdout))
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

    def test_write_mode_manual_mount_checks_android_and_denial_never_opens_files(self):
        def check(manager, settings, root):
            self.assertTrue(settings.read_write); self.assertTrue(settings.no_automount)
            self.assertFalse(settings.request_permission)
            self.assertEqual('read-write', json.loads(manager.action('health', ''))['storageMode'])
            record = dict(id='one', path='/dev/bus/usb/001/002', status='available')
            with patch('subprocess.run', return_value=Mock(returncode=1, stderr='Android volume mounted')) as run:
                with self.assertRaisesRegex(PermissionError, 'Android volume mounted'): manager.mount(record)
                self.assertNotIn('mount_called', record)
                self.assertEqual('--check-write', run.call_args.args[0][1])
            with patch('subprocess.run', return_value=Mock(returncode=0)):
                manager.mount(record)
                self.assertTrue(record['mount_called'])
        self.run_helper(check, mode='proot', gio=['/guest/gio'], storage='read-write')

    def test_real_manager_poll_and_authorization_never_automount_write_volumes(self):
        def check(manager, settings, root):
            path = '/dev/bus/usb/001/002'
            manager.sources = lambda: {path: {}}
            manager.launch = Mock(side_effect=AssertionError('must not start raw filesystem'))
            def run(command, **kwargs):
                if command[0] == 'tusb-inspect':
                    return Mock(returncode=0, stdout='{"storage":true}', stderr='')
                if '-r' in command: return Mock(returncode=0)
                return Mock(returncode=1, stderr='Android removable volume is mounted')
            with patch('subprocess.run', side_effect=run):
                manager.poll()
                self.assertEqual('available', next(iter(manager.records.values()))['status'])
                manager.action('authorize', path)
                manager.launch.assert_not_called()
                with self.assertRaisesRegex(PermissionError, 'volume is mounted'):
                    manager.action('mount', path)
                manager.launch.assert_not_called()
            record = next(iter(manager.records.values()))
            record['status'] = 'permission-required'; record['capabilities'] = None
            with patch('subprocess.run', return_value=Mock(returncode=1, stderr='permission denied')) as run:
                with self.assertRaisesRegex(PermissionError, 'foreground App'):
                    manager.action('mount', path)
                self.assertEqual('permission-required', record['status'])
                self.assertEqual('tusb-inspect', run.call_args.args[0][0])
                run.assert_called_once()
            manager.launch.assert_not_called()
        self.run_helper(check, mode='proot', gio=['/guest/gio'], storage='read-write', real=True)

    def test_failed_flush_stays_failed_and_busy_gvfs_can_retry(self):
        def check(manager, settings, root):
            record = dict(id='one', path='/dev/bus/usb/001/002', status='mounted', files=Mock(), fail_cleanup=True)
            with self.assertRaisesRegex(OSError, 'GVfs unmount failed'): manager.unmount(record)
            self.assertEqual('mounted', record['status'])
            self.assertNotIn('unsafe_disconnect', record)
            del record['fail_cleanup']; record['fail_flush'] = True
            with self.assertRaisesRegex(OSError, 'did not close cleanly'): manager.unmount(record)
            self.assertEqual('error', record['status']); self.assertTrue(record['unsafe_disconnect'])
            with self.assertRaisesRegex(RuntimeError, 'reinsert'): manager.unmount(record)
            with self.assertRaisesRegex(RuntimeError, 'reinsert'): manager.mount(record)
            manager.records['one'] = record
            with self.assertRaisesRegex(RuntimeError, 'safe removal'): manager.action('eject', 'one')
            self.assertIn('did not close cleanly', json.loads(manager.action('health', ''))['storageWarning'])
        self.run_helper(check, mode='proot', gio=['/guest/gio'], storage='read-write')

    def test_real_manager_busy_mount_and_nonzero_files_exit_are_distinct(self):
        def check(manager, settings, root):
            child = Mock(); child.poll.return_value = 7; child.returncode = 7
            record = dict(id='one', path='/dev/bus/usb/001/002', status='mounted', files=child,
                uri='dav://tusb@127.0.0.1:1234/')
            with patch('subprocess.run', return_value=Mock(returncode=1, stderr='busy')):
                with self.assertRaisesRegex(OSError, 'busy'): manager.unmount(record)
            self.assertIs(child, record['files']); self.assertEqual('mounted', record['status'])
            with patch('subprocess.run', return_value=Mock(returncode=0)):
                with self.assertRaisesRegex(OSError, 'did not close cleanly'): manager.unmount(record)
            self.assertNotIn('files', record); self.assertEqual('error', record['status'])
            manager.records['one'] = record
            with self.assertRaisesRegex(RuntimeError, 'safe removal'): manager.action('eject', 'one')
        self.run_helper(check, mode='proot', gio=['/guest/gio'], storage='read-write', real=True)

    def test_write_hot_unplug_reports_unconfirmed_flush(self):
        def check(manager, settings, root):
            record = dict(id='one', path='/dev/bus/usb/001/002', status='mounted', files=Mock(), allow_disconnect=True)
            manager.unmount(record, disconnected=True)
            self.assertIn('safe flush is not confirmed', json.loads(manager.action('health', ''))['storageWarning'])
        self.run_helper(check, mode='proot', gio=['/guest/gio'], storage='read-write')


if __name__ == '__main__':
    unittest.main()
