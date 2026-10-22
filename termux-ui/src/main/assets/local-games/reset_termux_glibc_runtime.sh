#!/bin/sh

set -u

SPEC_PATH="${1:?Usage: $0 <termux_glibc_reset_spec>}"
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

printf '%s\n' 'Resetting the GLIBC runtime environment.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
if [ -x "$PREFIX/bin/termux-setup-package-manager" ]; then
    PACKAGE_MANAGER="$PREFIX/bin/pkg"
else
    printf '%s\n' 'pkg helper is unavailable; using the Termux apt backend directly.' \
        | tee -a "$TERMUX_GLIBC_LOG_FILE"
    PACKAGE_MANAGER="$PREFIX/bin/apt"
fi

# Purge through apt/dpkg first so its package database stays consistent with what's
# actually on disk -- deleting $PREFIX/glibc directly first would leave dpkg believing
# these packages are still fully installed, so a later re-install would silently no-op.
# The installer pulls in a full glibc userland (bash/coreutils/findutils/...-glibc and
# their transitive deps), not just the X11/graphics set, so a fixed package list here
# leaves dependents behind and apt refuses the removal -- query what is actually
# installed instead.
glibc_packages=$(dpkg-query -W -f='${Package}|${Status}\n' 2>/dev/null \
    | awk -F'|' '$1 ~ /(^glibc$|^glibc-runner$|^glibc-repo$|-glibc$)/ && $2 ~ /installed$/ {print $1}')
if [ -n "$glibc_packages" ]; then
    # This purges the entire GLIBC userland (bash/coreutils/glibc itself), which apt marks
    # essential and otherwise refuses to remove even with -y.
    run "$PACKAGE_MANAGER" remove --purge -y --allow-remove-essential $glibc_packages \
        || { emit FAILED; exit 1; }
else
    printf '%s\n' 'No apt-tracked glibc packages found; skipping package removal.' \
        | tee -a "$TERMUX_GLIBC_LOG_FILE"
fi

# Anything left under $PREFIX/glibc at this point is a games-module component download
# (Wine builds, turnip, DirectX overlay) rather than an apt-tracked package -- sweep it too.
run rm -rf "$PREFIX/glibc" || { emit FAILED; exit 1; }

if [ -e "$PREFIX/glibc" ] || [ -x "$PREFIX/bin/grun" ]; then
    printf '%s\n' 'GLIBC runtime reset verification failed.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
    emit FAILED
    exit 1
fi

printf '%s\n' 'GLIBC runtime environment reset to not-installed.' | tee -a "$TERMUX_GLIBC_LOG_FILE"
emit SUCCEEDED
