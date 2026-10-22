#!/bin/sh

set -u

SPEC_PATH="${1:?Usage: $0 <termux_glibc_install_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"

emit() {
    printf '%s\n' "$1" > "$TERMUX_GLIBC_EVENT_FILE"
}

run() {
    status_file="$TERMUX_GLIBC_LOG_FILE.status.$$"
    rm -f "$status_file"
    (
        "$@"
        command_status=$?
        printf '%s\n' "$command_status" > "$status_file"
    ) 2>&1 | tee -a "$TERMUX_GLIBC_LOG_FILE"
    status=$(cat "$status_file" 2>/dev/null || printf '1')
    rm -f "$status_file"
    return "$status"
}

mkdir -p "$(dirname "$TERMUX_GLIBC_LOG_FILE")" \
    "$(dirname "$TERMUX_GLIBC_EVENT_FILE")"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
PATH="$PREFIX/bin:$PREFIX/bin/applets:${PATH:-}"
export PREFIX PATH DEBIAN_FRONTEND=noninteractive

printf '%s\n' 'Installing official Termux GLIBC packages.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
if [ -x "$PREFIX/bin/termux-setup-package-manager" ]; then
    PACKAGE_MANAGER="$PREFIX/bin/pkg"
else
    printf '%s\n' 'pkg helper is unavailable; using the Termux apt backend directly.' \
        | tee -a "$TERMUX_GLIBC_LOG_FILE"
    PACKAGE_MANAGER="$PREFIX/bin/apt"
fi
run "$PACKAGE_MANAGER" install -y glibc-repo || { emit FAILED; exit 1; }
run "$PACKAGE_MANAGER" update || { emit FAILED; exit 1; }
run "$PACKAGE_MANAGER" install -y glibc glibc-runner || { emit FAILED; exit 1; }

# Wine's X11 driver (winex11.drv) links these native libraries to talk to the Xlorie X
# server; without them Wine can start but never render a visible window (it silently
# falls back through "Error initializing native lib*.so" and either shows an empty
# desktop or nothing at all).
run "$PACKAGE_MANAGER" install -y \
    libx11-glibc libxext-glibc libxrender-glibc libxfixes-glibc libxcursor-glibc \
    libxi-glibc libxcomposite-glibc libxinerama-glibc libxxf86vm-glibc \
    freetype-glibc fontconfig-glibc libgnutls-glibc || { emit FAILED; exit 1; }

if [ ! -f "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || \
   [ ! -f "$PREFIX/glibc/lib/libc.so.6" ] || \
   [ ! -x "$PREFIX/bin/grun" ] || \
   [ ! -f "$PREFIX/glibc/lib/libX11.so.6" ] || \
   [ ! -f "$PREFIX/glibc/lib/libXrender.so.1" ] || \
   [ ! -f "$PREFIX/glibc/lib/libfreetype.so.6" ]; then
    printf '%s\n' 'Termux GLIBC package verification failed.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
    emit FAILED
    exit 1
fi

printf '%s\n' 'Official Termux GLIBC runtime is ready.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
emit SUCCEEDED
