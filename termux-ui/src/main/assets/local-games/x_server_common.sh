#!/bin/sh
# Picks the X server implementation for a game launch and exposes one small API:
#
#   x_server_require            0 when the selected X server is installed; otherwise 1 and
#                               X_SERVER_MISSING_ID names the failure
#   x_server_start <display> <log>
#                               starts it in the background; X_SERVER_PID is its pid
#   x_server_kill_all           stops every X server process of the selected implementation
#
# Sourced after X_SERVER_DIR is set to the directory holding these scripts. The selection comes
# from, in order: $X_SERVER_BACKEND, the one-line "x-server-backend" marker the app writes next to
# these scripts, and finally "legacy". Both implementations are always deployed, so switching is
# only a change of that value.

X_SERVER_PID=
X_SERVER_MISSING_ID=
X_SERVER_READY_ATTEMPTS=40

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
    if [ "$(x_server_backend)" = xlorie ]; then
        x_server_xlorie_start "$@"
    else
        x_server_legacy_start "$@"
    fi
}

x_server_kill_all() {
    if [ "$(x_server_backend)" = xlorie ]; then x_server_xlorie_kill_all; else x_server_legacy_kill_all; fi
}
