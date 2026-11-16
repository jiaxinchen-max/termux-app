#!/usr/bin/env python3
"""Execute Save/launch entrypoints with package and device boundaries explicitly replaced."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'


class SaveTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='desktop-save-')
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.prefix = self.base / 'usr'
        (self.prefix / 'bin').mkdir(parents=True)
        (self.prefix / 'tmp').mkdir()
        self.home = self.base / 'home'
        self.trace = self.base / 'calls'
        self.scripts = self.base / 'scripts'
        self.scripts.mkdir()
        for name in ('desktop_install.sh', 'desktop_common.sh', 'desktop_rendering.sh'):
            shutil.copy(ASSETS / name, self.scripts / name)
        wrapper = '#!/usr/bin/python3\n' + r'''
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
with open(os.environ['TRACE'], 'a') as out: out.write(json.dumps([name, sys.argv[1:]])+'\n')
if name == 'uname': print('aarch64')
if name in ('apt','pkg','curl','wget','dpkg-query'): sys.exit(98)
if name == 'pactl' and 'sinks' in sys.argv: print('0\tAndroid\tmodule-sles-sink.c\tIDLE')
if name == 'pulseaudio' and os.environ.get('AUDIO_START_DELAY'):
    import time
    time.sleep(float(os.environ['AUDIO_START_DELAY']))
if name == os.environ.get('FAIL'): sys.exit(43)
'''
        for name in ('uname', 'apt', 'pkg', 'curl', 'wget', 'dpkg-query', 'dpkg', 'python3', 'pulseaudio', 'pactl'):
            path = self.prefix / 'bin' / name
            path.write_text(wrapper)
            path.chmod(0o700)
        self.values = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_ID='save-test', DESKTOP_MODE='native',
            DESKTOP_DE='xfce4', DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='none',
            DESKTOP_USB_STORAGE='off', DESKTOP_RELEASE_TAG='termux-hwaccel-6.7-r4',
            DESKTOP_AUDIO_MODE='host-pulseaudio', DESKTOP_PID_FILE=str(self.base / 'state/pid'),
            DESKTOP_STATUS_FILE=str(self.base / 'state/status'), DESKTOP_LOG_FILE=str(self.base / 'log'),
            DESKTOP_WIDTH='1280', DESKTOP_HEIGHT='720', DESKTOP_REFRESH_RATE='60',
            DESKTOP_RENDER_SOCKET=str(self.base / 'render'), DESKTOP_PREPARE_ONLY='1')
        self.env = dict(os.environ, PREFIX=str(self.prefix), HOME=str(self.home), TRACE=str(self.trace), XDG_CONFIG_HOME=str(self.home / '.config'))

    def calls(self):
        return [json.loads(line) for line in self.trace.read_text().splitlines()]

    def save(self, failure=''):
        spec = self.base / 'spec'
        spec.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k, v in self.values.items()))
        return subprocess.run(['sh', str(self.scripts / 'desktop_install.sh'), str(spec), '--operation=install'],
            env=dict(self.env, FAIL=failure), text=True, capture_output=True, timeout=8)

    def test_save_prepares_locally_without_packages_or_downloads(self):
        result = self.save()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = self.calls()
        self.assertFalse(any(name in ('apt','pkg','curl','wget','dpkg-query') for name, _ in calls))
        command = next(args for name, args in calls if name == 'python3')
        self.assertIn('initialize', command)
        self.assertIn('save-test', command)
        self.assertTrue((self.home / '.config/autostart/tusb-desktop.desktop').is_file())
        self.assertEqual('exit:0\n', (self.base / 'state/status').read_text())

    def test_failed_save_keeps_failure_and_does_not_repair(self):
        result = self.save('python3')
        self.assertEqual(43, result.returncode, result.stdout + result.stderr)
        self.assertEqual('exit:43\n', (self.base / 'state/status').read_text())
        self.assertEqual(1, sum(name == 'python3' for name, _ in self.calls()))
        self.assertFalse(any(name in ('apt','pkg','curl','wget') for name, _ in self.calls()))

    def test_launch_audio_uses_no_sink_probe_or_default_write(self):
        script = '. ' + shlex.quote(str(self.scripts / 'desktop_common.sh')) + '\nstart_desktop_audio host-pulseaudio\n'
        result = subprocess.run(['sh', '-c', script], env=dict(self.env, **self.values),
            text=True, capture_output=True, timeout=8)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['pulseaudio'], [name for name, _ in self.calls()])
        self.assertIn('--start', self.calls()[0][1])

    def test_launch_audio_failure_exits_without_retry_or_probe(self):
        script = '. ' + shlex.quote(str(self.scripts / 'desktop_common.sh')) + '\nstart_desktop_audio host-pulseaudio\n'
        result = subprocess.run(['sh', '-c', script], env=dict(self.env, FAIL='pulseaudio', **self.values),
            text=True, capture_output=True, timeout=8)
        self.assertEqual(70, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['pulseaudio'], [name for name, _ in self.calls()])
        self.assertIn('Host PulseAudio start failed (status 43)', (self.base / 'log').read_text())

    def test_cold_audio_start_can_complete_after_client_deadline(self):
        script = '. ' + shlex.quote(str(self.scripts / 'desktop_common.sh')) + '\nstart_desktop_audio host-pulseaudio\n'
        result = subprocess.run(['sh', '-c', script], env=dict(self.env, AUDIO_START_DELAY='4', **self.values),
            text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['pulseaudio'], [name for name, _ in self.calls()])


class NativePlasmaSaveTests(unittest.TestCase):
    def test_native_cache_and_service_lookup_are_prepared_during_save(self):
        import importlib.util
        from types import SimpleNamespace
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('desktop_initialize_save', ASSETS / 'desktop_initialize.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(prefix='native-plasma-save-') as directory:
            prefix = Path(directory)
            for name in ('python3','pulseaudio','pactl','plasmashell','dbus-daemon','dbus-send',
                         'kwin_wayland','dbus-launch','dbus-run-session','kactivitymanagerd','kbuildsycoca6','bash'):
                path = prefix / 'bin' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('#!/bin/sh\nexit 0\n'); path.chmod(0o700)
            (prefix / 'lib').mkdir(); (prefix / 'lib/libtermux-render.so').touch()
            for name in ('start-plasma','kwin-android-rendering-env'):
                shutil.copy(ASSETS.parents[4] / 'docs/desktop-testing/fixtures/plasma-r28' / name,
                            prefix / 'bin' / name)
            args = SimpleNamespace(prefix=prefix, run_mode='native', de='kwin_plasma',
                host_gl_mode='system', guest_gl_mode='none', usb_storage='off')
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                return subprocess.CompletedProcess(command, 0, '', '')
            with patch.object(module.subprocess, 'run', side_effect=run), \
                 patch.object(module.runpy, 'run_path', return_value={'host_probe': lambda: None}):
                module.initialize(args)
            cache = [cmd for cmd in calls if str(prefix / 'bin/kbuildsycoca6') in cmd]
            self.assertEqual(1, len(cache))
            self.assertEqual([str(prefix / 'bin/dbus-run-session'), '--',
                              str(prefix / 'bin/kbuildsycoca6'), '--noincremental'], cache[0])
            launch = (prefix / 'libexec/termux-desktop/start-plasma').read_text()
            self.assertNotIn('kbuildsycoca', launch)
            self.assertNotIn('find_kactivitymanagerd', launch)
            self.assertIn('KACTIVITYMANAGERD_CMD=' + str(prefix / 'bin/kactivitymanagerd'), launch)


if __name__ == '__main__':
    unittest.main(verbosity=2)
