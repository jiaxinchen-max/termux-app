#!/bin/sh
# One explicit container-uninstall method. Preserve its diagnostics and exit status.
set -u
SPEC="${1:-}"
[ -n "$SPEC" ] && [ -f "$SPEC" ] || { echo 'ERROR: desktop removal spec is missing' >&2; exit 64; }
. "$SPEC"
finish_remove() {
    code=$?
    trap - EXIT
    if ! printf 'exit:%s\n' "$code" > "$DESKTOP_STATUS_FILE"; then
        [ "$code" -ne 0 ] || code=74
    fi
    exit "$code"
}
trap finish_remove EXIT
[ "${DESKTOP_MODE:-}" = proot ] && [ "${DESKTOP_REMOVE_METHOD:-}" = proot-distro ] || {
    echo 'ERROR: explicitly select proot mode and DESKTOP_REMOVE_METHOD=proot-distro' >&2
    exit 64
}
ALIAS="${DESKTOP_PROOT_DISTRO:-}"
case "$ALIAS" in
    ''|-*|.|..|*[!A-Za-z0-9._-]*) echo 'ERROR: invalid container alias for removal' >&2; exit 64 ;;
esac
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:${PATH:-}"
command -v proot-distro >/dev/null 2>&1 || { echo 'ERROR: proot-distro is unavailable for removal' >&2; exit 127; }
echo "Removing container $ALIAS with explicitly selected proot-distro"
proot-distro remove "$ALIAS"
