#!/bin/sh
set -eu
spec=${1:?Desktop action spec required}
. "$spec"
[ "${DESKTOP_DE:-}" = hyprland ] || exit 64
case "${DESKTOP_HYPRLAND_ACTION:-}" in terminal|files|next|float|close) ;; *) exit 64 ;; esac
. "$(dirname "$0")/desktop_common.sh"
rm -f "$spec"
exec "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" action \
    --desktop-id "$DESKTOP_ID" --ready-file "$DESKTOP_PID_FILE.hyprland-ready" \
    "$DESKTOP_HYPRLAND_ACTION" >> "$DESKTOP_LOG_FILE.out" 2>&1
