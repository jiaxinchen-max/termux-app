#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Install and validate the pinned Ubuntu appearance for native GNOME 48."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shlex
import shutil
import subprocess
import tarfile

BUNDLE_SHA256 = '5a767f2ef030ea401015e9c4afad6cfc582c203c6f4eb30e079ae6935474ff03'
EXTENSIONS = ('ubuntu-dock@ubuntu.com', 'appindicatorsupport@rgcjonas.gmail.com', 'ding@rastersoft.com')
MODE = 'termux-ubuntu'
MANIFEST = 'share/termux-native-ubuntu/manifest.json'
REQUIRED_FILES = (
    'share/gnome-shell/modes/termux-ubuntu.json',
    'share/gnome-shell/theme/Yaru/gnome-shell-theme.gresource',
    'share/gnome-shell/theme/Yaru/gnome-shell.css',
    'share/themes/Yaru/gtk-3.0/gtk.css', 'share/icons/Yaru/index.theme',
    'share/icons/Yaru/cursors/left_ptr',
    'share/backgrounds/termux-native-ubuntu/default.png',
)
RUNTIME_FILES = (
    'bin/gnome-shell', 'bin/gnome-extensions', 'bin/gjs', 'bin/gdbus',
    'bin/nautilus', 'bin/gnome-terminal', 'bin/gnome-text-editor',
    'bin/gnome-control-center', 'bin/gnome-calculator', 'bin/gnome-clocks', 'bin/eog',
    'share/applications/org.gnome.Settings.desktop',
    'share/applications/org.gnome.Calculator.desktop',
    'share/applications/org.gnome.clocks.desktop',
    'share/applications/org.gnome.eog.desktop',
    'share/dbus-1/services/org.gnome.Settings.service',
    'share/dbus-1/services/org.gnome.clocks.service',
    'lib/girepository-1.0/Gtk-3.0.typelib',
    'lib/girepository-1.0/GnomeDesktop-3.0.typelib',
    'share/applications/org.gnome.Nautilus.desktop',
    'share/applications/org.gnome.Terminal.desktop',
    'share/applications/org.gnome.TextEditor.desktop',
    'share/dbus-1/services/org.gnome.Terminal.service',
    'share/dbus-1/services/org.gnome.TextEditor.service',
)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def safe_destination(prefix, name):
    parts = PurePosixPath(name)
    if parts.is_absolute() or '..' in parts.parts or not parts.parts:
        raise ValueError('Unsafe native Ubuntu bundle path: ' + name)
    path = prefix / name
    if not path.resolve().is_relative_to(prefix.resolve()):
        raise ValueError('Native Ubuntu bundle path escapes PREFIX: ' + name)
    return path


def install(prefix, archive):
    if digest(archive) != BUNDLE_SHA256:
        raise ValueError('Native Ubuntu bundle SHA256 mismatch')
    # Validate the entire archive before writing. No links or executables outside
    # the exclusively managed appearance directories are accepted.
    with tarfile.open(archive, 'r:gz') as source:
        members = source.getmembers()
        regular = set()
        for member in members:
            safe_destination(prefix, member.name)
            if not (member.isdir() or member.isfile() or member.islnk()):
                raise ValueError('Only regular files, internal hardlinks and directories are supported')
            if member.islnk():
                safe_destination(prefix, member.linkname)
                if member.linkname not in regular:
                    raise ValueError('Hardlink must target an earlier regular file')
            elif member.isfile():
                regular.add(member.name)
            allowed = ('share/themes/Yaru/', 'share/themes/Yaru-dark/',
                       'share/icons/Yaru/', 'share/icons/Yaru-dark/',
                       'share/gnome-shell/theme/Yaru/', 'share/gnome-shell/theme/Yaru-dark/',
                       'share/termux-native-ubuntu/', 'share/backgrounds/termux-native-ubuntu/')
            specific = ('share/gnome-shell/modes/termux-ubuntu.json',)
            extensions = tuple('share/gnome-shell/extensions/' + uuid + '/' for uuid in EXTENSIONS)
            if not member.isdir() and member.name not in specific and not member.name.startswith(allowed + extensions):
                raise ValueError('Unexpected native Ubuntu bundle member: ' + member.name)
        (prefix / 'share/termux-native-ubuntu/.installed').unlink(missing_ok=True)
        for member in members:
            if member.isdir():
                continue
            path = safe_destination(prefix, member.name)
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_name(path.name + '.tmp.' + str(os.getpid()))
            try:
                if member.islnk():
                    # Android's Python omits os.link. Materialize the already
                    # validated earlier regular file; manifest hashes still apply.
                    shutil.copyfile(safe_destination(prefix, member.linkname), temporary)
                else:
                    with source.extractfile(member) as src, temporary.open('wb') as dst:
                        shutil.copyfileobj(src, dst)
                temporary.chmod(0o755 if member.mode & 0o111 else 0o644)
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
    validate(prefix)
    (prefix / 'share/termux-native-ubuntu/.installed').write_text(BUNDLE_SHA256 + '\n')


def validate(prefix):
    for name in REQUIRED_FILES + RUNTIME_FILES:
        if not (prefix / name).is_file():
            raise ValueError('Native Ubuntu required file missing: ' + name)
    mode = json.loads((prefix / REQUIRED_FILES[0]).read_text())
    if (mode.get('parentMode') != 'user' or mode.get('stylesheetName') != 'Yaru/gnome-shell.css'
            or mode.get('themeResourceName') != 'theme/Yaru/gnome-shell-theme.gresource'
            or mode.get('enabledExtensions') != list(EXTENSIONS)):
        raise ValueError('Native Ubuntu Shell mode does not match the pinned profile')
    for uuid in EXTENSIONS:
        directory = prefix / 'share/gnome-shell/extensions' / uuid
        metadata = json.loads((directory / 'metadata.json').read_text())
        if metadata.get('uuid') != uuid or '48' not in metadata.get('shell-version', []):
            raise ValueError('Native Ubuntu extension does not support GNOME 48: ' + uuid)
        if not (directory / 'extension.js').is_file():
            raise ValueError('Native Ubuntu extension code missing: ' + uuid)
    manifest = json.loads((prefix / MANIFEST).read_text())
    if manifest.get('profile') != 'native-ubuntu-gnome-48-r2':
        raise ValueError('Unknown native Ubuntu profile manifest')
    for name, expected in manifest['files'].items():
        path = safe_destination(prefix, name)
        if not path.is_file() or digest(path) != expected:
            raise ValueError('Native Ubuntu component corrupt or missing: ' + name)


def runtime_probe(prefix, env):
    env = dict(env, XDG_CURRENT_DESKTOP='ubuntu:GNOME')
    shell = subprocess.run([str(prefix / 'bin/gnome-shell'), '--version'], env=env,
                           capture_output=True, text=True, timeout=15)
    if shell.returncode or not shell.stdout.strip().startswith('GNOME Shell 48.'):
        raise ValueError('Native Ubuntu requires the pinned GNOME Shell 48 runtime: ' + shell.stdout + shell.stderr)
    probe = "imports.gi.versions.Gtk='3.0'; imports.gi.versions.GnomeDesktop='3.0'; imports.gi.Gtk; imports.gi.GnomeDesktop;"
    result = subprocess.run([str(prefix / 'bin/gjs'), '-c', probe], env=env,
                            capture_output=True, text=True, timeout=15)
    if result.returncode:
        raise ValueError('Native Ubuntu GJS/GTK/GnomeDesktop dependency probe failed: ' + result.stderr)

    # Apt/dpkg and file-presence checks cannot detect an obsolete ICU SONAME.
    # These options exit before opening a display or joining a desktop bus.
    for application in ('gnome-control-center', 'gnome-calculator', 'gnome-clocks', 'eog'):
        result = subprocess.run([str(prefix / 'bin' / application), '--version'], env=env,
                                capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise ValueError('Native Ubuntu application runtime probe failed: ' +
                             application + ': ' + result.stdout + result.stderr)


def session_setup(prefix):
    """Initialize the private profile once; leave later user choices alone."""
    wallpaper = (prefix / 'share/backgrounds/termux-native-ubuntu/default.png').as_uri()
    extensions = shlex.quote(str(list(EXTENSIONS)))
    return '''
profile_marker="$XDG_CONFIG_HOME/.termux-native-ubuntu-v1"
if [ ! -f "$profile_marker" ]; then
    gsettings set org.gnome.desktop.interface gtk-theme Yaru
    gsettings set org.gnome.desktop.interface icon-theme Yaru
    gsettings set org.gnome.desktop.interface cursor-theme Yaru
    gsettings set org.gnome.desktop.interface color-scheme prefer-dark
    gsettings set org.gnome.desktop.background picture-uri ''' + shlex.quote(wallpaper) + '''
    gsettings set org.gnome.desktop.background picture-uri-dark ''' + shlex.quote(wallpaper) + '''
    gsettings set org.gnome.desktop.background picture-options zoom
    gsettings set org.gnome.shell disable-user-extensions false
    gsettings set org.gnome.shell enabled-extensions ''' + extensions + '''
    gsettings set org.gnome.shell favorite-apps "['org.gnome.Nautilus.desktop', 'org.gnome.Terminal.desktop', 'org.gnome.TextEditor.desktop', 'org.gnome.Settings.desktop']"
    gsettings --schemadir "''' + str(prefix) + '''/share/gnome-shell/extensions/ubuntu-dock@ubuntu.com/schemas" set org.gnome.shell.extensions.dash-to-dock dock-position LEFT
    gsettings --schemadir "''' + str(prefix) + '''/share/gnome-shell/extensions/ubuntu-dock@ubuntu.com/schemas" set org.gnome.shell.extensions.dash-to-dock extend-height true
    gsettings --schemadir "''' + str(prefix) + '''/share/gnome-shell/extensions/ubuntu-dock@ubuntu.com/schemas" set org.gnome.shell.extensions.dash-to-dock dash-max-icon-size 44
    gsettings --schemadir "''' + str(prefix) + '''/share/gnome-shell/extensions/ubuntu-dock@ubuntu.com/schemas" set org.gnome.shell.extensions.dash-to-dock show-trash false
    gsettings --schemadir "''' + str(prefix) + '''/share/gnome-shell/extensions/ding@rastersoft.com/schemas" set org.gnome.shell.extensions.ding show-home true
    mkdir -p "$HOME/Desktop"
    printf 'XDG_DESKTOP_DIR="$HOME/Desktop"\\n' > "$XDG_CONFIG_HOME/user-dirs.dirs"
    touch "$profile_marker"
fi
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', type=Path, required=True)
    parser.add_argument('--install', type=Path)
    args = parser.parse_args()
    try:
        if args.install:
            (args.prefix / 'share/termux-native-ubuntu/.installed').unlink(missing_ok=True)
            runtime_probe(args.prefix, os.environ.copy())
            install(args.prefix, args.install)
        else:
            validate(args.prefix)
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, subprocess.TimeoutExpired) as error:
        parser.exit(65, str(error) + '\n')


if __name__ == '__main__':
    main()
