#!/bin/sh
# Stops the desktop started by desktop_start.sh, including the compositor and, in proot mode, the
# Plasma processes inside the container (start-plasma does not clean those up).

set -u

SPEC_PATH="${1:?Usage: $0 <desktop_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

printf 'stopping\n' > "$DESKTOP_STATUS_FILE"

# proot mode also has a container session; take it down first.
if [ -f "$DESKTOP_PID_FILE.container" ]; then
    container_pid=$(cat "$DESKTOP_PID_FILE.container" 2>/dev/null)
    case "$container_pid" in ''|*[!0-9]*) ;; *)
        kill -TERM "$container_pid" 2>/dev/null
    ;; esac
fi

if pid=$(read_pid) && pid_alive "$pid"; then
    log "Stopping desktop $DESKTOP_ID (pid $pid)"
    kill -TERM "$pid" 2>/dev/null
    waited=0
    while pid_alive "$pid" && [ "$waited" -lt 10 ]; do
        waited=$((waited + 1))
        sleep 1
    done
    pid_alive "$pid" && kill -KILL "$pid" 2>/dev/null
fi

for name in plasmashell kded6 kglobalacceld kactivitymanagerd kwin_wayland Xwayland; do
    signal_by_name TERM "$name"
done
sleep 1
for name in plasmashell kded6 kglobalacceld kactivitymanagerd kwin_wayland Xwayland; do
    signal_by_name KILL "$name"
done

rm -f "$DESKTOP_PID_FILE" "$DESKTOP_PID_FILE.container"
printf 'stopped\n' > "$DESKTOP_STATUS_FILE"
exit 0
