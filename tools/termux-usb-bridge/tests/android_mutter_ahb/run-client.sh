#!/data/data/com.termux/files/usr/bin/bash
# Usage: run-client.sh /absolute/session/work [AHB_format=1] [negative_test=0]
set -eu
: "${PREFIX:=/data/data/com.termux/files/usr}"
work=$1
shift
source "$work/host-session.env"
unset TERMUX_ANDROID_ZINK LIBGL_ALWAYS_SOFTWARE GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE
export LD_PRELOAD="$PREFIX/lib/mutter-16/libmutter-android-system-egl.so"
exec "$PREFIX/libexec/mutter-android-ahb-client" "$@"
