#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Rootless GNOME launcher: X11 or native Android GPU compositor.

Requires real GNOME packages. Does not provide GDM, logind, authentication,
or lock-screen support. Android mode requires the existing termux-render App view.
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
from xml.sax.saxutils import escape


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--desktop-id', required=True)
    ap.add_argument('--ready-file', type=Path, required=True)
    ap.add_argument('--run-mode', choices=('native', 'proot'), required=True)
    ap.add_argument('--backend', choices=('x11', 'android'), required=True)
    ap.add_argument('--session-startup', choices=('direct',), required=True)
    ap.add_argument('--gl-mode', choices=('system', 'llvmpipe', 'zink'), required=True)
    ap.add_argument('--x11-display-gl-mode', choices=('system', 'zink'))
    ap.add_argument('--x11-display-buffer-mode', choices=('native', 'dmabuf'))
    ap.add_argument('--size', required=True)
    ap.add_argument('--refresh-rate', type=int, required=True)
    ap.add_argument('--render-socket', type=Path, required=True)
    ap.add_argument('--wayland-display', required=True)
    ap.add_argument('--usb-storage', choices=('off', 'read-only'), required=True)
    ap.add_argument('--rootfs', type=Path)
    ap.add_argument('--work', type=Path, required=True)
    ap.add_argument('--display', required=True)
    ap.add_argument('--service-policy', choices=('core',), required=True)
    args = ap.parse_args()
    if args.backend == 'x11':
        expected_display = {'llvmpipe': ('system', 'native'), 'zink': ('zink', 'dmabuf')}
        if (args.x11_display_gl_mode, args.x11_display_buffer_mode) != expected_display.get(args.gl_mode):
            ap.error('X11 llvmpipe requires explicit system/native; Zink requires explicit zink/dmabuf')
    elif args.x11_display_gl_mode is not None or args.x11_display_buffer_mode is not None:
        ap.error('Android backend does not accept X11 display modes')
    if args.run_mode == 'native':
        if args.rootfs or args.backend != 'android' or args.gl_mode != 'system':
            ap.error('native GNOME requires android/system and no rootfs')
        # The pinned Mutter 48.1-24 backend hardcodes 60 Hz. Do not misreport others.
        if args.refresh_rate != 60:
            ap.error('native GNOME Mutter 48.1-24 supports exactly 60 Hz')
    elif not args.rootfs or args.backend != 'x11' or args.gl_mode not in ('llvmpipe', 'zink'):
        ap.error('PRoot GNOME requires an explicit Debian trixie rootfs and x11/llvmpipe|zink')
    if not 1 <= args.refresh_rate <= 120:
        ap.error('--refresh-rate must be between 1 and 120')
    if args.display != ':0':
        ap.error('the App Xlorie integration requires --display=:0')
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
    if args.x11_display_gl_mode == 'zink':
        host_icd = prefix / 'share/vulkan/icd.d/freedreno_icd.aarch64.json'
        try:
            host_library = json.loads(host_icd.read_text())['ICD']['library_path']
        except (OSError, ValueError, KeyError, TypeError) as error:
            ap.error('Xlorie Zink requires a valid fixed host Turnip ICD: ' + str(error))
        if host_library != str(prefix / 'lib/libvulkan_freedreno.so'):
            ap.error('Host Turnip ICD must explicitly select ' + str(prefix / 'lib/libvulkan_freedreno.so'))
        if not Path(host_library).is_file():
            ap.error('The fixed host Turnip ICD library is missing: ' + host_library)
    desktop_key = hashlib.sha256(args.desktop_id.encode()).hexdigest()[:16]
    runtime = prefix / ('tmp/gnome-' + desktop_key) / 'runtime'
    usb_parent = prefix / ('tmp/tusb-' + desktop_key)
    usb_runtime = usb_parent / 'desktop'
    if args.usb_storage == 'read-only':
        usb_parent.mkdir(parents=True, exist_ok=True)
        usb_parent.chmod(0o700)
        module_dir = (args.rootfs / 'usr/local/lib/termux-usb-bridge/gio/modules' if args.rootfs
                      else prefix / 'lib/gio/modules')
        if not (module_dir / 'libtusb-volume-monitor.so').is_file():
            ap.error('selected runtime GIO module directory is missing: ' + str(module_dir))
        if args.rootfs and not os.access(args.rootfs / 'usr/local/libexec/tusb-gio-mount', os.X_OK):
            ap.error('glibc tusb-gio-mount helper is missing from the selected rootfs')
    if args.rootfs:
        release = {}
        for line in (args.rootfs / 'etc/os-release').read_text().splitlines():
            if '=' in line:
                key, value = line.split('=', 1)
                release[key] = value.strip(chr(34) + chr(39))
        if release.get('ID') != 'debian' or release.get('VERSION_CODENAME') != 'trixie':
            ap.error('PRoot GNOME supports Debian trixie only')
        if args.gl_mode == 'zink':
            icd = args.rootfs / 'usr/share/vulkan/icd.d/freedreno_icd.aarch64.json'
            try:
                library = json.loads(icd.read_text())['ICD']['library_path']
            except (OSError, ValueError, KeyError, TypeError) as error:
                ap.error('PRoot GNOME Zink requires a valid fixed Turnip ICD: ' + str(error))
            if library != '/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so':
                ap.error('Turnip ICD must explicitly select /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so')
            if not (args.rootfs / library.lstrip('/')).is_file():
                ap.error('The fixed Turnip ICD library is missing: ' + library)
    if not args.render_socket.is_socket():
        ap.error('the requested App render socket is not available')
    w = args.work.resolve()
    w.mkdir(parents=True, exist_ok=True)
    w.chmod(0o700)
    termux_home = os.environ['HOME']
    if not Path(termux_home).is_absolute() or not Path(termux_home).is_dir():
        ap.error('the parent Termux HOME must be an existing absolute directory')
    if args.rootfs:
        user_home = args.rootfs.resolve() / 'root'
        config_home, cache_home, data_home = (user_home / '.config', user_home / '.cache',
                                            user_home / '.local/share')
        session_home = '/root'
        session_config, session_cache, session_data = '/root/.config', '/root/.cache', '/root/.local/share'
    else:
        config_home, cache_home, data_home = w / 'config', w / 'cache', w / 'data'
        session_home = termux_home
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
    import fcntl
    lock = (w / 'session.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    args.ready_file.unlink(missing_ok=True)
    # KWin aliases may be inherited from an earlier desktop. They are not GNOME
    # parameters, and Xlorie rejects a conflicting KWIN_ANDROID_GL_MODE alias.
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
        if not os.access(root / 'usr/bin/gnome-shell', os.X_OK):
            ap.error('rootfs does not contain an executable gnome-shell')
        for d in ('tusb-gnome', 'run/tusb-gnome', 'tmp/.X11-unix'):
            (root / d).mkdir(parents=True, exist_ok=True)
        gw, gr = '/tusb-gnome', '/run/tusb-gnome'
        guest.pop('LD_PRELOAD', None)
        base = ['proot', '--link2symlink', '--sysvipc', '-r', str(root), '-b', '/dev',
                '-b', '/proc', '-b', '/sys', '-b', str(w) + ':' + gw,
                '-b', str(w) + ':' + str(w),
                '-b', str(runtime) + ':' + gr,
                '-b', str(prefix / 'tmp/.X11-unix') + ':/tmp/.X11-unix']
        if args.usb_storage == 'read-only':
            (root / str(usb_parent).lstrip('/')).mkdir(parents=True, exist_ok=True)
            base += ['-b', str(usb_parent) + ':' + str(usb_parent)]
        base += ['-w', '/', '/usr/bin/env', '-i']
    values = dict(HOME=session_home, DISPLAY=args.display, XDG_RUNTIME_DIR=gr,
                  PULSE_SERVER=os.environ['PULSE_SERVER'],
                  XCURSOR_PATH='/usr/share/icons' if args.rootfs else str(prefix / 'share/icons'),
                  XCURSOR_THEME='Adwaita',
                  XDG_CONFIG_HOME=session_config, XDG_CACHE_HOME=session_cache,
                  XDG_DATA_HOME=session_data, XDG_CURRENT_DESKTOP='GNOME',
                  XDG_SESSION_TYPE='x11', XDG_SESSION_DESKTOP='gnome',
                  DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + gr + '/system-bus',
                  GDK_BACKEND='x11', GSK_RENDERER='cairo', GTK_A11Y='none', NO_AT_BRIDGE='1',
                  GST_PLUGIN_FEATURE_RANK=os.environ.get('GST_PLUGIN_FEATURE_RANK',
                      'ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0'),
                  PIPEWIRE_RUNTIME_DIR=gr, PIPEWIRE_REMOTE='pipewire-0',
                  TERMUX_USB_DESKTOP_DIR=str(usb_runtime),
                  LANG='C.UTF-8', TZ=os.environ.get('TZ', 'Asia/Shanghai'))
    if args.gl_mode == 'llvmpipe':
        values.update(LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe')
    elif args.gl_mode == 'zink':
        values.update(GALLIUM_DRIVER='zink', MESA_LOADER_DRIVER_OVERRIDE='zink',
                      VK_DRIVER_FILES='/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json')
    if android:
        for env in (host, guest, values):
            for key in ('DISPLAY', 'LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER',
                        'MESA_LOADER_DRIVER_OVERRIDE', 'COGL_DRIVER', 'CLUTTER_DRIVER'):
                env.pop(key, None)
        values['GST_PLUGIN_FEATURE_RANK'] = 'ximagesink:0,xvimagesink:0,cacasink:0'
        values.update(XDG_SESSION_TYPE='wayland', GDK_BACKEND='wayland',
                      WAYLAND_DISPLAY=args.wayland_display)
    if args.rootfs:
        # Both guest renderers are verified through GLX. Mutter's X11 GL3
        # winsys is GLX; restrict Clutter's list too so it cannot try GLES/EGL
        # or retry with its default "any" driver after GLX initialization fails.
        values.update(COGL_DRIVER='gl3', CLUTTER_DRIVER='gl3')
        values.update(PATH='/usr/sbin:/usr/bin:/sbin:/bin',
                      XDG_DATA_DIRS='/usr/local/share:/usr/share', XDG_CONFIG_DIRS='/etc/xdg')
        if args.usb_storage == 'read-only':
            values['GIO_EXTRA_MODULES'] = '/usr/local/lib/termux-usb-bridge/gio/modules'
        base += [k + '=' + v for k, v in values.items()]
    else:
        values.update(XDG_DATA_DIRS=str(prefix / 'share'),
                      XDG_CONFIG_DIRS=str(prefix / 'etc/xdg'),
                      GST_PLUGIN_PATH=str(prefix / 'lib/termux-usb-bridge/gstreamer-1.0') +
                          (':' + os.environ['GST_PLUGIN_PATH'] if os.environ.get('GST_PLUGIN_PATH') else ''))
        if args.usb_storage == 'read-only':
            values['GIO_EXTRA_MODULES'] = str(prefix / 'lib/gio/modules')
        guest.update(values)
        host.update(values)
    (w / 'systembus.conf').write_text('<busconfig><type>system</type><listen>unix:path=' +
        escape(gr + '/system-bus') + '</listen><auth>EXTERNAL</auth><policy context="default">'
        '<allow user="*"/><allow own="*"/><allow send_destination="*"/>'
        '<allow receive_sender="*"/></policy></busconfig>')
    (w / 'session.sh').write_text('''#!/bin/sh
set -eu
cd "$HOME"
printf 'export DBUS_SESSION_BUS_ADDRESS="%s"\\n' "$DBUS_SESSION_BUS_ADDRESS" > ''' + shlex.quote(gw + '/session.env') + '''
dbus-update-activation-environment ''' + ' '.join(values) + '''
gsettings set org.gnome.desktop.interface cursor-theme "$XCURSOR_THEME"
gsettings set org.gnome.desktop.interface enable-animations ''' + ('true' if android else 'false') + '''
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
printf '%s\\n' "$$" > ''' + shlex.quote(gw + '/shell.pid') + '''
''' + (('exec env MUTTER_BACKEND=android MUTTER_ANDROID_GL_MODE=' + args.gl_mode +
        ' MUTTER_ANDROID_WIDTH=' + size[1] + ' MUTTER_ANDROID_HEIGHT=' + size[2] +
        ' MUTTER_ANDROID_REFRESH_RATE=' + str(args.refresh_rate) +
        ' CLUTTER_DRIVER=gles2 TERMUX_ANDROID_ZINK=' + ('0' if args.gl_mode == 'system' else '1') +
        ((' LD_PRELOAD=' + shlex.quote(str(prefix / 'lib/mutter-16/libmutter-android-system-egl.so') +
            (':' + host['LD_PRELOAD'] if host.get('LD_PRELOAD') else ''))) if args.gl_mode == 'system' else '') +
        ' gnome-shell --wayland --no-x11 --wayland-display=' + shlex.quote(args.wayland_display) + '\n')
        if android else 'exec gnome-shell --x11 --sm-disable --mode=user\n'))
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
        # The bus is real and private to this App UID. It implements no login,
        # account, authorization, or power management services.
        if not android and subprocess.run(['xdpyinfo', '-display', args.display], env=host,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode == 0:
            raise RuntimeError('X11 display is already in use: ' + args.display)
        (w / 'session.env').unlink(missing_ok=True)
        (w / 'shell.pid').unlink(missing_ok=True)
        (w / 'host-session.env').unlink(missing_ok=True)
        (runtime / 'system-bus').unlink(missing_ok=True)
        (runtime / 'session-bus').unlink(missing_ok=True)
        if not android:
            # Host display EGL and the glibc Shell renderer are independent contracts.
            # llvmpipe presents native AHB via System EGL; Zink imports guest
            # DRI3 dma-buf through the explicitly selected host Zink renderer.
            host.update(XLORIE_GL_MODE=args.x11_display_gl_mode, XLORIE_GLX='1',
                        TERMUX_RENDER_AHB_RECEIVE=args.x11_display_buffer_mode)
            if args.x11_display_gl_mode == 'zink':
                host.update(GALLIUM_DRIVER='zink', MESA_LOADER_DRIVER_OVERRIDE='zink',
                            VK_DRIVER_FILES=str(host_icd))
            x11dir = prefix / 'tmp/.X11-unix'
            if x11dir.is_symlink():
                x11dir.unlink()
            x11dir.mkdir(parents=True, exist_ok=True)
            x11dir.chmod(0o1777)
            (x11dir / 'X0').unlink(missing_ok=True)
            (prefix / 'tmp/.X0-lock').unlink(missing_ok=True)
        x11 = None if android else start('x11', ['Xlorie', args.display, '-noreset',
            '-screen', '0', args.size + 'x24', '-framerate', str(args.refresh_rate),
            '-render-socket', str(args.render_socket), '-xkbdir', str(prefix / 'share/X11/xkb')], host)
        bus = start('systembus', base + ['dbus-daemon', '--config-file=' + gw + '/systembus.conf',
                    '--nofork', '--nopidfile'], guest)
        deadline = time.monotonic() + 30
        while not (runtime / 'system-bus').exists():
            if bus.poll() is not None or (x11 is not None and x11.poll() is not None) or time.monotonic() > deadline:
                raise RuntimeError('X11/system D-Bus startup failed; inspect work logs')
            time.sleep(.1)
        deadline = time.monotonic() + 30
        while not android and subprocess.run(['xdpyinfo', '-display', args.display], env=host,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode != 0:
            if x11.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('X11 display did not become ready')
            time.sleep(.1)
        if not android:
            # Check the guest GLX route before starting Shell. This verifies the
            # selected client environment, not an assumed Mutter rendering API.
            renderer_log = w / 'renderer-check.log'
            try:
                probe = subprocess.run(base + ['glxinfo', '-B'], env=guest, cwd=w,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, timeout=30)
                probe_output = probe.stdout
            except subprocess.TimeoutExpired as error:
                probe_output = error.stdout or ''
                if isinstance(probe_output, bytes):
                    probe_output = probe_output.decode(errors='replace')
                renderer_log.write_text(probe_output + '\nGLX renderer check timed out after 30 seconds\n')
                raise RuntimeError('PRoot GNOME ' + args.gl_mode + ' GLX verification timed out:\n' + probe_output[-6000:]) from error
            renderer_log.write_text(probe_output)
            renderer = re.search(r'^OpenGL renderer string:\s*(.*)$', probe_output, re.MULTILINE)
            accelerated = re.search(r'^\s*Accelerated:\s*(yes|no)\s*$', probe_output,
                                    re.MULTILINE | re.IGNORECASE)
            actual = renderer[1].lower() if renderer else ''
            matches = (bool(re.search(r'\bllvmpipe\b', actual)) and
                       not re.search(r'zink|lavapipe', actual)) if args.gl_mode == 'llvmpipe' else (
                bool(re.search(r'\bzink\b', actual)) and
                bool(re.search(r'\b(?:adreno|turnip)\b', actual)) and
                accelerated is not None and accelerated[1].lower() == 'yes' and
                not re.search(r'llvmpipe|lavapipe|softpipe|software|swrast|swiftshader|cpu', actual))
            if probe.returncode != 0 or not matches:
                raise RuntimeError('PRoot GNOME ' + args.gl_mode + ' GLX verification failed (exit ' +
                                   str(probe.returncode) + '):\n' + probe_output[-6000:])
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
        if args.usb_storage == 'read-only':
            helper = Path(__file__).with_name('desktop_usb_storage.py')
            command = [str(prefix / 'bin/python3'), '-I', '-B', str(helper),
                '--mode', 'read-only', '--run-mode', args.run_mode,
                '--termux-usb', str(prefix / 'libexec/termux-usb-app'),
                '--runtime', str(usb_runtime), '--broker-socket', str(usb_parent / 'broker.sock')]
            if args.rootfs:
                gio_command = base + ['/bin/sh', '-c',
                    '. ' + shlex.quote(gw + '/session.env') + '; exec "$@"',
                    'gnome-usb-gio', '/usr/local/libexec/tusb-gio-mount']
                command += ['--gio-command-json', json.dumps(gio_command)]
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
        shell_ping = ['dbus-send', '--session', '--dest=org.gnome.Shell', '--type=method_call',
                      '--print-reply', '/org/gnome/Shell', 'org.freedesktop.DBus.Peer.Ping']
        while subprocess.run(shell_ping, env=host, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, timeout=5).returncode != 0:
            if not shell_alive() or session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('GNOME Shell org.gnome.Shell D-Bus did not become ready')
            time.sleep(.1)
        if any(process.poll() is not None for _, process in optional):
            raise RuntimeError('A requested desktop service terminated before GNOME was ready')
        if not shell_alive():
            raise RuntimeError('Direct GNOME Shell exited before it became ready')
        temporary = args.ready_file.with_name(args.ready_file.name + '.' + str(os.getpid()) + '.tmp')
        temporary.write_text(str(os.getpid()) + '\n')
        temporary.replace(args.ready_file)
        print('GNOME session started; logs: ' + str(w), flush=True)
        while session.poll() is None:
            if not shell_alive():
                return session.wait(timeout=5)
            if bus.poll() is not None or (x11 is not None and x11.poll() is not None):
                raise RuntimeError('Display/system D-Bus terminated')
            for name, process in optional:
                if process.poll() is not None:
                    raise RuntimeError(name + ' terminated; inspect work logs')
            time.sleep(.5)
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
