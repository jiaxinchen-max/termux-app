#!/bin/sh
# Shared helpers of the desktop (KWin) scripts; sourced after the spec, never run directly.

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
PATH="$PREFIX/bin:$PREFIX/bin/applets:${PATH:-}"
export PREFIX HOME PATH

mkdir -p "$(dirname "$DESKTOP_LOG_FILE")" "$(dirname "$DESKTOP_PID_FILE")" \
    "$(dirname "$DESKTOP_STATUS_FILE")"

log() {
    printf '%s\n' "$*" | tee -a "$DESKTOP_LOG_FILE"
}

read_pid() {
    [ -f "$DESKTOP_PID_FILE" ] || return 1
    pid=$(cat "$DESKTOP_PID_FILE" 2>/dev/null)
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s' "$pid"
}

pid_alive() {
    [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

# Signals every same-UID process whose kernel command name is exactly $2 (procps may be absent).
signal_by_name() {
    for proc_dir in /proc/[0-9]*; do
        [ "$(cat "$proc_dir/comm" 2>/dev/null)" = "$2" ] || continue
        kill "-$1" "${proc_dir#/proc/}" 2>/dev/null
    done
}
