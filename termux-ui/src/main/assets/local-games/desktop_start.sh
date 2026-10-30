#!/bin/sh
# Starts KWin (native) or KWin plus the proot Plasma session, supervises it, and records the
# outcome in the status file. Launched by the app after the X11 session view is bound.

set -u

SPEC_PATH="${1:?Usage: $0 <desktop_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

existing=$(read_pid) && pid_alive "$existing" && {
    log "ERROR: Desktop $DESKTOP_ID is already running (pid $existing); stop it before applying the requested renderer ${DESKTOP_GL_MODE:-unset}."
    exit 73
}

# Every desktop shares one display bridge (the render socket) and X display, so another desktop's
# session still winding down - or left behind when the app was killed - can stall this one: Xlorie
# hangs while it initialises ("Xlorie did not become ready"). Stop those first and wait for them.
stop_other_desktops() {
    state_dir=$(dirname "$DESKTOP_PID_FILE")
    others=""
    stopped_files=""
    for pid_file in "$state_dir"/*.pid "$state_dir"/*.pid.container; do
        [ -f "$pid_file" ] || continue
        case "$pid_file" in "$DESKTOP_PID_FILE"|"$DESKTOP_PID_FILE.container") continue ;; esac
        other=$(cat "$pid_file" 2>/dev/null)
        case "$other" in ''|*[!0-9]*) continue ;; esac
        pid_alive "$other" || { rm -f "$pid_file"; continue; }
        log "Stopping $(basename "$pid_file") (pid $other) left over from another desktop"
        kill -TERM "$other" 2>/dev/null
        others="$others $other"
        stopped_files="$stopped_files $pid_file"
    done
    waited=0
    for other in $others; do
        while pid_alive "$other" && [ "$waited" -lt 100 ]; do
            waited=$((waited + 1))
            sleep 0.1
        done
        pid_alive "$other" && kill -KILL "$other" 2>/dev/null
    done
    # shellcheck disable=SC2086
    [ -z "$stopped_files" ] || rm -f $stopped_files
}

printf 'starting\n' > "$DESKTOP_STATUS_FILE"
: > "$DESKTOP_LOG_FILE"
# Resolve no modes implicitly: old Auto entries must be edited in the app first.
case "${DESKTOP_GL_MODE:-}" in
    system|zink|llvmpipe) ;;
    *) log "Invalid renderer '${DESKTOP_GL_MODE:-}'; select System EGL, Zink or Software in desktop settings."
       printf 'exit:64\n' > "$DESKTOP_STATUS_FILE"
       exit 64 ;;
esac
if [ "$DESKTOP_MODE" = "proot" ] && [ "${DESKTOP_DE:-kwin_plasma}" = "kwin_plasma" ] && [ "$DESKTOP_GL_MODE" = "system" ]; then
    log "Container Plasma does not support System EGL; select Zink or Software explicitly."
    printf 'exit:64\n' > "$DESKTOP_STATUS_FILE"
    exit 64
fi
log "Requested renderer: $DESKTOP_GL_MODE ($DESKTOP_MODE/${DESKTOP_DE:-kwin_plasma}); renderer fallback is disabled."
stop_other_desktops

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

finish_desktop() {
    rm -f "$DESKTOP_PID_FILE" "$CONTAINER_PID_FILE"
    # desktop_stop.sh owns a requested shutdown, even if it finished writing 'stopped' before us.
    case "$(cat "$DESKTOP_STATUS_FILE" 2>/dev/null)" in
        stopping|stopped) printf 'stopped\n' > "$DESKTOP_STATUS_FILE"; code=0 ;;
        *) printf 'exit:%s\n' "$code" > "$DESKTOP_STATUS_FILE" ;;
    esac
    exit "$code"
}

DESKTOP_DE="${DESKTOP_DE:-kwin_plasma}"

if [ "$DESKTOP_DE" != "xfce4" ] && [ ! -L "$PREFIX/tmp/.X11-unix" ] && [ -d "$PREFIX/tmp/.X11-unix" ]; then
    # An XFCE session leaves Xlorie's X0 socket in a real .X11-unix. start-plasma can only turn that
    # directory into the symlink to KWin's Xwayland sockets while it is empty (otherwise it just warns
    # and X11 clients reach the dead X0), so clear what Xlorie left behind.
    rm -f "$PREFIX/tmp/.X11-unix/X0" "$PREFIX/tmp/.X0-lock"
    rmdir "$PREFIX/tmp/.X11-unix" 2>/dev/null || true
fi

# A socket from this run and a live server are both required before starting clients.
wait_for_backend() { # socket label
    backend_socket="$1"
    backend_label="$2"
    waited=0
    while [ ! -S "$backend_socket" ] || ! kill -0 "$child" 2>/dev/null; do
        if ! kill -0 "$child" 2>/dev/null; then
            wait "$child"
            backend_code=$?
            # Exiting successfully without publishing a display is still a startup failure.
            [ "$backend_code" -ne 0 ] || backend_code=70
            log "$backend_label exited before its display became ready (status $backend_code); session was not started."
            return "$backend_code"
        fi
        if [ "$waited" -ge "${DESKTOP_BACKEND_WAIT_SECONDS:-60}" ]; then
            log "$backend_label did not become ready at $backend_socket; session was not started (status 124)."
            kill -TERM "$child" 2>/dev/null
            sleep 1
            kill -KILL "$child" 2>/dev/null
            wait "$child" 2>/dev/null
            return 124
        fi
        waited=$((waited + 1))
        sleep 1
    done
}

if [ "$DESKTOP_DE" = "xfce4" ]; then
    # Xlorie serves the X display; the XFCE session runs natively or inside the container.
    command -v Xlorie >/dev/null 2>&1 || {
        log "Xlorie is not installed; run the desktop install first."
        printf 'exit:127\n' > "$DESKTOP_STATUS_FILE"
        exit 127
    }
    gl_mode="$DESKTOP_GL_MODE"
    export XLORIE_GL_MODE="$gl_mode"
    case "$gl_mode" in
        system) export TERMUX_RENDER_AHB_RECEIVE=native ;;
        zink|llvmpipe) export TERMUX_RENDER_AHB_RECEIVE=dmabuf ;;
    esac
    log "Xlorie renderer=$gl_mode, AHB receive=$TERMUX_RENDER_AHB_RECEIVE"
    if [ "$DESKTOP_MODE" = "proot" ]; then
        # glibc compositors must not touch Xlorie's minimal GLX (xserver VERIFICATION.md #2).
        export XLORIE_GLX=0
    else
        export XLORIE_GLX=1
    fi
    # start-plasma turns $PREFIX/tmp/.X11-unix into a symlink to KWin's runtime directory and never
    # puts it back. The X server will not create its file socket (X0) through a symlink - it then
    # only listens on the abstract socket and the wait below times out - so give it a real directory.
    if [ -L "$PREFIX/tmp/.X11-unix" ]; then
        rm -f "$PREFIX/tmp/.X11-unix"
    fi
    mkdir -p "$PREFIX/tmp/.X11-unix"
    chmod 1777 "$PREFIX/tmp/.X11-unix" 2>/dev/null || true
    rm -f "$PREFIX/tmp/.X11-unix/X0" "$PREFIX/tmp/.X0-lock"
    Xlorie :0 -noreset -screen 0 "${DESKTOP_WIDTH}x${DESKTOP_HEIGHT}x24" \
        -framerate "$DESKTOP_REFRESH_RATE" -render-socket "$DESKTOP_RENDER_SOCKET" \
        -xkbdir "$PREFIX/share/X11/xkb" >> "$DESKTOP_LOG_FILE" 2>&1 &
    child=$!
    printf '%s\n' "$child" > "$DESKTOP_PID_FILE"
    trap 'kill -TERM "$child" 2>/dev/null; [ -z "${session:-}" ] || kill -TERM "$session" 2>/dev/null' TERM INT
    if wait_for_backend "$PREFIX/tmp/.X11-unix/X0" Xlorie; then
        :
    else
        code=$?
        finish_desktop
    fi
    export DISPLAY=:0
    log "Starting the $DESKTOP_MODE XFCE4 session (DISPLAY=:0, XLORIE_GL_MODE=$gl_mode)"
    if [ "$DESKTOP_MODE" = "proot" ]; then
        proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- env DISPLAY=:0 \
            sh -c 'export XDG_RUNTIME_DIR=/tmp/xdg; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"; exec dbus-run-session -- xfce4-session' \
            >> "$DESKTOP_LOG_FILE.out" 2>&1 &
    else
        DISPLAY=:0 dbus-run-session -- xfce4-session >> "$DESKTOP_LOG_FILE.out" 2>&1 &
    fi
    session=$!
    printf '%s\n' "$session" > "$CONTAINER_PID_FILE"
    code=0
    session_ended=0
    while kill -0 "$child" 2>/dev/null; do
        if [ -n "${session:-}" ] && ! kill -0 "$session" 2>/dev/null; then
            wait "$session"
            code=$?
            session_ended=1
            log "The XFCE session exited with status $code"
            kill -TERM "$child" 2>/dev/null
            sleep 1
            kill -KILL "$child" 2>/dev/null
            break
        fi
        sleep 1
    done
    if [ "$session_ended" -eq 0 ]; then
        wait "$child"
        code=$?
    fi
    [ -z "${session:-}" ] || kill -TERM "$session" 2>/dev/null
    finish_desktop
fi

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
    for helper in "$SESSION_SCRIPT" "$(dirname "$0")/desktop_container_statx_setup.sh" "$(dirname "$0")/desktop_container_run_config.sh" "$(dirname "$0")/desktop_container_statx_aarch64.bin"; do
        [ -r "$helper" ] || {
            log "Container session asset is missing: $helper"
            printf 'exit:66\n' > "$DESKTOP_STATUS_FILE"
            exit 66
        }
    done

    # Container clients show up to KWin as proot's loader, so its executable-path based authorisation
    # of plasma-window-management / kde-output-device would deny them; the whole container is trusted.
    rm -f "$XDG_RUNTIME_DIR_HOST/wayland-0"
    KWIN_WAYLAND_NO_PERMISSION_CHECKS=1 start-plasma --compositor-only >> "$DESKTOP_LOG_FILE.out" 2>&1 &
    child=$!
    printf '%s\n' "$child" > "$DESKTOP_PID_FILE"
    trap 'kill -TERM "$child" 2>/dev/null; [ -z "${container:-}" ] || kill -TERM "$container" 2>/dev/null' TERM INT

    if wait_for_backend "$XDG_RUNTIME_DIR_HOST/wayland-0" Compositor; then
        :
    else
        code=$?
        if [ -f "$DESKTOP_LOG_FILE.out" ]; then
            log "--- compositor output (tail) ---"
            tail -n 20 "$DESKTOP_LOG_FILE.out" | tee -a "$DESKTOP_LOG_FILE"
        fi
        finish_desktop
    fi

    if [ -S "$XDG_RUNTIME_DIR_HOST/wayland-0" ] && kill -0 "$child" 2>/dev/null; then
        log "Compositor ready; starting the $DESKTOP_PROOT_DISTRO container session"
        proot-distro login "$DESKTOP_PROOT_DISTRO" \
            --bind "$XDG_RUNTIME_DIR_HOST:/run/wl" \
            --bind "$SESSION_SCRIPT:/tmp/plasma-container-session.sh" \
            --bind "$(dirname "$0")/desktop_container_statx_setup.sh:/tmp/desktop_container_statx_setup.sh" \
            --bind "$(dirname "$0")/desktop_container_run_config.sh:/tmp/desktop_container_run_config.sh" \
            --bind "$(dirname "$0")/desktop_container_statx_aarch64.bin:/tmp/desktop_container_statx_aarch64.bin" \
            -- env DESKTOP_GL_MODE="$DESKTOP_GL_MODE" DESKTOP_STATX_BACKEND=proc-fdinfo \
                DESKTOP_KDE_SERVICE_POLICY=kwin-wayland QT_QPA_PLATFORM=wayland XDG_SESSION_TYPE=wayland \
                sh /tmp/plasma-container-session.sh >> "$DESKTOP_LOG_FILE.out" 2>&1 &
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
                tail -n 80 "$XDG_RUNTIME_DIR_HOST/plasma-container.log" | tee -a "$DESKTOP_LOG_FILE"
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
    while :; do
        wait "$child"
        code=$?
        kill -0 "$child" 2>/dev/null || break
    done
fi

finish_desktop
