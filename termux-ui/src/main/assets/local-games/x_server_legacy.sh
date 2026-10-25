#!/bin/sh
# Legacy X server: the in-process X server of libXlorie.so, started through the termux-x11
# launcher. This is the behaviour the launch scripts always had; nothing here may change it.

x_server_legacy_require() {
    command -v termux-x11 >/dev/null 2>&1 && return 0
    X_SERVER_MISSING_ID=termux_x11_missing
    return 1
}

x_server_legacy_start() { # display log
    X_SERVER_READY_ATTEMPTS=200 # the launcher boots a JVM-less app_process; cold starts are slow
    termux-x11 "$1" >> "$2" 2>&1 &
    X_SERVER_PID=$!
}

x_server_legacy_kill_all() {
    ps -ef | grep 'termux.x11*' | grep -v grep | awk '{print $2}' | xargs -r kill -9 >/dev/null 2>&1 || true
}
