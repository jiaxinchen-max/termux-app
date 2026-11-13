#!/data/data/com.termux/files/usr/bin/bash
# 完整的Plasma桌面启动脚本 - 集成硬件加速KWin

set -e

INSTALL_DEPS=0
VERBOSE_LOG=0
DEBUG_RENDERING=0
# Compositor-only mode: run kwin_wayland (+ Xwayland) natively but do NOT start
# the native Plasma shell/daemons. The Plasma session instead runs inside a
# proot/glibc container and connects to this compositor over the shared Wayland
# socket in $XDG_RUNTIME_DIR (the container runs its own D-Bus; the native bus
# cannot be shared across proot). See packaging/container/plasma-container-session.sh.
COMPOSITOR_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --install-deps|-i)
            INSTALL_DEPS=1
            ;;
        --compositor-only|--container-session)
            COMPOSITOR_ONLY=1
            # Container clients are proot processes KWin cannot match to a desktop file; its
            # executable-path authorisation of privileged protocols would deny them all.
            export KWIN_WAYLAND_NO_PERMISSION_CHECKS=1
            ;;
        -verbose|--verbose)
            VERBOSE_LOG=1
            ;;
        --debug-rendering)
            DEBUG_RENDERING=1
            VERBOSE_LOG=1
            ;;
        --test-xwayland)
            export KWIN_ANDROID_XWAYLAND_SELF_TEST=1
            export KWIN_XWAYLAND_DEBUG=1
            ;;
    esac
done

# 环境变量设置（日志初始化前）
export PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
export HOME="${HOME:-/data/data/com.termux/files/home}"
# TermuxService may clear $PREFIX/tmp while native desktop processes are still
# alive. Keep display sockets, locks and logs in a persistent per-user path.
export KWIN_ANDROID_RUNTIME_ROOT="${KWIN_ANDROID_RUNTIME_ROOT:-$HOME/.termux/kwin-runtime}"
export TMPDIR="${KWIN_ANDROID_TMPDIR:-$KWIN_ANDROID_RUNTIME_ROOT/tmp}"
export XDG_RUNTIME_DIR="${KWIN_ANDROID_XDG_RUNTIME_DIR:-$KWIN_ANDROID_RUNTIME_ROOT/runtime-$(id -u)}"
export KWIN_WAYLAND_SOCKET="${KWIN_WAYLAND_SOCKET:-wayland-0}"

mkdir -p "$KWIN_ANDROID_RUNTIME_ROOT" "$XDG_RUNTIME_DIR" "$TMPDIR/.X11-unix"
chmod 0700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
chmod 1777 "$TMPDIR/.X11-unix" 2>/dev/null || true

# Termux libX11/libxcb use $PREFIX/tmp/.X11-unix as their compiled-in Unix
# socket directory. KWin follows TMPDIR when it creates the lazy Xwayland
# socket, so expose the persistent socket directory at the legacy path too.
X11_SOCKET_DIR="$TMPDIR/.X11-unix"
X11_COMPAT_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
if [ "$X11_SOCKET_DIR" != "$X11_COMPAT_SOCKET_DIR" ]; then
    mkdir -p "$PREFIX/tmp"
    if [ -L "$X11_COMPAT_SOCKET_DIR" ]; then
        if [ "$(readlink "$X11_COMPAT_SOCKET_DIR" 2>/dev/null || true)" != "$X11_SOCKET_DIR" ]; then
            rm -f "$X11_COMPAT_SOCKET_DIR"
            ln -s "$X11_SOCKET_DIR" "$X11_COMPAT_SOCKET_DIR"
        fi
    elif [ -d "$X11_COMPAT_SOCKET_DIR" ]; then
        if rmdir "$X11_COMPAT_SOCKET_DIR" 2>/dev/null; then
            ln -s "$X11_SOCKET_DIR" "$X11_COMPAT_SOCKET_DIR"
        else
            echo "Warning: $X11_COMPAT_SOCKET_DIR is not empty; X11 clients may not find the Xwayland socket" >&2
        fi
    elif [ -e "$X11_COMPAT_SOCKET_DIR" ]; then
        echo "Warning: $X11_COMPAT_SOCKET_DIR exists and is not a directory" >&2
    else
        ln -s "$X11_SOCKET_DIR" "$X11_COMPAT_SOCKET_DIR"
    fi
fi

SESSION_LOCK_DIR="$XDG_RUNTIME_DIR/start-plasma.lock"
SESSION_LOCK_PID_FILE="$SESSION_LOCK_DIR/pid"

release_session_lock()
{
    if [ -f "$SESSION_LOCK_PID_FILE" ] && [ "$(cat "$SESSION_LOCK_PID_FILE" 2>/dev/null || true)" = "$$" ]; then
        rm -f "$SESSION_LOCK_PID_FILE"
        rmdir "$SESSION_LOCK_DIR" 2>/dev/null || true
    fi
}

acquire_session_lock()
{
    local owner_pid

    if mkdir "$SESSION_LOCK_DIR" 2>/dev/null; then
        printf '%s\n' "$$" > "$SESSION_LOCK_PID_FILE"
        return 0
    fi

    owner_pid="$(cat "$SESSION_LOCK_PID_FILE" 2>/dev/null || true)"
    if [[ "$owner_pid" =~ ^[0-9]+$ ]] && kill -0 "$owner_pid" 2>/dev/null; then
        echo "Plasma is already starting or running (start-plasma pid $owner_pid)."
        return 1
    fi

    rm -f "$SESSION_LOCK_PID_FILE"
    if ! rmdir "$SESSION_LOCK_DIR" 2>/dev/null || ! mkdir "$SESSION_LOCK_DIR" 2>/dev/null; then
        echo "Error: cannot acquire session lock: $SESSION_LOCK_DIR" >&2
        return 2
    fi
    printf '%s\n' "$$" > "$SESSION_LOCK_PID_FILE"
}

if acquire_session_lock; then
    :
else
    LOCK_STATUS=$?
    exit "$LOCK_STATUS"
fi
trap release_session_lock EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if command -v pgrep >/dev/null 2>&1; then
    EXISTING_KWIN_PID="$(pgrep -x kwin_wayland | head -n 1 || true)"
    if [ -n "$EXISTING_KWIN_PID" ]; then
        echo "Error: requested KWin session conflicts with existing kwin_wayland pid $EXISTING_KWIN_PID; refusing to reuse or stop it." >&2
        exit 73
    fi
fi

KWIN_SOCKET_PATH="$XDG_RUNTIME_DIR/$KWIN_WAYLAND_SOCKET"
if [ -S "$KWIN_SOCKET_PATH" ]; then
    if command -v pgrep >/dev/null 2>&1 && pgrep -x kwin_wayland >/dev/null 2>&1; then
        echo "Error: requested KWin session conflicts with an existing kwin_wayland on $KWIN_WAYLAND_SOCKET; refusing to reuse or stop it." >&2
        exit 73
    fi
    echo "Removing stale Wayland socket: $KWIN_SOCKET_PATH"
    rm -f "$KWIN_SOCKET_PATH" "$KWIN_SOCKET_PATH.lock"
fi

PLASMA_LOG="${PLASMA_LOG:-$KWIN_ANDROID_RUNTIME_ROOT/start-plasma.log}"
mkdir -p "$(dirname "$PLASMA_LOG")"
: > "$PLASMA_LOG"
exec 3>&1 4>&2
echo "Starting Plasma; log: $PLASMA_LOG" >&3
if [ "$VERBOSE_LOG" = "1" ]; then
    exec > >(tee -a "$PLASMA_LOG") 2>&1
else
    exec > "$PLASMA_LOG" 2>&1
fi

echo "=== Plasma Desktop with Explicit KWin Renderer ==="
echo "Logging to: $PLASMA_LOG"

# 检查Termux环境
if [ ! -d "/data/data/com.termux" ]; then
    echo "Error: Must run in Termux environment"
    exit 1
fi

# 检查是否需要安装依赖
if [ "$INSTALL_DEPS" = "1" ]; then
    echo "Installing required packages..."
    RENDER_DEPS=(
        plasma-desktop plasma-workspace kactivitymanagerd
        konsole dolphin dbus mesa vulkan-tools
    )
    if [ -e "${KWIN_ANDROID_KGSL_DEVICE:-/dev/kgsl-3d0}" ]; then
        RENDER_DEPS+=(mesa-vulkan-icd-freedreno)
    fi
    pkg install "${RENDER_DEPS[@]}" -y
    echo "✓ Dependencies installed"
    echo ""
fi

# 环境变量设置
export XDG_CURRENT_DESKTOP="KDE"
export XDG_SESSION_TYPE="wayland"
export DESKTOP_SESSION="${DESKTOP_SESSION:-plasma}"
export KDE_FULL_SESSION="${KDE_FULL_SESSION:-true}"
export KDE_SESSION_VERSION="${KDE_SESSION_VERSION:-6}"
export QT_QPA_PLATFORM="wayland"
export KWIN_BACKEND="android"
export KWIN_ANDROID_DISABLE_INPUT="${KWIN_ANDROID_DISABLE_INPUT:-0}"
export KWIN_ANDROID_REFRESH_RATE="${KWIN_ANDROID_REFRESH_RATE:-30}"
export KWIN_ANDROID_BUFFER_TYPE="${KWIN_ANDROID_BUFFER_TYPE:-ahb}"
export KWIN_ANDROID_ENABLE_EGL="${KWIN_ANDROID_ENABLE_EGL:-1}"
export XDG_CONFIG_DIRS="${XDG_CONFIG_DIRS:-$PREFIX/etc/xdg}"
export XDG_DATA_DIRS="${XDG_DATA_DIRS:-$PREFIX/share}"
export XDG_MENU_PREFIX="${XDG_MENU_PREFIX:-plasma-}"
export XCURSOR_THEME="${XCURSOR_THEME:-breeze_cursors}"
if [ "$DEBUG_RENDERING" = "1" ]; then
    export KWIN_ANDROID_DEBUG=1
fi
case "${KWIN_ANDROID_DEBUG:-0}" in
    1|true|yes|on)
        export QT_LOGGING_RULES="${QT_LOGGING_RULES:+${QT_LOGGING_RULES};}kwin.android.egl.debug=true"
        ;;
esac
find_kactivitymanagerd() {
    local candidate
    for candidate in \
        kactivitymanagerd \
        kactivitymanagerd6 \
        "$PREFIX/lib/libexec/kactivitymanagerd" \
        "$PREFIX/libexec/kactivitymanagerd" \
        "$PREFIX/lib/kf6/kactivitymanagerd" \
        "$PREFIX/lib/qt6/libexec/kactivitymanagerd"; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
        if [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done

    local service exec_line
    for service in \
        "$PREFIX/share/dbus-1/services/org.kde.ActivityManager.service" \
        "$PREFIX/share/dbus-1/services/org.kde.kactivitymanagerd.service"; do
        if [ -f "$service" ]; then
            exec_line="$(sed -n 's/^Exec=//p' "$service" | head -n 1)"
            if [ -n "$exec_line" ]; then
                echo "$exec_line"
                return 0
            fi
        fi
    done

    return 1
}

echo "Checking dependencies..."

# 检查termux-render库
TERMUX_RENDER_LIB="$PREFIX/lib/libtermux-render.so"
if [ -f "$TERMUX_RENDER_LIB" ]; then
    echo "✓ Found termux-render: $TERMUX_RENDER_LIB"
else
    echo "✗ Error: termux-display-client library not found"
    exit 1
fi

MISSING_COMMANDS=()
if [ "$COMPOSITOR_ONLY" = "1" ]; then
    # Compositor-only needs just the compositor and a bus for KWin's own use;
    # the Plasma shell and its daemons live in the container, not natively.
    REQUIRED_COMMANDS=(dbus-launch kwin_wayland)
else
    REQUIRED_COMMANDS=(dbus-launch kwin_wayland plasmashell)
fi
for cmd in "${REQUIRED_COMMANDS[@]}"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        MISSING_COMMANDS+=("$cmd")
    fi
done
if [ "$COMPOSITOR_ONLY" = "1" ]; then
    KACTIVITYMANAGERD_CMD=""
else
    KACTIVITYMANAGERD_CMD="$(find_kactivitymanagerd || true)"
    if [ -z "$KACTIVITYMANAGERD_CMD" ]; then
        MISSING_COMMANDS+=("kactivitymanagerd")
    fi
fi

if [ ${#MISSING_COMMANDS[@]} -gt 0 ]; then
    echo "✗ Missing required commands: ${MISSING_COMMANDS[*]}"
    echo "Install dependencies with:"
    echo "  pkg install plasma-desktop plasma-workspace kactivitymanagerd dbus"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERING_ENV="${KWIN_ANDROID_RENDERING_ENV:-$PREFIX/bin/kwin-android-rendering-env}"
if [ ! -f "$RENDERING_ENV" ]; then
    RENDERING_ENV="$SCRIPT_DIR/android-rendering-env.sh"
fi
if [ ! -f "$RENDERING_ENV" ]; then
    echo "Error: KWin Android rendering environment helper not found"
    exit 1
fi
# shellcheck source=android-rendering-env.sh
source "$RENDERING_ENV"
kwin_android_select_rendering

cleanup_session()
{
    local status=$?
    local pid

    trap - EXIT INT TERM
    for pid in \
        "${XWAYLAND_SELF_TEST_PID:-}" \
        "${PLASMASHELL_PID:-}" \
        "${KACTIVITYMANAGERD_PID:-}" \
        "${KDED_PID:-}" \
        "${KDEINIT_PID:-}" \
        "${KWIN_PID:-}"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    for pid in \
        "${XWAYLAND_SELF_TEST_PID:-}" \
        "${PLASMASHELL_PID:-}" \
        "${KACTIVITYMANAGERD_PID:-}" \
        "${KDED_PID:-}" \
        "${KDEINIT_PID:-}" \
        "${KWIN_PID:-}"; do
        if [ -n "$pid" ]; then
            wait "$pid" 2>/dev/null || true
        fi
    done
    if [ -n "${KWIN_ANDROID_AUX_PID:-}" ]; then
        kill "$KWIN_ANDROID_AUX_PID" 2>/dev/null || true
        wait "$KWIN_ANDROID_AUX_PID" 2>/dev/null || true
    fi
    if [ "${STARTED_DBUS:-0}" = "1" ] && [ -n "${DBUS_SESSION_BUS_PID:-}" ]; then
        kill "$DBUS_SESSION_BUS_PID" 2>/dev/null || true
    fi
    release_session_lock
    exit "$status"
}
trap cleanup_session EXIT

# 性能调优
export QSG_RENDER_LOOP=basic
export QT_XCB_GL_INTEGRATION=none

echo ""
echo "Environment setup complete:"
echo "  Acceleration: $ACCELERATION_MODE"
echo "  Mesa driver: ${MESA_LOADER_DRIVER_OVERRIDE:-default}"
echo "  Vulkan ICD: ${VK_DRIVER_FILES:-${VK_ICD_FILENAMES:-Android system loader}}"
echo "  Output buffer import: $KWIN_ANDROID_AHB_IMPORT"
echo "  Client buffer import: $KWIN_ANDROID_CLIENT_BUFFER_IMPORT"
echo "  termux-render receive: $TERMUX_RENDER_AHB_RECEIVE"
echo "  Client linux-dmabuf: $KWIN_ANDROID_LINUX_DMABUF"
echo ""

# 启动D-Bus
# NOTE: the container Plasma session runs its OWN D-Bus. Sharing this native bus
# across the proot boundary does not work: proot cannot forge SO_PEERCRED, so the
# container (which believes it is uid 0) fails dbus's EXTERNAL auth against the
# daemon. So compositor-only uses the same dbus-launch as the normal session;
# it is only for KWin's own use.
if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
    echo "Starting D-Bus..."
    if command -v dbus-launch >/dev/null 2>&1; then
        eval "$(dbus-launch --sh-syntax)"
        STARTED_DBUS=1
    else
        echo "✗ Error: dbus-launch not found"
        exit 1
    fi
else
    echo "Using existing D-Bus session"
fi

if command -v dbus-update-activation-environment >/dev/null 2>&1; then
    dbus-update-activation-environment \
        DBUS_SESSION_BUS_ADDRESS \
        DESKTOP_SESSION \
        KDE_FULL_SESSION \
        KDE_SESSION_VERSION \
        KWIN_BACKEND \
        KWIN_ANDROID_GL_MODE \
        KWIN_ANDROID_LINUX_DMABUF \
        KWIN_WAYLAND_SOCKET \
        GALLIUM_DRIVER \
        MESA_LOADER_DRIVER_OVERRIDE \
        QT_QPA_PLATFORM \
        VK_DRIVER_FILES \
        VK_ICD_FILENAMES \
        WRAPPER_VULKAN_PATH \
        WAYLAND_DISPLAY \
        XDG_CONFIG_DIRS \
        XDG_CURRENT_DESKTOP \
        XDG_DATA_DIRS \
        XDG_MENU_PREFIX \
        XDG_RUNTIME_DIR \
        XDG_SESSION_TYPE || true
fi

# 启动KWin Wayland
echo "Starting KWin with requested renderer: $KWIN_ANDROID_GL_MODE"
kwin_wayland \
    --socket "$KWIN_WAYLAND_SOCKET" \
    --xwayland \
    --no-global-shortcuts &
KWIN_PID=$!
KWIN_READY=0
KWIN_STARTUP_TIMEOUT="${KWIN_ANDROID_STARTUP_TIMEOUT:-15}"
for ((second = 0; second < KWIN_STARTUP_TIMEOUT; ++second)); do
    if ! kill -0 "$KWIN_PID" 2>/dev/null; then
        echo "✗ Error: kwin_wayland exited during startup"
        echo "KWin startup failed; see $PLASMA_LOG" >&4
        wait "$KWIN_PID" || true
        exit 1
    fi
    if [ -S "$KWIN_SOCKET_PATH" ] && grep -q "Android EGL backend initialized" "$PLASMA_LOG"; then
        KWIN_READY=1
        break
    fi
    sleep 1
done
if [ "$KWIN_READY" != "1" ]; then
    echo "✗ Error: kwin_wayland did not initialize the Android EGL backend within ${KWIN_STARTUP_TIMEOUT}s"
    echo "KWin startup timed out; see $PLASMA_LOG" >&4
    exit 1
fi
export WAYLAND_DISPLAY="$KWIN_WAYLAND_SOCKET"
if [ "$COMPOSITOR_ONLY" != "1" ]; then
    kwin_android_configure_qt_clients
    QT_CLIENT_PROBE="$PREFIX/bin/kwin-qt-client-renderer-probe"
    if [ ! -x "$QT_CLIENT_PROBE" ]; then
        echo "Error: required native Qt client renderer probe is unavailable: $QT_CLIENT_PROBE" >&2
        exit 1
    fi
    if ! "$QT_CLIENT_PROBE"; then
        echo "Error: native Qt client renderer does not match requested $KWIN_ANDROID_GL_MODE mode" >&2
        exit 1
    fi
fi

# KWin creates the lazy XWayland listening socket before spawning Xwayland.
# Export its display so Plasma children and compatibility clients use the same
# server instead of starting an independent display server on :0.
if [ -n "${KWIN_XWAYLAND_DISPLAY:-}" ]; then
    export DISPLAY="$KWIN_XWAYLAND_DISPLAY"
else
    unset DISPLAY
    for socket_path in "$TMPDIR"/.X11-unix/X*; do
        [ -S "$socket_path" ] || continue
        display_number="${socket_path##*/X}"
        if [[ "$display_number" =~ ^[0-9]+$ ]]; then
            export DISPLAY=":$display_number"
            break
        fi
    done
fi

KWIN_ANDROID_SESSION_ENV="${KWIN_ANDROID_SESSION_ENV:-$KWIN_ANDROID_RUNTIME_ROOT/session.env}"
{
    printf 'export DISPLAY=%q\n' "${DISPLAY:-}"
    printf 'export WAYLAND_DISPLAY=%q\n' "$WAYLAND_DISPLAY"
    printf 'export XDG_RUNTIME_DIR=%q\n' "$XDG_RUNTIME_DIR"
    printf 'export TMPDIR=%q\n' "$TMPDIR"
    printf 'export DBUS_SESSION_BUS_ADDRESS=%q\n' "${DBUS_SESSION_BUS_ADDRESS:-}"
} > "$KWIN_ANDROID_SESSION_ENV"
chmod 0600 "$KWIN_ANDROID_SESSION_ENV" 2>/dev/null || true

if command -v dbus-update-activation-environment >/dev/null 2>&1; then
    CLIENT_DBUS_ENV=(DISPLAY TMPDIR WAYLAND_DISPLAY XDG_RUNTIME_DIR)
    if [ "$COMPOSITOR_ONLY" != "1" ]; then
        CLIENT_DBUS_ENV+=("${KWIN_ANDROID_QT_CLIENT_ENV_VARS[@]}")
    fi
    if ! dbus-update-activation-environment "${CLIENT_DBUS_ENV[@]}"; then
        echo "Error: failed to publish the explicit desktop renderer environment to D-Bus" >&2
        exit 1
    fi
fi

# 启动Plasma组件
if [ "$COMPOSITOR_ONLY" = "1" ]; then
    echo ""
    echo "✓ Compositor-only mode: the native Plasma shell was NOT started."
    echo "  Run the Plasma session inside the container, pointing it at this"
    echo "  runtime dir (it runs its own D-Bus; see packaging/container/README.md):"
    echo "    proot-distro login debian --bind $XDG_RUNTIME_DIR:/run/wl \\"
    echo "      -- /root/plasma-container-session.sh"
    echo "  Shared Wayland socket: $KWIN_WAYLAND_SOCKET  (container: /run/wl/$KWIN_WAYLAND_SOCKET)"
else
    echo "Starting Plasma components..."
    if command -v kdeinit6 >/dev/null 2>&1; then
        kdeinit6 &
        KDEINIT_PID=$!
    elif command -v kdeinit5 >/dev/null 2>&1; then
        kdeinit5 &
        KDEINIT_PID=$!
    else
        echo "⚠ kdeinit not found, skipping"
    fi
    sleep 1

    if command -v kded6 >/dev/null 2>&1; then
        kded6 &
        KDED_PID=$!
    elif command -v kded5 >/dev/null 2>&1; then
        kded5 &
        KDED_PID=$!
    else
        echo "⚠ kded not found, skipping"
    fi

    echo "Starting kactivitymanagerd: $KACTIVITYMANAGERD_CMD"
    if [ -x "$KACTIVITYMANAGERD_CMD" ]; then
        "$KACTIVITYMANAGERD_CMD" &
    else
        sh -c "$KACTIVITYMANAGERD_CMD" &
    fi
    KACTIVITYMANAGERD_PID=$!
    sleep 1
    if ! kill -0 "$KACTIVITYMANAGERD_PID" 2>/dev/null; then
        echo "✗ Error: kactivitymanagerd exited during startup"
        wait "$KACTIVITYMANAGERD_PID" || true
        exit 1
    fi

    if command -v kbuildsycoca6 >/dev/null 2>&1; then
        kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
    elif command -v kbuildsycoca5 >/dev/null 2>&1; then
        kbuildsycoca5 --noincremental >/dev/null 2>&1 || true
    fi

    if command -v plasmashell >/dev/null 2>&1; then
        plasmashell &
        PLASMASHELL_PID=$!
    else
        echo "✗ Error: plasmashell not found"
        exit 1
    fi
    sleep 1
    if ! kill -0 "$PLASMASHELL_PID" 2>/dev/null; then
        echo "✗ Error: plasmashell exited during startup"
        wait "$PLASMASHELL_PID" || true
        exit 1
    fi
fi

echo ""
echo "✓ Plasma Desktop started with requested KWin renderer"
echo "✓ Acceleration mode: $ACCELERATION_MODE"
echo "✓ Output buffer import: $KWIN_ANDROID_AHB_IMPORT"
echo ""
echo "Launch applications:"
echo "  konsole    # Terminal"
echo "  dolphin    # File manager"
echo "  kate       # Text editor"
echo ""
echo "Usage:"
echo "  $0                # Start Plasma (normal)"
echo "  $0 --debug-rendering # Enable per-frame Android EGL diagnostics"
echo "  $0 --install-deps # Install dependencies first"
echo "  $0 --test-xwayland # Test xdpyinfo, glxinfo and glxgears"
echo ""
echo "Press Ctrl+C to stop..."
echo "Plasma started successfully (KWin pid $KWIN_PID)." >&3

if [ "${KWIN_ANDROID_XWAYLAND_SELF_TEST:-0}" = "1" ]; then
    XWAYLAND_SELF_TEST_LOG="${KWIN_ANDROID_XWAYLAND_SELF_TEST_LOG:-$KWIN_ANDROID_RUNTIME_ROOT/xwayland-self-test.log}"
    (
        echo "DISPLAY=${DISPLAY:-unset}"
        echo "TMPDIR=$TMPDIR"
        if [ -z "${DISPLAY:-}" ]; then
            echo "FAIL: no XWayland display socket was discovered"
            exit 1
        fi
        if timeout 10 xdpyinfo >/dev/null 2>&1; then
            echo "PASS: xdpyinfo connected"
        else
            status=$?
            echo "FAIL: xdpyinfo could not connect (status $status)"
            exit 1
        fi

        GL4ES_LIBRARY_PATH="$PREFIX/lib/gl4es"
        if [ ! -f "$GL4ES_LIBRARY_PATH/libGL.so.1" ]; then
            echo "FAIL: gl4es AHardwareBuffer client bridge is not installed"
            exit 1
        fi
        TEST_LD_LIBRARY_PATH="$GL4ES_LIBRARY_PATH${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        GL4ES_FB_MODE="${KWIN_XWAYLAND_GL4ES_FB:-3}"
        echo "Testing GLX through $GL4ES_LIBRARY_PATH/libGL.so.1"
        if env LIBGL_FB="$GL4ES_FB_MODE" LD_LIBRARY_PATH="$TEST_LD_LIBRARY_PATH" timeout 15 glxinfo -B 2>&1; then
            echo "PASS: gl4es created a GLX context"
        else
            echo "FAIL: gl4es could not create a GLX context"
            exit 1
        fi
        echo "Starting glxgears for 8 seconds"
        status=0
        env LIBGL_FB="$GL4ES_FB_MODE" LD_LIBRARY_PATH="$TEST_LD_LIBRARY_PATH" timeout 8 glxgears -info 2>&1 || status=$?
        if [ "${status:-0}" = "124" ]; then
            echo "PASS: glxgears stayed alive until timeout"
        else
            echo "FAIL: glxgears exited with status ${status:-0}"
            exit 1
        fi
    ) > "$XWAYLAND_SELF_TEST_LOG" 2>&1 &
    XWAYLAND_SELF_TEST_PID=$!
    echo "XWayland self-test started; log: $XWAYLAND_SELF_TEST_LOG" >&3
fi

set +e
wait "$KWIN_PID"
KWIN_STATUS=$?
set -e
exit "$KWIN_STATUS"
