#!/bin/sh
# The selected backend applies only to this package-configuration process and its children.
set -u
case "${1:-}" in
    --statx-backend=proc-fdinfo) shift ;;
    *) echo 'ERROR: explicitly select --statx-backend=proc-fdinfo' >&2; exit 64 ;;
esac
[ "$#" -gt 0 ] || { echo 'ERROR: package configuration command is missing' >&2; exit 64; }
library=/usr/local/lib/termux-proot/libstatx-procfdinfo.so
[ -r "$library" ] || { echo "ERROR: statx backend library is missing: $library" >&2; exit 66; }
probe=$(env TERMUX_PROOT_STATX_MODE=proc-fdinfo TERMUX_PROOT_STATX_PROBE=1 \
    LD_PRELOAD="$library" /bin/true 2>&1)
code=$?
if [ "$code" -ne 0 ]; then
    printf '%s\n' "$probe" >&2
    exit "$code"
fi
matched=$(printf '%s\n' "$probe" | grep -E '^proot statx backend=proc-fdinfo mnt_id=[1-9][0-9]* unique=0$')
[ -n "$matched" ] && [ "$matched" = "$probe" ] || {
    printf 'ERROR: statx backend did not load and verify exactly: %s\n' "$probe" >&2
    exit 65
}
exec env -u TERMUX_PROOT_STATX_PROBE TERMUX_PROOT_STATX_MODE=proc-fdinfo \
    DEBIAN_FRONTEND=noninteractive LD_PRELOAD="$library" "$@"
