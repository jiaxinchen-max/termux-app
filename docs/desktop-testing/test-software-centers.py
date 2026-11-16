#!/usr/bin/env python3
"""PRoot store preparation, private service ownership and failure cleanup.

GI doubles test transport/lifecycle only; they never assert a fake PAM grant.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import Mock, patch

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'


def module(name):
    spec = importlib.util.spec_from_file_location(name, ASSETS / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class Preparation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.m = module('desktop_software_center')
        status = self.root / 'var/lib/dpkg/status'
        status.parent.mkdir(parents=True)
        status.write_text('\n\n'.join('Package: '+name+'\nStatus: install ok installed'
            for name in ('gnome-software', 'gnome-software-plugin-deb', 'packagekit',
                         'appstream', 'apt-config-icons', 'polkitd', 'python3-gi',
                         'gir1.2-polkit-1.0', 'zenity')))
        for name in ('usr/bin/gnome-software', 'usr/bin/zenity', 'usr/bin/python3',
                     'usr/bin/dbus-daemon', 'usr/bin/appstreamcli', 'usr/lib/polkit-1/polkitd',
                     'usr/libexec/packagekitd'):
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.touch(); p.chmod(0o755)

    def test_save_publishes_current_guest_endpoint_and_no_activation_directory(self):
        self.m.prepare(self.root, '/run/example')
        endpoint = self.root / 'run/dbus/system_bus_socket'
        self.assertEqual('/run/example/system-bus', os.readlink(endpoint))
        directory = self.root / self.m.GUEST_HOME.lstrip('/')
        conf = (directory / 'systembus.conf').read_text()
        self.assertIn('/run/example/system-bus', conf)
        self.assertNotIn('servicedir', conf)
        self.assertEqual((ASSETS/'desktop_container_polkit_agent.py').read_bytes(),
                         (directory/'desktop_container_polkit_agent.py').read_bytes())
        self.m.prepare(self.root, '/tmp/restored')
        self.assertEqual('/tmp/restored/system-bus', os.readlink(endpoint))
        self.assertIn('Hidden=true', (self.root/'home/termux/.config/autostart/org.gnome.Software.desktop').read_text())

    def test_missing_dependency_fails_in_save_without_publishing_service_setup(self):
        (self.root/'usr/bin/zenity').unlink()
        with self.assertRaisesRegex(RuntimeError, 'zenity'):
            self.m.prepare(self.root, '/run/test')
        self.assertFalse((self.root/self.m.GUEST_HOME.lstrip('/')).exists())

    def test_save_does_not_overwrite_foreign_system_bus(self):
        endpoint = self.root/'run/dbus/system_bus_socket'
        endpoint.parent.mkdir(parents=True)
        endpoint.write_text('foreign')
        with self.assertRaisesRegex(RuntimeError, 'Foreign'):
            self.m.prepare(self.root, '/run/test')
        self.assertEqual('foreign', endpoint.read_text())

    def test_missing_deb_backend_is_rejected_during_save_even_when_gui_exists(self):
        status = self.root / 'var/lib/dpkg/status'
        status.write_text(status.read_text().replace('Package: gnome-software-plugin-deb\nStatus: install ok installed',
            'Package: gnome-software-plugin-deb\nStatus: install ok unpacked'))
        with self.assertRaisesRegex(RuntimeError, 'gnome-software-plugin-deb'):
            self.m.prepare(self.root, '/run/test')
        self.assertFalse((self.root/self.m.GUEST_HOME.lstrip('/')).exists())

    def test_rendezvous_fails_on_dead_service_and_timeout(self):
        with self.assertRaises(RuntimeError):
            self.m.wait_ready(Mock(poll=lambda: 9), self.root)
        with self.assertRaises(RuntimeError):
            self.m.wait_ready(Mock(poll=lambda: None), self.root, timeout=0)

    def test_hyprland_guest_client_uses_guest_bus_and_ordinary_user(self):
        m = module('desktop_hyprland')
        command = m.container_command(self.root, 'ubuntu-test', 'default', ['gnome-software'], 'token')
        self.assertEqual('termux', command[command.index('--user')+1])
        self.assertIn('DBUS_SYSTEM_BUS_ADDRESS=unix:path=/tmp/hyprland-runtime/system-bus', command)
        self.assertIn('GIO_USE_NETWORK_MONITOR=base', command)


    def test_xfce_saved_configuration_survives_session_tmp_cleanup(self):
        from types import SimpleNamespace
        import shutil
        m = module('desktop_xfce_session')
        prefix = self.root / 'prefix'
        prefix.mkdir()
        with patch.dict(os.environ, HOME=str(self.root/'home')):
            m.prepare(SimpleNamespace(desktop_id='test', run_mode='native', alias=None), prefix)
        shutil.rmtree(prefix/'tmp')
        work = self.root/'home/.termux/xfce-test'
        self.assertTrue((work/'session.sh').is_file())
        self.assertTrue((work/'sessionbus.conf').is_file())
        self.assertIn(str(prefix/'tmp'), (work/'sessionbus.conf').read_text())


class LauncherIntegration(unittest.TestCase):
    def test_gnome_starts_guest_system_services_as_root_without_changing_user_session_command(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder); runtime = work/'runtime'; runtime.mkdir()
            args = SimpleNamespace(work=work, rootfs=work/'rootfs', session_profile='ubuntu',
                                   ready_file=work/'ready', usb_storage='off')
            m = module('desktop_gnome_launch')
            base = ['proot', '--kill-on-exit', '-i', '1000:1000', '-r', str(args.rootfs),
                    '/usr/bin/env', '-i', 'HOME=/home/termux']
            config = dict(schema=1, request=m.session_request(args), runtime=str(runtime),
                          usb_parent=str(work/'usb'), gw='/work', gr='/run/guest',
                          base=base, session_sh='/bin/sh', extensions=[], host={}, guest={}, guest_unset=[])
            (work/'prepared-session.json').write_text(json.dumps(config))
            (runtime/'software-center.ready').write_text('stale')
            observed = []
            def first_service(command, **kwargs):
                observed.append(command)
                self.assertFalse((runtime/'software-center.ready').exists())
                raise RuntimeError('stop at first real service')
            with patch.dict(os.environ, PREFIX=folder), patch.object(m.signal, 'signal'), \
                 patch.object(m.subprocess, 'Popen', side_effect=first_service):
                with self.assertRaisesRegex(RuntimeError, 'first real service'): m.run_prepared(args)
            self.assertEqual('0:0', observed[0][observed[0].index('-i')+1])
            self.assertEqual('1000:1000', config['base'][config['base'].index('-i')+1])
            self.assertTrue(observed[0][-1].endswith('/desktop_container_software_services.py'))

    def test_xfce_usb_off_owns_both_guest_service_and_ordinary_session_processes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); prefix = root/'prefix'; binary = prefix/'bin'; binary.mkdir(parents=True)
            trace = root/'calls.jsonl'
            fake = binary/'proot-distro'
            fake.write_text('#!'+sys.executable+r"""
import json,os,sys,time
from pathlib import Path
with open(os.environ['TRACE'],'a') as f:f.write(json.dumps([os.getpid(),*sys.argv[1:]])+'\n')
runtime=Path(os.environ['XDG_RUNTIME_DIR'])
if sys.argv[sys.argv.index('--user')+1]=='root':
 (runtime/'software-center.ready').write_text(str(os.getpid()))
else:
 (runtime/'session-address').write_text('unix:path='+str(runtime/'session-bus'))
while True:time.sleep(.05)
""")
            fake.chmod(0o755)
            env = dict(os.environ, HOME=folder, PREFIX=str(prefix), PATH=str(binary)+':'+os.environ['PATH'],
                       TRACE=str(trace), PULSE_SERVER='tcp:127.0.0.1:4713')
            m = module('desktop_xfce_session')
            from types import SimpleNamespace
            with patch.dict(os.environ, env):
                # The independent service dependencies/config are already prepared;
                # this transport test only needs the persistent user-session files.
                m.prepare(SimpleNamespace(desktop_id='test',run_mode='native',alias=None),prefix)
            process = subprocess.Popen([sys.executable,str(ASSETS/'desktop_xfce_session.py'),
                '--desktop-id','test','--run-mode','proot','--alias','ubuntu-test',
                '--usb-storage','off','--display',':0'],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic()+5
                while not trace.exists() or len(trace.read_text().splitlines())<2:
                    if process.poll() is not None or time.monotonic()>deadline:
                        self.fail('XFCE did not start both guest roles: '+process.communicate(timeout=2)[0].decode())
                    time.sleep(.02)
                calls=[json.loads(line) for line in trace.read_text().splitlines()]
                self.assertEqual(['root','termux'],[c[c.index('--user')+1] for c in calls])
                self.assertTrue(any(c[-1].endswith('desktop_container_software_services.py') for c in calls))
            finally:
                process.terminate(); output=process.communicate(timeout=8)[0].decode()
            self.assertEqual(130,process.returncode,output)
            for call in calls:
                with self.assertRaises(ProcessLookupError):os.kill(call[0],0)
            self.assertTrue((root/'.termux/xfce-test/session.sh').exists())


class Error(Exception):
    pass


class Services(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.runtime = Path(self.temp.name)
        gi = types.ModuleType('gi'); gi.require_version = lambda *args: None
        repo = types.ModuleType('gi.repository')
        self.bus = Mock()
        repo.Gio = types.SimpleNamespace(DBusConnection=Mock(),
            DBusConnectionFlags=types.SimpleNamespace(AUTHENTICATION_CLIENT=1, MESSAGE_BUS_CONNECTION=2),
            DBusCallFlags=types.SimpleNamespace(NO_AUTO_START=1))
        repo.Gio.DBusConnection.new_for_address_sync.return_value = self.bus
        repo.GLib = types.SimpleNamespace(Error=Error, Variant=lambda *args: args)
        with patch.dict(sys.modules, {'gi': gi, 'gi.repository': repo}):
            self.m = module('desktop_container_software_services')
        self.children = []
        self.commands = []
        self.sockets = []
        self.addCleanup(lambda: [s.close() for s in self.sockets])
        self.signal_patch = patch.object(self.m.signal, 'signal')
        self.signal_patch.start(); self.addCleanup(self.signal_patch.stop)

    def popen(self, command, env):
        process = Mock()
        process.poll.return_value = None
        self.commands.append((command, env)); self.children.append(process)
        if 'dbus-daemon' in command[0]:
            connection = socket.socket(socket.AF_UNIX)
            connection.bind(str(self.runtime/'system-bus'))
            self.sockets.append(connection)
        if command[-1].endswith('desktop_container_polkit_agent.py'):
            (self.runtime/'polkit-agent.ready').write_text('agent\n')
        return process

    def run_case(self, sleep):
        with patch.object(self.m.os, 'getuid', return_value=0), \
             patch.object(self.m.os, 'access', return_value=True), \
             patch.object(self.m.subprocess, 'Popen', side_effect=self.popen), \
             patch.object(self.m, 'wait_service'), patch.object(self.m.time, 'sleep', side_effect=sleep):
            return self.m.run(self.runtime)

    def test_guest_root_is_required_before_any_daemon_is_started(self):
        with patch.object(self.m.os, 'getuid', return_value=1000), patch.object(self.m.subprocess, 'Popen') as start:
            with self.assertRaises(RuntimeError): self.m.run(self.runtime)
            start.assert_not_called()

    def test_normal_stop_reaps_owned_children_and_removes_rendezvous(self):
        def stop(delay):
            self.assertTrue((self.runtime/'software-center.ready').is_file())
            raise KeyboardInterrupt
        self.assertEqual(0, self.run_case(stop))
        self.assertEqual(4, len(self.children))
        for process in self.children:
            process.terminate.assert_called_once(); process.wait.assert_called_once()
        self.assertFalse((self.runtime/'software-center.ready').exists())
        self.assertFalse((self.runtime/'system-bus').exists())
        for command, env in self.commands:
            self.assertEqual('unix:path='+str(self.runtime/'system-bus'), env['DBUS_SYSTEM_BUS_ADDRESS'])
            self.assertNotIn('DBUS_SESSION_BUS_ADDRESS', env)
            self.assertEqual('zenity', env['DESKTOP_AUTH_DIALOG'])

    def test_required_daemon_death_aborts_without_restart_and_reaps_all(self):
        def fail(delay): self.children[1].poll.return_value = 5
        with self.assertRaisesRegex(RuntimeError, 'Required'):
            self.run_case(fail)
        self.assertEqual(4, len(self.commands))
        self.assertFalse((self.runtime/'software-center.ready').exists())
        for process in self.children: process.wait.assert_called_once()

    def test_auth_agent_death_is_reported_without_restarting_or_killing_desktop(self):
        turns = []
        def fail_agent(delay):
            turns.append(delay)
            self.assertTrue((self.runtime/'software-center.ready').exists())
            if len(turns) == 1: self.children[-1].poll.return_value = 139
            else: raise KeyboardInterrupt
        output = io.StringIO()
        with contextlib.redirect_stdout(output): self.run_case(fail_agent)
        self.assertIn('authentication unavailable', output.getvalue())
        self.assertEqual(4, len(self.commands))
        self.assertEqual(2, len(turns))

    def test_partial_start_failure_cleans_only_already_started_daemons(self):
        def failed_start(command, env):
            if self.children: raise OSError('missing polkitd')
            return self.popen(command, env)
        with patch.object(self.m.os, 'getuid', return_value=0), \
             patch.object(self.m.subprocess, 'Popen', side_effect=failed_start):
            with self.assertRaises(OSError): self.m.run(self.runtime)
        self.assertEqual(1, len(self.children))
        self.children[0].terminate.assert_called_once()
        self.assertFalse((self.runtime/'system-bus').exists())

    def test_unresponsive_owned_process_is_killed_and_reaped(self):
        process = Mock(poll=lambda: None)
        process.wait.side_effect = [subprocess.TimeoutExpired('owned', 0), 0]
        self.m.stop_children([process])
        process.terminate.assert_called_once(); process.kill.assert_called_once()
        self.assertEqual(2, process.wait.call_count)

    def test_name_owner_readiness_never_activates_another_service(self):
        self.bus.call_sync.return_value.unpack.return_value = (':1.12',)
        self.m.wait_service(self.bus, Mock(poll=lambda: None), 'service', '/object')
        calls = self.bus.call_sync.call_args_list
        self.assertEqual('org.freedesktop.DBus', calls[0].args[0])
        self.assertEqual(':1.12', calls[1].args[0])
        self.assertTrue(all(c.args[6] == 1 for c in calls))


if __name__ == '__main__': unittest.main()
