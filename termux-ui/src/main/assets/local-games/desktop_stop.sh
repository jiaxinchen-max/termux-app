#!/bin/sh
# Stops the desktop started by desktop_start.sh, including the compositor and, in proot mode, the
# Plasma processes inside the container (start-plasma does not clean those up).

set -u

SPEC_PATH="${1:?Usage: $0 <desktop_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

printf 'stopping\n' > "$DESKTOP_STATUS_FILE"
owned_storage_session=0
case "${DESKTOP_DE:-}:${DESKTOP_USB_STORAGE:-off}" in
    gnome:*|hyprland:*|xfce4:read-only) owned_storage_session=1 ;;
esac
stop_failure=0

# proot mode also has a container session; take it down first.
if [ -f "$DESKTOP_PID_FILE.container" ]; then
    container_pid=$(cat "$DESKTOP_PID_FILE.container" 2>/dev/null)
    case "$container_pid" in ''|*[!0-9]*) ;; *)
        kill -TERM "$container_pid" 2>/dev/null
        if [ "$owned_storage_session" -eq 1 ]; then
            waited=0
            while pid_alive "$container_pid" && [ "$waited" -lt 125 ]; do
                waited=$((waited + 1)); sleep 1
            done
            if pid_alive "$container_pid"; then
                log 'Storage session did not finish cleanup before the stop deadline.'
                kill -KILL "$container_pid" 2>/dev/null
                stop_failure=124
            fi
        fi
    ;; esac
fi

if pid=$(read_pid) && pid_alive "$pid"; then
    log "Stopping desktop $DESKTOP_ID (pid $pid)"
    kill -TERM "$pid" 2>/dev/null
    waited=0
    wait_limit=10
    [ "$owned_storage_session" -eq 0 ] || wait_limit=130
    while pid_alive "$pid" && [ "$waited" -lt "$wait_limit" ]; do
        waited=$((waited + 1))
        sleep 1
    done
    if pid_alive "$pid"; then
        kill -KILL "$pid" 2>/dev/null
        [ "$owned_storage_session" -eq 0 ] || stop_failure=124
    fi
fi

if [ "${DESKTOP_DE:-}" != hyprland ] && [ "${DESKTOP_DE:-}" != gnome ] && [ "${DESKTOP_DE:-}:${DESKTOP_USB_STORAGE:-off}" != xfce4:read-only ]; then
    for name in plasmashell kded6 kglobalacceld kactivitymanagerd kwin_wayland Xwayland Xlorie xfce4-session xfwm4 xfdesktop xfce4-panel; do
        signal_by_name TERM "$name"
    done
    sleep 1
    for name in plasmashell kded6 kglobalacceld kactivitymanagerd kwin_wayland Xwayland Xlorie xfce4-session xfwm4 xfdesktop xfce4-panel; do
        signal_by_name KILL "$name"
    done
fi

rm -f "$DESKTOP_PID_FILE" "$DESKTOP_PID_FILE.container" "$DESKTOP_PID_FILE.gnome-ready" "$DESKTOP_PID_FILE.hyprland-ready" "$DESKTOP_PID_FILE.owned-session"
if [ "$owned_storage_session" -eq 1 ]; then
    final_status=$(cat "$DESKTOP_STATUS_FILE" 2>/dev/null)
    case "$final_status" in
        exit:*)
            final_code="${final_status#exit:}"
            case "$final_code" in ''|*[!0-9]*|0) final_code=1 ;; esac
            exit "$final_code" ;;
    esac
fi
[ "$stop_failure" -eq 0 ] || { printf 'exit:%s\n' "$stop_failure" > "$DESKTOP_STATUS_FILE"; exit "$stop_failure"; }
printf 'stopped\n' > "$DESKTOP_STATUS_FILE"
exit 0
