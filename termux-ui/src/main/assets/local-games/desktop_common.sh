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

# Shared Termux server: never kill an existing Wine/terminal audio session.
# Containers connect over loopback; neither a container daemon nor a null sink is audio output.
# Every audio request, including connection/authentication, has a hard deadline.
desktop_audio_run() {
    timeout -k 1 3 "$@"
}

start_desktop_audio() {
    [ "${1:-}" = host-pulseaudio ] || {
        log 'Explicit DESKTOP_AUDIO_MODE=host-pulseaudio is required.'
        return 64
    }
    desktop_pulse_server=tcp:127.0.0.1:4713
    # After a package upgrade the live daemon may still use the replaced executable.
    # --check then rejects its PID even though the requested endpoint is healthy.
    # Probe that endpoint first so installing another desktop never starts a duplicate daemon.
    if ! desktop_audio_run "$PREFIX/bin/pactl" --server="$desktop_pulse_server" info >/dev/null 2>&1 &&
        ! desktop_audio_run env -u PULSE_SERVER "$PREFIX/bin/pulseaudio" --check >/dev/null 2>&1; then
        log 'Starting host PulseAudio with a loopback-only container endpoint.'
        mkdir -p "$HOME/.termux" || return 70
        log "PulseAudio server log: $HOME/.termux/desktop-pulseaudio.log"
        desktop_audio_run env -u PULSE_SERVER "$PREFIX/bin/pulseaudio" --start --exit-idle-time=-1 \
            --log-target="file:$HOME/.termux/desktop-pulseaudio.log" \
            --load='module-native-protocol-tcp listen=127.0.0.1 port=4713 auth-anonymous=1' \
            >> "$DESKTOP_LOG_FILE" 2>&1 || {
            log 'Host PulseAudio failed to start; desktop audio is required.'
            return 70
        }
    fi
    # --start does not add modules to an already-running local-only daemon.
    if ! desktop_audio_run "$PREFIX/bin/pactl" --server="$desktop_pulse_server" info >/dev/null 2>&1; then
        desktop_audio_run env -u PULSE_SERVER "$PREFIX/bin/pactl" load-module module-native-protocol-tcp \
            listen=127.0.0.1 port=4713 auth-anonymous=1 >> "$DESKTOP_LOG_FILE" 2>&1 || {
            log 'Cannot expose the host PulseAudio server to desktop clients.'
            return 70
        }
    fi
    desktop_audio_run "$PREFIX/bin/pactl" --server="$desktop_pulse_server" info >> "$DESKTOP_LOG_FILE" 2>&1 || {
        log 'Host PulseAudio endpoint is unavailable; desktop session was not started.'
        return 70
    }
    desktop_sinks=$(desktop_audio_run "$PREFIX/bin/pactl" --server="$desktop_pulse_server" list short sinks) || return 70
    desktop_android_sink=$(printf '%s\n' "$desktop_sinks" |
        awk '$3 ~ /module-(sles|aaudio)-sink/ { print $2; exit }')
    [ -n "$desktop_android_sink" ] || {
        log 'Host PulseAudio has no Android output sink; refusing a silent desktop session.'
        return 70
    }
    desktop_audio_run "$PREFIX/bin/pactl" --server="$desktop_pulse_server" set-default-sink "$desktop_android_sink" \
        >> "$DESKTOP_LOG_FILE" 2>&1 || return 70
    export PULSE_SERVER="$desktop_pulse_server"
    unset PULSE_SINK PULSE_SOURCE
    log "Desktop audio ready: mode=host-pulseaudio server=$PULSE_SERVER sink=$desktop_android_sink"
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
