#!/bin/sh
# Xlorie X server: the independent Xlorie executable from the xserver termuxdc package. It is a
# termux-render client, so the display view (X11SessionView) must already be listening on the
# render socket; like KWin it cannot run while another client owns that socket.
#
# Environment (all optional):
#   X_SERVER_SCREEN     WIDTHxHEIGHTx24, default 1280x720x24; callers pass their resolution
#   X_SERVER_FRAMERATE  default 60
#   XLORIE_WAIT_RENDER  seconds to wait for the render socket, default 30
#   XLORIE_GL_MODE      default system; TERMUX_RENDER_AHB_RECEIVE defaults to native for it

# The launch scripts do not all export PREFIX (and run with set -u).
X_SERVER_PREFIX="${X_SERVER_PREFIX:-${PREFIX:-${TERMUX_FILES_DIR:-/data/data/com.termux/files}/usr}}"
X_SERVER_RENDER_SOCKET="${X_SERVER_RENDER_SOCKET:-/data/data/com.termux/files/home/tmp/termux-render}"

x_server_xlorie_require() {
    command -v Xlorie >/dev/null 2>&1 && return 0
    X_SERVER_MISSING_ID=xlorie_missing
    return 1
}

# The directory holding rules/evdev, or nothing.
x_server_xlorie_xkb_dir() {
    for _dir in "${XLORIE_XKBDIR:-}" "${XKB_CONFIG_ROOT:-}" "$X_SERVER_PREFIX/share/X11/xkb" \
        "$X_SERVER_PREFIX/share/xkeyboard-config-2"; do
        [ -n "$_dir" ] && [ -f "$_dir/rules/evdev" ] && { printf '%s' "$_dir"; return 0; }
    done
    return 1
}

x_server_xlorie_start() { # display log
    X_SERVER_READY_ATTEMPTS=200
    _x_display="$1"
    _x_log="$2"
    _x_number="${_x_display#:}"
    rm -f "$X_SERVER_PREFIX/tmp/.X11-unix/X$_x_number" "$X_SERVER_PREFIX/tmp/.X$_x_number-lock" 2>/dev/null || true

    _x_waited=0
    while [ ! -S "$X_SERVER_RENDER_SOCKET" ] && [ "$_x_waited" -lt "${XLORIE_WAIT_RENDER:-30}" ]; do
        _x_waited=$((_x_waited + 1))
        sleep 1
    done
    [ -S "$X_SERVER_RENDER_SOCKET" ] || \
        printf '%s\n' "termux-render socket not found, starting Xlorie anyway: $X_SERVER_RENDER_SOCKET" >> "$_x_log"

    export XLORIE_GL_MODE="${XLORIE_GL_MODE:-system}"
    case "$XLORIE_GL_MODE" in
        system) export TERMUX_RENDER_AHB_RECEIVE="${TERMUX_RENDER_AHB_RECEIVE:-native}" ;;
    esac
    set -- "$_x_display" -noreset -screen 0 "${X_SERVER_SCREEN:-1280x720x24}" \
        -framerate "${X_SERVER_FRAMERATE:-60}" -render-socket "$X_SERVER_RENDER_SOCKET"
    _x_xkb=$(x_server_xlorie_xkb_dir) && {
        export XKB_CONFIG_ROOT="$_x_xkb"
        set -- "$@" -xkbdir "$_x_xkb"
    }
    Xlorie "$@" >> "$_x_log" 2>&1 &
    X_SERVER_PID=$!
}

x_server_xlorie_kill_all() {
    for _proc in /proc/[0-9]*; do
        [ "$(cat "$_proc/comm" 2>/dev/null)" = Xlorie ] && kill -9 "${_proc#/proc/}" 2>/dev/null
    done
    return 0
}
