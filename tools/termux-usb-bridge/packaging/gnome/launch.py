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
from xml.sax.saxutils import escape


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--backend', choices=('x11', 'android'), default='x11')
    ap.add_argument('--gl-mode', choices=('system', 'zink'), default='system')
    ap.add_argument('--size', default='1280x800')
    ap.add_argument('--wayland-display', default='wayland-gnome')
    ap.add_argument('--rootfs', type=Path)
    ap.add_argument('--work', type=Path, required=True)
    ap.add_argument('--display', default=':6')
    ap.add_argument('--gio-modules', type=Path,
                    help='GIO modules built against the selected runtime ABI')
    ap.add_argument('--minimal-services', action='store_true',
                    help='Activate only core GNOME/GVfs D-Bus services in this private session')
    ap.add_argument('--portals', action='store_true',
                    help='Include real desktop portal services in a minimal session')
    ap.add_argument('--bridge', action='store_true', help='Start the installed host USB coordinator')
    ap.add_argument('--read-write', action='store_true', help='Allow bridge storage writes (default: read-only)')
    ap.add_argument('--pipewire', action='store_true', help='Start private host PipeWire and WirePlumber')
    ap.add_argument('--gio-helper', type=Path, help='Host path to a glibc tusb-gio-mount helper for PRoot')
    args = ap.parse_args()
    if args.rootfs and args.bridge and not (args.gio_modules and args.gio_helper):
        ap.error('PRoot --bridge requires --gio-modules and --gio-helper built for this rootfs ABI')
    if args.backend == 'android' and args.rootfs:
        ap.error('Android mode runs the native Shell; launch glibc clients separately through its Wayland socket')
    import re
    size = re.fullmatch(r'([0-9]{3,4})x([0-9]{3,4})', args.size)
    if not size or not (320 <= int(size[1]) <= 8192 and 240 <= int(size[2]) <= 8192):
        ap.error('--size must be WIDTHxHEIGHT (320..8192 by 240..8192)')
    if '/' in args.wayland_display or not args.wayland_display:
        ap.error('--wayland-display must be a socket basename')
    android = args.backend == 'android'
    prefix = Path(os.environ['PREFIX'])
    w = args.work.resolve()
    w.mkdir(parents=True, exist_ok=True)
    w.chmod(0o700)
    for d in ('runtime', 'config/autostart', 'config/gnome-session/sessions', 'cache', 'data'):
        (w / d).mkdir(parents=True, exist_ok=True)
    (w / 'runtime').chmod(0o700)
    # Reject reuse while another session holds this workspace.
    import fcntl
    lock = (w / 'session.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    host = dict(os.environ, HOME=str(w), DISPLAY=args.display, XDG_RUNTIME_DIR=str(w / 'runtime'),
                XDG_CONFIG_HOME=str(w / 'config'), XDG_CACHE_HOME=str(w / 'cache'),
                XDG_DATA_HOME=str(w / 'data'), PIPEWIRE_RUNTIME_DIR=str(w / 'runtime'),
                PIPEWIRE_REMOTE='pipewire-0', TERMUX_USB_DESKTOP_DIR=str(w / 'runtime/usb'),
                DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + str(w / 'runtime/system-bus'))
    guest = dict(host)
    base = []
    gw, gr = str(w), str(w / 'runtime')
    if args.rootfs:
        root = args.rootfs.resolve()
        if not (root / 'usr/bin/gnome-session').exists():
            ap.error('rootfs does not contain gnome-session')
        for d in ('tusb-gnome', 'run/tusb-gnome', 'tmp/.X11-unix'):
            (root / d).mkdir(parents=True, exist_ok=True)
        gw, gr = '/tusb-gnome', '/run/tusb-gnome'
        guest.pop('LD_PRELOAD', None)
        base = ['proot', '--link2symlink', '--sysvipc', '-r', str(root), '-b', '/dev',
                '-b', '/proc', '-b', '/sys', '-b', str(w) + ':' + gw,
                '-b', str(w) + ':' + str(w),
                '-b', str(w / 'runtime') + ':' + gr,
                '-b', str(prefix / 'tmp/.X11-unix') + ':/tmp/.X11-unix']
        if args.gio_modules:
            module_dir = args.gio_modules.resolve()
            (root / 'tusb-gio-modules').mkdir(exist_ok=True)
            base += ['-b', str(module_dir) + ':/tusb-gio-modules']
        if args.gio_helper:
            target = root / 'tusb-gio-helper'
            if not target.exists():
                target.touch()
            base += ['-b', str(args.gio_helper.resolve()) + ':/tusb-gio-helper']
        base += ['-w', '/', '/usr/bin/env', '-i']
    values = dict(HOME=gw, DISPLAY=args.display, XDG_RUNTIME_DIR=gr,
                  XDG_CONFIG_HOME=gw + '/config', XDG_CACHE_HOME=gw + '/cache',
                  XDG_DATA_HOME=gw + '/data', XDG_CURRENT_DESKTOP='GNOME',
                  XDG_SESSION_TYPE='x11', XDG_SESSION_DESKTOP='gnome',
                  DBUS_SYSTEM_BUS_ADDRESS='unix:path=' + gr + '/system-bus',
                  GDK_BACKEND='x11', GSK_RENDERER='cairo', GTK_A11Y='none', NO_AT_BRIDGE='1',
                  LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe',
                  GST_PLUGIN_FEATURE_RANK=os.environ.get('GST_PLUGIN_FEATURE_RANK',
                      'ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0'),
                  PIPEWIRE_RUNTIME_DIR=gr, PIPEWIRE_REMOTE='pipewire-0',
                  TERMUX_USB_DESKTOP_DIR=str(w / 'runtime/usb'),
                  LANG='C.UTF-8', TZ=os.environ.get('TZ', 'Asia/Shanghai'))
    if android:
        for env in (host, guest, values):
            for key in ('DISPLAY', 'LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER',
                        'MESA_LOADER_DRIVER_OVERRIDE', 'COGL_DRIVER', 'CLUTTER_DRIVER'):
                env.pop(key, None)
        values['GST_PLUGIN_FEATURE_RANK'] = 'ximagesink:0,xvimagesink:0,cacasink:0'
        values.update(XDG_SESSION_TYPE='wayland', GDK_BACKEND='wayland',
                      WAYLAND_DISPLAY=args.wayland_display)
    if args.rootfs:
        values.update(PATH='/usr/sbin:/usr/bin:/sbin:/bin',
                      XDG_DATA_DIRS='/usr/local/share:/usr/share', XDG_CONFIG_DIRS='/etc/xdg')
        if args.gio_modules:
            values['GIO_EXTRA_MODULES'] = '/tusb-gio-modules'
        base += [k + '=' + v for k, v in values.items()]
    else:
        values.update(XDG_DATA_DIRS=str(prefix / 'share'),
                      XDG_CONFIG_DIRS=str(prefix / 'etc/xdg'),
                      GST_PLUGIN_PATH=str(prefix / 'lib/termux-usb-bridge/gstreamer-1.0') +
                          (':' + os.environ['GST_PLUGIN_PATH'] if os.environ.get('GST_PLUGIN_PATH') else ''))
        if args.gio_modules:
            values['GIO_EXTRA_MODULES'] = str(args.gio_modules.resolve())
        guest.update(values)
        host.update(values)
    (w / 'systembus.conf').write_text('<busconfig><type>system</type><listen>unix:path=' +
        escape(gr + '/system-bus') + '</listen><auth>EXTERNAL</auth><policy context="default">'
        '<allow user="*"/><allow own="*"/><allow send_destination="*"/>'
        '<allow receive_sender="*"/></policy></busconfig>')
    (w / 'config/gnome-session/sessions/gnome-bridge.session').write_text(
        '[GNOME Session]\nName=GNOME rootless X11\nRequiredComponents=org.gnome.Shell;\n')
    (w / 'session.sh').write_text('''#!/bin/sh
set -eu
cd "$HOME"
printf 'export DBUS_SESSION_BUS_ADDRESS="%s"\\n' "$DBUS_SESSION_BUS_ADDRESS" > session.env
dbus-update-activation-environment ''' + ' '.join(values) + '''
gsettings set org.gnome.desktop.interface enable-animations ''' + ('true' if android else 'false') + '''
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
''' + (('exec env MUTTER_BACKEND=android MUTTER_ANDROID_GL_MODE=' + args.gl_mode +
        ' MUTTER_ANDROID_WIDTH=' + size[1] + ' MUTTER_ANDROID_HEIGHT=' + size[2] +
        ' CLUTTER_DRIVER=gles2 TERMUX_ANDROID_ZINK=' + ('0' if args.gl_mode == 'system' else '1') +
        ((' LD_PRELOAD=' + shlex.quote(str(prefix / 'lib/mutter-16/libmutter-android-system-egl.so') +
            (':' + host['LD_PRELOAD'] if host.get('LD_PRELOAD') else ''))) if args.gl_mode == 'system' else '') +
        ' gnome-shell --wayland --no-x11 --wayland-display=' + shlex.quote(args.wayland_display) + '\n')
        if android else 'exec gnome-session --session=gnome-bridge --autostart="$HOME/config/autostart" --disable-acceleration-check\n'))
    session_bus_args = ['dbus-run-session', '--config-file=' + gw + '/sessionbus.conf']
    if args.minimal_services:
        import shutil
        services = w / 'dbus-services'
        services.mkdir(exist_ok=True)
        for old in services.glob('*.service'):
            old.unlink()
        service_root = (args.rootfs.resolve() / 'usr/share/dbus-1/services' if args.rootfs
                        else prefix / 'share/dbus-1/services')
        service_names = ['ca.desrt.dconf', 'org.gtk.vfs.Daemon',
                         'org.gnome.Shell.Notifications', 'org.gnome.ScreenSaver']
        if args.portals:
            service_names += ['org.freedesktop.portal.Desktop',
                              'org.freedesktop.impl.portal.PermissionStore',
                              'org.freedesktop.impl.portal.desktop.gnome',
                              'org.freedesktop.impl.portal.desktop.gtk']
        for name in service_names:
            source = service_root / (name + '.service')
            if source.exists():
                shutil.copyfile(source, services / source.name)
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
    children, logs, timeouts, optional = [], [], {}, []
    def start(name, command, env):
        log = (w / (name + '.log')).open('w')
        logs.append(log)
        p = subprocess.Popen(command, env=env, cwd=w, stdout=log, stderr=log,
                             start_new_session=True)
        children.append(p)
        timeouts[p.pid] = 120 if name == 'bridge' else 10
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
        (w / 'host-session.env').unlink(missing_ok=True)
        (w / 'runtime/system-bus').unlink(missing_ok=True)
        (w / 'runtime/session-bus').unlink(missing_ok=True)
        x11 = None if android else start('x11', ['termux-x11', args.display, '-ac'], host)
        bus = start('systembus', base + ['dbus-daemon', '--config-file=' + gw + '/systembus.conf',
                    '--nofork', '--nopidfile'], guest)
        deadline = time.monotonic() + 30
        while not (w / 'runtime/system-bus').exists():
            if bus.poll() is not None or (x11 is not None and x11.poll() is not None) or time.monotonic() > deadline:
                raise RuntimeError('X11/system D-Bus startup failed; inspect work logs')
            time.sleep(.1)
        deadline = time.monotonic() + 30
        while not android and subprocess.run(['xdpyinfo', '-display', args.display], env=host,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode != 0:
            if x11.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('X11 display did not become ready')
            time.sleep(.1)
        session = start('session', base + session_bus_args + ['--', '/bin/sh' if args.rootfs else str(prefix / 'bin/sh'), gw + '/session.sh'], guest)
        import re
        deadline = time.monotonic() + 30
        while not (w / 'session.env').exists():
            if session.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('session D-Bus did not start')
            time.sleep(.1)
        address = re.search(r'DBUS_SESSION_BUS_ADDRESS="([^"]+)"',
                            (w / 'session.env').read_text()).group(1)
        host['DBUS_SESSION_BUS_ADDRESS'] = address.replace(gr + '/', str(w / 'runtime') + '/')
        export_keys = ('HOME', 'DISPLAY', 'XDG_RUNTIME_DIR', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME',
                       'XDG_CACHE_HOME', 'PIPEWIRE_RUNTIME_DIR', 'PIPEWIRE_REMOTE',
                       'DBUS_SYSTEM_BUS_ADDRESS', 'DBUS_SESSION_BUS_ADDRESS', 'TERMUX_USB_DESKTOP_DIR',
                       'GST_PLUGIN_PATH', 'GST_PLUGIN_FEATURE_RANK', 'GDK_BACKEND', 'GSK_RENDERER',
                       'LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER', 'GIO_EXTRA_MODULES', 'WAYLAND_DISPLAY',
                       'XDG_SESSION_TYPE', 'XDG_CURRENT_DESKTOP', 'XDG_SESSION_DESKTOP',
                       'XDG_DATA_DIRS', 'XDG_CONFIG_DIRS')
        (w / 'host-session.env').write_text(''.join('export ' + k + '=' + shlex.quote(host[k]) + '\n'
            for k in export_keys if k in host))
        (w / 'host-session.env').chmod(0o600)
        if args.pipewire:
            pipewire = start('pipewire', ['pipewire'], host)
            optional.append(('PipeWire', pipewire))
            deadline = time.monotonic() + 15
            while not (w / 'runtime/pipewire-0').exists():
                if pipewire.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('PipeWire socket did not appear')
                time.sleep(.1)
            optional.append(('WirePlumber', start('wireplumber', ['wireplumber'], host)))
        if args.bridge:
            if args.rootfs:
                wrapper = w / 'host-bin/tusb-gio-mount'
                wrapper.parent.mkdir(exist_ok=True)
                command = base + ['/bin/sh', '-c', '. ' + shlex.quote(gw + '/session.env') +
                                  '; exec "$@"', 'tusb-gio-helper', '/tusb-gio-helper']
                wrapper.write_text('#!' + str(prefix / 'bin/python') + '\nimport os,sys\nargv=' +
                                   repr(command) + '\nos.execvpe(argv[0],argv+sys.argv[1:],os.environ)\n')
                wrapper.chmod(0o700)
                host['PATH'] = str(wrapper.parent) + ':' + host['PATH']
            command = ['tusb-desktop', '--runtime', str(w / 'runtime/usb'), 'serve']
            if args.read_write:
                command.append('--read-write')
            bridge = start('bridge', command, host)
            optional.append(('USB bridge', bridge))
            deadline = time.monotonic() + 30
            while not (w / 'runtime/usb/control.sock').exists():
                if bridge.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('USB bridge did not become ready; inspect bridge.log')
                time.sleep(.1)
        print('GNOME session started; logs: ' + str(w), flush=True)
        while session.poll() is None:
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
        for p in reversed(children):
            if p.poll() is None:
                os.killpg(p.pid, signal.SIGTERM)
                try:
                    p.wait(timeout=timeouts[p.pid])
                except subprocess.TimeoutExpired:
                    os.killpg(p.pid, signal.SIGKILL)
                    p.wait()
        for log in logs:
            log.close()
        lock.close()


if __name__ == '__main__':
    raise SystemExit(main())
