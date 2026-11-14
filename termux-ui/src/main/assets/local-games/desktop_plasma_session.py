#!/usr/bin/env python3
"""Supervise the native KWin + PRoot Plasma pair without equating their readiness."""
import argparse
import os
from pathlib import Path
import signal
import stat
import subprocess
import time
import uuid


def clock():
    # /proc/uptime in the guest uses CLOCK_BOOTTIME, including device suspend.
    return time.clock_gettime(time.CLOCK_BOOTTIME)


class SessionFailure(Exception):
    def __init__(self, message, code=70):
        super().__init__(message)
        self.code = code


def atomic_write(path, value):
    temporary = path.with_name(path.name + '.tmp.' + str(os.getpid()))
    try:
        temporary.write_text(value + '\n')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def read_small(path):
    try:
        with path.open() as source:
            return source.read(256).strip()
    except OSError:
        return ''


def stop_groups(processes):
    # Signal every owned group even if its leader exited, since descendants may remain.
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for process in reversed(processes):
            try:
                os.killpg(process.pid, sig)
            except ProcessLookupError:
                pass
        if sig == signal.SIGTERM:
            time.sleep(1.2)
    for process in processes:
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired as error:
            raise SessionFailure('owned process group did not stop before cleanup deadline', 124) from error


def require_alive(process, label):
    code = process.poll()
    if code is not None:
        raise SessionFailure(label + ' exited (status ' + str(code) + ')', code if code > 0 else 70)


def is_socket(path):
    try:
        return stat.S_ISSOCK(path.stat().st_mode)
    except OSError:
        return False


def check_audio(prefix):
    """Only inspect the existing shared server; never start, restart or kill it here."""
    for command in (['info'], ['list', 'short', 'sinks']):
        try:
            result = subprocess.run([str(prefix / 'bin/pactl'), '--server=tcp:127.0.0.1:4713'] + command,
                                    capture_output=True, text=True, timeout=3)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise SessionFailure('host audio health request failed: ' + str(error)) from error
        if result.returncode:
            raise SessionFailure('host audio endpoint failed: ' + result.stderr.strip())
        if command[0] == 'list' and not any(
                driver in result.stdout for driver in ('module-sles-sink', 'module-aaudio-sink')):
            raise SessionFailure('host audio lost its Android output sink')


def supervise(compositor_command, container_command, runtime, pid_file, env, audio_check,
              startup_timeout=60, audio_interval=5):
    """Commands are argv arrays. Both children own new process groups for bounded cleanup."""
    started = clock()
    processes = []
    ready_file = Path(str(pid_file) + '.plasma-ready')
    session_file = Path(str(pid_file) + '.plasma-session')
    container_pid_file = Path(str(pid_file) + '.container')
    guest_ready = runtime / 'plasma-container.ready'
    token = uuid.uuid4().hex
    session_identity = str(os.getpid()) + ':' + token

    def stage(name):
        print('STARTUP stage=' + name + ' elapsed_ms=' + str(int((clock() - started) * 1000)), flush=True)

    def deadline():
        if clock() >= started + startup_timeout:
            raise SessionFailure('container Plasma startup deadline exceeded', 124)

    try:
        for path in (ready_file, session_file, container_pid_file, guest_ready):
            path.unlink(missing_ok=True)
        atomic_write(session_file, session_identity)
        compositor = subprocess.Popen(compositor_command, env=env, start_new_session=True)
        processes.append(compositor)
        while not is_socket(runtime / 'wayland-0'):
            require_alive(compositor, 'KWin')
            deadline()
            time.sleep(0.1)
        require_alive(compositor, 'KWin')
        stage('compositor-socket')
        container = subprocess.Popen(container_command(token, int(started * 100)), env=env, start_new_session=True)
        processes.append(container)
        atomic_write(container_pid_file, str(container.pid))
        next_audio = 0
        ready = False
        while True:
            require_alive(compositor, 'KWin')
            require_alive(container, 'container session')
            if not ready:
                deadline()
            now = clock()
            guest_responsive = read_small(guest_ready) == token
            if now >= next_audio or (guest_responsive and not ready):
                audio_check()
                next_audio = clock() + audio_interval
            if guest_responsive and not ready:
                deadline()
                require_alive(compositor, 'KWin')
                require_alive(container, 'container session')
                atomic_write(ready_file, session_identity)
                ready = True
                stage('container-responsive')  # Service response, NOT a rendered first frame.
            time.sleep(0.1 if not ready else 0.5)
    finally:
        # Revoke readiness before terminating any process. Shared PulseAudio is not owned here.
        try:
            for path in (ready_file, session_file, container_pid_file):
                path.unlink(missing_ok=True)
        finally:
            try:
                stop_groups(processes)
            finally:
                guest_ready.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--pid-file', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--guest-mode', choices=('llvmpipe', 'zink'), required=True)
    parser.add_argument('--guest-icd', required=True)
    parser.add_argument('--guest-software', required=True)
    args = parser.parse_args()
    prefix = Path(os.environ['PREFIX'])
    assets = Path(__file__).resolve().parent
    env = dict(os.environ, KWIN_WAYLAND_NO_PERMISSION_CHECKS='1')
    args.runtime.mkdir(parents=True, exist_ok=True)
    (args.runtime / 'wayland-0').unlink(missing_ok=True)

    def container_command(token, started_cs):
        command = [str(prefix / 'bin/proot-distro'), 'login', args.alias,
                   '--bind', str(args.runtime) + ':/run/wl']
        for name in ('desktop_container_session.sh', 'desktop_container_statx_setup.sh',
                     'desktop_container_run_config.sh', 'desktop_container_statx_aarch64.bin'):
            command += ['--bind', str(assets / name) + ':/tmp/' + name]
        return command + ['--', 'env', 'DESKTOP_GUEST_GL_MODE=' + args.guest_mode,
                          'DESKTOP_PLASMA_GUEST_ICD=' + args.guest_icd,
                          'DESKTOP_PLASMA_GUEST_SOFTWARE=' + args.guest_software,
                          'DESKTOP_PLASMA_SESSION_TOKEN=' + token,
                          'DESKTOP_PLASMA_STARTED_CS=' + str(started_cs),
                          'PULSE_SERVER=' + env['PULSE_SERVER'], 'DESKTOP_STATX_BACKEND=proc-fdinfo',
                          'DESKTOP_KDE_SERVICE_POLICY=kwin-wayland', 'QT_QPA_PLATFORM=wayland',
                          'XDG_SESSION_TYPE=wayland', 'sh', '/tmp/desktop_container_session.sh']

    def interrupted(signum, frame):
        # A second stop must not interrupt owned process cleanup halfway through.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        raise SessionFailure('session stopped by signal ' + str(signum), 128 + signum)

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        supervise([str(prefix / 'bin/bash'), str(prefix / 'libexec/termux-desktop/start-plasma'),
                   '--compositor-only'], container_command, args.runtime, args.pid_file, env,
                  lambda: check_audio(prefix))
    except (OSError, SessionFailure) as error:
        print('ERROR: ' + str(error), flush=True)
        return getattr(error, 'code', 70)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
