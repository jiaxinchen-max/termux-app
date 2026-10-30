#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Supervise an XFCE session and its optional storage service on one private D-Bus."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
from xml.sax.saxutils import escape


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--desktop-id', required=True)
    parser.add_argument('--run-mode', choices=('native', 'proot'), required=True)
    parser.add_argument('--usb-storage', choices=('off', 'read-only'), required=True)
    parser.add_argument('--display', choices=(':0',), required=True)
    parser.add_argument('--alias')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,128}', args.desktop_id):
        parser.error('invalid desktop ID')
    if args.run_mode == 'proot':
        if not args.alias or not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', args.alias):
            parser.error('PRoot requires an explicit container alias')
    elif args.alias:
        parser.error('Native mode does not accept a container alias')
    prefix = Path(os.environ['PREFIX'])
    key = hashlib.sha256(args.desktop_id.encode()).hexdigest()[:16]
    parent = prefix / ('tmp/tusb-' + key)
    runtime = parent / 'xfce-session'
    usb_runtime = parent / 'desktop'
    runtime.mkdir(parents=True, exist_ok=True)
    parent.chmod(0o700)
    runtime.chmod(0o700)
    import fcntl
    lock = (runtime / 'session.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    marker = runtime / 'session-address'
    marker.unlink(missing_ok=True)
    bus_socket = runtime / 'session-bus'
    bus_socket.unlink(missing_ok=True)
    config = runtime / 'sessionbus.conf'
    config.write_text('<busconfig><type>session</type><listen>unix:path=' + escape(str(bus_socket)) +
        '</listen><auth>EXTERNAL</auth><standard_session_servicedirs/>'
        '<policy context="default"><allow user="*"/><allow own="*"/>'
        '<allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>')
    session_script = runtime / 'session.sh'
    session_script.write_text('''#!/bin/sh
set -eu
umask 077
printf '%s\\n' "$DBUS_SESSION_BUS_ADDRESS" > "$XDG_RUNTIME_DIR/session-address.tmp"
mv "$XDG_RUNTIME_DIR/session-address.tmp" "$XDG_RUNTIME_DIR/session-address"
exec xfce4-session
''')
    host = dict(os.environ, DISPLAY=args.display, XDG_RUNTIME_DIR=str(runtime),
                TERMUX_USB_DESKTOP_DIR=str(usb_runtime))
    for key in ('DBUS_SESSION_BUS_ADDRESS', 'DBUS_SESSION_BUS_PID', 'SESSION_MANAGER'):
        host.pop(key, None)
    guest_values = ['DISPLAY=' + args.display, 'XDG_RUNTIME_DIR=' + str(runtime),
                    'TERMUX_USB_DESKTOP_DIR=' + str(usb_runtime)]
    if args.usb_storage == 'read-only':
        module_dir = (prefix / 'var/lib/proot-distro/containers' / args.alias /
                      'rootfs/usr/local/lib/termux-usb-bridge/gio/modules' if args.run_mode == 'proot'
                      else prefix / 'lib/gio/modules')
        if not (module_dir / 'libtusb-volume-monitor.so').is_file():
            parser.error('GIO storage modules are missing from the selected runtime')
        if args.run_mode == 'proot':
            helper = prefix / 'var/lib/proot-distro/containers' / args.alias / 'rootfs/usr/local/libexec/tusb-gio-mount'
            if not os.access(helper, os.X_OK):
                parser.error('glibc tusb-gio-mount helper is missing from the selected rootfs')
            guest_values += ['GIO_EXTRA_MODULES=/usr/local/lib/termux-usb-bridge/gio/modules']
        else:
            host['GIO_EXTRA_MODULES'] = str(module_dir)
    if args.run_mode == 'proot':
        rootfs = prefix / 'var/lib/proot-distro/containers' / args.alias / 'rootfs'
        (rootfs / str(parent).lstrip('/')).mkdir(parents=True, exist_ok=True)
    base = [] if args.run_mode == 'native' else [
        'proot-distro', 'login', args.alias, '--shared-tmp', '--bind', str(parent) + ':' + str(parent),
        '--', 'env', '-u', 'DBUS_SESSION_BUS_ADDRESS', '-u', 'DBUS_SESSION_BUS_PID',
        '-u', 'SESSION_MANAGER'] + guest_values
    command = base + ['dbus-run-session', '--config-file=' + str(config), '--',
                      '/bin/sh' if base else str(prefix / 'bin/sh'), str(session_script)]
    children, logs = [], []
    storage = None
    def start(name, argv):
        output = (runtime / (name + '.log')).open('w')
        logs.append(output)
        process = subprocess.Popen(argv, env=host, stdout=output, stderr=output, start_new_session=True)
        children.append(process)
        return process
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        session = start('xfce', command)
        deadline = time.monotonic() + 30
        while not marker.is_file():
            if session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('XFCE private session D-Bus did not start; inspect ' + str(runtime / 'xfce.log'))
            time.sleep(.1)
        host['DBUS_SESSION_BUS_ADDRESS'] = marker.read_text().strip()
        if args.usb_storage == 'read-only':
            command = [str(prefix / 'bin/python3'), '-I', '-B',
                       str(Path(__file__).with_name('desktop_usb_storage.py')), '--mode', 'read-only',
                       '--run-mode', args.run_mode, '--runtime', str(usb_runtime),
                       '--broker-socket', str(parent / 'broker.sock'),
                       '--termux-usb', str(prefix / 'libexec/termux-usb-app')]
            if base:
                gio_command = base + ['DBUS_SESSION_BUS_ADDRESS=' + host['DBUS_SESSION_BUS_ADDRESS'],
                                     '/usr/local/libexec/tusb-gio-mount']
                command += ['--gio-command-json', json.dumps(gio_command)]
            storage = start('usb-storage', command)
            deadline = time.monotonic() + 30
            while not (usb_runtime / 'control.sock').is_socket():
                if session.poll() is not None or storage.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('XFCE USB storage did not start in the desktop session')
                time.sleep(.1)
        while session.poll() is None:
            if storage is not None and storage.poll() is not None:
                raise RuntimeError('XFCE USB storage service terminated; inspect ' + str(runtime / 'usb-storage.log'))
            time.sleep(.2)
        return session.returncode
    except KeyboardInterrupt:
        return 130
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        cleanup_error = None
        # Storage must unmount GVfs, sync files and release its broker while the
        # owning session bus is still alive. Signal only its supervisor first.
        if storage is not None:
            if storage.poll() is None:
                storage.terminate()
            try:
                storage_code = storage.wait(timeout=120)
                if storage_code != 0:
                    cleanup_error = 'USB storage cleanup failed (exit ' + str(storage_code) + ')'
            except subprocess.TimeoutExpired:
                cleanup_error = 'USB storage cleanup timed out after 120 seconds'
        for process in reversed(children):
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 5
        for process in reversed(children):
            try:
                process.wait(timeout=max(0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                pass
        for process in reversed(children):
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
        marker.unlink(missing_ok=True)
        for output in logs:
            output.close()
        lock.close()
        if cleanup_error:
            raise RuntimeError(cleanup_error)


if __name__ == '__main__':
    raise SystemExit(main())
