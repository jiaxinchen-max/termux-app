#!/usr/bin/env python3
"""Check install-only validation, failure markers and direct native launch."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'termux-ui/src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('native_ubuntu', ASSETS / 'desktop_native_ubuntu.py')
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class InitializationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.prefix = Path(temporary.name) / 'usr'
        for name in profile.REQUIRED_FILES + profile.RUNTIME_FILES:
            self.write(name, 'fixture\n')
        self.write(profile.REQUIRED_FILES[0], json.dumps(dict(
            parentMode='user', stylesheetName='Yaru/gnome-shell.css',
            themeResourceName='theme/Yaru/gnome-shell-theme.gresource',
            enabledExtensions=list(profile.EXTENSIONS))))
        self.theme_files = list(profile.REQUIRED_FILES)
        for uuid in profile.EXTENSIONS:
            path = 'share/gnome-shell/extensions/' + uuid + '/'
            self.write(path + 'metadata.json', json.dumps({'uuid': uuid, 'shell-version': ['48']}))
            self.write(path + 'extension.js', '// fixture\n')
            self.theme_files += [path + 'metadata.json', path + 'extension.js']
        self.write('share/icons/Yaru/sample.svg', '<svg/>')
        self.theme_files.append('share/icons/Yaru/sample.svg')
        self.write(profile.MANIFEST, json.dumps(dict(
            profile='native-ubuntu-gnome-48-r2', files={
                name: profile.digest(self.prefix / name) for name in self.theme_files})))
        self.write('var/lib/dpkg/status', 'Package: fixture\nVersion: 1\n')
        self.write('share/termux-native-ubuntu/.installed', profile.BUNDLE_SHA256 + '\n')
        for program in ('gnome-shell', 'gjs', 'gnome-control-center', 'gnome-calculator', 'gnome-clocks', 'eog'):
            path = self.write('bin/' + program, '#!/bin/sh\necho "GNOME Shell 48.1"\n')
            path.chmod(0o755)

    def write(self, name, content):
        path = self.prefix / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def archive(self, corrupt=False):
        if corrupt:
            self.write('share/icons/Yaru/sample.svg', '<corrupt/>')
        archive = self.prefix.parent / 'profile.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            for name in self.theme_files + [profile.MANIFEST]:
                output.add(self.prefix / name, arcname=name)
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def assert_not_installed(self):
        self.assertFalse((self.prefix / 'share/termux-native-ubuntu/.installed').exists())

    def test_install_validates_and_probes_before_success_marker(self):
        archive, digest = self.archive()
        real_probe = profile.runtime_probe
        def probe(*args):
            self.assert_not_installed()
            return real_probe(*args)
        with patch.object(profile, 'BUNDLE_SHA256', digest), \
             patch.object(profile, 'validate', wraps=profile.validate) as validation, \
             patch.object(profile, 'runtime_probe', side_effect=probe) as runtime:
            profile.install(self.prefix, archive)
            self.assertEqual(validation.call_count, 1)
            self.assertEqual(runtime.call_count, 1)
        self.assertEqual((self.prefix / 'share/termux-native-ubuntu/.installed').read_text().strip(), digest)

    def test_bad_archive_clears_previous_success(self):
        archive = self.write('invalid.tar.gz', 'invalid archive')
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            profile.install(self.prefix, archive)
        self.assert_not_installed()

    def test_corrupt_theme_does_not_publish_success(self):
        archive, digest = self.archive(corrupt=True)
        with patch.object(profile, 'BUNDLE_SHA256', digest):
            with self.assertRaisesRegex(ValueError, 'corrupt or missing'):
                profile.install(self.prefix, archive)
        self.assert_not_installed()

    def test_missing_dependency_does_not_publish_success(self):
        archive, digest = self.archive()
        (self.prefix / 'bin/gnome-clocks').unlink()
        with patch.object(profile, 'BUNDLE_SHA256', digest):
            with self.assertRaisesRegex(ValueError, 'required file missing'):
                profile.install(self.prefix, archive)
        self.assert_not_installed()

    def test_broken_application_does_not_publish_success(self):
        archive, digest = self.archive()
        self.write('bin/gnome-control-center', '#!/bin/sh\necho "missing ICU" >&2\nexit 127\n').chmod(0o755)
        with patch.object(profile, 'BUNDLE_SHA256', digest):
            with self.assertRaisesRegex(ValueError, 'missing ICU'):
                profile.install(self.prefix, archive)
        self.assert_not_installed()

    def test_gjs_import_failure_does_not_publish_success(self):
        archive, digest = self.archive()
        self.write('bin/gjs', '#!/bin/sh\necho "cannot import Gtk" >&2\nexit 1\n').chmod(0o755)
        with patch.object(profile, 'BUNDLE_SHA256', digest):
            with self.assertRaisesRegex(ValueError, 'cannot import Gtk'):
                profile.install(self.prefix, archive)
        self.assert_not_installed()

    def test_explicit_diagnosis_checks_theme_and_runtime(self):
        command = ['python3', '-I', '-B', str(ASSETS / 'desktop_native_ubuntu.py'), '--prefix', str(self.prefix)]
        subprocess.run(command, check=True, capture_output=True)
        self.write('bin/gnome-control-center', '#!/bin/sh\necho "missing ICU" >&2\nexit 127\n').chmod(0o755)
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 65)
        self.assertIn('missing ICU', result.stderr)
        self.write('share/icons/Yaru/sample.svg', 'corrupt')
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 65)
        self.assertIn('corrupt or missing', result.stderr)

    def test_native_launch_reaches_execution_without_theme_preflight(self):
        # Run the real launcher with no theme, initialization marker or apps.
        # Stop at process creation: this proves the launch boundary, not a desktop.
        driver = r'''
import os,runpy,socket,subprocess,sys
from pathlib import Path
base=Path(sys.argv[1]); launcher=sys.argv[2]
prefix=base/'empty-prefix';home=base/'home';home.mkdir();prefix.mkdir()
services=prefix/'share/dbus-1/services';services.mkdir(parents=True)
for name in ('ca.desrt.dconf','org.gtk.vfs.Daemon','org.gnome.Nautilus','org.gnome.Terminal','org.gnome.TextEditor','org.gnome.Settings','org.gnome.clocks'):
 (services/(name+'.service')).write_text('[D-BUS Service]\nName='+name+'\n')
render=socket.socket(socket.AF_UNIX);render.bind(str(base/'render.sock'))
os.environ.update(PREFIX=str(prefix),HOME=str(home),PULSE_SERVER='tcp:127.0.0.1:4713')
class ReachedExecution(Exception):pass
def no_probe(*args,**kwargs):raise AssertionError('startup dependency preflight: '+repr(args))
def spawn(command,**kwargs):
 assert command[0]=='dbus-daemon',command
 raise ReachedExecution()
subprocess.run=no_probe;subprocess.Popen=spawn
sys.argv=[launcher,'--desktop-id','fixture','--ready-file',str(base/'ready'),'--run-mode','native','--backend','android','--session-profile','native-ubuntu','--session-startup','direct','--gl-mode','system','--size','1280x720','--refresh-rate','60','--render-socket',str(base/'render.sock'),'--wayland-display','wayland-fixture','--usb-storage','off','--work',str(base/'work'),'--display',':0','--service-policy','core']
try:runpy.run_path(launcher,run_name='__main__')
except ReachedExecution:print('reached native execution without static preflight')
else:raise AssertionError('did not reach process creation')
'''
        with tempfile.TemporaryDirectory(dir='/tmp') as directory:
            result = subprocess.run(['python3', '-B', '-c', driver, directory,
                                     str(ASSETS / 'desktop_gnome_launch.py')], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('reached native execution', result.stdout)


if __name__ == '__main__':
    unittest.main()
