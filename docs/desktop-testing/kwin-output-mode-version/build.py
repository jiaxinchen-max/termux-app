#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Reuse the already configured KWin cross-toolchain without changing its build.
import pathlib, subprocess, shlex, sys
if len(sys.argv) != 5:
    raise SystemExit('usage: build.py ninja kwin-build-dir check.cpp output-file')
ninja, build, source, output = sys.argv[1:]
commands = subprocess.check_output([ninja, '-C', build, '-t', 'commands', 'src/CMakeFiles/kwin.dir/wayland/outputdevice_v2.cpp.o'], text=True)
command = shlex.split(commands.strip().splitlines()[-1])
flags = []
i = 0
while i < len(command):
    arg = command[i]
    if arg in ['-MT', '-MF', '-o', '-c']:
        i += 2
    elif arg == '-MD' or arg == '-Dkwin_EXPORTS':
        i += 1
    else:
        flags.append(arg); i += 1
prefix = pathlib.Path('/data/data/com.termux/files/usr')
flags += [source, '-o', output, '-L'+str(pathlib.Path(build)/'bin'), '-L'+str(prefix/'lib'), '-Wl,-rpath-link,'+str(prefix/'lib'), '-lkwin', '-lQt6Core', '-lQt6Gui', '-lwayland-server']
subprocess.run(flags, cwd=build, check=True)
