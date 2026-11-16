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

# Audio is a runtime dependency, shared with other desktop/terminal clients.
# Preparation and every desktop launch use the same detect/reuse/replace lifecycle.
prepare_desktop_audio() {
    start_desktop_audio "$@"
}

start_desktop_audio() {
    [ "${1:-}" = host-pulseaudio ] || return 64
    if "$PREFIX/bin/python3" -I -B "${2:-$(dirname "$0")/desktop_audio_service.py}" \
        >> "$DESKTOP_LOG_FILE" 2>&1; then :; else
        log "Host PulseAudio service failed; inspect $HOME/.termux/desktop-pulseaudio.log"
        return 70
    fi
    export PULSE_SERVER=tcp:127.0.0.1:4713
    unset PULSE_SINK PULSE_SOURCE
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

# The installed bridge's global autostart ignores this desktop's explicit USB policy.
# Disable only that entry; supported desktop launchers own the storage service lifecycle.
disable_global_usb_autostart() { # XDG_CONFIG_HOME of this desktop's user
    autostart_dir="$1/autostart"
    mkdir -p "$autostart_dir" || return 1
    autostart_file="$autostart_dir/tusb-desktop.desktop"
    autostart_temporary="$autostart_file.tmp.$$"
    if (umask 077; printf '[Desktop Entry]\nType=Application\nName=Termux USB storage\nHidden=true\n' > "$autostart_temporary") &&
        mv "$autostart_temporary" "$autostart_file"; then
        return 0
    fi
    rm -f "$autostart_temporary"
    return 1
}
