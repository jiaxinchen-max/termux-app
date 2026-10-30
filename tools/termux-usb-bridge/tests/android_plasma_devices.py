# SPDX-License-Identifier: MIT
"""Validate installed device bridges in isolated native/PRoot Plasma X11 sessions.

Storage uses disposable FAT32/exFAT images; camera input is Android Camera2.
This does not simulate physical USB transport or test the Android/Wayland backend.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
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
    ap.add_argument('--display', default=':4')
    args = ap.parse_args()
    w = args.work.resolve()
    if (w / 'session.env').exists():
        ap.error('use a fresh work directory')
    w.mkdir(parents=True, exist_ok=True)
    for d in ('runtime', 'config', 'cache', 'data'):
        (w / d).mkdir(exist_ok=True)
    (w / 'runtime').chmod(0o700)
    (w / 'config/kwinrc').write_text('[Compositing]\nEnabled=false\n')
    prefix = Path(os.environ['PREFIX'])
    sys.path.insert(0, str(prefix / 'share/termux-usb-bridge'))
    from filesystem import Filesystem
    e = dict(os.environ, HOME=str(w), DISPLAY=args.display,
             XDG_RUNTIME_DIR=str(w / 'runtime'), PIPEWIRE_RUNTIME_DIR=str(w / 'runtime'),
             PIPEWIRE_REMOTE='pipewire-0', XDG_CONFIG_HOME=str(w / 'config'),
             XDG_CACHE_HOME=str(w / 'cache'), XDG_DATA_HOME=str(w / 'data'))
    common = dict(DISPLAY=args.display, XDG_CURRENT_DESKTOP='KDE', XDG_SESSION_TYPE='x11',
                  KDE_FULL_SESSION='true', KDE_SESSION_VERSION='6', QT_QPA_PLATFORM='xcb',
                  QT_QUICK_BACKEND='software', QSG_RENDER_LOOP='basic',
                  QT_XCB_GL_INTEGRATION='none', GDK_BACKEND='x11',
                  GST_PLUGIN_FEATURE_RANK='ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0',
                  PIPEWIRE_REMOTE='pipewire-0')
    if args.mode == 'native':
        ge = dict(e, **common, XDG_DATA_DIRS=str(prefix / 'share'),
                  XDG_CONFIG_DIRS=str(prefix / 'etc/xdg'),
                  GST_PLUGIN_PATH=str(prefix / 'lib/termux-usb-bridge/gstreamer-1.0'))
        base = []
        gw = str(w)
        activity = str(prefix / 'lib/libexec/kactivitymanagerd')
        kioclient = 'kioclient'
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
        base = ['proot', '--link2symlink', '-0', '-r', str(root), '-b', '/dev', '-b', '/proc',
                '-b', '/sys', '-b', str(w.parent) + ':/tusb-work',
                '-b', str(w / 'runtime') + ':/run/tusb-pw',
                '-b', str(prefix / 'tmp/.X11-unix') + ':/tmp/.X11-unix', '-w', '/',
                '/usr/bin/env', '-i'] + [k + '=' + v for k, v in values.items()]
        activity = '/usr/lib/aarch64-linux-gnu/libexec/kactivitymanagerd'
        kioclient = subprocess.check_output(
            base + ['/bin/sh', '-c', 'command -v kioclient6 || command -v kioclient'],
            env=ge, text=True, timeout=10).strip()
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
        geo = dict(line.split('=', 1) for line in desk(
            ['xdotool', 'getwindowgeometry', '--shell', window_id]).decode().splitlines())
        # Click the actual Breeze title-bar close control; XDestroyWindow is
        # not a normal client shutdown and global shortcuts may be unavailable.
        desk(['xdotool', 'mousemove', str(int(geo['X']) + int(geo['WIDTH']) - 15),
              str(int(geo['Y']) - 15), 'click', '1'])

    session = '''#!/bin/sh
set -eu
cd "$HOME"
export DBUS_SESSION_BUS_ADDRESS
printf 'export DBUS_SESSION_BUS_ADDRESS="%s"\n' "$DBUS_SESSION_BUS_ADDRESS" > session.env
dbus-update-activation-environment DISPLAY XDG_RUNTIME_DIR XDG_CONFIG_HOME XDG_CACHE_HOME XDG_DATA_HOME XDG_DATA_DIRS XDG_CONFIG_DIRS XDG_CURRENT_DESKTOP XDG_SESSION_TYPE QT_QPA_PLATFORM QT_QUICK_BACKEND
''' + shlex.quote(activity) + ''' > activities.log 2>&1 &
activity_pid=$!
kwin_x11 --replace > kwin.log 2>&1 &
kwin_pid=$!
trap 'kill "$plasma_pid" "$kwin_pid" "$activity_pid" 2>/dev/null || true' EXIT
sleep 3
plasmashell --no-respawn > plasma.log 2>&1 &
plasma_pid=$!
printf '%s %s %s\n' "$kwin_pid" "$plasma_pid" "$activity_pid" > desktop.pids
while kill -0 "$kwin_pid" && kill -0 "$plasma_pid"; do sleep 1; done
'''
    (w / 'desktop-session.sh').write_text(session)
    report = {'mode': args.mode, 'desktop_backend': 'KWin X11 / Plasma software QtQuick',
              'storage_backend': 'host FAT32/exFAT images over KIO WebDAV; no physical USB',
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
        session_process = start('session', base + ['dbus-run-session', '--', '/bin/sh',
                                gw + '/desktop-session.sh'], env=ge)
        wait(lambda: (w / 'session.env').exists(), 'session bus did not start')
        desktop_window = wait(lambda: window('plasmashell'), 'Plasma desktop window absent', 60)
        assert session_process.poll() is None
        wm = desk(['xprop', '-root', '_NET_SUPPORTING_WM_CHECK']).decode()
        wm_id = wm.split()[-1]
        wm_name = desk(['xprop', '-id', wm_id, '_NET_WM_NAME']).decode()
        assert 'KWin' in wm_name, wm_name
        report['window_manager'] = wm_name.strip()
        report['desktop_window'] = desktop_window
        (w / 'desktop-windows.log').write_bytes(desk(['xwininfo', '-root', '-tree']))
        screenshot('desktop.png')
        print('PASS', args.mode, 'KWin and Plasma desktop window', flush=True)

        start('pipewire', ['pipewire'])
        wait(lambda: (w / 'runtime/pipewire-0').exists(), 'PipeWire unavailable')
        start('wireplumber', ['wireplumber'])
        time.sleep(2)
        payload = bytes((i * 17 + 3) & 255 for i in range(131123))
        (w / 'payload.bin').write_bytes(payload)
        for kind in ('fat32', 'exfat'):
            image = w / (kind + '.img')
            run(['tusb-files', 'create-image', str(image), '--mib', '64'] +
                (['--exfat'] if kind == 'exfat' else []))
            fs = Filesystem(image=str(image), writable=True)
            try:
                fs.write('/Android_USB_Bridge.txt', b'Android storage -> KDE KIO\n', truncate=True)
            finally:
                fs.close()
            token = secrets.token_urlsafe(24)
            token_file = w / 'storage.token'
            token_file.write_text(token + '\n')
            token_file.chmod(0o600)
            ready = w / (kind + '.ready.json')
            server = start(kind + '-server', ['tusb-files', '--image', str(image), '--read-write',
                           'serve', '--port', '0', '--token-file', str(token_file),
                           '--ready-file', str(ready)])
            wait(ready.exists, 'storage service unavailable')
            port = json.loads(ready.read_text())['port']
            url = f'webdav://tusb:{token}@127.0.0.1:{port}'

            def kio(command, *paths):
                return desk([kioclient, '--noninteractive', command, *paths])

            listing = kio('ls', url + '/').decode()
            assert 'Android_USB_Bridge.txt' in listing, listing
            (w / (kind + '-kio-list.log')).write_text(listing)
            dolphin = start(kind + '-dolphin', desk_argv(['dolphin', '--new-window', url + '/']), env=ge)
            dolphin_window = wait(lambda: window('dolphin'), 'Dolphin window absent')
            time.sleep(2)
            desk(['xdotool', 'windowactivate', '--sync', dolphin_window])
            desk(['xdotool', 'key', '--clearmodifiers', 'ctrl+shift+n'])
            time.sleep(1)
            desk(['xdotool', 'type', '--clearmodifiers', '--delay', '25', 'KDE-bridge'])
            desk(['xdotool', 'key', 'Return'])
            wait(lambda: 'KDE-bridge' in kio('ls', url + '/').decode(),
                 'Dolphin GUI did not create the remote folder')
            directory = '/KDE-bridge'
            remote = url + quote(directory + '/原始.bin', safe='/')
            renamed = url + quote(directory + '/改名.bin', safe='/')
            kio('copy', 'file://' + gw + '/payload.bin', remote)
            kio('move', remote, renamed)
            local_copy = w / (kind + '-readback.bin')
            kio('copy', renamed, 'file://' + gw + '/' + local_copy.name)
            assert local_copy.read_bytes() == payload
            kio('copy', renamed, url + '/temporary-copy.bin')
            kio('remove', url + '/temporary-copy.bin')
            desk(['xdotool', 'windowactivate', '--sync', dolphin_window])
            desk(['xdotool', 'key', '--clearmodifiers', 'ctrl+l'])
            desk(['xdotool', 'type', '--clearmodifiers', '--delay', '1',
                  url + quote(directory, safe='/')])
            desk(['xdotool', 'key', 'Return'])
            time.sleep(3)
            screenshot(kind + '-dolphin.png')
            # Actual file-manager process remains alive while the remote folder is shown.
            assert dolphin.poll() is None
            close_window(dolphin_window)
            dolphin.wait(timeout=15)
            assert stop(server) == 0
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
            token_file.unlink()
            report['storage'].append({'format': kind, 'status': 'PASS', 'bytes': len(payload),
                                      'sha256': hashlib.sha256(payload).hexdigest(),
                                      'dolphin_window': dolphin_window})
            print('PASS', args.mode, kind, 'KIO list/mkdir/copy/move/remove, Dolphin GUI, persistence', flush=True)

        for label, camera_id in (('back', '10'), ('front', '1')):
            photos = w / ('photos-' + label)
            photos.mkdir()
            node = 'tusb.camera2.plasma.' + args.mode + '.' + label
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
            x, y, width = int(geo['X']), int(geo['Y']), int(geo['WIDTH'])
            for index in range(2):
                time.sleep(2)
                screenshot(label + f'-preview-{index}.png')
                desk(['xdotool', 'mousemove', str(x + width - 80), str(y + 54), 'click', '1'])
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
        print('PLASMA_DEVICE_PASS', args.mode, flush=True)
    finally:
        for p in reversed(children):
            stop(p)
        for f in logs:
            f.close()
        (w / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
