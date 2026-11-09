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
run "$PACKAGE_MANAGER" install -y glibc glibc-runner python \
    7zip zip unzip tar gzip bzip2 xz-utils zstd || { emit FAILED; exit 1; }

# Wine's X11 driver (winex11.drv) loads these native libraries for Xlorie rendering
# and X11 extensions. Missing libraries can disable capabilities or prevent rendering.
run "$PACKAGE_MANAGER" install -y \
    libx11-glibc libxext-glibc libxrender-glibc libxrandr-glibc libxfixes-glibc libxcursor-glibc \
    libxi-glibc libxcomposite-glibc libxinerama-glibc libxxf86vm-glibc \
    freetype-glibc fontconfig-glibc libgnutls-glibc || { emit FAILED; exit 1; }

if [ ! -f "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || \
   [ ! -f "$PREFIX/glibc/lib/libc.so.6" ] || \
   [ ! -x "$PREFIX/bin/grun" ] || \
   [ ! -x "$PREFIX/bin/python" ] || \
   [ ! -f "$PREFIX/glibc/lib/libX11.so.6" ] || \
   [ ! -f "$PREFIX/glibc/lib/libXrender.so.1" ] || \
   [ ! -f "$PREFIX/glibc/lib/libXrandr.so.2" ] || \
   [ ! -s "$PREFIX/glibc/lib/libXrandr.so.2" ] || \
   [ ! -f "$PREFIX/glibc/lib/libfreetype.so.6" ]; then
    printf '%s\n' 'Termux GLIBC package verification failed.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
    emit FAILED
    exit 1
fi

for tool in 7z zip unzip tar gzip bzip2 xz zstd; do
    if [ ! -f "$PREFIX/bin/$tool" ] || [ ! -s "$PREFIX/bin/$tool" ] || [ ! -x "$PREFIX/bin/$tool" ]; then
        printf 'Required archive tool is unavailable: %s\n' "$tool" | tee -a "$TERMUX_GLIBC_LOG_FILE"
        emit FAILED
        exit 1
    fi
done

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
            # locale.gen uses whitespace-separated fields; official entries may have trailing spaces.
            sed -i 's/^[[:space:]]*#[[:space:]]*\(zh_CN[.]GBK[[:space:]]\{1,\}GBK[[:space:]]*\)$/\1/' "$PREFIX/glibc/etc/locale.gen" || exit 1
            sed -i 's/^[[:space:]]*#[[:space:]]*\(zh_CN[.]UTF-8[[:space:]]\{1,\}UTF-8[[:space:]]*\)$/\1/' "$PREFIX/glibc/etc/locale.gen" || exit 1
            grep -Eq '^[[:space:]]*zh_CN[.]GBK[[:space:]]+GBK[[:space:]]*$' "$PREFIX/glibc/etc/locale.gen" || exit 1
            grep -Eq '^[[:space:]]*zh_CN[.]UTF-8[[:space:]]+UTF-8[[:space:]]*$' "$PREFIX/glibc/etc/locale.gen" || exit 1
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
