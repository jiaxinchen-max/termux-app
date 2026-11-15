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


class PasswordConversation(PolkitAgent.Listener):
    def __init__(self, uid):
        super().__init__()
        self.uid = uid
        self.requests = {}

    def do_initiate_authentication(self, action, message, icon, details, cookie,
                                   identities, cancellable, callback, *user_data):
        task = Gio.Task.new(self, cancellable, callback, user_data[0] if user_data else None)
        identity = next((i for i in identities if isinstance(i, Polkit.UnixUser)
                         and i.get_uid() == self.uid), identities[0] if identities else None)
        if identity is None:
            task.return_error(GLib.Error.new_literal(Gio.io_error_quark(),
                'No authentication identity', Gio.IOErrorEnum.FAILED))
            return
        session = PolkitAgent.Session.new(identity, cookie)
        request = {'session': session, 'task': task, 'dialog': None}
        self.requests[cookie] = request

        def prompt(unused, text, echo):
            # Arguments and stdin/stdout only: never log passwords or pass them in argv.
            try:
                dialog = Gio.Subprocess.new(['/usr/bin/env', 'HOME=/home/termux', 'USER=termux', 'LOGNAME=termux',
                    'XDG_CONFIG_HOME=/home/termux/.config', 'XDG_CACHE_HOME=/home/termux/.cache',
                    'XDG_DATA_HOME=/home/termux/.local/share',
                    '/usr/bin/kdialog', '--title', 'Authentication',
                    '--inputbox' if echo else '--password', message + '\n' + text],
                    Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_SILENCE)
            except GLib.Error:
                session.cancel()
                return
            request['dialog'] = dialog
            def answered(process, result):
                if cookie not in self.requests:
                    return
                try:
                    ok, output, _ = process.communicate_utf8_finish(result)
                    if ok and process.get_successful():
                        session.response(output.removesuffix('\n'))
                    else:
                        session.cancel()
                except GLib.Error:
                    session.cancel()
            dialog.communicate_utf8_async(None, cancellable, answered)

        def completed(unused, gained):
            self.requests.pop(cookie, None)
            if request['dialog'] is not None:
                request['dialog'].force_exit()
            # Completion is not authorization: only PolicyKit validates PAM's cookie response.
            task.return_boolean(True)

        session.connect('request', prompt)
        session.connect('completed', completed)
        session.initiate()

    def do_initiate_authentication_finish(self, result):
        return result.propagate_boolean()

    def stop(self):
        for request in list(self.requests.values()):
            request['session'].cancel()


class Listener(PolkitAgent.Listener):
    """Root registers subjects; a fresh user process owns PAM conversations."""
    def __init__(self, uid):
        super().__init__()
        self.uid = uid
        self.requests = set()

    def do_initiate_authentication(self, action, message, icon, details, cookie,
                                   identities, cancellable, callback, *user_data):
        task = Gio.Task.new(self, cancellable, callback, user_data[0] if user_data else None)
        users = [i.get_uid() for i in identities if isinstance(i, Polkit.UnixUser)]
        try:
            worker = Gio.Subprocess.new(['/usr/sbin/runuser', '-u', 'termux', '--',
                '/usr/bin/python3', __file__, '--authenticate'],
                Gio.SubprocessFlags.STDIN_PIPE | Gio.SubprocessFlags.STDOUT_PIPE)
            self.requests.add(worker)
            def finished(process, result):
                self.requests.discard(process)
                try:
                    process.communicate_utf8_finish(result)
                    task.return_boolean(process.get_successful())
                except GLib.Error as error:
                    task.return_error(error)
            worker.communicate_utf8_async(json.dumps([action, message, cookie, users]),
                                           cancellable, finished)
        except GLib.Error as error:
            task.return_error(error)

    def do_initiate_authentication_finish(self, result):
        return result.propagate_boolean()

    def stop(self):
        for worker in list(self.requests):
            worker.send_signal(signal.SIGTERM)


def authenticate():
    uid = pwd.getpwnam('termux').pw_uid
    if uid == 0 or os.getuid() != uid:
        raise RuntimeError('Password conversation requires the ordinary termux user')
    action, message, cookie, users = json.load(sys.stdin)
    conversation = PasswordConversation(uid)
    loop = GLib.MainLoop()
    result = []
    for signum in (signal.SIGTERM, signal.SIGINT):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signum, lambda: (loop.quit(), False)[1])
    def completed(listener, task, *unused):
        result.append(listener.do_initiate_authentication_finish(task))
        loop.quit()
    conversation.do_initiate_authentication(action, message, '', None, cookie,
        [Polkit.UnixUser.new(user) for user in users], None, completed)
    try:
        loop.run()
    finally:
        conversation.stop()
    return 0 if result == [True] else 1


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
                PolkitAgent.Listener.unregister(handle)

    def stop(self):
        self.bus.signal_unsubscribe(self.subscription)
        for listener, handle in list(self.listeners.values()):
            listener.stop()
            PolkitAgent.Listener.unregister(handle)


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
