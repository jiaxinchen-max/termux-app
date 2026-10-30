# SPDX-License-Identifier: MIT
"""Validate installed device bridges in isolated native/PRoot GNOME X11 sessions.

Storage uses disposable FAT32/exFAT images; camera input is Android Camera2.
This does not simulate physical USB transport or test the Android/Wayland backend.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
from urllib.parse import quote


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--mode', choices=['native', 'proot'], required=True)
    ap.add_argument('--work', type=Path, required=True)
    ap.add_argument('--rootfs', type=Path)
    ap.add_argument('--camera-app', type=Path, required=True)
    ap.add_argument('--display', default=':6')
    ap.add_argument('--minimal-services', action='store_true',
                    help='Use a private D-Bus activation directory containing only GNOME and GVfs core services')
    ap.add_argument('--only', choices=['all', 'storage', 'cameras'], default='all')
    args = ap.parse_args()
    w = args.work.resolve()
    if (w / 'session.env').exists():
        ap.error('use a fresh work directory')
    w.mkdir(parents=True, exist_ok=True)
    for d in ('runtime', 'config', 'cache', 'data'):
        (w / d).mkdir(exist_ok=True)
    (w / 'runtime').chmod(0o700)
    prefix = Path(os.environ['PREFIX'])
    sys.path.insert(0, str(prefix / 'share/termux-usb-bridge'))
    from filesystem import Filesystem
    e = dict(os.environ, HOME=str(w), DISPLAY=args.display,
             XDG_RUNTIME_DIR=str(w / 'runtime'), PIPEWIRE_RUNTIME_DIR=str(w / 'runtime'),
             PIPEWIRE_REMOTE='pipewire-0', XDG_CONFIG_HOME=str(w / 'config'),
             XDG_CACHE_HOME=str(w / 'cache'), XDG_DATA_HOME=str(w / 'data'))
    common = dict(DISPLAY=args.display, XDG_CURRENT_DESKTOP='GNOME', XDG_SESSION_TYPE='x11',
                  GDK_BACKEND='x11', GSK_RENDERER='cairo', NO_AT_BRIDGE='1', GTK_A11Y='none',
                  LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe',
                  GST_PLUGIN_FEATURE_RANK='ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0',
                  PIPEWIRE_REMOTE='pipewire-0', TERMUX_USB_DESKTOP_DIR=str(w / 'runtime/usb'))
    if args.mode == 'native':
        ge = dict(e, **common, XDG_DATA_DIRS=str(prefix / 'share'),
                  XDG_CONFIG_DIRS=str(prefix / 'etc/xdg'),
                  GST_PLUGIN_PATH=str(prefix / 'lib/termux-usb-bridge/gstreamer-1.0'))
        base = []
        gw = str(w)
        ge['DBUS_SYSTEM_BUS_ADDRESS'] = 'unix:path=' + str(w / 'runtime/system-bus')
    else:
        if not args.rootfs:
            ap.error('--rootfs is required for proot')
        root = args.rootfs.resolve()
        for d in ('tusb-work', 'run/tusb-pw', 'tmp/.X11-unix'):
            (root / d).mkdir(parents=True, exist_ok=True)
        ge = dict(e)
        ge.pop('LD_PRELOAD', None)
        gw = '/tusb-work/' + w.name
        values = dict(common, HOME=gw, XDG_RUNTIME_DIR='/run/tusb-pw',
                      PIPEWIRE_RUNTIME_DIR='/run/tusb-pw', XDG_CONFIG_HOME=gw + '/config',
                      XDG_CACHE_HOME=gw + '/cache', XDG_DATA_HOME=gw + '/data',
                      PATH='/usr/sbin:/usr/bin:/sbin:/bin', XDG_DATA_DIRS='/usr/share:/usr/local/share',
                      XDG_CONFIG_DIRS='/etc/xdg')
        values['DBUS_SYSTEM_BUS_ADDRESS'] = 'unix:path=/run/tusb-pw/system-bus'
        values['GIO_EXTRA_MODULES'] = gw + '/gio-modules'
        base = ['proot', '--link2symlink', '--sysvipc', '-r', str(root), '-b', '/dev', '-b', '/proc',
                '-b', '/sys', '-b', str(w.parent) + ':/tusb-work',
                '-b', str(w / 'runtime') + ':/run/tusb-pw',
                '-b', str(w.parent) + ':' + str(w.parent),
                '-b', str(prefix / 'tmp/.X11-unix') + ':/tmp/.X11-unix', '-w', '/',
                '/usr/bin/env', '-i'] + [k + '=' + v for k, v in values.items()]
    children = []
    logs = []

    def start(name, command, env=e, cwd=w):
        f = (w / (name + '.log')).open('w')
        logs.append(f)
        p = subprocess.Popen(command, env=env, cwd=cwd, stdout=f, stderr=f,
                             start_new_session=True)
        children.append(p)
        return p

    def stop(p):
        if p.poll() is None:
            os.killpg(p.pid, signal.SIGTERM)
        try:
            return p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)
            return p.wait()

    def run(command, env=e, timeout=30):
        r = subprocess.run(command, env=env, cwd=w, capture_output=True, timeout=timeout)
        if r.returncode:
            raise RuntimeError(f'{command[0]} exited {r.returncode}: '
                               + r.stderr.decode(errors='replace')[-3000:])
        return r.stdout

    def wait(test, description, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = test()
            if value:
                return value
            time.sleep(.2)
        raise RuntimeError(description)

    def desk_argv(command, cwd=gw):
        script = '. ' + shlex.quote(gw + '/session.env') + '; cd ' + shlex.quote(cwd)
        return base + ['/bin/sh', '-c', script + '; exec ' + shlex.join(command)]

    def desk(command, timeout=30):
        return run(desk_argv(command), env=ge, timeout=timeout)

    def window(pattern):
        r = subprocess.run(desk_argv(['xdotool', 'search', '--onlyvisible', '--class', pattern]),
                           env=ge, capture_output=True, text=True, timeout=10)
        return r.stdout.strip().splitlines()[0] if r.returncode == 0 and r.stdout.strip() else None

    def screenshot(name):
        run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'x11grab', '-i', args.display,
             '-frames:v', '1', str(w / name)])

    def close_window(window_id):
        desk(['xdotool', 'windowactivate', '--sync', window_id, 'key', '--clearmodifiers', 'alt+F4'])

    session = """#!/bin/sh
set -eu
cd "$HOME"
export DBUS_SESSION_BUS_ADDRESS
printf 'export DBUS_SESSION_BUS_ADDRESS="%s"\\n' "$DBUS_SESSION_BUS_ADDRESS" > session.env
dbus-update-activation-environment DISPLAY XDG_RUNTIME_DIR XDG_CONFIG_HOME XDG_CACHE_HOME XDG_DATA_HOME XDG_DATA_DIRS XDG_CONFIG_DIRS XDG_CURRENT_DESKTOP XDG_SESSION_TYPE GDK_BACKEND GSK_RENDERER LIBGL_ALWAYS_SOFTWARE GALLIUM_DRIVER GIO_EXTRA_MODULES TERMUX_USB_DESKTOP_DIR PIPEWIRE_RUNTIME_DIR PIPEWIRE_REMOTE GST_PLUGIN_FEATURE_RANK
gsettings set org.gnome.desktop.interface enable-animations false
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
exec gnome-session --session=gnome-bridge --autostart="$HOME/config/autostart" --disable-acceleration-check --debug
"""
    for d in ('config/autostart', 'config/gnome-session/sessions', 'gio-modules', 'bin'):
        (w / d).mkdir(parents=True, exist_ok=True)
    (w / 'config/gnome-session/sessions/gnome-bridge.session').write_text(
        '[GNOME Session]\nName=GNOME rootless bridge validation\nRequiredComponents=org.gnome.Shell;\n')
    (w / 'desktop-session.sh').write_text(session)
    session_bus_args = ['dbus-run-session']
    if args.minimal_services:
        import shutil
        services = w / 'dbus-services'
        services.mkdir()
        service_root = (args.rootfs / 'usr/share/dbus-1/services' if args.mode == 'proot'
                        else prefix / 'share/dbus-1/services')
        names = ('ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gnome.Shell.Notifications',
                 'org.gnome.ScreenSaver')
        for name in names:
            source = service_root / (name + '.service')
            if source.exists():
                shutil.copyfile(source, services / source.name)
        (w / 'sessionbus.conf').write_text('<busconfig><type>session</type><listen>unix:path=' +
            str(w / 'runtime/session-bus') + '</listen><auth>EXTERNAL</auth><servicedir>' +
            gw + '/dbus-services</servicedir><policy context="default"><allow user="*"/>'
            '<allow own="*"/><allow send_destination="*"/><allow receive_sender="*"/>'
            '</policy></busconfig>')
        session_bus_args += ['--config-file=' + gw + '/sessionbus.conf']
    bus_address = ('unix:path=/run/tusb-pw/system-bus' if args.mode == 'proot'
                   else 'unix:path=' + str(w / 'runtime/system-bus'))
    (w / 'systembus.conf').write_text('<busconfig><type>system</type>'
        '<listen>' + bus_address + '</listen><auth>EXTERNAL</auth>'
        '<policy context="default"><allow user="*"/><allow own="*"/>'
        '<allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>')
    report = {'mode': args.mode, 'desktop_backend': 'GNOME Shell X11 / llvmpipe',
              'storage_backend': 'host FAT32/exFAT images over GIO/GVfs; no physical USB',
              'camera_backend': 'Android Camera2 HAL via host PipeWire', 'storage': [], 'cameras': []}
    try:
        # Debian splits ximagesink into gstreamer1.0-x. Without it autovideosink
        # can fall back to a fake sink while camera discovery/capture still work.
        for plugin in ('ximagesink', 'pipewiresrc', 'jpegenc'):
            (w / (plugin + '-inspect.log')).write_bytes(
                run(base + ['gst-inspect-1.0', plugin], env=ge))
        start('x11', ['termux-x11', args.display, '-ac'])
        wait(lambda: subprocess.run(['xdpyinfo', '-display', args.display], env=e,
             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3).returncode == 0,
             'X11 server unavailable', 30)
        if args.mode == 'proot':
            script = ('set -e; cd ' + shlex.quote(gw) + '; '
                'gcc -O2 -fPIC -shared sources/volume_monitor.c '
                '$(pkg-config --cflags --libs gio-unix-2.0 gmodule-2.0) -o gio-modules/libtusb-volume-monitor.so; '
                'gio-querymodules gio-modules; '
                'gcc -O2 sources/gio_mount.c $(pkg-config --cflags --libs gio-2.0) -o bin/tusb-gio-mount; ')
        else:
            (w / 'bin/tusb-gio-mount').symlink_to(prefix / 'bin/tusb-gio-mount')
            script = 'set -e; cd ' + shlex.quote(gw) + '; '
        compiler = 'gcc' if args.mode == 'proot' else 'clang'
        script += (compiler + ' -O2 sources/desktop_probe.c $(pkg-config --cflags --libs gio-2.0) '
            '-o bin/desktop-probe; ' + compiler + ' -O2 -Wno-deprecated-declarations ' +
            shlex.quote('-DCAMERA_APPS_UIDIR="' + gw + '/sources"') + ' sources/gst-camera2.c '
            '$(pkg-config --cflags --libs gtk+-3.0 gdk-x11-3.0 gstreamer-video-1.0 '
            'gstreamer-pbutils-1.0 gmodule-export-2.0) -o bin/gst-camera2')
        (w / 'compile.log').write_bytes(run(base + ['/bin/sh' if args.mode == 'proot' else str(prefix / 'bin/sh'),
                                         '-c', script], env=ge, timeout=120))
        start('systembus', base + ['dbus-daemon', '--config-file=' + gw + '/systembus.conf',
              '--nofork', '--nopidfile'], env=ge)
        wait(lambda: (w / 'runtime/system-bus').exists(), 'private system bus unavailable')
        session_process = start('session', base + session_bus_args + ['--', '/bin/sh',
                                gw + '/desktop-session.sh'], env=ge)
        wait(lambda: (w / 'session.env').exists(), 'session bus did not start')
        def current_wm():
            value = run(['xprop', '-display', args.display, '-root', '_NET_SUPPORTING_WM_CHECK'])
            return value.decode().split()[-1] if b'0x' in value else None
        wm = wait(current_wm, 'GNOME Shell absent', 100)
        wm_name = desk(['xprop', '-id', wm, '_NET_WM_NAME']).decode()
        assert 'GNOME Shell' in wm_name, wm_name
        time.sleep(8)
        assert session_process.poll() is None and current_wm() == wm, 'GNOME Shell exited during startup'
        desk(['xdotool', 'key', 'Escape'])
        report['window_manager'] = wm_name.strip()
        screenshot('desktop.png')
        print('PASS', args.mode, 'GNOME Shell desktop', flush=True)
        # Keep the filesystem server in Bionic; its mount helper must use the
        # same glibc GVfs and D-Bus session as Files inside the container.
        wrapper = w / 'bin/host-gio/tusb-gio-mount'
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text('#!' + str(prefix / 'bin/python') + '\nimport os,sys\nargv=' +
            repr(base + ['/bin/sh', '-c', '. ' + shlex.quote(gw + '/session.env') +
                '; exec "$@"', 'tusb-helper', gw + '/bin/tusb-gio-mount']) +
            '\nos.execvpe(argv[0],argv+sys.argv[1:],os.environ)\n')
        wrapper.chmod(0o700)
        host_env = dict(e, PATH=str(wrapper.parent) + ':' + e['PATH'])
        probe = gw + '/bin/desktop-probe'
        def mounted(key):
            import configparser
            config = configparser.ConfigParser(interpolation=None)
            config.read(w / 'runtime/usb/volumes.ini')
            sec = 'volume:' + key
            return dict(config[sec]) if config.has_section(sec) and config[sec]['mounted'] == 'true' else None
        if args.minimal_services:
            import re
            address = re.search(r'DBUS_SESSION_BUS_ADDRESS="([^"]+)"',
                                (w / 'session.env').read_text()).group(1)
            e['DBUS_SESSION_BUS_ADDRESS'] = address
            host_env['DBUS_SESSION_BUS_ADDRESS'] = address
        start('pipewire', ['pipewire'])
        wait(lambda: (w / 'runtime/pipewire-0').exists(), 'PipeWire unavailable')
        start('wireplumber', ['wireplumber'])
        time.sleep(2)
        payload = bytes((i * 17 + 3) & 255 for i in range(131123))
        (w / 'payload.bin').write_bytes(payload)
        for kind in (() if args.only == 'cameras' else ('fat32', 'exfat')):
            image = w / (kind + '.img')
            run(['tusb-files', 'create-image', str(image), '--mib', '64'] +
                (['--exfat'] if kind == 'exfat' else []))
            fs = Filesystem(image=str(image), writable=True)
            try:
                fs.write('/Android_USB_Bridge.txt', b'Android storage -> GNOME GIO\n', truncate=True)
            finally:
                fs.close()
            manifest = w / (kind + '-devices.json')
            manifest.write_text('{}')
            path = '/dev/bus/usb/001/002'
            key = hashlib.sha256(path.encode()).hexdigest()[:16]
            uuid = 'tusb-' + key
            daemon = start(kind + '-desktop', ['tusb-desktop', '--runtime', str(w / 'runtime/usb'),
                'serve', '--test-manifest', str(manifest), '--read-write', '--interval', '0.2'], env=host_env)
            manifest.write_text(json.dumps({path: {'image': str(image), 'name': 'USB ' + kind.upper()}}))
            current = wait(lambda: mounted(key), 'automatic GVfs volume mount', 70)
            listing = desk([probe, 'list']).decode()
            assert listing.count(uuid) == 1, listing
            (w / (kind + '-volumes.log')).write_text(listing)
            url = current['uri']
            def gio(command, *paths):
                return desk(['gio', command, *paths], timeout=60)
            files = start(kind + '-files', desk_argv(['nautilus', '--new-window', url]), env=ge)
            files_window = wait(lambda: window('org.gnome.Nautilus'), 'Files window absent')
            time.sleep(2)
            desk(['xdotool', 'windowactivate', '--sync', files_window])
            desk(['xdotool', 'key', '--clearmodifiers', 'ctrl+shift+n'])
            time.sleep(1)
            desk(['xdotool', 'type', '--clearmodifiers', '--delay', '25', 'GNOME-bridge'])
            desk(['xdotool', 'key', 'Return'])
            wait(lambda: 'GNOME-bridge' in gio('list', url).decode(),
                 'Files GUI did not create the remote folder')
            directory = '/GNOME-bridge'
            remote = url + quote(directory + '/原始.bin', safe='/')
            renamed = url + quote(directory + '/改名.bin', safe='/')
            gio('copy', gw + '/payload.bin', remote)
            gio('move', remote, renamed)
            local_copy = w / (kind + '-readback.bin')
            gio('copy', renamed, gw + '/' + local_copy.name)
            assert local_copy.read_bytes() == payload
            gio('copy', renamed, url + '/temporary-copy.bin')
            gio('remove', url + '/temporary-copy.bin')
            desk(['xdotool', 'windowactivate', '--sync', files_window])
            desk(['xdotool', 'key', '--clearmodifiers', 'ctrl+l'])
            desk(['xdotool', 'type', '--clearmodifiers', '--delay', '1',
                  url + quote(directory, safe='/')])
            desk(['xdotool', 'key', 'Return'])
            time.sleep(3)
            screenshot(kind + '-files.png')
            # Actual file-manager process remains alive while the remote folder is shown.
            assert files.poll() is None
            desk([probe, 'unmount', uuid], timeout=100)
            if files.poll() is None and window('org.gnome.Nautilus'):
                close_window(files_window)
            files.wait(timeout=20)
            assert stop(daemon) == 0
            fs = Filesystem(image=str(image))
            try:
                assert fs.read(directory + '/改名.bin', len(payload) + 1) == payload
                try:
                    fs.stat('/temporary-copy.bin')
                except FileNotFoundError:
                    pass
                else:
                    raise AssertionError('KIO delete did not persist')
            finally:
                fs.close()
            report['storage'].append({'format': kind, 'status': 'PASS', 'bytes': len(payload),
                                      'sha256': hashlib.sha256(payload).hexdigest(),
                                      'files_window': files_window, 'automatic_volume': uuid})
            print('PASS', args.mode, kind, 'GIO list/mkdir/copy/move/remove, Files GUI, persistence', flush=True)

        for label, camera_id in (() if args.only == 'storage' else (('back', '10'), ('front', '1'))):
            photos = w / ('photos-' + label)
            photos.mkdir()
            node = 'tusb.camera2.gnome.' + args.mode + '.' + label
            source = start(label + '-bridge', ['tusb-camera2', 'serve', '--camera', camera_id,
                           '--name', node])
            wait(lambda: node in run(['pw-dump']).decode(), 'camera source absent')
            (w / (label + '-devices.log')).write_bytes(desk(['gst-device-monitor-1.0', 'Video/Source']))
            viewer = start(label + '-viewer', desk_argv([str(args.camera_app)],
                           cwd=gw + '/' + photos.name), env=ge)
            # Cold GTK/GStreamer startup under PRoot can exceed 35 seconds.
            camera_window = wait(lambda: window('gst-camera2'), 'camera application window absent', 120)
            wait(lambda: node + ':' in run(['pw-link', '-l']).decode() and
                 'gst-camera2:input_' in run(['pw-link', '-l']).decode(), 'camera did not auto-connect')
            (w / (label + '-links.log')).write_bytes(run(['pw-link', '-l']))
            geo = dict(line.split('=', 1) for line in desk(
                ['xdotool', 'getwindowgeometry', '--shell', camera_window]).decode().splitlines())
            width = int(geo['WIDTH'])
            (w / (label + '-geometry.json')).write_text(json.dumps(geo))
            (w / (label + '-xwininfo.log')).write_bytes(desk(['xwininfo', '-id', camera_window]))
            desk(['xdotool', 'windowactivate', '--sync', camera_window])
            for index in range(2):
                time.sleep(2)
                screenshot(label + f'-preview-{index}.png')
                desk(['xdotool', 'mousemove', '--window', camera_window, str(width - 80), '54', 'click', '1'])
                time.sleep(1)
                screenshot(label + f'-after-click-{index}.png')
                photo = photos / ('cap_' + str(index))
                wait(lambda: photo.exists() and photo.stat().st_size > 1000, 'GUI capture not saved')
            close_window(camera_window)
            assert viewer.wait(timeout=15) == 0
            assert stop(source) == 0
            wait(lambda: node not in run(['pw-dump']).decode(), 'camera node leaked')
            report['cameras'].append({'label': label, 'id': camera_id, 'status': 'PASS',
                                      'window': camera_window, 'photos': 2})
            print('PASS', args.mode, label, 'automatic camera connection and two GUI captures', flush=True)
        report['status'] = 'PASS'
        print('GNOME_DEVICE_PASS', args.mode, flush=True)
    except BaseException as error:
        report['status'] = 'FAIL'
        report['error'] = str(error)
        raise
    finally:
        for p in reversed(children):
            stop(p)
        for f in logs:
            f.close()
        (w / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
