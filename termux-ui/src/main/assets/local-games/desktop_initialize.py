#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Installation-only desktop validation. Never called by ordinary session launchers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shlex
import struct
import subprocess


ASSETS = Path(__file__).resolve().parent


def executable(path):
    if not path.is_file() or not os.access(path, os.X_OK):
        raise RuntimeError('Required desktop executable is missing: ' + str(path))


def required(path):
    if not path.is_file():
        raise RuntimeError('Required desktop file is missing: ' + str(path))


def icd(root, relative, library):
    path = root / relative
    if json.loads(path.read_text())['ICD']['library_path'] != library:
        raise RuntimeError('Unexpected Vulkan ICD library: ' + str(path))
    required(root / library.lstrip('/') if library.startswith('/usr/') else Path(library))


def clean_environment():
    env = dict(os.environ)
    for key in list(env):
        if key.startswith(('MESA_', 'GALLIUM_', 'LIBGL_', 'VK_', 'ZINK_', 'KWIN_')) or key in (
                'LD_PRELOAD', 'LD_LIBRARY_PATH', 'DISPLAY', 'WAYLAND_DISPLAY',
                'EGL_PLATFORM', 'TERMUX_ANDROID_ZINK', '__EGL_VENDOR_LIBRARY_FILENAMES'):
            env.pop(key, None)
    return env


def check_egl(command, mode, env, allow_software=False):
    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
    print(result.stdout, end='', flush=True)
    if result.stderr:
        print(result.stderr, end='', flush=True)
    renderers = re.findall(r'(?im)^.*renderer(?: string)?:\s*(.+)$', result.stdout)
    actual = ' '.join(renderers).lower()
    software = re.search(r'llvmpipe|lavapipe|softpipe|swrast|swiftshader|software', actual)
    matches = ('llvmpipe' in actual and 'zink' not in actual) if mode == 'llvmpipe' else (
        'zink' in actual and (bool(software) if allow_software else 'adreno' in actual and not software))
    if result.returncode or not matches:
        raise RuntimeError('Desktop initialization EGL renderer check failed: ' + mode)
    return actual


def is_emulator():
    try:
        return subprocess.check_output(['/system/bin/getprop', 'ro.kernel.qemu'],
                                       text=True, timeout=3).strip() == '1'
    except (OSError, subprocess.SubprocessError):
        return False


def plasma_icd(root, guest, software):
    """One explicit Vulkan backend, with matching AArch64 Bionic/glibc library identity."""
    driver = 'lvp' if software else 'freedreno'
    directory = 'usr/share/vulkan/icd.d' if guest else 'share/vulkan/icd.d'
    names = [driver + '_icd.aarch64.json']
    if guest:
        names.append(driver + '_icd.json')
    path = next((root / directory / n for n in names if (root / directory / n).is_file()), None)
    if path is None:
        raise RuntimeError(('Container' if guest else 'Host') + ' Zink requires an explicit ' + driver + ' Vulkan ICD')
    library = json.loads(path.read_text())['ICD']['library_path']
    expected = 'libvulkan_' + driver + '.so'
    if Path(library).name != expected:
        raise RuntimeError('Unexpected Plasma ICD library: ' + library)
    if guest:
        allowed = ['/usr/lib/aarch64-linux-gnu/' + expected, expected]
        actual = root / ('usr/lib/aarch64-linux-gnu/' + expected)
    else:
        allowed = [str(root / 'lib' / expected)]
        actual = root / 'lib' / expected
    if library not in allowed or not actual.resolve().is_relative_to(root.resolve()):
        raise RuntimeError('Wrong Plasma ICD ABI/library path: ' + library)
    with actual.open('rb') as stream:
        elf = stream.read(20)
    if len(elf) != 20 or elf[:6] != b'\x7fELF\x02\x01' or struct.unpack('<H', elf[18:20])[0] != 183:
        raise RuntimeError('Plasma Vulkan library must be AArch64 ELF: ' + str(actual))
    return '/' + str(path.relative_to(root)) if guest else str(path)


def plasma_rendering(args, root, guest, env):
    """Verify both layers, then publish only this pair's trusted startup environment."""
    if (args.host_gl_mode not in ('system', 'zink', 'llvmpipe')
            or args.guest_gl_mode not in ('zink', 'llvmpipe')
            or (args.host_gl_mode == 'llvmpipe' and args.guest_gl_mode != 'llvmpipe')):
        raise RuntimeError('Unsupported Plasma renderer pair')
    destination = args.prefix / 'var/lib/termux-desktop/rendering' / (
        args.alias + '.' + args.host_gl_mode + '.' + args.guest_gl_mode + '.env')
    destination.unlink(missing_ok=True)
    software = is_emulator()
    values = {'DESKTOP_PLASMA_RENDERING': '1',
              'DESKTOP_PLASMA_HOST_MODE': args.host_gl_mode,
              'DESKTOP_PLASMA_GUEST_MODE': args.guest_gl_mode,
              'DESKTOP_PLASMA_HOST_ICD': '', 'DESKTOP_PLASMA_GUEST_ICD': '',
              'DESKTOP_PLASMA_HOST_SOFTWARE': '0', 'DESKTOP_PLASMA_GUEST_SOFTWARE': '0'}
    for scope, mode in [('HOST', args.host_gl_mode), ('GUEST', args.guest_gl_mode)]:
        if mode == 'system':
            runpy.run_path(str(ASSETS / 'desktop_hyprland_gpu.py'))['host_probe']()
            print('Plasma host: Android System EGL', flush=True)
            continue
        settings = dict(EGL_PLATFORM='surfaceless', GALLIUM_DRIVER=mode,
                        MESA_LOADER_DRIVER_OVERRIDE=mode,
                        LIBGL_ALWAYS_SOFTWARE='1' if mode == 'llvmpipe' or software else '0')
        if scope == 'HOST':
            settings['TERMUX_ANDROID_ZINK'] = '1'
        if mode == 'zink':
            icd_path = plasma_icd(root if scope == 'GUEST' else args.prefix, scope == 'GUEST', software)
            settings.update(VK_DRIVER_FILES=icd_path, VK_ICD_FILENAMES=icd_path)
            values['DESKTOP_PLASMA_' + scope + '_ICD'] = icd_path
            values['DESKTOP_PLASMA_' + scope + '_SOFTWARE'] = '1' if software else '0'
        probe = ['eglinfo', '-p', 'surfaceless', '-B']
        command = (guest + [k + '=' + v for k, v in settings.items()] + probe
                   if scope == 'GUEST' else [str(args.prefix / 'bin/eglinfo')] + probe[1:])
        actual = check_egl(command, mode, dict(env, **settings) if scope == 'HOST' else env,
                           allow_software=software and mode == 'zink')
        print('Plasma ' + scope.lower() + ': ' + actual + (' [CPU software Vulkan]' if mode == 'zink' and software else ''), flush=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + '.tmp.' + str(os.getpid()))
    try:
        temporary.write_text(''.join('export ' + k + '=' + shlex.quote(v) + '\n' for k, v in values.items()))
        temporary.chmod(0o600)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_plasma(prefix):
    """Derive app-owned scripts only from the pinned, reviewed r28 package bytes."""
    expected = {
        'start-plasma': '366c1300a6b3c255e401b13d358778b2536300a4a92fe96117887ed9431e6de0',
        'kwin-android-rendering-env': 'c00cef0311e4829e8229059837cb8ee177751345856c159294b15494572c42da',
    }
    sources = {}
    for name, digest in expected.items():
        data = (prefix / 'bin' / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError('Unsupported Plasma component script; reinstall pinned r28: ' + name)
        sources[name] = data.decode()
    start = sources['start-plasma']
    # Retain lookup of the actual service command, not the pre-launch dependency inventory.
    begin = start.index('echo "Checking dependencies..."')
    end = start.index('SCRIPT_DIR=', begin)
    start = start[:begin] + '''KACTIVITYMANAGERD_CMD=""
if [ "$COMPOSITOR_ONLY" != "1" ]; then
    KACTIVITYMANAGERD_CMD="$(find_kactivitymanagerd)"
fi

''' + start[end:]
    begin = start.index('RENDERING_ENV=')
    end = start.index('kwin_android_select_rendering', begin)
    start = start[:begin] + 'source "$PREFIX/libexec/termux-desktop/rendering-env"\n' + start[end:]
    begin = start.index('    QT_CLIENT_PROBE=')
    end = start.index('\nfi\n', begin)
    start = start[:begin] + start[end:]
    # Explicit CLI installation/debug actions stay in the original component script.
    begin = start.index('        --install-deps|-i)')
    end = start.index('        --compositor-only', begin)
    start = start[:begin] + start[end:]
    begin = start.index('        --test-xwayland)')
    end = start.index('    esac', begin)
    start = start[:begin] + start[end:]
    begin = start.index('if [ "$INSTALL_DEPS" = "1" ]; then')
    end = start.index('\nfi', begin) + len('\nfi')
    start = start[:begin] + start[end:]
    rendering = sources['kwin-android-rendering-env'].replace('It probes the\n# device, exports', 'Initialization validates the device. This file exports')
    begin = rendering.index('kwin_android_probe_zink_icd()')
    end = rendering.index('kwin_android_configure_icd_zink()', begin)
    rendering = rendering[:begin] + rendering[end:]
    # These exact blocks exist only in the SHA-pinned source above.
    rendering = re.sub(r'            if ! kwin_android_probe_zink_icd .*?\n            fi\n', '', rendering, flags=re.S)
    rendering = re.sub(r'            if \[ ! -r .*?\n            fi\n', '', rendering, flags=re.S)
    rendering = rendering.replace('kwin_android_select_rendering()', 'kwin_android_select_component_rendering()')
    rendering += '''
kwin_android_select_rendering() {
    if [ "${DESKTOP_PLASMA_RENDERING:-}" != 1 ]; then
        kwin_android_select_component_rendering
        return $?
    fi
    case "$KWIN_ANDROID_GL_MODE" in
        system) kwin_android_use_system_gles ;;
        llvmpipe) kwin_android_use_llvmpipe ;;
        zink)
            kwin_android_configure_icd_zink "$DESKTOP_PLASMA_HOST_ICD" "Plasma explicit Zink ICD: $DESKTOP_PLASMA_HOST_ICD"
            if [ "$DESKTOP_PLASMA_HOST_SOFTWARE" = 1 ]; then
                export LIBGL_ALWAYS_SOFTWARE=1
                export KWIN_ANDROID_AHB_IMPORT=off TERMUX_RENDER_AHB_RECEIVE=native
                export KWIN_ANDROID_LINUX_DMABUF=off
                export ACCELERATION_MODE="Zink over CPU software Vulkan (explicit)"
            fi ;;
        *) return 64 ;;
    esac
}
'''
    begin = start.index('if [ "${KWIN_ANDROID_XWAYLAND_SELF_TEST:-0}" = "1" ]; then')
    end = start.index('\nset +e', begin)
    start = start[:begin] + start[end:]
    start = '\n'.join(line for line in start.split('\n') if '--install-deps #' not in line and '--test-xwayland #' not in line)
    destination = prefix / 'libexec/termux-desktop'
    destination.mkdir(parents=True, exist_ok=True)
    for name, text in [('start-plasma', start), ('rendering-env', rendering)]:
        path = destination / name
        temporary = path.with_name(path.name + '.tmp.' + str(os.getpid()))
        temporary.write_text(text)
        temporary.chmod(0o700)
        # Do not publish a partially transformed/invalid script as initialized.
        subprocess.run([str(prefix / 'bin/bash'), '-n', str(temporary)], check=True, timeout=10)
        temporary.replace(path)


def initialize(args):
    prefix = args.prefix
    root = prefix
    env = clean_environment()
    guest = []
    if args.run_mode == 'proot':
        if not args.alias or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', args.alias):
            raise ValueError('Explicit valid container alias required')
        root = prefix / 'var/lib/proot-distro/containers' / args.alias / 'rootfs'
        release = dict(line.split('=', 1) for line in (root / 'etc/os-release').read_text().splitlines()
                       if '=' in line)
        wanted = ('ubuntu', 'noble') if args.de == 'ubuntu_gnome' else (
            'debian', 'forky' if args.de == 'kwin_plasma' else 'trixie')
        if tuple(release.get(k, '').strip('"\'') for k in ('ID', 'VERSION_CODENAME')) != wanted:
            raise RuntimeError('Desktop rootfs must be ' + ':'.join(wanted))
        executable(prefix / 'bin/proot-distro')
        guest = [str(prefix / 'bin/proot-distro'), 'login', args.alias, '--', 'env']
    for name in ('python3', 'pulseaudio', 'pactl'):
        executable(prefix / 'bin' / name)
    commands = {
        'xfce4': ['xfce4-session', 'dbus-run-session'],
        'gnome': ['gnome-shell', 'gjs', 'dbus-daemon', 'dbus-run-session'],
        'ubuntu_gnome': ['gnome-shell', 'gjs', 'dbus-daemon', 'dbus-run-session'],
        'hyprland': ['xfce4-terminal', 'thunar'],
        'kwin_plasma': ['plasmashell', 'dbus-daemon', 'dbus-send'],
    }[args.de]
    for command in commands:
        executable(root / ('usr/bin' if guest else 'bin') / command)
    if args.de in ('gnome', 'ubuntu_gnome', 'xfce4'):
        executable(prefix / 'bin/Xlorie')
    if args.de == 'ubuntu_gnome' and guest:
        runpy.run_path(str(ASSETS / 'desktop_ubuntu_gnome.py'))['validate_rootfs'](root)
    if args.de in ('gnome', 'ubuntu_gnome'):
        services = root / ('usr/share/dbus-1/services' if guest else 'share/dbus-1/services')
        for name in ('ca.desrt.dconf', 'org.gtk.vfs.Daemon', 'org.gnome.Nautilus'):
            required(services / (name + '.service'))
        subprocess.run(guest + ['gjs', '-c', 'imports.gi.Gio; imports.gi.Gtk;'], env=env,
                       check=True, timeout=30)
    if args.de == 'hyprland':
        module = runpy.run_path(str(ASSETS / 'desktop_hyprland.py'))
        module['verify'](module['runtime_root']())
        module['COMPONENTS']['resolve'](args.runtime_id, args.theme_id)
        module['verify_shell'](args.theme_id)
        components = module['COMPONENTS']
        components['verify_theme'](components['theme_root'](args.theme_id), args.runtime_id, args.theme_id)
        if args.guest_gl_mode == 'zink':
            gpu = runpy.run_path(str(ASSETS / 'desktop_hyprland_gpu.py'))
            gpu['validate_host'](gpu['host_probe']())
    if args.usb_storage == 'read-only':
        executable(prefix / 'libexec/termux-usb-app')
        required(root / ('usr/local/lib/termux-usb-bridge/gio/modules' if guest else 'lib/gio/modules') /
                 'libtusb-volume-monitor.so')
        if guest:
            executable(root / 'usr/local/libexec/tusb-gio-mount')
    plasma_proot = args.de == 'kwin_plasma' and bool(guest)
    if args.host_gl_mode == 'zink' and not plasma_proot:
        library = str(prefix / 'lib/libvulkan_freedreno.so')
        icd(prefix, 'share/vulkan/icd.d/freedreno_icd.aarch64.json', library)
    if guest and args.guest_gl_mode == 'zink' and args.de != 'kwin_plasma':
        icd(root, 'usr/share/vulkan/icd.d/freedreno_icd.aarch64.json',
            '/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so')
    if plasma_proot:
        pass  # Both layers are validated together below.
    elif args.host_gl_mode == 'system':
        runpy.run_path(str(ASSETS / 'desktop_hyprland_gpu.py'))['host_probe']()
    else:
        host_env = dict(env, EGL_PLATFORM='surfaceless', GALLIUM_DRIVER=args.host_gl_mode,
                        MESA_LOADER_DRIVER_OVERRIDE=args.host_gl_mode, TERMUX_ANDROID_ZINK='1',
                        LIBGL_ALWAYS_SOFTWARE='1' if args.host_gl_mode == 'llvmpipe' else '0')
        if args.host_gl_mode == 'zink':
            host_env['VK_DRIVER_FILES'] = str(prefix / 'share/vulkan/icd.d/freedreno_icd.aarch64.json')
        check_egl([str(prefix / 'bin/eglinfo'), '-p', 'surfaceless', '-B'], args.host_gl_mode, host_env)
    if args.de == 'kwin_plasma':
        executable(prefix / 'bin/kwin_wayland')
        executable(prefix / 'bin/dbus-launch')
        if not guest and not any(os.access(prefix / name, os.X_OK) for name in (
                'bin/kactivitymanagerd', 'bin/kactivitymanagerd6', 'lib/libexec/kactivitymanagerd',
                'libexec/kactivitymanagerd', 'lib/kf6/kactivitymanagerd', 'lib/qt6/libexec/kactivitymanagerd')):
            raise RuntimeError('Native Plasma activity manager is missing')
        required(prefix / 'lib/libtermux-render.so')
        if guest:
            executable(root / 'usr/lib/polkit-1/polkitd')
            if not any((root / name).is_file() for name in ('usr/libexec/packagekitd', 'usr/lib/packagekit/packagekitd')):
                raise RuntimeError('Container PackageKit daemon is missing')
            if not list(root.glob('usr/lib/*/qt6/plugins/platforms/libqwayland*.so')):
                raise RuntimeError('Container Qt Wayland plugin is missing')
        # Original helper remains the installation-time authority for native renderer validation.
        if not plasma_proot:
            subprocess.run([str(prefix / 'bin/bash'), '-c',
                        'source "$PREFIX/bin/kwin-android-rendering-env"; '
                        'kwin_android_select_rendering' + (
                            ' && kwin_android_configure_qt_clients' if not guest else '')],
                       env=dict(env, KWIN_ANDROID_GL_MODE=args.host_gl_mode), check=True, timeout=30)
        prepare_plasma(prefix)
        if plasma_proot:
            plasma_rendering(args, root, guest, env)
    if guest and args.de != 'kwin_plasma' and (args.de in ('gnome', 'ubuntu_gnome') or args.guest_gl_mode == 'zink'):
        mode = args.guest_gl_mode
        settings = ['EGL_PLATFORM=surfaceless', 'GALLIUM_DRIVER=' + mode,
                    'MESA_LOADER_DRIVER_OVERRIDE=' + mode,
                    'LIBGL_ALWAYS_SOFTWARE=' + ('1' if mode == 'llvmpipe' else '0')]
        if mode == 'zink':
            settings += ['VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json']
        check_egl(guest + settings + ['eglinfo', '-p', 'surfaceless', '-B'], mode, env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prepare = sub.add_parser('prepare-plasma', help='Prepare host scripts without modifying an imported rootfs')
    prepare.add_argument('--prefix', type=Path, default=Path(os.environ['PREFIX']))
    install = sub.add_parser('initialize')
    install.add_argument('--prefix', type=Path, default=Path(os.environ['PREFIX']))
    install.add_argument('--de', choices=('xfce4', 'gnome', 'ubuntu_gnome', 'hyprland', 'kwin_plasma'), required=True)
    install.add_argument('--run-mode', choices=('native', 'proot'), required=True)
    install.add_argument('--alias')
    install.add_argument('--runtime-id')
    install.add_argument('--theme-id')
    install.add_argument('--host-gl-mode', choices=('system', 'zink', 'llvmpipe'), required=True)
    install.add_argument('--guest-gl-mode', choices=('none', 'default', 'zink', 'llvmpipe'), required=True)
    install.add_argument('--usb-storage', choices=('off', 'read-only'), required=True)
    args = parser.parse_args()
    if args.command == 'prepare-plasma':
        prepare_plasma(args.prefix)
    else:
        initialize(args)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print('Desktop initialization failed: ' + str(error), flush=True)
        raise SystemExit(1)
