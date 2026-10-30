#!/data/data/com.termux/files/usr/bin/sh
# SPDX-License-Identifier: MIT
# Test the installed producer; compile observation/codec clients from its SDK.
set -eu
export PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
export PATH="$PREFIX/bin:/system/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"
sdk="$PREFIX/share/termux-usb-bridge/sdk"
camera_work=$(mktemp -d "$PREFIX/../home/tusb-camera-validation-XXXXXX")
mkdir "$camera_work/build" "$camera_work/captures"
printf 'Camera validation directory: %s\n' "$camera_work"
clang -O2 -Wall -Wextra -Werror -I"$sdk/src/adapters" "$sdk/tests/camera_codec.c" "$sdk/src/adapters/camera_frame.c" \
    $(pkg-config --cflags --libs libturbojpeg) -o "$camera_work/build/tusb-camera-codec"
clang "$sdk/tests/camera_sink.c" $(pkg-config --cflags --libs libpipewire-0.3) -o "$camera_work/build/tusb-camera-sink"
ln -s "$PREFIX/bin/tusb-camera" "$camera_work/build/tusb-camera"
"$camera_work/build/tusb-camera-codec"
python "$sdk/tests/test_camera.py" "$camera_work/build" --output-dir "$camera_work/captures"

# Framed Camera2 transport parser; no camera permission or hardware is needed.
python "$sdk/tests/test_camera2_wire.py" "$PREFIX/libexec/termux-usb-bridge/tusb-camera2-source"
