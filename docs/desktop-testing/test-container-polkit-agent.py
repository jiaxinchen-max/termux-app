#!/usr/bin/env python3
"""Agent routing/lifetime and password-dialog failures, with GI transport doubles.
Real PAM authorization is deliberately not simulated as a successful grant.
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

SOURCE=Path(__file__).resolve().parents[2]/'termux-ui/src/main/assets/local-games/desktop_container_polkit_agent.py'
class Error(Exception):
    @property
    def message(self): return str(self)
    @classmethod
    def new_literal(cls, domain, message, code): return cls(message)

class AgentTests(unittest.TestCase):
    def setUp(self):
        self.gio=types.SimpleNamespace(Task=Mock(),Subprocess=Mock(),
            SubprocessFlags=types.SimpleNamespace(STDOUT_PIPE=1,STDERR_SILENCE=2,STDIN_PIPE=4),
            IOErrorEnum=types.SimpleNamespace(FAILED=1),io_error_quark=lambda:1)
        self.pka=types.SimpleNamespace(Listener=type('Base',(),{'unregister':Mock()}),
            RegisterFlags=types.SimpleNamespace(NONE=0),Session=Mock())
        self.polkit=types.SimpleNamespace(UnixProcess=Mock(),UnixUser=type('Identity',(),{}))
        gi=types.ModuleType('gi');gi.require_version=lambda *args:None
        repo=types.ModuleType('gi.repository');repo.Gio=self.gio;repo.GLib=types.SimpleNamespace(Error=Error)
        repo.Polkit=self.polkit;repo.PolkitAgent=self.pka
        with patch.dict(sys.modules,{'gi':gi,'gi.repository':repo}):
            spec=importlib.util.spec_from_file_location('agent_under_test',SOURCE)
            self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)

    def test_registration_broker_requires_root_and_matching_private_bus_credentials(self):
        with patch.object(self.m.os,'getuid',return_value=1000):
            with self.assertRaises(RuntimeError):self.m.Agent()
        self.gio.BusType=types.SimpleNamespace(SYSTEM=0)
        self.gio.bus_get_sync=Mock(return_value=Mock())
        with patch.object(self.m.os,'getuid',return_value=0), patch.object(self.m.Agent,'call',return_value=(1000,)):
            with self.assertRaises(RuntimeError):self.m.Agent()

    def router(self, users, pids):
        agent=self.m.Agent.__new__(self.m.Agent);agent.uid=1000;agent.names={};agent.listeners={}
        agent.bus=Mock();agent.bus.get_unique_name.return_value=':self'
        def call(method,name):
            if name not in users: raise Error('name vanished')
            return (users[name] if method=='GetConnectionUnixUser' else pids[name],)
        agent.call=call
        return agent

    def test_registers_only_same_uid_clients_and_never_its_own_connection(self):
        agent=self.router({':root':0,':other':2000,':user':1000},{':user':123})
        with patch.object(self.m,'Listener') as listener:
            for name in [':self',':root',':other',':user']:agent.attach(name)
            listener.return_value.register.assert_called_once()
            self.polkit.UnixProcess.new_for_owner.assert_called_once_with(123,0,1000)
            self.assertEqual({':user':123},agent.names)

    def test_multiple_connections_share_agent_until_last_disconnect(self):
        agent=self.router({':a':1000,':b':1000},{':a':123,':b':123})
        with patch.object(self.m,'Listener') as listener:
            agent.attach(':a');agent.attach(':b');agent.attach(':a')
            listener.return_value.register.assert_called_once()
            for name in [':a',':b']:
                agent.changed(None,None,None,None,None,types.SimpleNamespace(unpack=lambda:(name,name,'')))
                if name==':a':listener.return_value.stop.assert_not_called()
            listener.return_value.stop.assert_called_once()
            self.assertFalse(agent.listeners)

    def test_vanished_connection_does_not_leave_a_registration(self):
        agent=self.router({},{} )
        with contextlib.redirect_stderr(io.StringIO()):agent.attach(':gone')
        self.assertFalse(agent.names);self.assertFalse(agent.listeners)

    def request(self, cookie='private-cookie', users=None):
        listener=self.m.Listener(1000);listener.authority=Mock()
        listener.authority.get_owner.return_value=':authority'
        values=types.SimpleNamespace(unpack=lambda:('install','Permission','',{},cookie,
            [('unix-user',{'uid':1000})] if users is None else users))
        invocation=Mock();worker=Mock();self.gio.Subprocess.new.return_value=worker
        listener.method_call(None,':authority','/agent','interface','BeginAuthentication',values,invocation)
        return listener,worker,invocation,values

    def test_root_routes_cookie_on_stdin_to_normal_user_worker(self):
        listener,worker,invocation,values=self.request()
        argv=self.gio.Subprocess.new.call_args.args[0]
        self.assertEqual(['/usr/sbin/runuser','-u','termux','--'],argv[:4])
        self.assertNotIn('private-cookie',argv)
        self.assertEqual(['install','Permission','private-cookie',[1000]],
            json.loads(worker.communicate_utf8_async.call_args.args[0]))
        invocation.return_value.assert_not_called()
        listener.stop();worker.send_signal.assert_called_once()
        invocation.return_dbus_error.assert_called_once()

    def test_worker_completion_replies_exactly_once_without_gi_virtual_task(self):
        listener,worker,invocation,values=self.request()
        callback=worker.communicate_utf8_async.call_args.args[2]
        worker.get_successful.return_value=True
        callback(worker,Mock());callback(worker,Mock())
        invocation.return_value.assert_called_once_with(None)
        self.assertFalse(listener.requests)
        self.gio.Task.new.assert_not_called()

    def test_worker_failure_returns_error_without_stopping_broker(self):
        listener,worker,invocation,values=self.request()
        callback=worker.communicate_utf8_async.call_args.args[2]
        worker.get_successful.return_value=False;callback(worker,Mock())
        invocation.return_dbus_error.assert_called_once();self.assertFalse(listener.requests)
        invocation.return_value.assert_not_called()

    def test_cancel_targets_only_requested_cookie(self):
        listener,worker,invocation,values=self.request();cancel_reply=Mock()
        listener.method_call(None,':authority','/agent','interface','CancelAuthentication',
            types.SimpleNamespace(unpack=lambda:('private-cookie',)),cancel_reply)
        worker.send_signal.assert_called_once();cancel_reply.return_value.assert_called_once_with(None)

    def test_untrusted_sender_cannot_prompt_or_cancel(self):
        listener=self.m.Listener(1000);listener.authority=Mock()
        listener.authority.get_owner.return_value=':authority';invocation=Mock()
        listener.method_call(None,':imposter','/agent','interface','BeginAuthentication',None,invocation)
        invocation.return_dbus_error.assert_called_once();self.gio.Subprocess.new.assert_not_called()

    def test_stop_and_late_completion_do_not_reply_twice(self):
        listener,worker,invocation,values=self.request()
        callback=worker.communicate_utf8_async.call_args.args[2]
        listener.stop();callback(worker,Mock());listener.stop()
        invocation.return_dbus_error.assert_called_once();invocation.return_value.assert_not_called()

    def test_gtk_password_prompt_is_masked_and_never_uses_message_as_options(self):
        with patch.dict(self.m.os.environ, DESKTOP_AUTH_DIALOG='zenity'):
            command = self.m.dialog_command('--example action', 'Password:', False)
            self.assertEqual('/usr/bin/zenity', command[0])
            self.assertIn('--hide-text', command)
            self.assertIn('--text=--example action\nPassword:', command)
            self.assertNotIn('--hide-text', self.m.dialog_command('action', 'Username:', True))

    def test_plasma_keeps_packaged_kdialog_and_unknown_frontend_fails(self):
        with patch.dict(self.m.os.environ, DESKTOP_AUTH_DIALOG='kdialog'):
            self.assertIn('--password', self.m.dialog_command('action', 'Password:', False))
        with patch.dict(self.m.os.environ, DESKTOP_AUTH_DIALOG='unknown'):
            with self.assertRaises(RuntimeError): self.m.dialog_command('action', 'Password:', False)

if __name__=='__main__':unittest.main()
