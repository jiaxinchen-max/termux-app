#!/bin/sh
# Read-only regression against the distribution's real ucf/ucfr environment sanitisation.
set -eu
runner=/usr/local/libexec/termux-proot/run-config
library=/usr/local/lib/termux-proot/libstatx-procfdinfo.so
[ -x "$runner" ] && [ -r "$library" ] || exit 66
[ -x /usr/bin/ucf ] && [ -x /usr/bin/ucfr ] || exit 66
"$runner" --statx-backend=proc-fdinfo /usr/bin/ucf --help >/dev/null
"$runner" --statx-backend=proc-fdinfo /usr/bin/ucfr --help >/dev/null
for mode in absent invalid; do
    set +e
    if [ "$mode" = absent ]; then
        env -u UCF_TERMUX_PROOT_STATX_MODE LD_PRELOAD="$library" /bin/true
    else
        env UCF_TERMUX_PROOT_STATX_MODE=invalid LD_PRELOAD="$library" /bin/true
    fi
    code=$?
    set -e
    [ "$code" -eq 64 ] || { echo "ERROR: $mode mode returned $code" >&2; exit 65; }
done
[ -z "${LD_PRELOAD+x}" ] && [ -z "${UCF_TERMUX_PROOT_STATX_MODE+x}" ] || exit 65
echo 'PASS real ucf/ucfr sanitisation; missing/invalid modes rejected; parent environment unchanged'
