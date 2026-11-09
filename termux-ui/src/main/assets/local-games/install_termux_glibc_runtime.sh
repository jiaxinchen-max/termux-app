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
run "$PACKAGE_MANAGER" install -y glibc glibc-runner python 7zip || { emit FAILED; exit 1; }

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
   [ ! -x "$PREFIX/bin/python" ] || \
   [ ! -x "$PREFIX/bin/7z" ] || \
   [ ! -f "$PREFIX/glibc/lib/libX11.so.6" ] || \
   [ ! -f "$PREFIX/glibc/lib/libXrender.so.1" ] || \
   [ ! -f "$PREFIX/glibc/lib/libfreetype.so.6" ]; then
    printf '%s\n' 'Termux GLIBC package verification failed.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
    emit FAILED
    exit 1
fi

# Locales belong to the shared host runtime, not to a game prefix. Publish readiness
# only after both required data sets exist; an old marker alone proves nothing.
prepare_cjk_locale() {
    locale_root="$PREFIX/glibc/lib/locale"
    locale_marker="$PREFIX/glibc/termux-box/run/games-zh-cn-locale-v1"
    locale_data_ready() {
        [ -f "$locale_root/zh_CN.utf8/LC_CTYPE" ] && [ -s "$locale_root/zh_CN.utf8/LC_CTYPE" ] &&
        [ -f "$locale_root/zh_CN.gbk/LC_CTYPE" ] && [ -s "$locale_root/zh_CN.gbk/LC_CTYPE" ]
    }
    if ! locale_data_ready; then
        rm -f "$locale_marker" || return 1
        [ -x "$PREFIX/glibc/bin/locale-gen" ] &&
        [ -f "$PREFIX/glibc/etc/locale.gen" ] || return 1
        printf '%s\n' 'Preparing shared glibc zh_CN UTF-8 and GBK locales.'
        (
            export PATH="$PREFIX/glibc/bin:$PATH"
            sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' "$PREFIX/glibc/etc/locale.gen" || exit 1
            sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' "$PREFIX/glibc/etc/locale.gen" || exit 1
            grep -Fx 'zh_CN.GBK GBK' "$PREFIX/glibc/etc/locale.gen" >/dev/null || exit 1
            grep -Fx 'zh_CN.UTF-8 UTF-8' "$PREFIX/glibc/etc/locale.gen" >/dev/null || exit 1
            "$PREFIX/glibc/bin/locale-gen"
        ) || return 1
    fi
    locale_data_ready || return 1
    mkdir -p "$(dirname "$locale_marker")" || return 1
    # Keep the existing marker valid for already prepared prefixes.
    printf '%s\n' 'zh_CN.UTF-8 zh_CN.GBK' > "$locale_marker.tmp.$$" || return 1
    if ! mv -f "$locale_marker.tmp.$$" "$locale_marker"; then
        rm -f "$locale_marker.tmp.$$"
        return 1
    fi
}
if ! run prepare_cjk_locale; then
    printf '%s\n' 'Termux GLIBC locale preparation or verification failed.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
    emit FAILED
    exit 1
fi

printf '%s\n' 'Official Termux GLIBC runtime is ready.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
emit SUCCEEDED
