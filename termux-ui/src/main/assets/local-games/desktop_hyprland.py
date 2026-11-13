#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Private Hyprland candidate installation and owned Android desktop sessions."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import runpy
import secrets
import shlex
import shutil
import signal
import socket
import struct
import subprocess
import tarfile
import tempfile
import time

VERSION = '0.57.0-r7'
RELEASE = 'hyprland-android-0.57.0-70ec30e7-r7'
ASSET = 'hyprland-android-runtime-aarch64-0.57.0-r7.tar.gz'
ARCHIVE_SHA256 = '2fd3bd98104d7b411c9760efb2ad3270e950a1be4a146fa089b19a6fea169b10'
MANIFEST_SHA256 = '6024fd61a4923031ce9d7c4d35c1d545e75cbff16fe1ae9ab8aab86a0167f948'
PREFIX = Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr'))
HOME = Path(os.environ.get('HOME', '/data/data/com.termux/files/home'))


def runtime_root():
    return PREFIX / 'opt/termux-hyprland' / VERSION


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify(root):
    manifest_path = root / 'manifest.json'
    if digest(manifest_path) != MANIFEST_SHA256:
        raise RuntimeError('Hyprland manifest identity mismatch; reinstall desktop')
    manifest = json.loads(manifest_path.read_text())
    for name, expected in manifest['files'].items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts:
            raise RuntimeError('Invalid component manifest path')
        target = root / name
        if target.is_symlink() or digest(target) != expected:
            raise RuntimeError('Hyprland component mismatch: ' + name)
    for name in manifest['needed']:
        with (root / name).open('rb') as stream:
            header = stream.read(20)
        if header[:4] != b'\x7fELF' or struct.unpack('<H', header[18:20])[0] != 183:
            raise RuntimeError('Hyprland requires AArch64 ELF: ' + name)
    return manifest


def install(archive):
    if digest(archive) != ARCHIVE_SHA256:
        raise RuntimeError('Hyprland archive SHA-256 mismatch')
    root = runtime_root()
    root.parent.mkdir(parents=True, exist_ok=True)
    if root.parent.is_symlink() or root.is_symlink():
        raise RuntimeError('Hyprland installation path must not be a symlink')
    install_lock = (root.parent / '.install.lock').open('w')
    fcntl.flock(install_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    if root.exists():
        try:
            verify(root)
            print('Hyprland runtime already verified', flush=True)
            install_lock.close()
            return
        except (OSError, RuntimeError, ValueError):
            print('Repairing damaged Hyprland runtime from verified archive', flush=True)
    staging = Path(tempfile.mkdtemp(prefix='.install-', dir=root.parent))
    try:
        with tarfile.open(archive, 'r:gz') as tar:
            total = 0
            seen = set()
            for member in tar:
                path = PurePosixPath(member.name)
                if (path.is_absolute() or '..' in path.parts or member.name in seen
                        or not (member.isdir() or member.isfile())):
                    raise RuntimeError('Unsafe component archive member: ' + member.name)
                seen.add(member.name)
                total += member.size
                if total > 512 * 1024 * 1024:
                    raise RuntimeError('Hyprland component is too large')
                target = staging / path
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as source, target.open('xb') as output:
                        shutil.copyfileobj(source, output)
                    target.chmod(0o755 if member.mode & 0o111 else 0o644)
        verify(staging)
        (staging / '.installed').write_text(MANIFEST_SHA256 + '\n')
        backup = root.with_name(root.name + '.repair-backup')
        if backup.exists():
            raise RuntimeError('Hyprland repair backup exists; inspect previous installation')
        if root.exists():
            root.rename(backup)
        try:
            staging.rename(root)
        except OSError:
            if backup.exists():
                backup.rename(root)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        print('Hyprland private runtime installed and verified', flush=True)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
        install_lock.close()


def work_for(desktop_id):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', desktop_id):
        raise ValueError('Invalid desktop id')
    return HOME / '.termux' / ('hyprland-' + desktop_id)


def session_runtime(work):
    # Unix socket paths must fit 107 bytes including the Hyprland instance suffix.
    return PREFIX / 'tmp' / ('hyprland-' + hashlib.sha256(work.name.encode()).hexdigest()[:16])


def clean_environment(work):
    root = runtime_root()
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(('KWIN_', 'WLR_', 'MUTTER_', 'CLUTTER_')) or key in {
            'LD_PRELOAD', 'LD_LIBRARY_PATH', 'DISPLAY', 'WAYLAND_DISPLAY',
            'HYPRLAND_INSTANCE_SIGNATURE', 'EGL_PLATFORM', 'GBM_BACKEND',
            'MESA_LOADER_DRIVER_OVERRIDE', 'GALLIUM_DRIVER', 'LIBGL_ALWAYS_SOFTWARE',
            'DBUS_SESSION_BUS_ADDRESS', 'DBUS_SYSTEM_BUS_ADDRESS', 'PLASMA_LOG'}:
            env.pop(key, None)
    env.update(PATH=f'{root}/bin:{PREFIX}/bin:/system/bin',
               LD_LIBRARY_PATH=f'{root}/lib:{PREFIX}/lib',
               XDG_RUNTIME_DIR=str(session_runtime(work)), XDG_CONFIG_HOME=str(work / 'config'),
               XDG_CACHE_HOME=str(work / 'cache'), XDG_DATA_HOME=str(work / 'data'),
               XDG_DATA_DIRS=f'{root}/share:{PREFIX}/share',
               XDG_CURRENT_DESKTOP='Hyprland', XDG_SESSION_TYPE='wayland',
               XKB_CONFIG_ROOT=str(root / 'share/X11/xkb'),
               HYPRLAND_ANDROID_BACKEND='1', HYPRLAND_NO_RT='1')
    return env


def ipc(env, *arguments):
    return subprocess.check_output([str(runtime_root() / 'bin/hyprctl'), *arguments],
                                   env=env, text=True, stderr=subprocess.STDOUT, timeout=5)


def prepare_shell(work, run_mode):
    return runpy.run_path(str(Path(__file__).with_name('desktop_hyprland_shell.py')))['prepare'](work, run_mode)


def verify_shell():
    for name in ['bin/eww', 'bin/foot', 'bin/fish', 'bin/starship', 'bin/fc-cache', 'bin/xfce4-panel', 'bin/swaybg', 'bin/xfconf-query', 'bin/dbus-send',
                 'lib/xfce4/notifyd/xfce4-notifyd', 'lib/xfce4/panel/plugins/libgenmon.so']:
        if not (PREFIX / name).is_file():
            raise RuntimeError('Hyprland desktop dependency missing: ' + name + '; reinstall desktop')
    probe = subprocess.run([str(PREFIX / 'bin/python3'), '-I', '-c',
                            "import gi; gi.require_version('Gtk', '3.0'); "
                            "gi.require_version('GtkLayerShell', '0.1'); "
                            "from gi.repository import Gtk, GtkLayerShell"],
                           capture_output=True, text=True, timeout=15)
    if probe.returncode:
        raise RuntimeError('Hyprland desktop requires PyGObject and GtkLayerShell; reinstall desktop: '
                           + probe.stderr[-1000:])


def owned_service_pids(token):
    marker = ('TERMUX_HYPRLAND_SESSION=' + token).encode()
    result = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdecimal() or int(entry.name) == os.getpid():
            continue
        try:
            if entry.stat().st_uid == os.getuid() and marker in (entry / 'environ').read_bytes().split(b'\0'):
                result.append(int(entry.name))
        except (OSError, ValueError):
            pass
    return result


CLIENT_CLEAN_KEYS = (
    'LD_LIBRARY_PATH', 'LD_PRELOAD', 'VK_DRIVER_FILES', 'VK_ICD_FILENAMES',
    'MESA_LOADER_DRIVER_OVERRIDE', 'GALLIUM_DRIVER', 'LIBGL_ALWAYS_SOFTWARE',
    'MESA_GL_VERSION_OVERRIDE', 'MESA_GLES_VERSION_OVERRIDE', 'EGL_PLATFORM', 'GBM_BACKEND',
    '__EGL_VENDOR_LIBRARY_FILENAMES', '__EGL_VENDOR_LIBRARY_DIRS', 'LIBGL_DRIVERS_PATH',
    'QT_QUICK_BACKEND', 'QSG_RHI_BACKEND', 'QT_OPENGL', 'QT_XCB_GL_INTEGRATION',
    'QT_WAYLAND_CLIENT_BUFFER_INTEGRATION', 'QT_PLUGIN_PATH', 'QT_QPA_PLATFORM_PLUGIN_PATH',
    'GDK_GL', 'GSK_RENDERER', 'LIBGL_ALWAYS_INDIRECT', 'WRAPPER_VULKAN_PATH',
    'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_DATA_DIRS', 'XDG_CACHE_HOME',
    'DBUS_SESSION_BUS_ADDRESS', 'DBUS_SYSTEM_BUS_ADDRESS', 'DISPLAY',
)


def container_command(work, alias, client_mode, program, token):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', alias or ''):
        raise ValueError('Invalid container alias')
    if client_mode not in ('default', 'zink'):
        raise ValueError('Unsupported Hyprland container renderer')
    helper = Path(__file__).with_name('desktop_hyprland_gpu.py')
    clean = [item for key in CLIENT_CLEAN_KEYS for item in ('-u', key)]
    guest_env = ['XDG_RUNTIME_DIR=/tmp/hyprland-runtime', 'WAYLAND_DISPLAY=wayland-hyprland',
                 'GDK_BACKEND=wayland', 'QT_QPA_PLATFORM=wayland', 'XDG_SESSION_TYPE=wayland',
                 'XDG_CURRENT_DESKTOP=Hyprland', 'PULSE_SERVER=tcp:127.0.0.1:4713',
                 'TERMUX_HYPRLAND_SESSION=' + token]
    if client_mode == 'zink':
        guest_env += ['VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json',
                      'VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json',
                      'MESA_LOADER_DRIVER_OVERRIDE=zink', 'GALLIUM_DRIVER=zink',
                      'LIBGL_ALWAYS_SOFTWARE=0', 'EGL_PLATFORM=wayland',
                      'QSG_RHI_BACKEND=vulkan', 'QT_OPENGL=desktop',
                      'QT_WAYLAND_CLIENT_BUFFER_INTEGRATION=wayland-egl']
    return [str(PREFIX / 'bin/env'), *clean, str(PREFIX / 'bin/proot-distro'), 'login', alias,
            '--shared-tmp', '--bind', str(work / 'runtime') + ':/tmp/hyprland-runtime',
            '--bind', str(helper) + ':/tmp/hyprland-gpu-check.py', '--',
            'env', *clean, *guest_env, *program]


def verify_zink_host(work, env, log):
    report = work / 'gpu-host-report.json'
    report.unlink(missing_ok=True)
    helper = Path(__file__).with_name('desktop_hyprland_gpu.py')
    result = subprocess.run([str(PREFIX / 'bin/env'), '-u', 'LD_LIBRARY_PATH', '-u', 'LD_PRELOAD',
        str(PREFIX / 'bin/python3'), '-I', '-B', str(helper), 'host-check', '--report', str(report)],
        env=env, stdout=log, stderr=subprocess.STDOUT, timeout=20)
    if result.returncode:
        raise RuntimeError('Experimental container Zink is unsupported by this device/display; see '
                           + str(report) + ' and session.log; select default container mode')


def verify_zink_client(work, env, alias, log, clients):
    report = work / 'runtime/gpu-client-report.json'
    report.unlink(missing_ok=True)
    report.with_suffix('.ack').unlink(missing_ok=True)
    (work / 'gpu-client-report.json').unlink(missing_ok=True)
    command = container_command(work, alias, 'zink',
        ['python3', '-I', '-B', '/tmp/hyprland-gpu-check.py', 'guest-check',
         '--report', '/tmp/hyprland-runtime/gpu-client-report.json'], env['TERMUX_HYPRLAND_SESSION'])
    process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    clients.append(process)
    deadline = time.monotonic() + 40
    observed_window = False
    while time.monotonic() < deadline:
        if report.exists():
            try:
                result = json.loads(report.read_text())
            except ValueError:
                result = {}
            if result.get('status') == 'Failed':
                raise RuntimeError('Container GPU test failed: ' + result.get('error', 'see session.log'))
            windows = json.loads(ipc(env, '-j', 'clients'))
            observed_window |= any(w.get('mapped') and w.get('title') == 'Hyprland GPU check' for w in windows)
            if result.get('status') == 'Rendered' and observed_window:
                gpu = runpy.run_path(str(Path(__file__).with_name('desktop_hyprland_gpu.py')))
                gpu['require_hardware'](result.get('renderer', ''), require_zink=True)
                result.update(mapped_wayland_window=True, status='PASS')
                # Ask the guest to close its window and exit through proot normally.
                report.with_suffix('.ack').write_text('observed\n')
                process.wait(timeout=5)
                if process.returncode:
                    raise RuntimeError('Container GPU diagnostic exited unsuccessfully; see session.log')
                clients.remove(process)
                (work / 'gpu-client-report.json').write_text(json.dumps(result, indent=2) + '\n')
                return
        if process.poll() is not None:
            break
        time.sleep(.2)
    raise RuntimeError('Container Zink failed to render/map a hardware Wayland window; see session.log; select default container mode')


def session(args):
    client_mode = getattr(args, 'client_gl_mode', 'default')
    if client_mode not in ('default', 'zink') or (client_mode == 'zink' and args.run_mode != 'proot'):
        raise ValueError('Experimental Zink is supported only for PRoot Hyprland clients')
    work = work_for(args.desktop_id)
    work.mkdir(mode=0o700, parents=True, exist_ok=True)
    if work.is_symlink() or work.stat().st_uid != os.getuid():
        raise RuntimeError('Unsafe Hyprland session directory')
    lock = (work / 'session.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    for name in ['config', 'cache', 'data']:
        p = work / name
        if p.is_symlink():
            raise RuntimeError('Unsafe Hyprland private directory: ' + name)
        p.mkdir(mode=0o700, exist_ok=True)
        p.chmod(0o700)
    # Keep socket paths short, and expose the current runtime through the desktop directory.
    runtime = session_runtime(work)
    runtime.parent.mkdir(parents=True, exist_ok=True)
    alias = work / 'runtime'
    if alias.is_symlink():
        if alias.readlink() != runtime:
            raise RuntimeError('Unexpected Hyprland runtime link')
    elif alias.exists():
        shutil.rmtree(alias)
    if runtime.is_symlink() or (runtime.exists() and runtime.stat().st_uid != os.getuid()):
        raise RuntimeError('Unsafe Hyprland runtime directory')
    if runtime.exists():
        shutil.rmtree(runtime)
    runtime.mkdir(mode=0o700)
    if not alias.is_symlink():
        alias.symlink_to(runtime, target_is_directory=True)
    if len(str(runtime / 'hypr' / ('a_' + '9'*10 + '_' + 'f'*8) / '.socket2.sock').encode()) > 107:
        raise RuntimeError('Hyprland IPC socket path exceeds the Unix limit')
    args.ready_file.unlink(missing_ok=True)
    (work / 'session.json').unlink(missing_ok=True)
    (work / 'gpu-client-report.json').unlink(missing_ok=True)
    (work / 'gpu-host-report.json').unlink(missing_ok=True)
    (work / 'hyprland.log').unlink(missing_ok=True)
    env = clean_environment(work)
    env['TERMUX_HYPRLAND_SESSION'] = secrets.token_hex(16)
    env.update(AQ_ANDROID_WIDTH=str(args.width), AQ_ANDROID_HEIGHT=str(args.height),
               AQ_ANDROID_REFRESH=str(args.refresh))
    # Preload only the compositor: the wrapper removes it from client environments.
    env['DBUS_SESSION_BUS_ADDRESS'] = 'unix:path=' + str(runtime / 'session-bus')
    command = [str(PREFIX / 'bin/env'),
               'LD_PRELOAD=' + str(runtime_root() / 'lib/hyprland/android/libhyprland-system-gl.so'),
               str(runtime_root() / 'bin/Hyprland'), '--config', str(work / 'config/hyprland.lua')]
    config = work / 'config/hyprland.lua'
    if not config.exists():
        config.write_text('''-- User configuration. Preserved across updates and restarts.
hl.monitor({ output = "ANDROID-1", mode = "preferred", position = "auto", scale = 1 })
hl.config({
 general = { gaps_in = 4, gaps_out = 8, border_size = 2, layout = "dwindle", allow_tearing = false },
 decoration = { rounding = 4, blur = { enabled = false }, shadow = { enabled = false } },
 animations = { enabled = false },
 input = { kb_layout = "us", touchdevice = { output = "ANDROID-1" } },
 misc = { disable_hyprland_logo = true, force_default_wallpaper = -1 },
})
hl.bind("SUPER + Return", hl.dsp.exec_cmd("termux-hyprland-terminal"))
hl.bind("SUPER + E", hl.dsp.exec_cmd("termux-hyprland-files"))
hl.bind("SUPER + C", hl.dsp.window.close())
hl.bind("SUPER + left", hl.dsp.focus({ direction = "left" }))
hl.bind("SUPER + right", hl.dsp.focus({ direction = "right" }))
''')
    # Session-local wrappers select host or guest clients without user-code interpolation.
    bin_dir = work / 'bin'
    bin_dir.mkdir(exist_ok=True)
    env['PATH'] = str(bin_dir) + ':' + env['PATH']
    for action, program in [('terminal', ['xfce4-terminal', '--disable-server']), ('files', ['thunar'])]:
        executable = [str(PREFIX / "bin/env"), "-u", "LD_LIBRARY_PATH", "-u", "LD_PRELOAD", *program]
        if args.run_mode == 'proot':
            executable = container_command(work, args.alias, client_mode,
                ['dbus-run-session', '--', *program], env['TERMUX_HYPRLAND_SESSION'])
        wrapper = bin_dir / ('termux-hyprland-' + action)
        wrapper.write_text('#!' + str(PREFIX / 'bin/sh') + '\nexec ' + shlex.join(executable) + ' "$@"\n')
        wrapper.chmod(0o700)
    (work / 'theme-session-config').unlink(missing_ok=True)
    shell_commands = prepare_shell(work, args.run_mode)
    themed = work / 'theme-session-config'
    if themed.exists():
        command[-1] = themed.read_text()
    process = None
    bus = None
    clients = []
    def stop(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        with (work / 'session.log').open('w') as log:
            bus = subprocess.Popen([str(PREFIX / 'bin/dbus-daemon'), '--session', '--nofork',
                                    '--address=' + env['DBUS_SESSION_BUS_ADDRESS']],
                                   env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            bus_deadline = time.monotonic() + 5
            while not (work / 'runtime/session-bus').is_socket():
                if bus.poll() is not None or time.monotonic() > bus_deadline:
                    raise RuntimeError('Hyprland private D-Bus failed to start')
                time.sleep(.05)
            process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            deadline = time.monotonic() + 45
            ready = False
            while time.monotonic() < deadline and not ready:
                if process.poll() is not None:
                    raise RuntimeError('Hyprland exited before readiness; see ' + str(work / 'session.log'))
                try:
                    instances = json.loads(ipc(env, '-j', 'instances'))
                    for instance in instances:
                        if os.getpgid(instance['pid']) != process.pid:
                            continue
                        env['HYPRLAND_INSTANCE_SIGNATURE'] = instance['instance']
                        env['WAYLAND_DISPLAY'] = instance['wl_socket']
                        monitors = json.loads(ipc(env, '-j', 'monitors'))
                        if not any(m.get('name') == 'ANDROID-1' and m.get('width') == args.width
                                   and m.get('height') == args.height
                                   and abs(m.get('refreshRate', 0) - args.refresh) < 1 for m in monitors):
                            continue
                        with socket.socket(socket.AF_UNIX) as connection:
                            connection.settimeout(1)
                            connection.connect(str(runtime / env['WAYLAND_DISPLAY']))
                        ready = True
                        break
                except (subprocess.SubprocessError, OSError, ValueError, KeyError):
                    pass
                if not ready:
                    time.sleep(.25)
            if not ready:
                raise RuntimeError('Hyprland Android output/IPC/socket readiness timed out')
            (work / 'runtime/wayland-hyprland').symlink_to(env['WAYLAND_DISPLAY'])
            env['GDK_BACKEND'] = 'wayland'
            # Host GTK clients must use the installed libraries, not compositor-private ones.
            client_env = env.copy()
            client_env.pop('LD_LIBRARY_PATH', None)
            client_env.pop('LD_PRELOAD', None)
            (work / 'session.json').write_text(json.dumps({'supervisor': os.getpid(), 'env': env}) + '\n')
            for shell_command in shell_commands:
                clients.append(subprocess.Popen(shell_command, env=client_env, stdout=log,
                                                stderr=subprocess.STDOUT, start_new_session=True))
            if not shell_commands:
                clients.append(subprocess.Popen([str(bin_dir / 'termux-hyprland-terminal')],
                                                env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True))
            shell_deadline = time.monotonic() + 15
            while shell_commands and time.monotonic() < shell_deadline:
                if any(client.poll() is not None for client in clients):
                    raise RuntimeError('Hyprland desktop service exited during startup; see session.log')
                layers = json.loads(ipc(env, '-j', 'layers'))
                namespaces = {layer.get('namespace') for output in layers.values()
                              for level in output.get('levels', {}).values() for layer in level}
                notification = subprocess.run([str(PREFIX / 'bin/dbus-send'), '--session', '--print-reply',
                    '--dest=org.freedesktop.DBus', '/org/freedesktop/DBus',
                    'org.freedesktop.DBus.NameHasOwner', 'string:org.freedesktop.Notifications'],
                    env=client_env, capture_output=True, text=True, timeout=3)
                if {('m3ww-bar' if themed.exists() else 'xfce4-panel'), 'wallpaper'} <= namespaces and 'boolean true' in notification.stdout:
                    break
                time.sleep(.2)
            else:
                if shell_commands:
                    raise RuntimeError('Hyprland panel/background/notification readiness timed out; see session.log')
            time.sleep(.1)
            if any(client.poll() is not None for client in clients) or process.poll() is not None:
                raise RuntimeError('Hyprland compositor or default clients exited during startup')
            if process.poll() is not None or bus.poll() is not None or any(client.poll() is not None for client in clients):
                raise RuntimeError('Hyprland compositor or desktop service exited during startup')
            (work / 'session.json').write_text(json.dumps({'supervisor':os.getpid(), 'env':env,
                'host_gl_mode':'system', 'client_gl_mode':client_mode})+'\n')
            temporary = args.ready_file.with_name(args.ready_file.name + '.tmp.' + str(os.getpid()))
            temporary.write_text(str(os.getpid()) + '\n')
            temporary.replace(args.ready_file)
            print('Hyprland Android output and IPC ready; visual/input validation pending', flush=True)
            while process.poll() is None:
                if bus.poll() is not None:
                    raise RuntimeError('Hyprland private D-Bus exited')
                if any(client.poll() is not None for client in clients[:len(shell_commands)]):
                    raise RuntimeError('Hyprland desktop service exited; see session.log')
                time.sleep(.25)
            return process.returncode
    except KeyboardInterrupt:
        return 0
    finally:
        args.ready_file.unlink(missing_ok=True)
        (work / 'session.json').unlink(missing_ok=True)
        for client in clients:
            try:
                os.killpg(client.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        if process is not None:
            for sig in [signal.SIGTERM, signal.SIGKILL]:
                try:
                    os.killpg(process.pid, sig)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
        for client in clients:
            try:
                client.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(client.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                client.wait()
        if bus is not None:
            for sig in [signal.SIGTERM, signal.SIGKILL]:
                try:
                    os.killpg(bus.pid, sig)
                except ProcessLookupError:
                    pass
                try:
                    bus.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
        # D-Bus activation and application launchers can create new process groups.
        # Clean only processes carrying this unguessable session token, never by name.
        for sig in [signal.SIGTERM, signal.SIGKILL]:
            for pid in owned_service_pids(env['TERMUX_HYPRLAND_SESSION']):
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
            if sig == signal.SIGTERM:
                time.sleep(.2)
        # The compositor disables stdout logging by default. Keep its own log
        # before removing the private runtime, including initialization failures.
        for backend_log in (runtime / 'hypr').glob('*/hyprland.log'):
            try:
                shutil.copyfile(backend_log, work / 'hyprland.log')
            except OSError as error:
                print('Hyprland: could not preserve compositor log: ' + str(error), flush=True)
        # Preserve config, data and logs, remove only this session's IPC directory.
        shutil.rmtree(runtime)
        runtime.mkdir(mode=0o700)
        lock.close()


def action(args):
    work = work_for(args.desktop_id)
    state = json.loads((work / 'session.json').read_text())
    ready = Path(args.ready_file).read_text().strip()
    if ready != str(state['supervisor']):
        raise RuntimeError('Hyprland session identity changed')
    os.kill(state['supervisor'], 0)
    env = state['env']
    if args.action in ['terminal', 'files']:
        command = str(work / 'bin' / ('termux-hyprland-' + args.action))
        return subprocess.call([str(runtime_root() / 'bin/hyprctl'), 'dispatch',
                                'hl.dsp.exec_cmd(' + json.dumps(command) + ')'], env=env, timeout=5)
    dispatch = {'close':'hl.dsp.window.close()', 'next':'hl.dsp.focus({ direction = "right" })',
                'float':'hl.dsp.window.float({ action = "toggle" })'}[args.action]
    print(ipc(env, 'dispatch', dispatch), end='')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('install'); p.add_argument('archive', type=Path)
    sub.add_parser('check')
    p = sub.add_parser('session')
    p.add_argument('--desktop-id', required=True); p.add_argument('--ready-file', type=Path, required=True)
    p.add_argument('--run-mode', choices=['native','proot'], required=True); p.add_argument('--alias')
    p.add_argument('--client-gl-mode', choices=['default','zink'], default='default')
    p.add_argument('--width', type=int, required=True); p.add_argument('--height', type=int, required=True)
    p.add_argument('--refresh', type=int, required=True)
    p = sub.add_parser('action')
    p.add_argument('--desktop-id', required=True); p.add_argument('--ready-file', required=True)
    p.add_argument('action', choices=['terminal','files','close','next','float'])
    args = parser.parse_args()
    if args.command == 'install':
        install(args.archive)
    elif args.command == 'check':
        verify(runtime_root())
        verify_shell()
    elif args.command == 'session':
        if not (16 <= args.width <= 8192 and 16 <= args.height <= 8192 and 1 <= args.refresh <= 240):
            raise ValueError('Invalid Hyprland output dimensions/refresh rate')
        return session(args)
    else:
        return action(args)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print('Hyprland: ' + str(error), flush=True)
        raise SystemExit(1)
