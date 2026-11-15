#!/usr/bin/python3
"""Process-scoped PolicyKit registration broker and user password dialogs for a private, session-less PRoot bus.

PolicyKit verifies the cookie through its packaged PAM helper. No authorization
rules, logind sessions or successful authentication responses are fabricated.
The management broker registers subjects as root because Android /proc exposes
physical ownership, while PRoot/D-Bus expose guest credentials. Dialogs run as
termux and registration is restricted to that user's private-bus clients.
"""
import json
import os
import pwd
import signal
import sys

import gi
gi.require_version('Polkit', '1.0')
gi.require_version('PolkitAgent', '1.0')
from gi.repository import Gio, GLib, Polkit, PolkitAgent


AGENT_XML = """<node><interface name="org.freedesktop.PolicyKit1.AuthenticationAgent">
<method name="BeginAuthentication">
<arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/>
<arg type="a{ss}" direction="in"/><arg type="s" direction="in"/>
<arg type="a(sa{sv})" direction="in"/></method>
<method name="CancelAuthentication"><arg type="s" direction="in"/></method>
</interface></node>"""


class Listener:
    """Explicit D-Bus replies avoid unsafe GI virtual async callback ownership."""
    def __init__(self, uid):
        self.uid = uid
        self.requests = {}

    def register(self, flags, subject, path, cancellable):
        self.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        self.authority = Polkit.Authority.get_sync(None)
        self.subject, self.path = subject, path
        interface = Gio.DBusNodeInfo.new_for_xml(AGENT_XML).interfaces[0]
        handle = self.bus.register_object(path, interface, self.method_call, None, None)
        try:
            self.authority.register_authentication_agent_sync(subject,
                os.environ.get('LANG', 'C.UTF-8'), path, None)
        except Exception:
            self.bus.unregister_object(handle)
            raise
        return handle

    def method_call(self, connection, sender, path, interface, method, values, invocation):
        if sender != self.authority.get_owner():
            invocation.return_dbus_error('org.freedesktop.DBus.Error.AccessDenied',
                                         'Only PolicyKit may request authentication')
            return
        if method == 'CancelAuthentication':
            request = self.requests.get(values.unpack()[0])
            if request is not None:
                request[0].send_signal(signal.SIGTERM)
            invocation.return_value(None)
            return
        if method != 'BeginAuthentication':
            invocation.return_dbus_error('org.freedesktop.DBus.Error.UnknownMethod', method)
            return
        action, message, icon, details, cookie, identities = values.unpack()
        if cookie in self.requests:
            invocation.return_dbus_error('org.freedesktop.PolicyKit1.Error.Failed', 'Duplicate request')
            return
        users = [data['uid'] for kind, data in identities if kind == 'unix-user']
        try:
            worker = Gio.Subprocess.new(['/usr/sbin/runuser', '-u', 'termux', '--',
                '/usr/bin/python3', __file__, '--authenticate'],
                Gio.SubprocessFlags.STDIN_PIPE | Gio.SubprocessFlags.STDOUT_PIPE)
            self.requests[cookie] = worker, invocation
            def finished(process, result):
                request = self.requests.pop(cookie, None)
                if request is None:
                    return
                try:
                    process.communicate_utf8_finish(result)
                    if process.get_successful():
                        invocation.return_value(None)
                    else:
                        invocation.return_dbus_error('org.freedesktop.PolicyKit1.Error.Cancelled',
                                                     'Authentication conversation ended')
                except GLib.Error:
                    invocation.return_dbus_error('org.freedesktop.PolicyKit1.Error.Failed',
                                                 'Authentication worker failed')
            worker.communicate_utf8_async(json.dumps([action, message, cookie, users]),
                                           None, finished)
        except GLib.Error:
            invocation.return_dbus_error('org.freedesktop.PolicyKit1.Error.Failed',
                                         'Cannot start authentication worker')

    def stop(self):
        for worker, invocation in list(self.requests.values()):
            worker.send_signal(signal.SIGTERM)
            invocation.return_dbus_error('org.freedesktop.PolicyKit1.Error.Cancelled', 'Agent stopped')
        self.requests.clear()

    def unregister(self, handle):
        try:
            self.authority.unregister_authentication_agent_sync(self.subject, self.path, None)
        except GLib.Error:
            pass
        self.bus.unregister_object(handle)


def authenticate():
    uid = pwd.getpwnam('termux').pw_uid
    if uid == 0 or os.getuid() != uid:
        raise RuntimeError('Password conversation requires the ordinary termux user')
    action, message, cookie, users = json.load(sys.stdin)
    if not users:
        return 1
    identity = Polkit.UnixUser.new(uid if uid in users else users[0])
    session = PolkitAgent.Session.new(identity, cookie)
    loop = GLib.MainLoop()
    dialogs = set()
    completed = []

    def prompt(unused, text, echo):
        try:
            dialog = Gio.Subprocess.new(['/usr/bin/env', 'HOME=/home/termux', 'USER=termux',
                'LOGNAME=termux', 'XDG_CONFIG_HOME=/home/termux/.config',
                'XDG_CACHE_HOME=/home/termux/.cache', 'XDG_DATA_HOME=/home/termux/.local/share',
                '/usr/bin/kdialog', '--title', 'Authentication',
                '--inputbox' if echo else '--password', message + '\n' + text],
                Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_SILENCE)
            dialogs.add(dialog)
            def answered(process, result):
                dialogs.discard(process)
                if completed:
                    return
                try:
                    ok, output, _ = process.communicate_utf8_finish(result)
                    if ok and process.get_successful():
                        session.response(output.removesuffix('\n'))
                    else:
                        session.cancel()
                except GLib.Error:
                    session.cancel()
            dialog.communicate_utf8_async(None, None, answered)
        except GLib.Error:
            session.cancel()

    def finished(unused, gained):
        completed.append(gained)
        loop.quit()

    def cancel():
        session.cancel()
        loop.quit()
        return False
    session.connect('request', prompt)
    session.connect('completed', finished)
    for signum in (signal.SIGTERM, signal.SIGINT):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signum, cancel)
    session.initiate()
    try:
        if not completed:
            loop.run()
    finally:
        if not completed:
            session.cancel()
        for dialog in list(dialogs):
            dialog.send_signal(signal.SIGTERM)
    # Successful method completion is distinct from authorization: PAM and PolicyKit
    # already decided the result. A wrong password must still close the conversation.
    return 0 if completed else 1


class Agent:
    def __init__(self):
        if os.getuid() != 0:
            raise RuntimeError('PolicyKit registration broker requires container root')
        self.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        if self.call('GetConnectionUnixUser', self.bus.get_unique_name())[0] != 0:
            raise RuntimeError('private system bus does not preserve the manager identity')
        self.uid = pwd.getpwnam('termux').pw_uid
        if self.uid == 0:
            raise RuntimeError('termux must be an ordinary user')
        self.names, self.listeners = {}, {}
        self.subscription = self.bus.signal_subscribe('org.freedesktop.DBus',
            'org.freedesktop.DBus', 'NameOwnerChanged', '/org/freedesktop/DBus', None,
            Gio.DBusSignalFlags.NONE, self.changed)
        for name in self.call('ListNames')[0]:
            if name.startswith(':'):
                self.attach(name)

    def call(self, method, name=None):
        return self.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
            'org.freedesktop.DBus', method, None if name is None else GLib.Variant('(s)', (name,)),
            None, Gio.DBusCallFlags.NONE, 2000, None).unpack()

    def attach(self, name):
        if name in self.names or name == self.bus.get_unique_name():
            return
        try:
            if self.call('GetConnectionUnixUser', name)[0] != self.uid:
                return
            pid = self.call('GetConnectionUnixProcessID', name)[0]
            if pid not in self.listeners:
                subject = Polkit.UnixProcess.new_for_owner(pid, 0, self.uid)
                listener = Listener(self.uid)
                handle = listener.register(PolkitAgent.RegisterFlags.NONE, subject,
                    '/com/termux/PolicyKit/Process' + str(pid), None)
                self.listeners[pid] = listener, handle
            self.names[name] = pid
        except GLib.Error as error:
            # Names can vanish between enumeration and credential lookup.
            print('PolicyKit process registration:', error.message, file=sys.stderr, flush=True)

    def changed(self, connection, sender, path, interface, signal_name, values):
        name, old, new = values.unpack()
        if not name.startswith(':'):
            return
        if new:
            self.attach(name)
        elif name in self.names:
            pid = self.names.pop(name)
            if pid not in self.names.values():
                listener, handle = self.listeners.pop(pid)
                listener.stop()
                listener.unregister(handle)

    def stop(self):
        self.bus.signal_unsubscribe(self.subscription)
        for listener, handle in list(self.listeners.values()):
            listener.stop()
            listener.unregister(handle)


def main():
    agent = Agent()
    loop = GLib.MainLoop()
    def stop():
        loop.quit()
        return GLib.SOURCE_REMOVE
    for signum in (signal.SIGTERM, signal.SIGINT):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signum, stop)
    print('PolicyKit process password agent ready', flush=True)
    try:
        loop.run()
    finally:
        agent.stop()


if __name__ == '__main__':
    if sys.argv[1:] == ['--authenticate']:
        sys.exit(authenticate())
    main()
