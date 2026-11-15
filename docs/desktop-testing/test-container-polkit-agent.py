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

    def request(self):
        listener=self.m.PasswordConversation(self.m.os.getuid());identity=self.polkit.UnixUser();identity.get_uid=lambda:self.m.os.getuid()
        task=Mock();self.gio.Task.new.return_value=task
        session=Mock();self.pka.Session.new.return_value=session
        callbacks={};session.connect.side_effect=lambda name,fn:callbacks.update({name:fn})
        listener.do_initiate_authentication('action','Permission required','',None,'cookie',[identity],None,Mock())
        session.cancel.side_effect=lambda:callbacks['completed'](session,False)
        return listener,session,callbacks,task

    def test_password_is_only_a_helper_response_and_failure_is_not_a_grant(self):
        listener,session,callbacks,task=self.request();process=Mock()
        self.gio.Subprocess.new.return_value=process
        callbacks['request'](session,'Password:',False)
        process.communicate_utf8_finish.return_value=(True,'private-password\n','')
        process.get_successful.return_value=True
        callback=process.communicate_utf8_async.call_args.args[2];callback(process,Mock())
        session.response.assert_called_once_with('private-password')
        self.assertNotIn('private-password',str(self.gio.Subprocess.new.call_args))
        self.assertEqual('/usr/bin/env',self.gio.Subprocess.new.call_args.args[0][0])
        task.return_boolean.assert_not_called()
        callbacks['completed'](session,False)
        self.assertFalse(listener.requests)
        self.assertFalse(hasattr(listener,'AuthenticationAgentResponse'))

    def test_root_registration_routes_cookie_on_stdin_to_normal_user_worker(self):
        listener=self.m.Listener(1000);identity=self.polkit.UnixUser();identity.get_uid=lambda:1000
        worker=Mock();self.gio.Subprocess.new.return_value=worker
        listener.do_initiate_authentication('install','Permission','',None,'private-cookie',[identity],None,Mock())
        argv=self.gio.Subprocess.new.call_args.args[0]
        self.assertEqual(['/usr/sbin/runuser','-u','termux','--'],argv[:4])
        self.assertNotIn('private-cookie',argv)
        payload=worker.communicate_utf8_async.call_args.args[0]
        self.assertEqual(['install','Permission','private-cookie',[1000]],json.loads(payload))
        listener.stop();worker.send_signal.assert_called_once()

    def test_dialog_failure_cancels_and_releases_request(self):
        listener,session,callbacks,task=self.request()
        self.gio.Subprocess.new.side_effect=Error('missing display')
        callbacks['request'](session,'Password:',False)
        session.cancel.assert_called_once();self.assertFalse(listener.requests)

    def test_shutdown_cancels_pending_authentication(self):
        listener,session,callbacks,task=self.request();listener.stop()
        session.cancel.assert_called_once();self.assertFalse(listener.requests)

if __name__=='__main__':unittest.main()
