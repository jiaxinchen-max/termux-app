#!/usr/bin/env python3
"""Native profile installation/repair, integrity, runtime and user-choice gates."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('native_ubuntu', ASSETS / 'desktop_native_ubuntu.py')
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class NativeProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='native-ubuntu-')
        cls.root = Path(cls.tmp.name)
        cls.prefix = cls.root / 'prefix'
        for file in profile.RUNTIME_FILES:
            path = cls.prefix / file
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        cls.bundle = Path(os.environ['NATIVE_UBUNTU_BUNDLE'])
        profile.install(cls.prefix, cls.bundle)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_actual_bundle_is_installed_and_native_paths_are_patched(self):
        profile.validate(self.prefix)
        self.assertEqual(profile.BUNDLE_SHA256, (self.prefix / 'share/termux-native-ubuntu/.installed').read_text().strip())
        ding = self.prefix / 'share/gnome-shell/extensions/ding@rastersoft.com'
        self.assertTrue((ding / 'app/ding.js').read_text().startswith('#!/data/data/com.termux/files/usr/bin/gjs'))
        extension = (ding / 'extension.js').read_text()
        self.assertNotIn("'/bin/kill'", extension)
        cleanup = extension.split('    doKillAllOldDesktopProcesses() {', 1)[1].split('\n    /**', 1)[0]
        self.assertNotIn('enumerate_children', cleanup)
        self.assertNotIn('load_bytes', cleanup)
        self.assertNotIn('Gio.Subprocess', cleanup)
        self.assertIn('session-owned cleanup', json.loads((self.prefix / profile.MANIFEST).read_text())['patches']['share/gnome-shell/extensions/ding@rastersoft.com/extension.js'])
        with tarfile.open(self.bundle) as archive:
            self.assertTrue(any(m.islnk() for m in archive.getmembers()))
            for entry in archive:
                self.assertFalse(entry.name.startswith(('bin/', 'lib/')))

    def test_corrupt_theme_and_missing_application_are_rejected(self):
        path = self.prefix / 'share/gnome-shell/theme/Yaru/gnome-shell.css'
        original = path.read_bytes()
        try:
            path.write_bytes(b'corrupted stylesheet')
            with self.assertRaisesRegex(ValueError, 'corrupt or missing'):
                profile.validate(self.prefix)
        finally:
            path.write_bytes(original)
        for relative in ('bin/gnome-terminal', 'bin/gnome-control-center',
                         'bin/gnome-calculator', 'bin/gnome-clocks', 'bin/eog',
                         'share/applications/org.gnome.Settings.desktop',
                         'share/dbus-1/services/org.gnome.Settings.service',
                         'share/dbus-1/services/org.gnome.clocks.service'):
            app = self.prefix / relative
            content = app.read_bytes()
            app.unlink()
            try:
                with self.subTest(relative=relative), self.assertRaisesRegex(ValueError, 'required file missing'):
                    profile.validate(self.prefix)
            finally:
                app.write_bytes(content)

    def test_wrong_extension_version_is_rejected(self):
        path = self.prefix / 'share/gnome-shell/extensions/ding@rastersoft.com/metadata.json'
        original = path.read_bytes()
        try:
            metadata = json.loads(original)
            metadata['shell-version'] = ['51']
            path.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'GNOME 48'):
                profile.validate(self.prefix)
        finally:
            path.write_bytes(original)

    def test_android_without_os_link_materializes_internal_file(self):
        path = self.root / 'android-no-link.bin'
        name = 'share/icons/Yaru/original.txt'
        alias = 'share/icons/Yaru/alias.txt'
        content = b'icon content'
        with tarfile.open(path, 'w:gz') as archive:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
            entry = tarfile.TarInfo(alias)
            entry.type = tarfile.LNKTYPE
            entry.linkname = name
            archive.addfile(entry)
        prefix = self.root / 'android-prefix'
        (prefix / 'share/termux-native-ubuntu').mkdir(parents=True)
        # Remove the attribute entirely, matching Termux Python on Android.
        link = getattr(os, 'link', None)
        if link is not None:
            del os.link
        try:
            with patch.object(profile, 'BUNDLE_SHA256', profile.digest(path)), patch.object(profile, 'validate'):
                profile.install(prefix, path)
        finally:
            if link is not None:
                os.link = link
        self.assertEqual(content, (prefix / alias).read_bytes())
        self.assertFalse((prefix / alias).is_symlink())
        self.assertNotEqual((prefix / name).stat().st_ino, (prefix / alias).stat().st_ino)
        self.assertTrue((prefix / 'share/termux-native-ubuntu/.installed').is_file())

    def test_wrong_digest_cannot_write_into_prefix(self):
        path = self.root / 'corrupt.bin'
        path.write_bytes(b'not the released bundle')
        destination = self.root / 'untouched'
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            profile.install(destination, path)
        self.assertFalse(destination.exists())

    def test_archive_cannot_escape_or_install_executables(self):
        for name in ('../outside', '/absolute', 'bin/gnome-shell'):
            with self.subTest(name=name):
                path = self.root / 'malicious.bin'
                with tarfile.open(path, 'w:gz') as archive:
                    member = tarfile.TarInfo(name)
                    member.size = 1
                    archive.addfile(member, io.BytesIO(b'x'))
                with patch.object(profile, 'BUNDLE_SHA256', profile.digest(path)):
                    with self.assertRaises(ValueError):
                        profile.install(self.root / 'untouched', path)
        self.assertFalse((self.root / 'outside').exists())

    def test_failed_install_removes_success_marker_and_reinstall_repairs(self):
        application = self.prefix / 'bin/gnome-text-editor'
        application.unlink()
        try:
            with self.assertRaisesRegex(ValueError, 'gnome-text-editor'):
                profile.install(self.prefix, self.bundle)
            self.assertFalse((self.prefix / 'share/termux-native-ubuntu/.installed').exists())
        finally:
            application.touch()
            profile.install(self.prefix, self.bundle)
        profile.validate(self.prefix)

    def test_first_start_initializes_once_then_preserves_customization(self):
        work = self.root / 'settings'
        work.mkdir()
        commands = work / 'commands'
        gsettings = work / 'gsettings'
        gsettings.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$PROFILE_TEST_COMMANDS"\n')
        gsettings.chmod(0o755)
        home = work / 'home'
        home.mkdir()
        config = work / 'config'
        config.mkdir()
        env = dict(os.environ, PATH=str(work) + ':' + os.environ['PATH'], HOME=str(home),
                   XDG_CONFIG_HOME=str(config), PROFILE_TEST_COMMANDS=str(commands))
        for iteration in range(2):
            result = subprocess.run(['sh', '-eu', '-c', profile.session_setup(self.prefix)],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            if iteration == 0:
                first = commands.read_bytes()
                self.assertIn(b'gtk-theme Yaru', first)
                self.assertIn(b'dock-position LEFT', first)
                # Simulate later user customization: no gsettings command may
                # run again, including cursor-theme.
            else:
                self.assertEqual(first, commands.read_bytes())
        self.assertTrue((home / 'Desktop').is_dir())
        self.assertTrue((config / 'user-dirs.dirs').is_file())

    def test_runtime_requires_correct_shell_and_loadable_gi_libraries(self):
        ok = subprocess.CompletedProcess([], 0, 'GNOME Shell 48.7\n', '')
        broken_gi = subprocess.CompletedProcess([], 1, '', 'cannot load Gtk')
        with patch.object(profile.subprocess, 'run', side_effect=[ok, broken_gi]):
            with self.assertRaisesRegex(ValueError, 'dependency probe failed'):
                profile.runtime_probe(self.prefix, {})
        with patch.object(profile.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'GNOME Shell 51.0', '')):
            with self.assertRaisesRegex(ValueError, 'GNOME Shell 48'):
                profile.runtime_probe(self.prefix, {})

    def test_runtime_rejects_installed_clock_linked_to_missing_icu(self):
        shell = subprocess.CompletedProcess([], 0, 'GNOME Shell 48.7', '')
        ok = subprocess.CompletedProcess([], 0, 'version', '')
        broken = subprocess.CompletedProcess([], 127, '', 'libicui18n.so.78 not found')
        with patch.object(profile.subprocess, 'run', side_effect=[shell, ok, ok, ok, broken]):
            with self.assertRaisesRegex(ValueError, 'gnome-clocks.*libicui18n'):
                profile.runtime_probe(self.prefix, {})

    def test_runtime_accepts_all_loadable_native_applications(self):
        shell = subprocess.CompletedProcess([], 0, 'GNOME Shell 48.7', '')
        ok = subprocess.CompletedProcess([], 0, 'version', '')
        with patch.object(profile.subprocess, 'run', side_effect=[shell] + [ok] * 5) as run:
            profile.runtime_probe(self.prefix, {})
        self.assertEqual([self.prefix / 'bin' / name for name in
                          ('gnome-control-center', 'gnome-calculator', 'gnome-clocks', 'eog')],
                         [Path(call.args[0][0]) for call in run.call_args_list[2:]])

    def test_cli_runtime_failure_removes_marker_and_never_installs(self):
        marker = self.prefix / 'share/termux-native-ubuntu/.installed'
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text(profile.BUNDLE_SHA256)
        with patch('sys.argv', ['profile', '--prefix', str(self.prefix), '--install', str(self.bundle)]), \
             patch.object(profile, 'runtime_probe', side_effect=ValueError('obsolete ICU')), \
             patch.object(profile, 'install') as install:
            with self.assertRaises(SystemExit) as failure:
                profile.main()
        self.assertEqual(65, failure.exception.code)
        self.assertFalse(marker.exists())
        install.assert_not_called()


if __name__ == '__main__':
    unittest.main()
