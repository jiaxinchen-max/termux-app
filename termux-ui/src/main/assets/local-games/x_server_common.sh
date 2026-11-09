#!/bin/sh
# Picks the X server implementation for a game launch and exposes one small API:
#
#   x_server_require            0 when the selected X server is installed; otherwise 1 and
#                               X_SERVER_MISSING_ID names the failure
#   x_server_start <display> <log>
#                               starts it in the background; X_SERVER_PID is its pid
#   x_server_wait_ready <display>
#                               0 once the display socket exists and belongs to the server just
#                               started (a socket older than x_server_start only counts when the
#                               launcher has already exited, i.e. a server was running); waits
#                               X_SERVER_READY_ATTEMPTS x 0.1 s, then 1
#   x_server_kill_all           stops every X server process of the selected implementation
#
# Sourced after X_SERVER_DIR is set to the directory holding these scripts. The selection comes
# from, in order: $X_SERVER_BACKEND, the one-line "x-server-backend" marker the app writes next to
# these scripts, and finally "legacy". Both implementations are always deployed, so switching is
# only a change of that value.

X_SERVER_PID=
X_SERVER_MISSING_ID=
X_SERVER_READY_ATTEMPTS=40
X_SERVER_STARTED_AT=0

x_server_backend() {
    _x_value="${X_SERVER_BACKEND:-}"
    [ -n "$_x_value" ] || _x_value=$(sed -n '1p' "$X_SERVER_DIR/x-server-backend" 2>/dev/null)
    case "$_x_value" in
        xlorie) printf '%s' xlorie ;;
        *) printf '%s' legacy ;;
    esac
}

. "$X_SERVER_DIR/x_server_legacy.sh"
. "$X_SERVER_DIR/x_server_xlorie.sh"

x_server_require() {
    if [ "$(x_server_backend)" = xlorie ]; then x_server_xlorie_require; else x_server_legacy_require; fi
}

x_server_start() {
    X_SERVER_STARTED_AT=$(date +%s)
    if [ "$(x_server_backend)" = xlorie ]; then
        x_server_xlorie_start "$@"
    else
        x_server_legacy_start "$@"
    fi
}

x_server_kill_all() {
    if [ "$(x_server_backend)" = xlorie ]; then x_server_xlorie_kill_all; else x_server_legacy_kill_all; fi
}

x_server_wait_ready() { # display
    _x_socket="${X_SERVER_PREFIX:-${PREFIX:-/data/data/com.termux/files/usr}}/tmp/.X11-unix/X${1#:}"
    _x_attempt=0
    while [ "$_x_attempt" -lt "$X_SERVER_READY_ATTEMPTS" ]; do
        if [ -S "$_x_socket" ]; then
            _x_mtime=$(stat -c %Y "$_x_socket" 2>/dev/null || echo "$X_SERVER_STARTED_AT")
            [ "$_x_mtime" -ge $((X_SERVER_STARTED_AT - 1)) ] && return 0
            # Older socket: only trustworthy when no new server is coming up behind it.
            [ -n "$X_SERVER_PID" ] && kill -0 "$X_SERVER_PID" 2>/dev/null || return 0
        fi
        sleep 0.1
        _x_attempt=$((_x_attempt + 1))
    done
    return 1
}

# Read-only diagnostics through the X11 socket: no guest libraries or tools are loaded.
x_server_log_dimensions() { # display target-resolution wine log
    printf 'X11 display: server=%s wine=%s target=%s\n' "$(x_server_backend)" "$3" "$2" >> "$4"
    if command -v python >/dev/null 2>&1 && [ -f "$X_SERVER_DIR/x_server_dimensions.py" ]; then
        python "$X_SERVER_DIR/x_server_dimensions.py" \
            "${X_SERVER_PREFIX:-${PREFIX:-/data/data/com.termux/files/usr}}/tmp/.X11-unix/X${1#:}" \
            "$2" >> "$4" 2>&1 || true
    else
        printf '%s\n' 'X11 dimensions: diagnostic unavailable (Python or helper missing)' >> "$4"
    fi
}
