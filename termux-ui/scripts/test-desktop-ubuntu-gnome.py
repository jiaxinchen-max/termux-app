#!/usr/bin/env python3
"""Ubuntu profile and real installer control flow; external apt/network are recorded fixtures."""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('ubuntu_profile', ASSETS / 'desktop_ubuntu_gnome.py')
DING_FIXTURE = Path(__file__).with_name('fixtures') / 'ubuntu-ding47-extension.js'
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


def rootfs_fixture(root):
    (root / 'etc').mkdir(parents=True)
    (root / 'etc/os-release').write_text('ID="ubuntu"\nVERSION_CODENAME=noble\n')
    for relative in profile.REQUIRED_FILES:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    (root / 'usr/share/gnome-shell/modes/ubuntu.json').write_text(json.dumps({
        'parentMode': 'user', 'stylesheetName': 'Yaru/gnome-shell.css'}))
    (root / 'usr/share/xsessions/ubuntu-xorg.desktop').write_text(
        'Exec=env GNOME_SHELL_SESSION_MODE=ubuntu gnome-session --session=ubuntu\n')
    for uuid in profile.EXTENSIONS:
        folder = root / 'usr/share/gnome-shell/extensions' / uuid
        folder.mkdir(parents=True)
        (folder / 'metadata.json').write_text(json.dumps({'uuid': uuid, 'shell-version': ['46']}))
        (folder / 'extension.js').touch()
    (root / profile.DING_SOURCE).write_bytes(DING_FIXTURE.read_bytes())
    profile.prepare_rootfs(root)


class UbuntuProfileTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='ubuntu-profile-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        rootfs_fixture(self.root)

    def test_valid_official_profile_layout(self):
        profile.validate_rootfs(self.root)

    def test_each_required_file_is_checked(self):
        for relative in profile.REQUIRED_FILES:
            file = self.root / relative
            content = file.read_bytes()
            file.unlink()
            with self.subTest(relative=relative), self.assertRaises((OSError, ValueError)):
                profile.validate_rootfs(self.root)
            file.write_bytes(content)

    def test_debian_or_wrong_ubuntu_release_rejected(self):
        for text in ('ID=debian\nVERSION_CODENAME=noble', 'ID=ubuntu\nVERSION_CODENAME=jammy'):
            (self.root / 'etc/os-release').write_text(text)
            with self.assertRaisesRegex(ValueError, 'requires ubuntu:noble'):
                profile.validate_rootfs(self.root)

    def test_extensions_must_match_uuid_and_shell_version(self):
        file = self.root / 'usr/share/gnome-shell/extensions/ubuntu-dock@ubuntu.com/metadata.json'
        for metadata in ({'uuid': 'wrong', 'shell-version': ['46']},
                         {'uuid': 'ubuntu-dock@ubuntu.com', 'shell-version': ['48']}):
            file.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
                profile.validate_rootfs(self.root)

    def test_plain_gnome_mode_is_rejected(self):
        (self.root / 'usr/share/gnome-shell/modes/ubuntu.json').write_text(
            json.dumps({'parentMode': 'user', 'stylesheetName': 'gnome-shell.css'}))
        with self.assertRaisesRegex(ValueError, 'select Yaru'):
            profile.validate_rootfs(self.root)

    def test_session_initialization_preserves_user_customizations_on_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            bin_dir = base / 'bin'; bin_dir.mkdir()
            trace = base / 'calls'
            command = bin_dir / 'gsettings'
            command.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$TRACE"\n')
            command.chmod(0o755)
            config = base / 'config'; config.mkdir()
            env = dict(os.environ, PATH=str(bin_dir)+':'+os.environ['PATH'],
                       HOME=str(base), XDG_CONFIG_HOME=str(config), TRACE=str(trace))
            for _ in range(2):
                subprocess.run(['sh', '-ec', profile.session_setup()], env=env, check=True)
                if _ == 0:
                    original = trace.read_text()
            self.assertEqual(original, trace.read_text())
            self.assertIn('gtk-theme Yaru', original)
            self.assertIn('ubuntu-dock@ubuntu.com', original)
            self.assertNotIn('snap-store', original)

    def test_known_ding_source_is_patched_once_and_unknown_source_is_rejected(self):
        import hashlib
        path = self.root / profile.DING_SOURCE
        original = DING_FIXTURE.read_bytes()
        self.assertEqual(profile.DING_SOURCE_SHA256, hashlib.sha256(original).hexdigest())
        path.write_bytes(original)
        with self.assertRaisesRegex(ValueError, 'cleanup patch'):
            profile.validate_rootfs(self.root)
        profile.prepare_rootfs(self.root)
        patched = path.read_bytes()
        self.assertEqual(profile.DING_PATCHED_SHA256, hashlib.sha256(patched).hexdigest())
        self.assertNotIn(b"Gio.File.new_for_path('/proc')", patched)
        self.assertIn(b'this.doKillAllOldDesktopProcesses();', patched)
        profile.prepare_rootfs(self.root)
        self.assertEqual(patched, path.read_bytes())
        unknown = original + b'// unknown revision\n'
        path.write_bytes(unknown)
        with self.assertRaisesRegex(ValueError, 'Unsupported Ubuntu DING'):
            profile.prepare_rootfs(self.root)
        self.assertEqual(unknown, path.read_bytes())
        self.assertFalse(list(path.parent.glob('*.tmp.*')))

    def test_ding_source_outside_rootfs_is_rejected(self):
        import tempfile
        path = self.root / profile.DING_SOURCE
        with tempfile.TemporaryDirectory() as directory:
            outside = Path(directory) / 'extension.js'
            outside.write_bytes(DING_FIXTURE.read_bytes())
            path.unlink()
            path.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, 'escapes'):
                profile.prepare_rootfs(self.root)
            self.assertEqual(DING_FIXTURE.read_bytes(), outside.read_bytes())


class UbuntuInstallTests(unittest.TestCase):
    def install(self, failure='', patch=None, operation='install', existing=False):
        with tempfile.TemporaryDirectory(prefix='ubuntu-install-') as directory:
            base = Path(directory)
            prefix = base / 'usr'; (prefix / 'bin').mkdir(parents=True); (prefix / 'tmp').mkdir()
            (prefix / 'bin/python3').symlink_to(sys.executable)
            uname = prefix / 'bin/uname'; uname.write_text('#!/bin/sh\nprintf aarch64\n'); uname.chmod(0o755)
            scripts = base / 'scripts'; scripts.mkdir()
            for name in ('desktop_common.sh', 'desktop_rendering.sh', 'desktop_gnome_launch.py',
                         'desktop_ubuntu_gnome.py', 'desktop_usb_storage.py', 'desktop_container_plasma_apt.sh',
                         'desktop_container_statx_setup.sh', 'desktop_container_run_config.sh', 'desktop_container_statx_aarch64.bin'):
                shutil.copy(ASSETS / name, scripts / name)
            container = prefix / 'var/lib/proot-distro/containers/ubuntu-ubuntu-gnome-1'
            rootfs = container / 'rootfs'; rootfs_fixture(rootfs)
            marker = container / '.desktop-de-ubuntu_gnome'
            if existing:
                marker.touch()
            if failure == 'files':
                (rootfs / 'usr/share/gnome-shell/theme/Yaru/gnome-shell.css').unlink()
            if failure == 'ding':
                (rootfs / profile.DING_SOURCE).write_bytes(DING_FIXTURE.read_bytes() + b'// unknown revision\n')
            if failure == 'base':
                (rootfs / 'etc/os-release').write_text('ID=debian\nVERSION_CODENAME=trixie\n')
            mocks = '''
run() {
    printf 'RUN %s\\n' "$*" >> "$FIXTURE_TRACE"
    if [ "$1" = python3 ]; then "$@"; return $?; fi
    case "$*" in *"proot-distro login"*) [ "$FIXTURE_FAIL" != apt ] || return 37 ;; esac
    return 0
}
fetch_debs() { printf 'FETCH %s\\n' "$1 $3" >> "$FIXTURE_TRACE"; printf '%s' "$PREFIX/tmp/xlorie.deb"; }
ensure_container_instance() { printf 'IMAGE %s\\n' "$2" >> "$FIXTURE_TRACE"; }
pinned_package_valid() { return 0; }
dpkg() { return 0; }
'''
            source = (ASSETS / 'desktop_install.sh').read_text()
            boundary = 'log "Installing $DESKTOP_LABEL desktop packages ($DESKTOP_MODE mode)"'
            source = source.replace(boundary, mocks+'\n'+boundary)
            script = scripts / 'desktop_install.sh'; script.write_text(source)
            values = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_MODE='proot', DESKTOP_DE='ubuntu_gnome',
                          DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='llvmpipe', DESKTOP_USB_STORAGE='off',
                          DESKTOP_PROOT_DISTRO='ubuntu-ubuntu-gnome-1', DESKTOP_CONTAINER_BASE='ubuntu:noble',
                          DESKTOP_CONTAINER_CODENAME='noble', DESKTOP_GNOME_SESSION_PROFILE='ubuntu',
                          DESKTOP_GNOME_BACKEND='x11', DESKTOP_GNOME_RENDERER='llvmpipe', DESKTOP_GNOME_SESSION_STARTUP='direct',
                          DESKTOP_GNOME_DISPLAY_GL_MODE='system', DESKTOP_GNOME_DISPLAY_BUFFER_MODE='native',
                          DESKTOP_RELEASE_TAG='termux-hwaccel-6.7-r4', DESKTOP_LOG_FILE=str(base/'log'),
                          DESKTOP_STATUS_FILE=str(base/'status'), DESKTOP_PID_FILE=str(base/'pid'), DESKTOP_REFRESH_RATE='60')
            values.update(patch or {})
            file = base / 'spec'; file.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in values.items()))
            command = ['sh', str(script), str(file), '--operation='+operation]
            if operation == 'host-prerequisites':
                command += ['--task-id=ubuntu', '--result-file='+str(base/'result')]
            trace = base/'trace'
            result = subprocess.run(command, capture_output=True, text=True, timeout=10,
                                    env=dict(os.environ, PREFIX=str(prefix), HOME=str(base/'home'),
                                             FIXTURE_TRACE=str(trace), FIXTURE_FAIL=failure))
            return result.returncode, result.stdout+result.stderr, trace.read_text() if trace.exists() else '', marker.exists()

    def test_install_routes_ubuntu_and_only_host_xlorie_components(self):
        code, output, trace, marker = self.install()
        self.assertEqual(0, code, output)
        self.assertTrue(marker)
        self.assertIn('IMAGE ubuntu:noble', trace)
        self.assertIn('ubuntu-session ubuntu-settings gnome-shell', trace)
        self.assertIn('--no-install-recommends --reinstall', trace)
        self.assertIn('gnome-shell-extension-ubuntu-dock', trace)
        self.assertIn('fonts-ubuntu ', trace)
        self.assertNotIn('fonts-ubuntu-classic', trace)
        self.assertIn('gnome-control-center gnome-calculator gnome-clocks eog', trace)
        self.assertIn('termux-xlorie-21.1.16-r9', trace)
        for forbidden in ('mesa-for-android-container', 'desktop_install_gio.py', 'desktop_install_mesa.py',
                          'gnome-shell_48', 'kwin_6.6', 'snapd', 'gdm3'):
            self.assertNotIn(forbidden, trace)

    def test_install_failure_preserves_status_and_never_marks_ready(self):
        for failure, expected in [('apt', 37), ('files', 65), ('base', 1)]:
            with self.subTest(failure=failure):
                code, output, trace, marker = self.install(failure=failure)
                self.assertEqual(expected, code, output)
                self.assertFalse(marker)

    def test_unknown_ding_source_removes_existing_success_marker(self):
        code, output, trace, marker = self.install(existing=True, failure='ding')
        self.assertEqual(65, code, output)
        self.assertFalse(marker)
        self.assertIn('Unsupported Ubuntu DING source', output)

    def test_existing_complete_install_skips_guest_packages_and_rechecks_files(self):
        code, output, trace, marker = self.install(existing=True)
        self.assertEqual(0, code, output)
        self.assertTrue(marker)
        self.assertNotIn('proot-distro login', trace)
        self.assertIn('desktop_ubuntu_gnome.py', trace)

    def test_incomplete_existing_marker_is_removed(self):
        code, output, trace, marker = self.install(existing=True, failure='files')
        self.assertEqual(65, code, output)
        self.assertFalse(marker)
        self.assertIn('proot-distro login', trace)

    def test_wrong_rootfs_does_not_stop_an_existing_desktop(self):
        with tempfile.TemporaryDirectory(prefix='ubuntu-preflight-') as directory:
            base = Path(directory); prefix = base/'usr'; (prefix/'bin').mkdir(parents=True)
            (prefix/'bin/python3').symlink_to(sys.executable)
            state = base/'state'; state.mkdir()
            other = subprocess.Popen(['sleep', '60'])
            try:
                (state/'other.pid').write_text(str(other.pid))
                values = dict(DESKTOP_SPEC_VERSION='3', DESKTOP_ID='new', DESKTOP_MODE='proot',
                    DESKTOP_DE='ubuntu_gnome', DESKTOP_HOST_GL_MODE='system', DESKTOP_GUEST_GL_MODE='llvmpipe',
                    DESKTOP_PROOT_DISTRO='ubuntu-ubuntu-gnome-1', DESKTOP_USB_STORAGE='off',
                    DESKTOP_CONTAINER_BASE='ubuntu:noble', DESKTOP_CONTAINER_CODENAME='noble',
                    DESKTOP_GNOME_SESSION_PROFILE='ubuntu', DESKTOP_GNOME_BACKEND='x11',
                    DESKTOP_GNOME_RENDERER='llvmpipe', DESKTOP_GNOME_SESSION_STARTUP='direct',
                    DESKTOP_GNOME_DISPLAY_GL_MODE='system', DESKTOP_GNOME_DISPLAY_BUFFER_MODE='native',
                    DESKTOP_PID_FILE=str(state/'new.pid'), DESKTOP_LOG_FILE=str(base/'log'),
                    DESKTOP_STATUS_FILE=str(state/'new.status'))
                file = base/'spec'; file.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in values.items()))
                result = subprocess.run(['sh', str(ASSETS/'desktop_start.sh'), str(file)],
                    env=dict(os.environ, PREFIX=str(prefix), HOME=str(base/'home')), capture_output=True, text=True, timeout=5)
                self.assertEqual(65, result.returncode, result.stdout+result.stderr)
                self.assertIsNone(other.poll())
                self.assertEqual('exit:65\n', (state/'new.status').read_text())
            finally:
                other.terminate(); other.wait(timeout=3)

    def test_unsupported_native_gpu_usb_and_archive_fail_before_install(self):
        for patch, operation in [({'DESKTOP_MODE': 'native'}, 'install'),
                                  ({'DESKTOP_GUEST_GL_MODE': 'zink'}, 'install'),
                                  ({'DESKTOP_GNOME_SESSION_PROFILE': 'gnome'}, 'install'),
                                  ({'DESKTOP_GNOME_RENDERER': 'zink'}, 'install'),
                                  ({'DESKTOP_USB_STORAGE': 'read-only'}, 'install'),
                                  ({'DESKTOP_CONTAINER_BASE': 'debian:trixie'}, 'install'),
                                  ({}, 'host-prerequisites')]:
            with self.subTest(patch=patch, operation=operation):
                code, output, trace, marker = self.install(patch=patch, operation=operation)
                self.assertEqual(64, code, output)
                self.assertFalse(marker)
                self.assertEqual('', trace)


if __name__ == '__main__':
    unittest.main(verbosity=2)
