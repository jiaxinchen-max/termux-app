#!/usr/bin/env python3
"""Execute readiness, deadlines and owned cleanup without starting a real desktop."""
import importlib.util
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'


def module(name):
    spec = importlib.util.spec_from_file_location(name, ASSETS / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class PlasmaStartup(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='plasma-session-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.m = module('desktop_plasma_session')

    def run_session(self, marker=True, audio_failure=False, compositor_exit=None, container_exit=None):
        compositor_code = ('import socket,time,sys; from pathlib import Path; '
                           'Path(sys.argv[1]+"/compositor.pid").write_text(str(__import__("os").getpid())); ')
        if compositor_exit is None:
            compositor_code += 's=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1]+"/wayland-0"); time.sleep(30)'
        else:
            compositor_code += 'sys.exit(' + str(compositor_exit) + ')'
        compositor = [sys.executable, '-c', compositor_code, str(self.root)]
        def container(token, started_cs):
            code = ('import time,sys,os; from pathlib import Path; '
                    'r=Path(sys.argv[1]); (r/"guest.pid").write_text(str(os.getpid())); ')
            if container_exit is not None:
                code += 'sys.exit(' + str(container_exit) + ')'
            elif marker:
                code += 'time.sleep(.15); (r/"plasma-container.ready").write_text(sys.argv[2]); time.sleep(30)'
            else:
                code += 'time.sleep(30)'
            return [sys.executable, '-c', code, str(self.root), token]
        self.observed_ready = False
        self.before_ready = False
        def audio():
            ready = self.root / 'session.pid.plasma-ready'
            if ready.exists():
                self.observed_ready = True
                self.assertEqual(ready.read_text(), (self.root / 'session.pid.plasma-session').read_text())
                raise self.m.SessionFailure('injected runtime audio loss')
            self.before_ready = True
            if audio_failure:
                raise self.m.SessionFailure('injected startup audio failure')
        with self.assertRaises(self.m.SessionFailure) as raised:
            self.m.supervise(compositor, container, self.root, self.root / 'session.pid',
                             dict(os.environ), audio, startup_timeout=.9, audio_interval=.05)
        for path in ('session.pid.plasma-ready', 'session.pid.plasma-session',
                     'session.pid.container', 'plasma-container.ready'):
            self.assertFalse((self.root / path).exists(), path)
        for name in ('compositor.pid', 'guest.pid'):
            if (self.root / name).exists():
                pid = int((self.root / name).read_text())
                self.assertFalse(Path('/proc/' + str(pid)).exists(), name)
        return raised.exception

    def test_only_guest_response_publishes_readiness_and_runtime_audio_loss_revokes_it(self):
        error = self.run_session()
        self.assertTrue(self.before_ready)
        self.assertTrue(self.observed_ready)
        self.assertIn('runtime audio loss', str(error))

    def test_bare_compositor_and_stale_evidence_cannot_be_ready(self):
        (self.root / 'session.pid.plasma-ready').write_text('old')
        (self.root / 'session.pid.plasma-session').write_text('old')
        (self.root / 'plasma-container.ready').write_text('old')
        error = self.run_session(marker=False)
        self.assertEqual(124, error.code)
        self.assertFalse(self.observed_ready)

    def test_failed_compositor_or_guest_ends_the_pair(self):
        for options in ({'compositor_exit': 7}, {'container_exit': 9}):
            with self.subTest(options=options):
                # Separate runtime socket for each run, as a real start removes the old socket.
                (self.root / 'wayland-0').unlink(missing_ok=True)
                error = self.run_session(**options)
                self.assertEqual(next(iter(options.values())), error.code)
                self.assertFalse(self.observed_ready)

    def test_audio_failure_does_not_kill_unowned_shared_service(self):
        shared = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            self.run_session(audio_failure=True)
            self.assertIsNone(shared.poll())
            self.assertFalse(self.observed_ready)
        finally:
            shared.terminate()
            shared.wait()

    def test_audio_health_uses_android_sink_and_has_a_deadline(self):
        binary = self.root / 'bin/pactl'
        binary.parent.mkdir()
        for body, succeeds in [('echo "0 Android module-sles-sink.c"', True),
                               ('echo "0 auto_null module-null-sink.c"', False),
                               ('exit 1', False), ('exec sleep 30', False)]:
            binary.write_text('#!/bin/sh\n' + body + '\n')
            binary.chmod(0o755)
            before = time.monotonic()
            if succeeds:
                self.m.check_audio(self.root)
            else:
                with self.assertRaises(self.m.SessionFailure):
                    self.m.check_audio(self.root)
            self.assertLess(time.monotonic() - before, 4)

    def test_cache_preparation_uses_session_environment_and_returns_failure(self):
        initializer = module('desktop_initialize')
        binary = self.root / 'kbuildsycoca6'
        trace = self.root / 'trace'
        binary.write_text('#!/bin/sh\nprintf "%s|%s|%s|%s" "$HOME" "$XDG_DATA_DIRS" "$XDG_MENU_PREFIX" "$*" > "$TRACE"\nexit "${CACHE_EXIT:-0}"\n')
        binary.chmod(0o755)
        env = dict(os.environ, PATH=str(self.root) + ':' + os.environ['PATH'],
                   HOME='/root', TRACE=str(trace), XDG_DATA_DIRS='/stale', XDG_MENU_PREFIX='stale-')
        initializer.prepare_plasma_cache(['env'], env)
        self.assertEqual('/root|/usr/share:/usr/local/share|plasma-|--noincremental', trace.read_text())
        with self.assertRaises(subprocess.CalledProcessError):
            initializer.prepare_plasma_cache(['env'], dict(env, CACHE_EXIT='7'))

    def test_command_line_stop_cleans_children_and_can_start_again(self):
        binary = self.root / 'bin'
        binary.mkdir()
        (binary / 'bash').symlink_to('/bin/bash')
        launcher = self.root / 'libexec/termux-desktop/start-plasma'
        launcher.parent.mkdir(parents=True)
        child_code = ('import os,socket,time; from pathlib import Path; '
                      'r=Path(os.environ["TEST_RUNTIME"]); '
                      '(r/"compositor.pid").write_text(str(os.getpid())); '
                      's=socket.socket(socket.AF_UNIX); s.bind(str(r/"wayland-0")); time.sleep(30)')
        launcher.write_text('#!/bin/sh\nexec ' + shlex.join([sys.executable, '-c', child_code]) + '\n')
        proot = binary / 'proot-distro'
        proot.write_text('#!' + sys.executable + '\n'
                         'import os,sys,time\nfrom pathlib import Path\n'
                         'r=Path(os.environ["TEST_RUNTIME"])\n'
                         '(r/"guest.pid").write_text(str(os.getpid()))\n'
                         'token=next(x.split("=",1)[1] for x in sys.argv if x.startswith("DESKTOP_PLASMA_SESSION_TOKEN="))\n'
                         '(r/"plasma-container.ready").write_text(token)\n'
                         'time.sleep(30)\n')
        proot.chmod(0o755)
        pactl = binary / 'pactl'
        pactl.write_text('#!/bin/sh\necho "0 Android module-sles-sink.c"\n')
        pactl.chmod(0o755)
        env = dict(os.environ, PREFIX=str(self.root), TEST_RUNTIME=str(self.root),
                   PULSE_SERVER='tcp:127.0.0.1:4713')
        identities = []
        for _ in range(2):
            process = subprocess.Popen([sys.executable, str(ASSETS / 'desktop_plasma_session.py'),
                '--runtime', str(self.root), '--pid-file', str(self.root / 'session.pid'),
                '--alias', 'test', '--guest-mode', 'llvmpipe', '--guest-icd', '', '--guest-software', '0'],
                env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                deadline = time.monotonic() + 5
                marker = self.root / 'session.pid.plasma-ready'
                while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertTrue(marker.exists())
                identities.append(marker.read_text())
                process.send_signal(signal.SIGTERM)
                output, _ = process.communicate(timeout=4)
                self.assertEqual(143, process.returncode, output)
                self.assertFalse(marker.exists())
                for name in ('compositor.pid', 'guest.pid'):
                    self.assertFalse(Path('/proc/' + (self.root / name).read_text()).exists())
            finally:
                if process.poll() is None:
                    process.kill()
                process.communicate()
        self.assertNotEqual(identities[0], identities[1])


class GuestServiceWait(unittest.TestCase):
    def run_wait(self, behavior='ready', child='sleep 30', delay=''):
        with tempfile.TemporaryDirectory(prefix='plasma-bus-') as temporary:
            root = Path(temporary)
            mock = root / 'dbus-send'
            mock.write_text('#!/bin/sh\n'
                            'echo "$*" >> "$TRACE"\n'
                            + ('exec sleep 30\n' if behavior == 'hang' else
                               'case "$*" in *GetNameOwner*) echo \'string ":1.7"\';; esac\n'))
            mock.chmod(0o755)
            source = (ASSETS / 'desktop_container_session.sh').read_text()
            functions = source[source.index('monotonic_cs()'):source.index('# Dependencies')]
            functions += source[source.index('require_service_alive()'):source.index('# Keep PolicyKit')]
            functions = functions.replace('/usr/bin/dbus-send', shlex.quote(str(mock)))
            script = functions + '\n' + child + ' &\nchild=$!\ntrap \'kill "$child" 2>/dev/null; wait "$child" 2>/dev/null\' EXIT\n'
            script += delay + '\nwait_for_service "$child" --session org.kde.Test /Test\nresult=$?\nexit "$result"\n'
            start = time.monotonic()
            result = subprocess.run(['sh', '-c', script], env=dict(os.environ, TRACE=str(root / 'trace')),
                                    capture_output=True, text=True, timeout=13)
            trace = (root / 'trace').read_text() if (root / 'trace').exists() else ''
            return result, time.monotonic() - start, trace

    def test_immediate_service_is_not_forced_to_sleep_one_second(self):
        result, elapsed, trace = self.run_wait()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertLess(elapsed, .8)
        self.assertIn('--dest=:1.7 /Test org.freedesktop.DBus.Peer.Ping', trace)

    def test_dead_service_fails_with_its_exit_code(self):
        result, elapsed, _ = self.run_wait(child="sh -c 'exit 7'", delay='sleep .1')
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertLess(elapsed, 1)

    def test_nonresponsive_bus_has_one_total_deadline(self):
        result, elapsed, _ = self.run_wait(behavior='hang')
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertLess(elapsed, 11.5)


if __name__ == '__main__':
    unittest.main()
