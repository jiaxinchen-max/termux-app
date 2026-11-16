#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Rootless GNOME 50 launcher using the native Android EGL/AHB compositor.

Requires real GNOME packages. Does not provide GDM, logind or lock-screen
support. PRoot package management owns guest PolicyKit/PAM services. Android
mode requires the existing termux-render App view.
"""
import argparse
import os
from pathlib import Path
import signal
import shlex
import subprocess
import time
import re
import shutil
import hashlib
import json
import runpy
from xml.sax.saxutils import escape


def session_command(base, shell, session_env, command):
    """Probe with the session's filesystem, credentials and exact bus address."""
    return base + [shell, '-c', '. ' + shlex.quote(str(session_env)) + '; exec "$@"',
                   'gnome-session-probe'] + command


def probe_session(command, env, timeout=5):
    """A slow reply is a failed attempt, not an escape from the startup deadline."""
    try:
        return subprocess.run(command, env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(command, 124, 'Session probe timed out')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--prepare', action='store_true', help='Save/install phase only: prepare and validate this session')
    ap.add_argument('--desktop-id', required=True)
    ap.add_argument('--ready-file', type=Path, required=True)
    ap.add_argument('--run-mode', choices=('native', 'proot'), required=True)
    ap.add_argument('--backend', choices=('android',), required=True)
    ap.add_argument('--session-profile', choices=('gnome', 'ubuntu', 'native-ubuntu'), default='gnome')
    ap.add_argument('--session-startup', choices=('direct',), required=True)
    ap.add_argument('--gl-mode', choices=('system',), required=True)
    ap.add_argument('--app-renderer', choices=('llvmpipe',))
    ap.add_argument('--size', required=True)
    ap.add_argument('--refresh-rate', type=int, required=True)
    ap.add_argument('--render-socket', type=Path, required=True)
    ap.add_argument('--wayland-display', required=True)
    ap.add_argument('--usb-storage', choices=('off', 'read-only', 'read-write'), required=True)
    ap.add_argument('--rootfs', type=Path)
    ap.add_argument('--work', type=Path, required=True)
    ap.add_argument('--display', required=True)
    ap.add_argument('--service-policy', choices=('core',), required=True)
    args = ap.parse_args()
    if args.usb_storage == 'read-write' and args.run_mode != 'proot':
        ap.error('USB read-write requires PRoot')
    if not args.prepare:
        return run_prepared(args)
    import fcntl
    w = args.work.resolve()
    w.mkdir(parents=True, exist_ok=True)
    lock = (w / 'session.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    (w / 'prepared-session.json').unlink(missing_ok=True)
    if args.run_mode == 'proot' and args.session_profile == 'gnome':
        ap.error('Independent GNOME containers are retired; create a supported desktop')
    ubuntu = args.session_profile in ('ubuntu', 'native-ubuntu')
    native_ubuntu = args.session_profile == 'native-ubuntu'
    profile = None
    if ubuntu:
        if native_ubuntu and ((args.run_mode, args.backend, args.gl_mode, args.usb_storage) != ('native', 'android', 'system', 'off') or args.rootfs):
            ap.error('Native Ubuntu GNOME requires native Android/System EGL, USB off and no rootfs')
        if not native_ubuntu and ((args.run_mode, args.backend, args.gl_mode) != ('proot', 'android', 'system') or not args.rootfs):
            ap.error('Ubuntu GNOME requires Resolute PRoot and Android/System EGL')
        # -I excludes the script directory from sys.path. Load only the adjacent shipped asset.
        import importlib.util
        module_spec = importlib.util.spec_from_file_location(
            'desktop_ubuntu_gnome', Path(__file__).with_name(
                'desktop_native_ubuntu.py' if native_ubuntu else 'desktop_ubuntu_gnome.py'))
        profile = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(profile)
        if not native_ubuntu:
            profile.validate_rootfs(args.rootfs)
    if args.run_mode == 'native':
        if args.rootfs or args.app_renderer is not None:
            ap.error('Native GNOME does not accept a rootfs or container renderer')
    elif not ubuntu or native_ubuntu or not args.rootfs or args.app_renderer != 'llvmpipe':
        ap.error('PRoot Ubuntu GNOME requires a Resolute rootfs and explicit app renderer llvmpipe')
    # The Android output backend currently supports exactly 60 Hz.
    if args.refresh_rate != 60:
        ap.error('GNOME Android output supports exactly 60 Hz')
    if args.display != ':0':
        ap.error('the reserved desktop display identifier must be :0')
    if not args.render_socket.is_absolute():
        ap.error('--render-socket must be absolute')
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,128}', args.desktop_id):
        ap.error('invalid --desktop-id')
    if not args.ready_file.is_absolute():
        ap.error('--ready-file must be absolute')
    size = re.fullmatch(r'([0-9]{3,4})x([0-9]{3,4})', args.size)
    if not size or not (320 <= int(size[1]) <= 8192 and 240 <= int(size[2]) <= 8192):
        ap.error('--size must be WIDTHxHEIGHT (320..8192 by 240..8192)')
    if '/' in args.wayland_display or not args.wayland_display:
        ap.error('--wayland-display must be a socket basename')
    android = args.backend == 'android'
    prefix = Path(os.environ['PREFIX'])
    termux_home = os.environ['HOME']
    if not Path(termux_home).is_absolute() or not Path(termux_home).is_dir():
        ap.error('the parent Termux HOME must be an existing absolute directory')
    desktop_key = hashlib.sha256(args.desktop_id.encode()).hexdigest()[:16]
    # TermuxService.onDestroy clears PREFIX/tmp. Session sockets must survive
    # that independent service lifecycle and still fit sockaddr_un.sun_path.
    runtime = Path(termux_home) / ('.cache/gnome-' + desktop_key)
    usb_parent = prefix / ('tmp/tusb-' + desktop_key)
    usb_runtime = usb_parent / 'desktop'
    if args.usb_storage != 'off':
        usb_parent.mkdir(parents=True, exist_ok=True)
        usb_parent.chmod(0o700)
    w = args.work.resolve()
    w.mkdir(parents=True, exist_ok=True)
    w.chmod(0o700)
    if args.rootfs:
        user_home = args.rootfs.resolve() / 'home/termux'
        config_home, cache_home, data_home = (user_home / '.config', user_home / '.cache',
                                            user_home / '.local/share')
        session_home = '/home/termux'
        session_config, session_cache, session_data = '/home/termux/.config', '/home/termux/.cache', '/home/termux/.local/share'
    else:
        config_home, cache_home, data_home = w / 'config', w / 'cache', w / 'data'
        session_home = str(w / 'home') if native_ubuntu else termux_home
        if native_ubuntu:
            Path(session_home).mkdir(parents=True, exist_ok=True)
        session_config, session_cache, session_data = str(config_home), str(cache_home), str(data_home)
    for directory in (config_home / 'autostart', cache_home, data_home):
        directory.mkdir(parents=True, exist_ok=True)
    (config_home / 'autostart/tusb-desktop.desktop').write_text(
        '[Desktop Entry]\nType=Application\nName=Termux USB storage\nHidden=true\n')
    runtime.mkdir(parents=True, exist_ok=True)
    runtime.chmod(0o700)
    # Socket syscalls must use a short path even when the container rootfs path is long.
    runtime_link = w / 'runtime'
    if runtime_link.is_symlink():
        if runtime_link.resolve() != runtime.resolve():
            ap.error('workspace runtime link belongs to another session')
    elif runtime_link.exists():
        ap.error('workspace runtime path must be the managed session symlink')
    else:
        runtime_link.symlink_to(runtime, target_is_directory=True)
    # Reject reuse while another session holds this workspace.
    inherited = parent_environment()
    host = dict(inherited)
    host.update(HOME=termux_home, DISPLAY=args.display, XDG_RUNTIME_DIR=str(runtime),
                XCURSOR_PATH=str(prefix / 'share/icons'), XCURSOR_THEME='Adwaita',
                XDG_CONFIG_HOME=str(config_home), XDG_CACHE_HOME=str(cache_home),
                XDG_DATA_HOME=str(data_home), PIPEWIRE_RUNTIME_DIR=str(runtime),
                PIPEWIRE_REMOTE='pipewire-0', TERMUX_USB_DESKTOP_DIR=str(usb_runtime),
                DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + str(runtime / 'system-bus'))
    guest = dict(host)
    base = []
    gw, gr = str(w), str(runtime)
    if args.rootfs:
        root = args.rootfs.resolve()
        for d in ('tusb-gnome', 'run/tusb-gnome'):
            (root / d).mkdir(parents=True, exist_ok=True)
        gw, gr = '/tusb-gnome', '/run/tusb-gnome'
        guest.pop('LD_PRELOAD', None)
        account = next((line.split(':') for line in (root / 'etc/passwd').read_text().splitlines()
                        if line.startswith('termux:')), None)
        if not account or len(account) != 7 or not account[2].isdigit() or not account[3].isdigit() or int(account[2]) == 0 or account[5] != '/home/termux':
            ap.error('container requires the ordinary termux account; create a new container')
        adapter_spec = importlib.util.spec_from_file_location(
            'gnome_glycin_adapter', Path(__file__).with_name('desktop_install_glycin.py'))
        adapter = importlib.util.module_from_spec(adapter_spec)
        adapter_spec.loader.exec_module(adapter)
        glycin = adapter.verify_installed(root)
        # D-Bus activation can detach service process groups. Have the tracer
        # reap its whole guest when the owning command exits, including those
        # services, without scanning unrelated Android processes.
        base = ['proot', '--kill-on-exit', '-i', account[2] + ':' + account[3], '--link2symlink', '--sysvipc', '-r', str(root), '-b', '/dev',
                '-b', '/proc', '-b', '/sys', '-b', str(w) + ':' + gw,
                '-b', str(w) + ':' + str(w),
                '-b', str(runtime) + ':' + gr,
                '-b', str(glycin) + ':/usr/lib/aarch64-linux-gnu/libglycin-2.so.0']
        # Native Shell and guest applications share one filesystem and private bus.
        # The Android linker loads Bionic libraries from PREFIX; glibc apps keep /usr.
        for platform in (prefix, Path('/apex'), Path('/system'), Path('/vendor'),
                         Path('/product'), Path('/system_ext')):
            # Android permits searching /apex without listing the directory.
            # Its runtime linker is still required by every Bionic executable.
            if platform.is_dir():
                (root / str(platform).lstrip('/')).mkdir(parents=True, exist_ok=True)
                base += ['-b', str(platform)]
        # libtermux-render connects at its original absolute socket path. PREFIX
        # alone does not expose this socket under the separate Termux HOME.
        (root / str(args.render_socket.parent).lstrip('/')).mkdir(parents=True, exist_ok=True)
        base += ['-b', str(args.render_socket)]
        # /linkerconfig itself can deny getattr/listing while its generated
        # config remains readable. PRoot cannot canonicalize that directory.
        # Snapshot the device's current config through a direct open, then bind
        # the file so Bionic keeps its real platform/driver namespace links.
        for config_path in (Path('/linkerconfig/ld.config.txt'), Path('/system/etc/ld.config.txt')):
            try:
                with config_path.open('rb') as source:
                    linker_config = source.read(2 * 1024 * 1024 + 1)
            except (FileNotFoundError, PermissionError):
                continue
            if not linker_config or len(linker_config) > 2 * 1024 * 1024:
                raise RuntimeError('Android linker configuration is empty or too large')
            snapshot = w / 'android-ld.config.txt'
            snapshot.write_bytes(linker_config)
            snapshot.chmod(0o600)
            (root / str(config_path.parent).lstrip('/')).mkdir(parents=True, exist_ok=True)
            base += ['-b', str(snapshot) + ':' + str(config_path)]
            break
        else:
            raise RuntimeError('Android linker configuration is unavailable for the native compositor')
        if args.usb_storage != 'off':
            guest_usb_parent = Path('/tmp') / usb_parent.name
            runpy.run_path(str(Path(__file__).with_name('desktop_usb_storage.py')))['prepare_guest_mount'](root, guest_usb_parent)
            base += ['-b', str(usb_parent) + ':' + str(guest_usb_parent)]
        base += ['-w', '/home/termux', '/usr/bin/env', '-i']
    values = dict(HOME=session_home, DISPLAY=args.display, XDG_RUNTIME_DIR=gr,
                  PULSE_SERVER=os.environ['PULSE_SERVER'],
                  XCURSOR_PATH='/usr/share/icons' if args.rootfs else str(prefix / 'share/icons'),
                  XCURSOR_THEME='Adwaita',
                  XDG_CONFIG_HOME=session_config, XDG_CACHE_HOME=session_cache,
                  XDG_DATA_HOME=session_data, XDG_CURRENT_DESKTOP='GNOME',
                  XDG_SESSION_TYPE='wayland', XDG_SESSION_DESKTOP='gnome',
                  DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + gr + '/system-bus',
                  GDK_BACKEND='wayland', GSK_RENDERER='cairo', GTK_A11Y='none', NO_AT_BRIDGE='1',
                  GST_PLUGIN_FEATURE_RANK=os.environ.get('GST_PLUGIN_FEATURE_RANK',
                      'ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0'),
                  PIPEWIRE_RUNTIME_DIR=gr, PIPEWIRE_REMOTE='pipewire-0',
                  TERMUX_USB_DESKTOP_DIR=str(usb_runtime),
                  LANG='C.UTF-8', TZ=os.environ.get('TZ', 'Asia/Shanghai'))
    if ubuntu:
        values.update(XDG_CURRENT_DESKTOP='ubuntu:GNOME', XDG_SESSION_DESKTOP='ubuntu',
                      GNOME_SHELL_SESSION_MODE=profile.MODE, XCURSOR_THEME='Yaru')
    if android:
        for env in (host, guest, values):
            for key in ('DISPLAY', 'LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER',
                        'MESA_LOADER_DRIVER_OVERRIDE', 'COGL_DRIVER', 'CLUTTER_DRIVER'):
                env.pop(key, None)
        values['GST_PLUGIN_FEATURE_RANK'] = 'ximagesink:0,xvimagesink:0,cacasink:0'
        values.update(XDG_SESSION_TYPE='wayland', GDK_BACKEND='wayland',
                      WAYLAND_DISPLAY=args.wayland_display)
    if args.rootfs:
        # This renderer belongs to guest applications, never the native compositor.
        values.update(PREFIX=str(prefix), LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe')
        values.update(USER='termux', LOGNAME='termux', SHELL='/bin/bash',
                      PATH='/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
                      GIO_USE_NETWORK_MONITOR='base',
                      XDG_DATA_DIRS=('/usr/share/ubuntu:/usr/local/share:/usr/share' if ubuntu else '/usr/local/share:/usr/share'), XDG_CONFIG_DIRS='/etc/xdg')
        if args.usb_storage != 'off':
            values['GIO_EXTRA_MODULES'] = '/usr/local/lib/termux-usb-bridge/gio/modules'
            values['TERMUX_USB_DESKTOP_DIR'] = str(guest_usb_parent / 'desktop')
        base += [k + '=' + v for k, v in values.items()]
    else:
        values.update(XDG_DATA_DIRS=str(prefix / 'share'),
                      XDG_CONFIG_DIRS=str(prefix / 'etc/xdg'),
                      GST_PLUGIN_PATH=str(prefix / 'lib/termux-usb-bridge/gstreamer-1.0') +
                          (':' + os.environ['GST_PLUGIN_PATH'] if os.environ.get('GST_PLUGIN_PATH') else ''))
        if args.usb_storage != 'off':
            values['GIO_EXTRA_MODULES'] = str(prefix / 'lib/gio/modules')
        guest.update(values)
        host.update(values)
    session_sh = '/bin/sh' if args.rootfs else str(prefix / 'bin/sh')
    (w / 'systembus.conf').write_text('<busconfig><type>system</type><listen>unix:path=' +
        escape(gr + '/system-bus') + '</listen><auth>EXTERNAL</auth><policy context="default">'
        '<allow user="*"/><allow own="*"/><allow send_destination="*"/>'
        '<allow receive_sender="*"/></policy></busconfig>')
    shell_binary = str(prefix / 'bin/gnome-shell')
    if native_ubuntu:
        # Direct invocation must enforce the same integrity/dependency gates as
        # installation. Reject corruption or a broken application before buses
        # and the compositor are created, even when an old marker still exists.
        profile.validate(prefix)
        profile.runtime_probe(prefix, guest)
    else:
        native_probe = subprocess.run(base + [shell_binary, '--version'], env=guest,
                                      capture_output=True, text=True, timeout=20)
        if native_probe.returncode or not native_probe.stdout.strip().startswith('GNOME Shell 50.'):
            raise RuntimeError('GNOME requires the matching native Shell/Mutter 50 runtime: ' +
                               native_probe.stdout + native_probe.stderr)
    compositor_env = {
        'MUTTER_BACKEND': 'android', 'MUTTER_ANDROID_GL_MODE': 'system',
        'MUTTER_ANDROID_WIDTH': size[1], 'MUTTER_ANDROID_HEIGHT': size[2],
        'MUTTER_ANDROID_REFRESH_RATE': str(args.refresh_rate),
        'CLUTTER_DRIVER': 'gles2', 'TERMUX_ANDROID_ZINK': '0',
        'LD_PRELOAD': str(prefix / 'lib/mutter-18/libmutter-android-system-egl.so'),
    }
    if args.rootfs:
        compositor_env.update(GNOME_SHELL_DATADIR='/usr/share/gnome-shell',
                              MUTTER_ANDROID_CLIENT_RENDERER=args.app_renderer)
    elif host.get('LD_PRELOAD'):
        compositor_env['LD_PRELOAD'] += ':' + host['LD_PRELOAD']
    compositor = ['env', '-u', 'LIBGL_ALWAYS_SOFTWARE', '-u', 'GALLIUM_DRIVER',
                  '-u', 'MESA_LOADER_DRIVER_OVERRIDE', '-u', 'VK_DRIVER_FILES']
    compositor += [key + '=' + value for key, value in compositor_env.items()]
    compositor += [shell_binary, '--wayland', '--no-x11',
                   '--wayland-display=' + args.wayland_display]
    if ubuntu:
        compositor += ['--mode=' + profile.MODE]
    (w / 'session.sh').write_text('''#!/bin/sh
set -eu
cd "$HOME"
session_env_file=''' + shlex.quote(gw + '/session.env.') + '''$$
printf 'export DBUS_SESSION_BUS_ADDRESS="%s"\\n' "$DBUS_SESSION_BUS_ADDRESS" > "$session_env_file"
mv -f "$session_env_file" ''' + shlex.quote(gw + '/session.env') + '''
dbus-update-activation-environment ''' + ' '.join(values) + '''
shell_pid_file=''' + shlex.quote(gw + '/shell.pid.') + '''$$
printf '%s\\n' "$$" > "$shell_pid_file"
mv -f "$shell_pid_file" ''' + shlex.quote(gw + '/shell.pid') + '\nexec ' + shlex.join(compositor) + '\n')
    session_bus_args = ['dbus-run-session', '--config-file=' + gw + '/sessionbus.conf']
    if args.service_policy == 'core':
        services = w / 'dbus-services'
        services.mkdir(exist_ok=True)
        for old in services.glob('*.service'):
            old.unlink()
        service_root = (args.rootfs.resolve() / 'usr/share/dbus-1/services' if args.rootfs
                        else prefix / 'share/dbus-1/services')
        required_services = {'ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gnome.Nautilus'}
        service_names = ['ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gtk.vfs.Metadata',
                         'org.gnome.Nautilus', 'org.gnome.Shell.Notifications', 'org.gnome.ScreenSaver']
        if ubuntu or native_ubuntu:
            application_services = {'org.gnome.Terminal', 'org.gnome.TextEditor', 'org.gnome.Settings', 'org.gnome.clocks'}
            required_services.update(application_services)
            service_names += sorted(application_services)
            service_names += ['org.gnome.Calculator', 'org.gnome.Calculator.SearchProvider', 'org.gnome.eog']
        for name in service_names:
            source = service_root / (name + '.service')
            if source.is_file():
                shutil.copyfile(source, services / source.name)
            elif name in required_services:
                raise RuntimeError('Required GNOME D-Bus activation service is missing: ' + str(source))
        (w / 'sessionbus.conf').write_text('<busconfig><type>session</type><listen>unix:path=' +
            escape(gr + '/session-bus') + '</listen><auth>EXTERNAL</auth><servicedir>' +
            escape(gw + '/dbus-services') + '</servicedir><policy context="default">'
            '<allow user="*"/><allow own="*"/><allow send_destination="*"/>'
            '<allow receive_sender="*"/></policy></busconfig>')
    else:
        (w / 'sessionbus.conf').write_text('<busconfig><type>session</type><listen>unix:path=' +
            escape(gr + '/session-bus') + '</listen><auth>EXTERNAL</auth>'
            '<standard_session_servicedirs/><policy context="default"><allow user="*"/>'
            '<allow own="*"/><allow send_destination="*"/><allow receive_sender="*"/>'
            '</policy></busconfig>')
    # Only preparation may write defaults, inspect packages or copy services.
    settings = '''if [ ! -f "$XDG_CONFIG_HOME/.termux-gnome-configured" ]; then
gsettings set org.gnome.desktop.interface cursor-theme "$XCURSOR_THEME"
gsettings set org.gnome.desktop.interface enable-animations true
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
touch "$XDG_CONFIG_HOME/.termux-gnome-configured"
fi
''' + (profile.session_setup(prefix) if native_ubuntu else profile.session_setup() if ubuntu else '')
    subprocess.run(base + ['dbus-run-session', '--', session_sh, '-ec', settings],
                   env=guest, check=True, timeout=60)
    if args.rootfs:
        runpy.run_path(str(Path(__file__).with_name('desktop_software_center.py')))['prepare'](root, gr)
    config = dict(schema=1, request=session_request(args),
                  runtime=str(runtime), usb_parent=str(usb_parent), gw=gw, gr=gr,
                  base=base, session_sh=session_sh,
                  extensions=list(profile.EXTENSIONS) if ubuntu else [],
                  host={key: value for key, value in host.items() if inherited.get(key) != value},
                  guest={key: value for key, value in guest.items() if inherited.get(key) != value},
                  guest_unset=[key for key in inherited if key not in guest])
    temporary = w / ('prepared-session.json.' + str(os.getpid()) + '.tmp')
    try:
        temporary.write_text(json.dumps(config, sort_keys=True) + '\n')
        temporary.chmod(0o600)
        temporary.replace(w / 'prepared-session.json')
    finally:
        temporary.unlink(missing_ok=True)
        lock.close()
    print('GNOME session prepared during save/install: ' + str(w), flush=True)
    return 0


def session_request(args):
    return {key: str(value) if isinstance(value, Path) else value
            for key, value in vars(args).items() if key != 'prepare'}


def parent_environment():
    # Do not inherit another compositor's renderer selection.
    host = {key: value for key, value in os.environ.items()
            if not key.startswith('KWIN_') and key != 'PLASMA_LOG'}
    # An inherited Mesa/Vulkan selection must never alter the requested mode,
    # including the independent System EGL display server.
    for key in list(host):
        if key.startswith(('MESA_', 'GALLIUM_', 'LIBGL_', 'VK_', 'ZINK_', 'MUTTER_ANDROID_')) or key in (
                'COGL_DRIVER', 'CLUTTER_DRIVER', 'DRI_PRIME', '__GLX_VENDOR_LIBRARY_NAME',
                'EGL_PLATFORM', 'TERMUX_ANDROID_ZINK', 'MUTTER_BACKEND',
                'XLORIE_GL_MODE', 'XLORIE_GLX', 'TERMUX_RENDER_AHB_RECEIVE'):
            host.pop(key)
    # A new session always obtains its own bus identity.
    host.pop('DBUS_SESSION_BUS_ADDRESS', None)
    host.pop('DBUS_SESSION_BUS_PID', None)
    host.pop('SESSION_MANAGER', None)
    return host


def run_prepared(args):
    # Read the small save result; no package inventory, hashes, --version,
    # dependency probe, default settings, initialization or repair on launch.
    from types import SimpleNamespace
    import fcntl
    w = args.work.resolve()
    config = json.loads((w / 'prepared-session.json').read_text())
    if config.get('schema') != 1 or config.get('request') != session_request(args):
        raise RuntimeError('Desktop settings are not prepared; save/install the desktop first')
    prefix = Path(os.environ['PREFIX'])
    runtime, usb_parent = Path(config['runtime']), Path(config['usb_parent'])
    usb_runtime = usb_parent / 'desktop'
    gw, gr, base, session_sh = config['gw'], config['gr'], config['base'], config['session_sh']
    profile = SimpleNamespace(EXTENSIONS=config['extensions'])
    native_ubuntu = args.session_profile == 'native-ubuntu'
    ubuntu = args.session_profile in ('ubuntu', 'native-ubuntu')
    android = True
    session_bus_args = ['dbus-run-session', '--config-file=' + gw + '/sessionbus.conf']
    host = dict(parent_environment(), **config['host'])
    guest = dict(parent_environment(), **config['guest'])
    for key in config['guest_unset']:
        guest.pop(key, None)
    lock = (w / 'session.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    args.ready_file.unlink(missing_ok=True)
    children, logs, optional = [], [], []
    storage = None
    def start(name, command, env):
        log = (w / (name + '.log')).open('w')
        logs.append(log)
        p = subprocess.Popen(command, env=env, cwd=w, stdout=log, stderr=log,
                             start_new_session=True)
        children.append(p)
        return p
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        # Native keeps its minimal private bus. PRoot owns real guest package
        # authorization services without publishing login or hardware daemons.
        (w / 'session.env').unlink(missing_ok=True)
        (w / 'shell.pid').unlink(missing_ok=True)
        (w / 'host-session.env').unlink(missing_ok=True)
        (runtime / 'system-bus').unlink(missing_ok=True)
        (runtime / 'session-bus').unlink(missing_ok=True)
        if args.rootfs:
            software = runpy.run_path(str(Path(__file__).with_name('desktop_software_center.py')))
            (runtime / 'software-center.ready').unlink(missing_ok=True)
            root_base = list(base)
            root_base[root_base.index('-i') + 1] = '0:0'
            bus = start('software-center', root_base + ['USER=root', 'LOGNAME=root', 'HOME=/root',
                '/usr/bin/python3', '-I', '-B', software['MANAGER']], guest)
            software['wait_ready'](bus, runtime)
        else:
            bus = start('systembus', base + ['dbus-daemon', '--config-file=' + gw + '/systembus.conf',
                        '--nofork', '--nopidfile'], guest)
        deadline = time.monotonic() + 30
        while not (runtime / 'system-bus').exists():
            if bus.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Private system D-Bus startup failed; inspect work logs')
            time.sleep(.1)
        session = start('session', base + session_bus_args + ['--', '/bin/sh' if args.rootfs else str(prefix / 'bin/sh'), gw + '/session.sh'], guest)
        deadline = time.monotonic() + 30
        while not (w / 'session.env').exists():
            if session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('session D-Bus did not start')
            time.sleep(.1)
        address = re.search(r'DBUS_SESSION_BUS_ADDRESS="([^"]+)"',
                            (w / 'session.env').read_text()).group(1)
        host['DBUS_SESSION_BUS_ADDRESS'] = address.replace(gr + '/', str(runtime) + '/')
        deadline = time.monotonic() + 30
        while not (w / 'shell.pid').is_file():
            if session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Direct GNOME Shell process did not start')
            time.sleep(.1)
        shell_pid_text = (w / 'shell.pid').read_text().strip()
        if not re.fullmatch(r'[1-9][0-9]*', shell_pid_text) or int(shell_pid_text) <= 1:
            raise RuntimeError('Direct GNOME Shell published an invalid process ID')
        shell_pid = int(shell_pid_text)
        def shell_alive():
            try:
                os.kill(shell_pid, 0)
            except ProcessLookupError:
                return False
            return True
        export_keys = ('HOME', 'DISPLAY', 'XCURSOR_PATH', 'XCURSOR_THEME', 'XDG_RUNTIME_DIR', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME',
                       'XDG_CACHE_HOME', 'PIPEWIRE_RUNTIME_DIR', 'PIPEWIRE_REMOTE',
                       'DBUS_SYSTEM_BUS_ADDRESS', 'DBUS_SESSION_BUS_ADDRESS', 'TERMUX_USB_DESKTOP_DIR',
                       'GST_PLUGIN_PATH', 'GST_PLUGIN_FEATURE_RANK', 'GDK_BACKEND', 'GSK_RENDERER',
                       'LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER', 'GIO_EXTRA_MODULES', 'WAYLAND_DISPLAY',
                       'XDG_SESSION_TYPE', 'XDG_CURRENT_DESKTOP', 'XDG_SESSION_DESKTOP',
                       'XDG_DATA_DIRS', 'XDG_CONFIG_DIRS')
        (w / 'host-session.env').write_text(''.join('export ' + k + '=' + shlex.quote(host[k]) + '\n'
            for k in export_keys if k in host))
        (w / 'host-session.env').chmod(0o600)
        # USB storage is attached here, after this exact desktop session bus is ready.
        # Its helper must be a supervised child and receive host/guest session environments.
        if args.usb_storage != 'off':
            helper = Path(__file__).with_name('desktop_usb_storage.py')
            command = [str(prefix / 'bin/python3'), '-I', '-B', str(helper),
                '--mode', args.usb_storage, '--run-mode', args.run_mode,
                '--termux-usb', str(prefix / 'libexec/termux-usb-app'),
                '--runtime', str(usb_runtime), '--broker-socket', str(usb_parent / 'broker.sock')]
            if args.rootfs:
                gio_command = base + ['/bin/sh', '-c',
                    '. ' + shlex.quote(gw + '/session.env') + '; exec "$@"',
                    'gnome-usb-gio', '/usr/local/libexec/tusb-gio-mount']
                command += ['--gio-command-json', json.dumps(gio_command),
                            '--guest-runtime', str(Path('/tmp') / usb_parent.name / 'desktop')]
            storage = start('usb-storage', command, host)
            optional.append(('USB storage', storage))
            deadline = time.monotonic() + 30
            while not (usb_runtime / 'control.sock').is_socket():
                if optional[-1][1].poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('USB storage did not become ready in this desktop session')
                time.sleep(.1)
        if android:
            deadline = time.monotonic() + 30
            while not (runtime / args.wayland_display).is_socket():
                if session.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('GNOME Android Wayland display did not become ready')
                time.sleep(.1)
        deadline = time.monotonic() + 30
        # PRoot may map the ordinary account to a different UID than Android.
        # Host EXTERNAL authentication cannot be used against that session bus.
        shell_ping = session_command(base, session_sh, gw + '/session.env',
            ['dbus-send', '--session', '--dest=org.gnome.Shell', '--type=method_call',
             '--print-reply', '/org/gnome/Shell', 'org.freedesktop.DBus.Peer.Ping'])
        while probe_session(shell_ping, guest).returncode != 0:
            if not shell_alive() or session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('GNOME Shell org.gnome.Shell D-Bus did not become ready')
            time.sleep(.1)
        if ubuntu:
            # Shell's bus alone is insufficient: broken Dock/DING must fail readiness.
            for uuid in profile.EXTENSIONS:
                command = session_command(base, session_sh, gw + '/session.env',
                    ['gdbus', 'call', '--session', '--dest', 'org.gnome.Shell',
                     '--object-path', '/org/gnome/Shell',
                     '--method', 'org.gnome.Shell.Extensions.GetExtensionInfo', uuid])
                deadline = time.monotonic() + 30
                while True:
                    result = probe_session(command, guest)
                    if result.returncode == 0 and re.search(
                            r"'state': <(?:uint32 |double )?1(?:\.0)?>", result.stdout):
                        break
                    # Shell may set ERROR provisionally while awaiting module import.
                    # An empty error during that interval is not a completed failure.
                    if (re.search(r"'state': <(?:uint32 |double )?3(?:\.0)?>", result.stdout)
                            and not re.search(r"'error': <''>", result.stdout)):
                        raise RuntimeError('Ubuntu extension failed: ' + uuid + '\n' + result.stdout[-2000:])
                    if not shell_alive() or session.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('Ubuntu extension did not become active: ' + uuid + '\n' + result.stdout[-2000:])
                    time.sleep(.5)
            for name in ('com.rastersoft.ding',):
                command = session_command(base, session_sh, gw + '/session.env',
                    ['gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus',
                     '--object-path', '/org/freedesktop/DBus',
                     '--method', 'org.freedesktop.DBus.NameHasOwner', name])
                deadline = time.monotonic() + 30
                while True:
                    result = probe_session(command, guest)
                    if result.returncode == 0 and result.stdout.strip() == '(true,)':
                        break
                    if not shell_alive() or session.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('Ubuntu desktop service did not become ready: ' + name + '\n' + result.stdout[-2000:])
                    time.sleep(.5)
            # DING acquires its bus name before decoding icons and creating its
            # windows. The pinned extension logs this after Mutter has associated
            # the final required window; a restart loop must not count as ready.
            deadline = time.monotonic() + 30
            while 'Received notification for window. 0 notifications remaining.' not in (w / 'session.log').read_text(errors='replace'):
                if not shell_alive() or session.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Ubuntu desktop windows did not become ready; inspect session.log')
                time.sleep(.1)
        if any(process.poll() is not None for _, process in optional):
            raise RuntimeError('A requested desktop service terminated before GNOME was ready')
        if not shell_alive():
            raise RuntimeError('Direct GNOME Shell exited before it became ready')
        deadline = time.monotonic() + 30
        while 'Android first frame submitted' not in (w / 'session.log').read_text(errors='replace'):
            if not shell_alive() or session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('GNOME did not submit an Android GPU frame; inspect session.log')
            time.sleep(.1)
        temporary = args.ready_file.with_name(args.ready_file.name + '.' + str(os.getpid()) + '.tmp')
        temporary.write_text(str(os.getpid()) + '\n')
        temporary.replace(args.ready_file)
        print('GNOME session started; logs: ' + str(w), flush=True)
        while session.poll() is None:
            if not shell_alive():
                code = session.wait(timeout=5)
                print('GNOME compositor exited; session status: ' + str(code), flush=True)
                return code
            if bus.poll() is not None:
                raise RuntimeError('Display/system D-Bus terminated')
            for name, process in optional:
                if process.poll() is not None:
                    raise RuntimeError(name + ' terminated; inspect work logs')
            time.sleep(.5)
        print('GNOME session exited; status: ' + str(session.returncode), flush=True)
        return session.returncode
    except KeyboardInterrupt:
        return 130
    finally:
        if args.ready_file.exists() and args.ready_file.read_text().strip() == str(os.getpid()):
            args.ready_file.unlink()
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        cleanup_error = None
        # Storage must unmount GVfs, sync files and release its broker while the
        # owning session bus is still alive. Signal only its supervisor first.
        if storage is not None:
            if storage.poll() is None:
                storage.terminate()
            try:
                storage_code = storage.wait(timeout=120)
                if storage_code != 0:
                    cleanup_error = 'USB storage cleanup failed (exit ' + str(storage_code) + ')'
            except subprocess.TimeoutExpired:
                cleanup_error = 'USB storage cleanup timed out after 120 seconds'
        # Each child starts its own session. Signal only these owned process groups,
        # including descendants whose original group leader has already exited.
        for p in reversed(children):
            try:
                os.killpg(p.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 5
        for p in reversed(children):
            try:
                p.wait(timeout=max(0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                pass
        for p in reversed(children):
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            p.wait()
        (w / 'shell.pid').unlink(missing_ok=True)
        for log in logs:
            log.close()
        lock.close()
        if cleanup_error:
            raise RuntimeError(cleanup_error)


if __name__ == '__main__':
    raise SystemExit(main())
