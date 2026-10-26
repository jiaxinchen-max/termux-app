#!/bin/sh
# Removes a container desktop's proot instance. Sourced spec provides DESKTOP_PROOT_DISTRO.
# Runs after the record is gone; best effort, never fails the UI.
set -u
SPEC="${1:-}"
[ -n "$SPEC" ] && [ -f "$SPEC" ] && . "$SPEC"

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
ALIAS="${DESKTOP_PROOT_DISTRO:-}"

case "${DESKTOP_MODE:-}" in
    proot) ;;
    *) exit 0 ;;
esac
[ -n "$ALIAS" ] || exit 0
command -v proot-distro >/dev/null 2>&1 || exit 0

for base in "$PREFIX/var/lib/proot-distro/containers" "$PREFIX/var/lib/proot-distro/installed-rootfs"; do
    [ -d "$base/$ALIAS" ] || continue
    proot-distro uninstall "$ALIAS" -y >/dev/null 2>&1 || rm -rf "$base/$ALIAS"
done
exit 0
