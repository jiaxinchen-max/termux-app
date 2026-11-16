#!/usr/bin/env python3
"""Manage the shared host audio service, including on every desktop launch."""
import contextlib
import fcntl
import os
from pathlib import Path
import signal
import subprocess
import time

ENDPOINT = 'tcp:127.0.0.1:4713'
PROC = Path('/proc')


class AudioService:
    def __init__(self, prefix, home):
        self.prefix, self.home = Path(prefix), Path(home)
        self.env = dict(os.environ)
        self.env.pop('PULSE_SERVER', None)
        self.env.pop('PULSE_SINK', None)
        self.env.pop('PULSE_SOURCE', None)

    def run(self, command, *args, timeout=3):
        return subprocess.run([str(self.prefix / 'bin' / command), *args],
                              env=self.env, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout)

    def ready(self):
        try:
            sinks = self.run('pactl', '--server=' + ENDPOINT, 'list', 'short', 'sinks')
            if sinks.returncode:
                return False
            for line in sinks.stdout.splitlines():
                fields = line.split()
                if len(fields) >= 3 and fields[2] in (
                        'module-sles-sink.c', 'module-aaudio-sink.c'):
                    return self.run('pactl', '--server=' + ENDPOINT,
                                    'set-default-sink', fields[1]).returncode == 0
        except subprocess.TimeoutExpired:
            pass
        return False

    def identity(self, pid):
        """Return a verified identity; never trust a stale daemon PID file."""
        proc = PROC / str(pid)
        try:
            try:
                owner = proc.stat().st_uid
            except PermissionError:
                # Android hidepid exposes some other-UID entries but denies even stat.
                # They cannot be inspected or signalled by this application.
                return None
            if owner != os.getuid():
                return None
            if (proc / 'comm').read_text().strip() != 'pulseaudio':
                return None
            # comm can contain spaces; starttime is field 22, after the closing ')'.
            fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
            if fields[0] == 'Z':
                return None
            executable = (proc / 'cmdline').read_bytes().split(b'\0', 1)[0]
            if executable != b'pulseaudio' and os.path.realpath(os.fsdecode(executable)) != str(
                    (self.prefix / 'bin/pulseaudio').resolve()):
                raise RuntimeError('Unrecognized same-UID PulseAudio process: ' + str(pid))
            return pid, fields[19]
        except (FileNotFoundError, ProcessLookupError):
            return None

    def processes(self):
        result = []
        for proc in PROC.iterdir():
            if proc.name.isdigit():
                identity = self.identity(int(proc.name))
                if identity is not None:
                    result.append(identity)
        return result

    def stop_old(self):
        for sig, deadline in ((signal.SIGTERM, 3), (signal.SIGKILL, 1)):
            processes = self.processes()
            if not processes:
                return
            for pid, started in processes:
                # Recheck identity immediately before signalling (including PID reuse).
                if self.identity(pid) == (pid, started):
                    try:
                        print('Stopping old PulseAudio pid=%s signal=%s' % (pid, sig.name), flush=True)
                        os.kill(pid, sig)
                    except ProcessLookupError:
                        pass
            until = time.monotonic() + deadline
            while self.processes() and time.monotonic() < until:
                time.sleep(0.05)
        if self.processes():
            raise RuntimeError('Old PulseAudio did not stop; refusing a second service')

    @contextlib.contextmanager
    def locked(self):
        directory = self.home / '.termux'
        directory.mkdir(parents=True, exist_ok=True)
        fd = os.open(directory / 'desktop-audio.lock',
                     os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            until = time.monotonic() + 30
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= until:
                        raise RuntimeError('Timed out waiting for shared audio service lock')
                    time.sleep(0.05)
            yield
        finally:
            os.close(fd)

    def ensure(self):
        with self.locked():
            if self.ready():
                print('Reusing host PulseAudio: ' + ENDPOINT, flush=True)
                return
            print('Host audio unavailable; stopping old PulseAudio before one start', flush=True)
            self.stop_old()
            result = self.run('pulseaudio', '--start', '--exit-idle-time=-1',
                              '--log-target=file:' + str(self.home / '.termux/desktop-pulseaudio.log'),
                              '--load=module-native-protocol-tcp listen=127.0.0.1 port=4713 auth-anonymous=1',
                              timeout=15)
            if result.stdout:
                print(result.stdout, end='', flush=True)
            if result.returncode or not self.ready():
                raise RuntimeError('Host PulseAudio start failed or Android output is unavailable')
            print('Host PulseAudio ready: ' + ENDPOINT, flush=True)


if __name__ == '__main__':
    try:
        AudioService(os.environ['PREFIX'], os.environ['HOME']).ensure()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print('Host audio service failed: ' + str(error), flush=True)
        raise SystemExit(70)
