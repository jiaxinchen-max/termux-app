#!/usr/bin/python3
"""Own only this guest session's system bus, PolicyKit, PackageKit and auth broker."""
import os
from pathlib import Path
import signal
import subprocess
import time

import gi
gi.require_version('Gio', '2.0')
from gi.repository import Gio, GLib


def wait_service(bus, process, name, path, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(name + ' exited during startup')
        try:
            owner = bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
                'org.freedesktop.DBus', 'GetNameOwner', GLib.Variant('(s)', (name,)),
                None, Gio.DBusCallFlags.NO_AUTO_START, 500, None).unpack()[0]
            bus.call_sync(owner, path, 'org.freedesktop.DBus.Peer', 'Ping', None,
                          None, Gio.DBusCallFlags.NO_AUTO_START, 500, None)
            return
        except GLib.Error:
            time.sleep(.05)
    raise RuntimeError(name + ' readiness timed out')


def stop_children(children):
    for process in reversed(children):
        if process.poll() is None:
            process.terminate()
    deadline = time.monotonic() + 3
    for process in reversed(children):
        try:
            process.wait(timeout=max(0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def run(runtime):
    if os.getuid() != 0:
        raise RuntimeError('Package services require guest root')
    ready = runtime / 'software-center.ready'
    agent_ready = runtime / 'polkit-agent.ready'
    endpoint = runtime / 'system-bus'
    for marker in (ready, agent_ready, endpoint):
        marker.unlink(missing_ok=True)
    env = dict(os.environ, DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + str(endpoint),
               DESKTOP_AUTH_DIALOG='zenity', DESKTOP_POLKIT_READY_FILE=str(agent_ready),
               HOME='/root', USER='root', LOGNAME='root', GIO_USE_NETWORK_MONITOR='base')
    env.pop('DBUS_SESSION_BUS_ADDRESS', None)
    env.pop('DBUS_SESSION_BUS_PID', None)
    children = []
    def start(command):
        process = subprocess.Popen(command, env=env)
        children.append(process)
        return process
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    bus = None
    try:
        daemon = start(['/usr/bin/dbus-daemon', '--nofork', '--nopidfile',
                        '--config-file=' + str(Path(__file__).with_name('systembus.conf'))])
        deadline = time.monotonic() + 5
        while not endpoint.is_socket():
            if daemon.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('Guest system bus failed to start')
            time.sleep(.05)
        bus = Gio.DBusConnection.new_for_address_sync(env['DBUS_SYSTEM_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
            None, None)
        authority = start(['/usr/lib/polkit-1/polkitd', '--log-level=debug'])
        wait_service(bus, authority, 'org.freedesktop.PolicyKit1', '/org/freedesktop/PolicyKit1/Authority')
        packagekit = next(path for path in ('/usr/libexec/packagekitd', '/usr/lib/packagekit/packagekitd')
                          if os.access(path, os.X_OK))
        # APT's prepared dpkg wrapper supplies the statx adapter to package
        # configuration; don't preload it into GUI/PolicyKit processes.
        packages = start(['/usr/bin/env', 'DEBIAN_FRONTEND=noninteractive',
                          packagekit, '--keep-environment', '--disable-timer'])
        wait_service(bus, packages, 'org.freedesktop.PackageKit', '/org/freedesktop/PackageKit')
        agent = start(['/usr/bin/python3', '-I', '-B',
                       str(Path(__file__).with_name('desktop_container_polkit_agent.py'))])
        deadline = time.monotonic() + 10
        while not agent_ready.is_file():
            if agent.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('Guest password agent failed to start')
            time.sleep(.05)
        ready.write_text(str(os.getpid()) + '\n')
        print('Guest DEB application services ready', flush=True)
        auth_failed = False
        while True:
            if any(p.poll() is not None for p in (daemon, authority, packages)):
                raise RuntimeError('Required guest package service exited')
            if agent.poll() is not None and not auth_failed:
                auth_failed = True
                print('ERROR: password agent exited; software management authentication unavailable', flush=True)
            time.sleep(.2)
    except KeyboardInterrupt:
        return 0
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        ready.unlink(missing_ok=True)
        stop_children(children)
        if bus is not None:
            try:
                bus.close_sync(None)
            except GLib.Error:
                pass  # The owned daemon has already closed this connection.
        agent_ready.unlink(missing_ok=True)
        endpoint.unlink(missing_ok=True)


if __name__ == '__main__':
    raise SystemExit(run(Path(os.environ['XDG_RUNTIME_DIR'])))
