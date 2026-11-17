#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Prepare and own an Ubuntu Hyprland client desktop; no host application index."""
import argparse
import json
import os
from pathlib import Path
import re
import runpy
import shlex
import shutil
import signal
import socket
import subprocess
import time

LIB = Path('/usr/local/lib/termux-hyprland')
RUNTIME = Path('/tmp/hyprland-runtime')
GUEST_HOME = Path('/home/termux')
SCRIPTS = ('desktop_hyprland_guest.py', 'desktop_hyprland_theme.py',
           'desktop_hyprland_shell.py', 'desktop_hyprland_components.py', 'hyprland-components.json')
COMMON_PACKAGES = 'python3 python3-gi gir1.2-gtk-3.0 gir1.2-gtklayershell-0.1 swaybg xfce4-notifyd librsvg2-common xdg-terminal-exec'
THEME_PACKAGES = {'classic-r7': 'xfce4-panel xfce4-genmon-plugin',
                  'm3ww-v1': 'foot fish starship fontconfig'}


def guest_work(desktop_id):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', desktop_id):
        raise ValueError('Invalid desktop ID')
    return GUEST_HOME / '.local/share/termux-hyprland' / desktop_id


def validate_rootfs(root, theme):
    if theme not in THEME_PACKAGES:
        raise ValueError('Unknown container desktop preset')
    required = ['usr/bin/python3', 'usr/bin/dbus-daemon', 'usr/bin/xfce4-terminal',
                'usr/bin/thunar', 'usr/bin/swaybg']
    required += (['usr/bin/xfce4-panel', 'usr/bin/xfconf-query'] if theme == 'classic-r7' else
                 ['usr/bin/foot', 'usr/bin/fish', 'usr/bin/starship', str(LIB / 'bin/eww').lstrip('/')])
    for name in required:
        p = root / name
        if not p.is_file() or not os.access(p, os.X_OK):
            raise RuntimeError('Container desktop executable missing: /' + name + '; install the selected preset')
    if not list((root / 'usr').glob('lib/**/xfce4/notifyd/xfce4-notifyd')):
        raise RuntimeError('Container notification service missing')


def prepare(root, work, desktop_id, theme, components):
    """Host save/install only. Copy trusted assets, then prepare as the guest user."""
    validate_rootfs(root, theme)
    target = root / str(LIB).lstrip('/')
    if target.is_symlink() or not target.resolve().is_relative_to(root.resolve()):
        raise RuntimeError('Unsafe guest desktop library directory')
    target.mkdir(parents=True, exist_ok=True)
    for name in SCRIPTS:
        destination = target / name
        if destination.is_symlink():
            raise RuntimeError('Unsafe guest desktop asset: ' + str(destination))
        shutil.copyfile(Path(__file__).with_name(name), destination)
        destination.chmod(0o644)
    # The same immutable archive is installed INTO Ubuntu; it is not a host UI dependency.
    namespace = components['theme_root'].__globals__
    original_prefix = namespace['PREFIX']
    try:
        namespace['PREFIX'] = root / 'usr'
        components['install_theme'](None, theme)
    finally:
        namespace['PREFIX'] = original_prefix
    return guest_work(desktop_id)


def environment(work):
    return dict(os.environ, PREFIX='/usr', HOME=str(GUEST_HOME),
                PATH=str(LIB / 'bin') + ':/usr/local/bin:/usr/bin:/bin',
                TERMUX_HYPRLAND_GUEST='1', TERMUX_HYPRLAND_EWW=str(LIB / 'bin/eww'),
                TERMUX_HYPRLAND_BATTERY=str(RUNTIME / 'android-battery.json'),
                XDG_CONFIG_HOME=str(work / 'config'), XDG_DATA_HOME=str(work / 'data'),
                XDG_CACHE_HOME=str(work / 'cache'), XDG_DATA_DIRS=str(GUEST_HOME / '.local/share') + ':/usr/local/share:/usr/share',
                XDG_RUNTIME_DIR=str(RUNTIME),
                XDG_CURRENT_DESKTOP='Hyprland', XDG_SESSION_TYPE='wayland',
                DBUS_SESSION_BUS_ADDRESS='unix:path=' + str(RUNTIME / 'guest-session-bus'),
                DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + str(RUNTIME / 'system-bus'))


def ipc(*args):
    signature = os.environ.get('HYPRLAND_INSTANCE_SIGNATURE', '')
    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,160}', signature):
        raise RuntimeError('Invalid Hyprland IPC identity')
    flags = 'j' if args and args[0] == '-j' else ''
    words = args[1:] if flags else args
    if not words or words[0] not in ('clients', 'activewindow', 'activeworkspace', 'monitors', 'layers', 'dispatch'):
        raise ValueError('Unsupported desktop IPC operation')
    request = (flags + '/' + ' '.join(words)).encode()
    if len(request) > 8192 or b'\0' in request or b'\n' in request:
        raise ValueError('Invalid desktop IPC request')
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(4)
        connection.connect(str(RUNTIME / 'hypr' / signature / '.socket.sock'))
        connection.sendall(request)
        chunks = []; size = 0
        while True:
            chunk = connection.recv(65536)
            if not chunk:
                break
            size += len(chunk)
            if size > 8 * 1024 * 1024:
                raise RuntimeError('Desktop IPC response exceeds limit')
            chunks.append(chunk)
        return b''.join(chunks).decode()


def app_visible(app):
    from gi.repository import Gio
    executable = app.get_executable()
    if not app.get_id() or not app.should_show():
        return False
    # A valid D-Bus application may intentionally have no Exec fallback.
    if app.get_boolean('DBusActivatable') and Gio.Application.id_is_valid(app.get_id()[:-8]):
        return True
    return bool(executable) and shutil.which(executable) is not None


def apps():
    from gi.repository import Gio, GLib
    for directory in (GLib.get_user_data_dir(), *GLib.get_system_data_dirs()):
        try:
            with os.scandir(Path(directory) / 'applications'):
                pass
        except FileNotFoundError:
            continue  # Optional XDG directories need not exist.
        except OSError as error:
            raise RuntimeError('Application directory unavailable: ' + directory) from error
    result = []
    for app in Gio.AppInfo.get_all():
        if isinstance(app, Gio.DesktopAppInfo) and app_visible(app):
            result.append(dict(name=app.get_display_name(), description=app.get_description() or 'Application',
                               icon=app.get_icon(), desktop_id=app.get_id()))
    return sorted(result, key=lambda item: (item['name'].casefold(), item['desktop_id']))


def launch(desktop_id):
    from gi.repository import Gio
    if (not isinstance(desktop_id, str) or len(desktop_id) > 512
            or not desktop_id.endswith('.desktop') or '/' in desktop_id
            or any(ord(ch) < 32 for ch in desktop_id)):
        raise ValueError('Invalid desktop application ID')
    try:
        app = Gio.DesktopAppInfo.new(desktop_id)
    except TypeError:
        app = None  # PyGObject raises instead of returning None for a NULL constructor.
    if app is None or not app_visible(app):
        raise RuntimeError('Application is no longer available: ' + desktop_id)
    if not app.launch([], None):
        raise RuntimeError('Application did not launch: ' + desktop_id)


def prepare_user(work, theme):
    """Guest save/install only, with Ubuntu libraries and guest paths."""
    os.environ.update(environment(work))
    if work.is_symlink() or not work.resolve().is_relative_to(GUEST_HOME.resolve()):
        raise RuntimeError('Unsafe container desktop directory')
    work.mkdir(mode=0o700, parents=True, exist_ok=True)
    work.chmod(0o700)
    for name in ('bin', 'config', 'data', 'cache'):
        if (work / name).is_symlink():
            raise RuntimeError('Unsafe container desktop directory: ' + name)
        (work / name).mkdir(mode=0o700, parents=True, exist_ok=True)
    for action, program in [('terminal', ['xfce4-terminal', '--disable-server']), ('files', ['thunar'])]:
        wrapper = work / 'bin' / ('termux-hyprland-' + action)
        wrapper.write_text('#!/bin/sh\nexec ' + shlex.join(program) + ' "$@"\n')
        wrapper.chmod(0o700)
    scripts = Path(__file__).parent
    commands = runpy.run_path(str(scripts / 'desktop_hyprland_shell.py'))['prepare'](work, 'guest', theme)
    # Fail while saving, not after desktop startup, when a typelib is missing.
    import gi
    gi.require_version('Gtk', '3.0'); gi.require_version('GtkLayerShell', '0.1')
    from gi.repository import Gtk, GtkLayerShell  # noqa: F401
    (work / 'prepared-guest.json').write_text(json.dumps(dict(schema=1, theme=theme, commands=commands)) + '\n')
    return 0


def bus_has_owner(name):
    result = subprocess.run(['dbus-send', '--session', '--print-reply',
        '--dest=org.freedesktop.DBus', '/org/freedesktop/DBus',
        'org.freedesktop.DBus.NameHasOwner', 'string:' + name],
        capture_output=True, text=True, timeout=3)
    return result.returncode == 0 and 'boolean true' in result.stdout


def run(work):
    prepared = json.loads((work / 'prepared-guest.json').read_text())
    if prepared['schema'] != 1:
        raise RuntimeError('Container session is not prepared; save desktop settings')
    os.environ.update(environment(work))
    token = os.environ['TERMUX_HYPRLAND_SESSION']
    marker = RUNTIME / 'guest-desktop.ready'
    marker.unlink(missing_ok=True)
    children = []
    def stop(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop); signal.signal(signal.SIGINT, stop)
    try:
        bus = subprocess.Popen(['dbus-daemon', '--session', '--nofork', '--nopidfile',
                                '--address=' + os.environ['DBUS_SESSION_BUS_ADDRESS']])
        children.append(bus)
        deadline = time.monotonic() + 10
        while not (RUNTIME / 'guest-session-bus').is_socket():
            if bus.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('Container desktop session D-Bus failed')
            time.sleep(.05)
        state = dict(supervisor=os.getpid(), token=token, env=dict(os.environ))
        (work / 'session.json').write_text(json.dumps(state) + '\n')
        for command in prepared['commands']:
            children.append(subprocess.Popen(command))
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if any(child.poll() is not None for child in children):
                raise RuntimeError('Container desktop component exited before readiness')
            layers = json.loads(ipc('-j', 'layers'))
            names = {layer.get('namespace') for output in layers.values()
                     for level in output.get('levels', {}).values() for layer in level}
            # Ubuntu's unpatched Xfce panel uses GtkLayerShell's default namespace.
            # Also require its bus owner so another GTK layer cannot satisfy readiness.
            themed = prepared['theme'] == 'm3ww-v1'
            panel = 'm3ww-bar' if themed else 'gtk-layer-shell'
            panel_bus = themed or bus_has_owner('org.xfce.Panel')
            if {panel, 'wallpaper'} <= names and panel_bus and bus_has_owner('org.freedesktop.Notifications'):
                marker.write_text(token + '\n')
                break
            time.sleep(.15)
        else:
            raise RuntimeError('Container panel/background/notifications did not become ready')
        while all(child.poll() is None for child in children):
            time.sleep(.2)
        raise RuntimeError('Container desktop service exited')
    except KeyboardInterrupt:
        return 0
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        marker.unlink(missing_ok=True)
        (work / 'session.json').unlink(missing_ok=True)
        # Stop UI before its session bus. The outer owner stops USB before signaling us.
        for child in reversed(children):
            if child.poll() is None:
                child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--desktop-id', required=True)
    parser.add_argument('action', choices=('prepare', 'session', 'terminal', 'files', 'launcher'))
    parser.add_argument('--theme', choices=tuple(THEME_PACKAGES))
    args = parser.parse_args()
    work = guest_work(args.desktop_id)
    if os.getuid() == 0:
        raise RuntimeError('Container desktop must run as termux, not root')
    if args.action == 'prepare':
        return prepare_user(work, args.theme)
    if args.action == 'session':
        return run(work)
    os.environ.update(environment(work))
    state = json.loads((work / 'session.json').read_text())
    if state['token'] != os.environ.get('TERMUX_HYPRLAND_SESSION'):
        raise RuntimeError('Container desktop session identity changed')
    os.kill(state['supervisor'], 0)
    os.environ.update(state['env'])
    if args.action == 'launcher':
        helper = Path(__file__).with_name('desktop_hyprland_theme.py')
        return subprocess.call(['/usr/bin/python3', '-I', '-B', str(helper), '--work', str(work), 'launcher'])
    return subprocess.call([str(work / 'bin' / ('termux-hyprland-' + args.action))])


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit('Hyprland container desktop: ' + str(error))
