#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""App-owned, read-only USB GIO coordinator inside the selected desktop D-Bus session."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import signal
import time
from types import SimpleNamespace

ERROR_PATH = None


def paths(prefix, desktop_id):
    key = hashlib.sha256(desktop_id.encode()).hexdigest()[:16]
    return prefix / ('tmp/tusb-' + key), Path('/tmp/tusb-' + key)


def prepare_guest_mount(root, guest_parent):
    """Save/install only. Walk guest mount points without following guest symlinks."""
    if guest_parent.parent != Path('/tmp') or not guest_parent.name.startswith('tusb-'):
        raise ValueError('Invalid guest USB mount point')
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open(root, flags)
    try:
        for name in guest_parent.parts[1:]:
            try:
                os.mkdir(name, mode=0o700, dir_fd=descriptor)
            except FileExistsError:
                pass
            child = os.open(name, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
    finally:
        os.close(descriptor)


class Session:
    """An owned coordinator; readiness is IPC only, never device enumeration or permission."""
    def __init__(self, prefix, desktop_id, environment, gio_command, log_path):
        self.parent, self.guest_parent = paths(prefix, desktop_id)
        self.runtime = self.parent / 'desktop'
        self.process = None
        self.environment = dict(environment, PATH=str(prefix / 'bin') + ':/system/bin',
            XDG_RUNTIME_DIR=str(self.parent))
        for key in ('LD_PRELOAD', 'LD_LIBRARY_PATH'):
            self.environment.pop(key, None)
        self.command = [str(prefix / 'bin/python3'), '-I', '-B', str(Path(__file__).resolve()),
            '--mode', 'read-only', '--run-mode', 'proot', '--runtime', str(self.runtime),
            '--broker-socket', str(self.parent / 'broker.sock'),
            '--termux-usb', str(prefix / 'libexec/termux-usb-app'),
            '--gio-command-json', json.dumps(gio_command),
            '--guest-runtime', str(self.guest_parent / 'desktop')]
        self.log_path = log_path

    def start(self):
        control = self.runtime / 'control.sock'
        def identity():
            try:
                info = control.stat()
                return info.st_ino, info.st_ctime_ns
            except FileNotFoundError:
                return None
        old = identity()
        with self.log_path.open('w') as log:
            self.process = subprocess.Popen(self.command, env=self.environment,
                stdout=log, stderr=log, start_new_session=True)
        deadline = time.monotonic() + 10
        while True:
            self.require_alive()
            if control.is_socket() and identity() != old:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('USB coordinator IPC startup timed out; inspect ' + str(self.log_path))
            time.sleep(.05)

    def require_alive(self):
        if self.process is not None and self.process.poll() is not None:
            raise RuntimeError('USB coordinator exited; inspect ' + str(self.log_path))

    def stop(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.terminate()
        try:
            code = self.process.wait(timeout=120)
        except subprocess.TimeoutExpired as error:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)
            raise RuntimeError('USB cleanup timed out; inspect ' + str(self.log_path)) from error
        if code:
            # The leader can fail during cleanup while its broker still owns USB FDs.
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(self.process.pid, sig)
                except ProcessLookupError:
                    pass
            raise RuntimeError('USB cleanup failed (status ' + str(code) + '); inspect ' + str(self.log_path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('read-only',), required=True)
    parser.add_argument('--run-mode', choices=('native', 'proot'), required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--broker-socket', type=Path, required=True)
    parser.add_argument('--termux-usb', type=Path, required=True)
    parser.add_argument('--gio-command-json')
    parser.add_argument('--guest-runtime', type=Path)
    args = parser.parse_args()
    if not os.environ.get('DBUS_SESSION_BUS_ADDRESS') or not os.environ.get('XDG_RUNTIME_DIR'):
        parser.error('the desktop session D-Bus and XDG runtime are required')
    if not args.runtime.is_absolute() or not args.broker_socket.is_absolute():
        parser.error('absolute runtime and broker socket paths are required')
    if len(os.fsencode(str(args.runtime / 'control.sock'))) >= 108:
        parser.error('USB control socket path exceeds the Unix socket limit')
    prefix = Path(os.environ['PREFIX'])
    if args.termux_usb != prefix / 'libexec/termux-usb-app':
        parser.error('the explicit built-in App USB client is missing or invalid')
    sys.path.insert(0, str(prefix / 'share/termux-usb-bridge'))
    import desktop
    desktop.private_directory(args.runtime.parent)
    desktop.private_directory(args.runtime)
    global ERROR_PATH
    ERROR_PATH = args.runtime / 'last-error.txt'
    ERROR_PATH.unlink(missing_ok=True)
    if args.run_mode == 'proot':
        if not args.gio_command_json:
            parser.error('PRoot requires an explicit glibc GIO helper command')
        command = json.loads(args.gio_command_json)
        if not isinstance(command, list) or not command or any(not isinstance(v, str) or '\0' in v for v in command):
            parser.error('invalid glibc GIO helper command')
        if args.guest_runtime is None or not args.guest_runtime.is_absolute() or '..' in args.guest_runtime.parts:
            parser.error('PRoot requires an absolute guest USB runtime')
        wrappers = args.runtime / 'host-wrappers'
        desktop.private_directory(wrappers)
        configuration = wrappers / 'gio-command.json'
        configuration.write_text(json.dumps(dict(command=command, host=str(args.runtime),
            guest=str(args.guest_runtime))), encoding='utf-8')
        configuration.chmod(0o600)
        wrapper = wrappers / 'tusb-gio-mount'
        wrapper.write_text('#!' + sys.executable + '\nimport json,os,sys,re\nfrom pathlib import Path\n'
            + 'with open(' + repr(str(configuration)) + ') as f: config=json.load(f)\n'
            + 'command=config["command"]; args=sys.argv[1:]\n'
            + 'if len(args)==3 and args[0]=="mount":\n'
            + '    token=Path(args[2]).relative_to(config["host"])\n'
            + '    if len(token.parts)!=1 or not re.fullmatch(r"[0-9a-f]+\\.token",token.name): raise ValueError("invalid USB token path")\n'
            + '    args[2]=str(Path(config["guest"])/token)\n'
            + 'os.execvpe(command[0],command+args,dict(os.environ))\n', encoding='utf-8')
        wrapper.chmod(0o700)
        os.environ['PATH'] = str(wrappers) + os.pathsep + os.environ['PATH']
    elif args.gio_command_json or args.guest_runtime:
        parser.error('Native mode must use the installed Bionic GIO helper')
    os.environ['TERMUX_USB_DESKTOP_DIR'] = str(args.runtime)
    os.environ['TERMUX_USB_BRIDGE_SOCKET'] = str(args.broker_socket)

    class AppStorageManager(desktop.Manager):
        def __init__(self, settings):
            super().__init__(settings)
            self.enumeration_error = 'USB enumeration has not completed'
            self.last_poll_millis = 0

        def start_broker(self):
            # Never reuse a daemon whose backend may belong to another USB API.
            if args.broker_socket.exists():
                raise RuntimeError('App USB broker socket already exists; stop the owning desktop first')
            self.broker_process = self.launch([str(prefix / 'bin/termux-usb-bridge'),
                '--socket', str(args.broker_socket), 'serve', '--termux-usb', str(args.termux_usb)], 'broker')
            deadline = time.monotonic() + 5
            while not args.broker_socket.is_socket():
                if self.broker_process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('App USB broker did not start; inspect broker.log')
                time.sleep(.05)

        def classify(self, record, authorize=False):
            if authorize:
                subprocess.run([str(args.termux_usb), '-r', record['path']],
                    env=self.environment, check=True, timeout=90, stdout=subprocess.DEVNULL)
            return super().classify(record, authorize=False)

        def poll(self):
            try:
                super().poll()
            except Exception as error:
                with self.lock:
                    self.enumeration_error = str(error)[:2048]
                raise
            else:
                with self.lock:
                    self.enumeration_error = None
                    self.last_poll_millis = int(time.time() * 1000)

        def action(self, command, key):
            if command == 'health':
                with self.lock:
                    return json.dumps(dict(schemaVersion=1, storageMode=args.mode,
                        runMode=args.run_mode, pid=os.getpid(),
                        enumerationError=self.enumeration_error,
                        lastPollMillis=self.last_poll_millis,
                        devices=json.loads(super().action('status', ''))), ensure_ascii=False)
            return super().action(command, key)

        def cleanup(self):
            failures = []
            with self.lock:
                for key, record in list(self.records.items()):
                    try:
                        # Shutdown is not a physical unplug: GVfs/flush errors must propagate.
                        self.unmount(record, disconnected=False)
                    except Exception as error:
                        failures.append(str(error))
                    else:
                        del self.records[key]
                self.publish()
            if self.broker_process and not failures:
                try:
                    if self.stop_process(self.broker_process) != 0:
                        raise RuntimeError('USB broker did not exit cleanly')
                except Exception as error:
                    failures.append(str(error))
            if failures:
                raise RuntimeError('USB storage cleanup failed: ' + '; '.join(failures))

    desktop.Manager = AppStorageManager
    settings = SimpleNamespace(runtime=args.runtime, broker_socket=str(args.broker_socket),
        request_permission=False, no_automount=False, no_camera=True, no_gamepad=True,
        read_write=False, interval=1.0, test_manifest=None)
    import logging
    logging.basicConfig(level=logging.INFO, format='%(name)s: %(message)s')
    desktop.serve(settings)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        if ERROR_PATH is not None:
            ERROR_PATH.write_text(str(error)[:4096], encoding='utf-8')
            ERROR_PATH.chmod(0o600)
        sys.exit(str(error))
