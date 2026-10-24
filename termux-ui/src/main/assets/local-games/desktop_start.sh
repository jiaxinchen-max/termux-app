#!/bin/sh
# Starts KWin (native) or KWin plus the proot Plasma session, supervises it, and records the
# outcome in the status file. Launched by the app after the X11 session view is bound.

set -u

SPEC_PATH="${1:?Usage: $0 <desktop_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

existing=$(read_pid) && pid_alive "$existing" && {
    log "Desktop $DESKTOP_ID is already running (pid $existing)"
    exit 0
}

printf 'starting\n' > "$DESKTOP_STATUS_FILE"
: > "$DESKTOP_LOG_FILE"

wait_for_socket() {
    waited=0
    while [ ! -S "$DESKTOP_RENDER_SOCKET" ]; do
        waited=$((waited + 1))
        [ "$waited" -le "${DESKTOP_SOCKET_WAIT_SECONDS:-90}" ] || return 1
        sleep 1
    done
}

if ! wait_for_socket; then
    log "The display bridge socket $DESKTOP_RENDER_SOCKET never appeared; open the desktop view first."
    printf 'exit:66\n' > "$DESKTOP_STATUS_FILE"
    exit 66
fi

# DESKTOP_RENDER_SOCKET is fixed by the display bridge; everything below is KWin's contract.
KWIN_ANDROID_RUNTIME_ROOT="$HOME/.termux/kwin-runtime"
export KWIN_ANDROID_RUNTIME_ROOT
export KWIN_ANDROID_GL_MODE="$DESKTOP_GL_MODE"
export KWIN_ANDROID_REFRESH_RATE="$DESKTOP_REFRESH_RATE"
export KWIN_ANDROID_WIDTH="$DESKTOP_WIDTH"
export KWIN_ANDROID_HEIGHT="$DESKTOP_HEIGHT"
export PLASMA_LOG="$DESKTOP_LOG_FILE"

CONTAINER_PID_FILE="$DESKTOP_PID_FILE.container"
rm -f "$CONTAINER_PID_FILE"
code=0

if [ "$DESKTOP_MODE" = "proot" ]; then
    # The r19 kwin package ships neither start-plasma-container nor the container session script,
    # so do what start-plasma-container does: native compositor first, then the Plasma session
    # inside the proot-distro container, sharing the compositor's runtime directory.
    for needed in start-plasma proot-distro; do
        command -v "$needed" >/dev/null 2>&1 || {
            log "$needed is not installed; run the desktop install first."
            printf 'exit:127\n' > "$DESKTOP_STATUS_FILE"
            exit 127
        }
    done
    XDG_RUNTIME_DIR_HOST="$KWIN_ANDROID_RUNTIME_ROOT/runtime-$(id -u)"
    SESSION_SCRIPT="$(dirname "$0")/desktop_container_session.sh"
    [ -r "$SESSION_SCRIPT" ] || {
        log "Container session script is missing: $SESSION_SCRIPT"
        printf 'exit:66\n' > "$DESKTOP_STATUS_FILE"
        exit 66
    }

    start-plasma --compositor-only >> "$DESKTOP_LOG_FILE.out" 2>&1 &
    child=$!
    printf '%s\n' "$child" > "$DESKTOP_PID_FILE"
    trap 'kill -TERM "$child" 2>/dev/null; [ -z "${container:-}" ] || kill -TERM "$container" 2>/dev/null' TERM INT

    waited=0
    while [ ! -S "$XDG_RUNTIME_DIR_HOST/wayland-0" ] && kill -0 "$child" 2>/dev/null; do
        waited=$((waited + 1))
        if [ "$waited" -gt 60 ]; then
            log "The compositor's Wayland socket never appeared in $XDG_RUNTIME_DIR_HOST"
            kill -TERM "$child" 2>/dev/null
            break
        fi
        sleep 1
    done

    if [ -S "$XDG_RUNTIME_DIR_HOST/wayland-0" ]; then
        log "Compositor ready; starting the $DESKTOP_PROOT_DISTRO container session"
        proot-distro login "$DESKTOP_PROOT_DISTRO" \
            --bind "$XDG_RUNTIME_DIR_HOST:/run/wl" \
            --bind "$SESSION_SCRIPT:/tmp/plasma-container-session.sh" \
            -- sh /tmp/plasma-container-session.sh >> "$DESKTOP_LOG_FILE.out" 2>&1 &
        container=$!
        printf '%s\n' "$container" > "$CONTAINER_PID_FILE"
    fi

    # Either half dying ends the desktop: a container session that fails (for example a missing
    # Vulkan ICD) must not leave a bare compositor behind.
    container_ended=0
    while kill -0 "$child" 2>/dev/null; do
        if [ -n "${container:-}" ] && ! kill -0 "$container" 2>/dev/null; then
            wait "$container"
            code=$?
            container_ended=1
            log "The container session exited with status $code"
            if [ -f "$XDG_RUNTIME_DIR_HOST/plasma-container.log" ]; then
                log "--- plasma-container.log (tail) ---"
                tail -n 20 "$XDG_RUNTIME_DIR_HOST/plasma-container.log" | tee -a "$DESKTOP_LOG_FILE"
            fi
            kill -TERM "$child" 2>/dev/null
            sleep 1
            kill -KILL "$child" 2>/dev/null
            break
        fi
        sleep 1
    done
    if [ "$container_ended" -eq 0 ]; then
        wait "$child"
        code=$?
    fi
    [ -z "${container:-}" ] || kill -TERM "$container" 2>/dev/null
    rm -f "$CONTAINER_PID_FILE"
else
    command_name="start-plasma"
    command -v "$command_name" >/dev/null 2>&1 || {
        log "$command_name is not installed; run the desktop install first."
        printf 'exit:127\n' > "$DESKTOP_STATUS_FILE"
        exit 127
    }
    "$command_name" >> "$DESKTOP_LOG_FILE.out" 2>&1 &
    child=$!
    printf '%s\n' "$child" > "$DESKTOP_PID_FILE"
    trap 'kill -TERM "$child" 2>/dev/null' TERM INT
    while kill -0 "$child" 2>/dev/null; do
        wait "$child"
        code=$?
    done
fi

rm -f "$DESKTOP_PID_FILE"
if [ "$(cat "$DESKTOP_STATUS_FILE" 2>/dev/null)" = "stopping" ]; then
    printf 'stopped\n' > "$DESKTOP_STATUS_FILE"
else
    printf 'exit:%s\n' "$code" > "$DESKTOP_STATUS_FILE"
fi
exit "$code"
