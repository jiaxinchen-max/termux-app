#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Ubuntu noble GNOME profile validation shared by installation and launch."""
import argparse
import hashlib
import json
import os
from pathlib import Path

DING_SOURCE_SHA256 = 'bdc6c9460d4688e0b410417e664a715c409d5c9591bd8227adc544fdcde5b25f'
DING_PATCHED_SHA256 = '06b32faba87947ab1d1c003cda03670418d7092cb952e92bb63eb379b7e29697'
DING_SOURCE = 'usr/share/gnome-shell/extensions/ding@rastersoft.com/extension.js'

EXTENSIONS = ('ubuntu-dock@ubuntu.com', 'ubuntu-appindicators@ubuntu.com',
              'ding@rastersoft.com', 'tiling-assistant@ubuntu.com')
REQUIRED_FILES = (
    'usr/bin/gnome-shell', 'usr/bin/gnome-extensions', 'usr/bin/gdbus',
    'usr/bin/nautilus', 'usr/bin/gnome-terminal', 'usr/bin/gnome-text-editor',
    'usr/bin/gnome-control-center', 'usr/bin/gnome-calculator', 'usr/bin/gnome-clocks', 'usr/bin/eog',
    'usr/share/applications/org.gnome.Settings.desktop',
    'usr/share/applications/org.gnome.Calculator.desktop',
    'usr/share/applications/org.gnome.clocks.desktop',
    'usr/share/applications/org.gnome.eog.desktop',
    'usr/share/dbus-1/services/org.gnome.Settings.service',
    'usr/share/dbus-1/services/org.gnome.clocks.service',
    'usr/libexec/gsd-xsettings',
    'usr/share/gnome-session/sessions/ubuntu.session',
    'usr/share/xsessions/ubuntu-xorg.desktop',
    'usr/share/gnome-shell/modes/ubuntu.json',
    'usr/share/gnome-shell/theme/Yaru/gnome-shell.css',
    'usr/share/gnome-shell/theme/Yaru/gnome-shell-theme.gresource',
    'usr/share/gnome-shell/theme/Yaru/gnome-shell-icons.gresource',
    'usr/share/themes/Yaru/gtk-3.0/gtk.css', 'usr/share/icons/Yaru/index.theme',
    'usr/share/sounds/Yaru/index.theme',
    'usr/share/backgrounds/warty-final-ubuntu.png',
    'usr/share/glib-2.0/schemas/10_ubuntu-settings.gschema.override',
    'usr/share/glib-2.0/schemas/gschemas.compiled',
    'usr/share/dbus-1/services/ca.desrt.dconf.service',
    'usr/share/dbus-1/services/org.gtk.vfs.Daemon.service',
    'usr/share/dbus-1/services/org.gnome.Nautilus.service',
    'usr/share/dbus-1/services/org.gnome.Terminal.service',
    'usr/share/dbus-1/services/org.gnome.TextEditor.service',
)


def validate_rootfs(root, require_patched=True):
    root = Path(root)
    release = {}
    for line in (root / 'etc/os-release').read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            release[key] = value.strip('\"\'')
    if (release.get('ID'), release.get('VERSION_CODENAME')) != ('ubuntu', 'noble'):
        raise ValueError('Ubuntu GNOME requires ubuntu:noble; got ' +
                         str(release.get('ID')) + ':' + str(release.get('VERSION_CODENAME')))
    for relative in REQUIRED_FILES:
        if not (root / relative).is_file():
            raise ValueError('Required Ubuntu GNOME file is missing: ' + relative)
    mode = json.loads((root / 'usr/share/gnome-shell/modes/ubuntu.json').read_text())
    if mode.get('parentMode') != 'user' or not mode.get('stylesheetName', '').startswith('Yaru/'):
        raise ValueError('Ubuntu Shell mode must inherit user and select Yaru')
    for uuid in EXTENSIONS:
        extension = root / 'usr/share/gnome-shell/extensions' / uuid
        metadata = json.loads((extension / 'metadata.json').read_text())
        if metadata.get('uuid') != uuid or '46' not in metadata.get('shell-version', []):
            raise ValueError('Ubuntu GNOME 46 extension metadata mismatch: ' + uuid)
        if not (extension / 'extension.js').is_file():
            raise ValueError('Ubuntu extension implementation is missing: ' + uuid)
    desktop = (root / 'usr/share/xsessions/ubuntu-xorg.desktop').read_text()
    if 'GNOME_SHELL_SESSION_MODE=ubuntu' not in desktop or '--session=ubuntu' not in desktop:
        raise ValueError('Ubuntu Xorg session does not select Ubuntu mode/session')
    if require_patched and hashlib.sha256((root / DING_SOURCE).read_bytes()).hexdigest() != DING_PATCHED_SHA256:
        raise ValueError('Ubuntu DING session cleanup patch is missing or corrupt; reinstall the desktop')


def prepare_rootfs(root):
    root = Path(root)
    validate_rootfs(root, require_patched=False)
    path = root / DING_SOURCE
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Ubuntu DING source escapes the selected rootfs')
    original = path.read_bytes()
    digest = hashlib.sha256(original).hexdigest()
    if digest == DING_PATCHED_SHA256:
        return
    if digest != DING_SOURCE_SHA256:
        raise ValueError('Unsupported Ubuntu DING source SHA256; refusing to patch unknown code')
    text = original.decode('utf-8')
    start = text.index('    doKillAllOldDesktopProcesses() {')
    end = text.index('\n    /**', start)
    patched = text[:start] + '''    doKillAllOldDesktopProcesses() {
        // The owning desktop session cleans its DING subprocess groups.
        // Android denies unrelated /proc reads; never scan or kill them.
    }
''' + text[end:]
    data = patched.encode('utf-8')
    if hashlib.sha256(data).hexdigest() != DING_PATCHED_SHA256:
        raise ValueError('Ubuntu DING patch result SHA256 mismatch')
    temporary = path.with_name(path.name + '.tmp.' + str(os.getpid()))
    try:
        temporary.write_bytes(data)
        temporary.chmod(path.stat().st_mode & 0o777)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def session_setup():
    # Initialize a fresh managed session only; preserve subsequent user customization.
    # Replace Ubuntu's default Snap favorites with the apt applications actually installed.
    return '''
if [ ! -f "$XDG_CONFIG_HOME/.termux-ubuntu-profile-v1" ]; then
    gsettings set org.gnome.desktop.interface gtk-theme Yaru
    gsettings set org.gnome.desktop.interface icon-theme Yaru
    gsettings set org.gnome.desktop.interface cursor-theme Yaru
    gsettings set org.gnome.desktop.sound theme-name Yaru
    gsettings set org.gnome.desktop.background picture-uri file:///usr/share/backgrounds/warty-final-ubuntu.png
    gsettings set org.gnome.shell favorite-apps "['org.gnome.Nautilus.desktop', 'org.gnome.Terminal.desktop', 'org.gnome.TextEditor.desktop', 'org.gnome.Settings.desktop']"
    gsettings set org.gnome.shell disable-user-extensions false
    gsettings set org.gnome.shell enabled-extensions "['ubuntu-dock@ubuntu.com', 'ubuntu-appindicators@ubuntu.com', 'ding@rastersoft.com', 'tiling-assistant@ubuntu.com']"
    gsettings set org.gnome.shell.extensions.ding show-home true
    mkdir -p "$HOME/Desktop"
    if [ ! -f "$XDG_CONFIG_HOME/user-dirs.dirs" ]; then
        printf '%s\\n' 'XDG_DESKTOP_DIR="$HOME/Desktop"' > "$XDG_CONFIG_HOME/user-dirs.dirs"
    fi
    touch "$XDG_CONFIG_HOME/.termux-ubuntu-profile-v1"
fi
'''


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', type=Path, required=True)
    parser.add_argument('--prepare', action='store_true', help='Apply the fixed session cleanup patch after apt')
    args = parser.parse_args()
    try:
        if args.prepare:
            prepare_rootfs(args.rootfs)
        validate_rootfs(args.rootfs)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(65, 'Ubuntu GNOME validation failed: ' + str(error) + '\n')
    print('Ubuntu noble session, Yaru and extension files verified')
