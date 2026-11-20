#!/usr/bin/env python3
"""Install only missing graphics dependencies for an existing desktop."""
import argparse
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess

ASSETS = Path(__file__).resolve().parent
GUEST_PATH = 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'


def missing_packages(command, packages):
    missing = []
    for package in packages:
        result = subprocess.run(command + ['dpkg-query', '-W', '-f=${db:Status-Status}', package],
                                capture_output=True, text=True, timeout=30)
        if result.returncode not in (0, 1):
            raise RuntimeError('Cannot query graphics dependency: ' + package + ': ' + result.stderr)
        if result.returncode or result.stdout.strip() != 'installed':
            missing.append(package)
    return missing


def guest_command(prefix, alias):
    return [str(prefix / 'bin/proot-distro'), 'login', alias, '--user', 'root',
            '--shared-tmp', '--', 'env', GUEST_PATH]


def dependencies(prefix, de, host_mode, guest_mode, alias):
    init = runpy.run_path(str(ASSETS / 'desktop_initialize.py'))
    # Refuse to create a new container or provision an absent desktop here.
    if alias:
        container = prefix / 'var/lib/proot-distro/containers' / alias
        if not (container / ('.desktop-de-' + de)).is_file():
            raise RuntimeError('Desktop is not installed; use Install or Reinstall explicitly')
    host_packages = []
    if host_mode in ('zink', 'llvmpipe'):
        host_packages = ['mesa', 'mesa-demos']
    if host_mode == 'zink':
        host_packages += ['vulkan-loader', 'mesa-vulkan-icd-swrast' if init['is_emulator']()
                          else 'mesa-vulkan-icd-freedreno']
    missing = missing_packages([], host_packages)
    if missing:
        subprocess.run([str(prefix / 'bin/apt'), 'install', '-y', '--no-remove',
                        '-o', 'Dpkg::Options::=--force-confdef',
                        '-o', 'Dpkg::Options::=--force-confold'] + missing, check=True)
    if alias and guest_mode == 'zink':
        packages = ['libvulkan-dev', 'vulkan-tools', 'libllvm21', 'libdisplay-info3',
                    'libsensors5', 'libdrm2', 'libgbm1', 'libegl1', 'libgles2',
                    'libgl1-mesa-dri', 'libegl-mesa0', 'libglx-mesa0',
                    'mesa-vulkan-drivers', 'mesa-utils-bin']
        if de == 'hyprland':
            packages += ['python3-gi', 'gir1.2-gtk-3.0', 'libepoxy0']
        missing = missing_packages(guest_command(prefix, alias), packages)
        if missing:
            for name in ('desktop_container_plasma_apt.sh', 'desktop_container_apt_sources.sh',
                         'desktop_container_statx_setup.sh', 'desktop_container_run_config.sh',
                         'desktop_container_statx_aarch64.bin'):
                shutil.copyfile(ASSETS / name, prefix / 'tmp' / name)
            subprocess.run(guest_command(prefix, alias) + ['sh', '/tmp/desktop_container_plasma_apt.sh',
                           '--statx-backend=proc-fdinfo', '--no-install-recommends'] + missing, check=True)
    print('Graphics dependencies ready; existing packages were reused.', flush=True)


def probe_guest(prefix, alias):
    init = runpy.run_path(str(ASSETS / 'desktop_initialize.py'))
    root = prefix / 'var/lib/proot-distro/containers' / alias / 'rootfs'
    software = init['is_emulator']()
    icd = init['plasma_icd'](root, True, software)
    settings = dict(EGL_PLATFORM='surfaceless', GALLIUM_DRIVER='zink',
                    MESA_LOADER_DRIVER_OVERRIDE='zink', LIBGL_ALWAYS_SOFTWARE='1' if software else '0',
                    VK_DRIVER_FILES=icd, VK_ICD_FILENAMES=icd)
    init['check_egl'](guest_command(prefix, alias) + ['env'] +
                      [k + '=' + v for k, v in settings.items()] +
                      ['eglinfo', '-p', 'surfaceless', '-B'], 'zink',
                      init['clean_environment'](), allow_software=software)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('dependencies', 'probe-guest'))
    parser.add_argument('--de', required=True, choices=('kwin_plasma', 'xfce4', 'hyprland', 'ubuntu_gnome'))
    parser.add_argument('--host-mode', required=True, choices=('system', 'zink', 'llvmpipe'))
    parser.add_argument('--guest-mode', required=True, choices=('none', 'default', 'zink', 'llvmpipe'))
    parser.add_argument('--alias', default='')
    args = parser.parse_args()
    if args.alias and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.alias):
        parser.error('Invalid container alias')
    prefix = Path(os.environ['PREFIX'])
    if args.operation == 'dependencies':
        dependencies(prefix, args.de, args.host_mode, args.guest_mode, args.alias)
    else:
        probe_guest(prefix, args.alias)
