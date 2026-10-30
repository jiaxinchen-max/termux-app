#!/data/data/com.termux/files/usr/bin/sh
# SPDX-License-Identifier: MIT
# Run inside Termux after installing the deb; requires clang and pkg-config.
set -eu
export PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
export PATH="$PREFIX/bin:/system/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"
sdk="$PREFIX/share/termux-usb-bridge/sdk"
work=$(mktemp -d "$PREFIX/../home/tusb-installed-validation-XXXXXX")
printf 'Validation directory: %s\n' "$work"
mkdir "$work/tests" "$work/build"
cp "$sdk"/tests/test_*.py "$work/tests/"
ln -s "$PREFIX/share/termux-usb-bridge/broker.py" "$work/broker.py"
ln -s "$PREFIX/share/termux-usb-bridge/filesystem.py" "$work/filesystem.py"
for name in tusb-camera tusb-gamepadd; do
    ln -s "$PREFIX/bin/$name" "$work/build/$name"
done
for name in bridge fs gamepad sdl2 sdl3; do
    ln -s "$PREFIX/lib/libtermux-usb-$name.so" "$work/build/libtermux-usb-$name.so"
done
# These are observation clients, not rebuilt implementations of the adapters.
clang "$sdk/src/probe.c" $(pkg-config --cflags --libs termux-usb-bridge) -o "$work/build/raw-fd-probe"
clang "$sdk/tests/libusb_smoke.c" -I"$PREFIX/opt/termux-usb-bridge/include/libusb-1.0" \
    -L"$PREFIX/opt/termux-usb-bridge/lib" -lusb-1.0 -o "$work/build/libusb-smoke"
clang "$sdk/tests/sdl_probe.c" $(pkg-config --cflags --libs sdl2) -o "$work/build/tusb-sdl2-probe"
clang "$sdk/tests/sdl_probe.c" -DTUSB_SDL3 $(pkg-config --cflags --libs sdl3) -o "$work/build/tusb-sdl3-probe"
clang "$sdk/tests/camera_sink.c" $(pkg-config --cflags --libs libpipewire-0.3) -o "$work/build/tusb-camera-sink"
clang -O2 -I"$sdk/src/adapters" "$sdk/tests/camera_codec.c" "$sdk/src/adapters/camera_frame.c" \
    $(pkg-config --cflags --libs libturbojpeg) -o "$work/build/tusb-camera-codec"
dpkg-query -W termux-usb-bridge sdl2 sdl3 pipewire
sha256sum "$PREFIX/lib/libtermux-usb-fs.so" "$PREFIX/bin/tusb-camera"
python "$work/tests/test_bridge.py" "$PREFIX/lib/libtermux-usb-bridge.so" "$work/build/raw-fd-probe"
termux-usb-run python "$work/tests/test_libusb.py" "$work/build/libusb-smoke"
python "$work/tests/test_hid.py" "$PREFIX/lib/libtermux-usb-gamepad.so"
python "$work/tests/test_filesystem.py" "$PREFIX/lib/libtermux-usb-fs.so"
python "$work/tests/test_sdl.py" "$work/build"
"$work/build/tusb-camera-codec"
python "$work/tests/test_camera.py" "$work/build"
# Exercise the installed file CLI, including persistence between invocations.
tusb-files create-image "$work/cli-exfat.img" --mib 64 --exfat
printf 'Android installed package: FAT/exFAT CLI roundtrip\n' > "$work/payload"
tusb-files --image "$work/cli-exfat.img" --read-write mkdir /验证
tusb-files --image "$work/cli-exfat.img" --read-write put "$work/payload" /验证/payload.txt
tusb-files --image "$work/cli-exfat.img" cat /验证/payload.txt > "$work/readback"
cmp "$work/payload" "$work/readback"
printf 'PASS installed tusb-files CLI persistence\n'
# Exercise both installed SDL launchers with the same deterministic HID report.
python - "$work" <<'PY'
import os
from pathlib import Path
import subprocess
import sys
import time

work = Path(sys.argv[1])
descriptor = bytes.fromhex('05 01 09 05 a1 01 05 09 19 01 29 08 15 00 25 01 75 01 95 08 81 02 05 01 09 30 09 31 15 81 25 7f 75 08 95 02 81 02 09 39 15 00 25 07 75 04 95 01 81 42 75 04 95 01 81 03 c0')
(work / 'hid.bin').write_bytes(descriptor)
address = str(work / 'gamepad.sock')
daemon = subprocess.Popen(['tusb-gamepadd', '--socket', address, '--descriptor', str(work / 'hid.bin'), '--report', '03 81 7f 02'])
try:
    deadline = time.monotonic() + 5
    while not Path(address).exists():
        if daemon.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError('installed gamepad daemon did not start')
        time.sleep(0.02)
    env = dict(os.environ, TERMUX_USB_GAMEPAD_SOCKET=address, SDL_VIDEODRIVER='dummy', SDL_AUDIODRIVER='dummy')
    for version in (2, 3):
        command = ['tusb-sdl-run'] + (['--sdl3'] if version == 3 else []) + [str(work / 'build' / f'tusb-sdl{version}-probe')]
        subprocess.run(command, env=env, check=True, timeout=10)
        print(f'PASS installed SDL{version} launcher', flush=True)
finally:
    daemon.terminate()
    daemon.wait(timeout=5)
PY
printf 'TUSB_ANDROID_INSTALLED_PASS\n'
if [ -f "$PREFIX/lib/gio/modules/libtusb-volume-monitor.so" ]; then
    clang "$sdk/tests/desktop_probe.c" $(pkg-config --cflags --libs gio-2.0) -o "$work/build/tusb-desktop-probe"
    dbus-run-session -- python "$sdk/tests/test_desktop.py" "$work/build/tusb-desktop-probe"
fi
