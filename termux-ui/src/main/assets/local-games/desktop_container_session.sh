#!/bin/sh
# Plasma clients run in the container; the native compositor uses the same explicit mode.

set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# This launcher supports one explicit session policy. Choose services before launching them.
case "${DESKTOP_KDE_SERVICE_POLICY:-}:${QT_QPA_PLATFORM:-}:${XDG_SESSION_TYPE:-}" in
    kwin-wayland:wayland:wayland) ;;
    *) echo 'ERROR: explicitly select DESKTOP_KDE_SERVICE_POLICY=kwin-wayland, QT_QPA_PLATFORM=wayland and XDG_SESSION_TYPE=wayland' >&2
       exit 64 ;;
esac

# --- Wayland / XDG --------------------------------------------------------
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/wl}"
export QT_QPA_PLATFORM
export GDK_BACKEND="${GDK_BACKEND:-wayland}"
export XDG_CURRENT_DESKTOP="${XDG_CURRENT_DESKTOP:-KDE}"
export XDG_SESSION_TYPE
export XDG_DATA_DIRS="${XDG_DATA_DIRS:-/usr/share:/usr/local/share}"
export XDG_MENU_PREFIX="${XDG_MENU_PREFIX:-plasma-}"
unset DISPLAY

# Clear inherited renderer settings before applying the requested mode exactly.
unset QSG_RHI_BACKEND QT_QUICK_BACKEND VK_ICD_FILENAMES VK_DRIVER_FILES
unset LIBGL_ALWAYS_SOFTWARE MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER
case "${DESKTOP_GL_MODE:-}" in
    zink)
        export QSG_RHI_BACKEND=vulkan
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        export VK_DRIVER_FILES="$VK_ICD_FILENAMES"
        ;;
    llvmpipe)
        export QT_QUICK_BACKEND=software
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
        ;;
    *) echo "ERROR: container Plasma requires DESKTOP_GL_MODE=zink or llvmpipe; got '${DESKTOP_GL_MODE:-}'." >&2
       exit 64 ;;
esac

# Optional: set PLASMA_CONTAINER_DEBUG=1 for Qt RHI/scenegraph diagnostics.
if [ "${PLASMA_CONTAINER_DEBUG:-0}" = "1" ]; then
    export QT_LOGGING_RULES="qt.rhi.general=true;qt.scenegraph.general=true"
fi

LOG="${PLASMA_CONTAINER_LOG:-$XDG_RUNTIME_DIR/plasma-container.log}"
exec >"$LOG" 2>&1
echo "=== Plasma container session (renderer=$DESKTOP_GL_MODE, fallback=disabled) $(date) ==="

# --- Ensure required container packages ------------------------------------
# apt-installable components are auto-installed if missing; a failed install
# aborts the session. The Turnip Vulkan ICD is NOT apt-installable (it ships in
# the mesa-for-android-container tarball), so it is only checked, not installed.
ensure_container_deps() {
    configure_proot_statx "${DESKTOP_STATX_BACKEND:-}" "$(dirname "$0")/desktop_container_statx_aarch64.bin" "$(dirname "$0")/desktop_container_run_config.sh" || exit "$?"
    needed=""
    command -v plasmashell  >/dev/null 2>&1 || needed="$needed plasma-workspace"
    # Do not mistake a host Termux executable in PATH for a container daemon.
    [ -x /usr/bin/dbus-daemon ] || needed="$needed dbus"
    [ -x /usr/bin/dbus-send ] || needed="$needed dbus-bin"
    [ -x /usr/lib/polkit-1/polkitd ] || needed="$needed polkitd"
    if [ ! -x /usr/libexec/packagekitd ] && [ ! -x /usr/lib/packagekit/packagekitd ]; then
        needed="$needed packagekit"
    fi
    # The Vulkan loader is required only by the explicitly selected GPU mode.
    if [ "$DESKTOP_GL_MODE" = "zink" ] && ! ls /usr/lib/*/libvulkan.so >/dev/null 2>&1 && [ ! -e /usr/lib/libvulkan.so ]; then
        needed="$needed libvulkan-dev"
    fi
    # Qt Wayland platform plugin (QT_QPA_PLATFORM=wayland).
    if ! ls /usr/lib/*/qt6/plugins/platforms/libqwayland*.so >/dev/null 2>&1; then
        needed="$needed qt6-wayland"
    fi

    if [ -n "$needed" ]; then
        echo "Installing missing container packages:$needed"
        export DEBIAN_FRONTEND=noninteractive
        run_proot_config proc-fdinfo apt-get update
        package_code=$?
        if [ "$package_code" -ne 0 ]; then
            echo "ERROR: 'apt-get update' failed (status $package_code); cannot install:$needed" >&2
            exit "$package_code"
        fi
        run_proot_config proc-fdinfo apt-get install -y --no-remove $needed
        package_code=$?
        if [ "$package_code" -ne 0 ]; then
            echo "ERROR: failed to install:$needed (status $package_code)" >&2
            exit "$package_code"
        fi
    fi

    restore_legacy_postinsts || exit "$?"
    run_proot_config proc-fdinfo /usr/bin/systemd-sysusers polkit.conf || exit "$?"
    validate_polkit_identity || exit "$?"

    # Turnip Vulkan ICD (the GPU driver) -- from the mesa-for-android-container
    # tarball, not apt. Without it there is no hardware Vulkan device.
    if [ "$DESKTOP_GL_MODE" = "zink" ] && [ ! -r "$VK_ICD_FILENAMES" ]; then
        echo "ERROR: Turnip Vulkan ICD not found: $VK_ICD_FILENAMES" >&2
        echo "  Install the mesa-for-android-container tarball" >&2
        echo "  (provides libvulkan_freedreno.so + the ICD json)." >&2
        exit 1
    fi
}
STATX_SETUP="$(dirname "$0")/desktop_container_statx_setup.sh"
[ -r "$STATX_SETUP" ] || { echo "ERROR: proot statx setup asset is missing"; exit 66; }
. "$STATX_SETUP"
ensure_container_deps

# Each desktop owns both buses. Never inherit a host bus across proot credentials.
SESSION_PIDS=""
cleanup() {
    trap - EXIT TERM INT
    for pid in $SESSION_PIDS; do kill -TERM "$pid" 2>/dev/null || true; done
    sleep 1
    for pid in $SESSION_PIDS; do kill -KILL "$pid" 2>/dev/null || true; done
    for pid in $SESSION_PIDS; do wait "$pid" 2>/dev/null || true; done
    rm -rf "$BUS_DIR"
}
BUS_DIR="$XDG_RUNTIME_DIR/container-dbus"
mkdir -p "$BUS_DIR" || exit 1
chmod 700 "$BUS_DIR" || exit 1
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
unset DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID DBUS_SYSTEM_BUS_ADDRESS

# A private system bus permits service activation without systemd. All container processes have
# the same Android UID under proot; the containing directory restricts access to that UID.
cat > "$BUS_DIR/system.conf" <<CONF
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>system</type>
  <listen>unix:path=$BUS_DIR/system_bus_socket</listen>
  <auth>EXTERNAL</auth>
  <standard_system_servicedirs/>
  <policy context="default">
    <!-- Standard system-bus connection permission, including the polkitd service UID. -->
    <allow user="*"/>
    <allow send_destination="*"/>
    <allow receive_sender="*"/>
    <allow own="*"/>
  </policy>
</busconfig>
CONF
/usr/bin/dbus-daemon --config-file="$BUS_DIR/system.conf" --fork --nopidfile \
    --print-address=1 --print-pid=3 > "$BUS_DIR/system.address" 3> "$BUS_DIR/system.pid" || {
    echo "ERROR: container system D-Bus failed to start"; exit 1;
}
SESSION_PIDS="$(cat "$BUS_DIR/system.pid")"
export DBUS_SYSTEM_BUS_ADDRESS="$(cat "$BUS_DIR/system.address")"
# Keep the physical socket in the short runtime bind. A default /tmp socket exceeds
# sun_path after rootfs translation, so PRoot moves it into Termux's exit-cleaned TMPDIR.
/usr/bin/dbus-daemon --session --address="unix:path=$BUS_DIR/session.sock" \
    --fork --nopidfile --print-address=1 --print-pid=3 \
    > "$BUS_DIR/session.address" 3> "$BUS_DIR/session.pid" || {
    echo "ERROR: container session D-Bus failed to start"; exit 1;
}
SESSION_PIDS="$SESSION_PIDS $(cat "$BUS_DIR/session.pid")"
export DBUS_SESSION_BUS_ADDRESS="$(cat "$BUS_DIR/session.address")"
/usr/bin/dbus-send --bus="$DBUS_SESSION_BUS_ADDRESS" --print-reply --reply-timeout=3000 \
    --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.Peer.Ping || {
    bus_code=$?
    echo "ERROR: container session D-Bus is unavailable at $DBUS_SESSION_BUS_ADDRESS (status $bus_code)"
    exit "$bus_code"
}
echo "Started private container system and session D-Bus"

# Wait for the explicitly started process, without asking D-Bus to activate another instance.
wait_for_system_service() { # pid bus-name label
    service_pid="$1"
    service_name="$2"
    service_label="$3"
    waited=0
    while ! /usr/bin/dbus-send --system --print-reply --reply-timeout=1000 \
        --dest=org.freedesktop.DBus /org/freedesktop/DBus \
        org.freedesktop.DBus.NameHasOwner "string:$service_name" | grep -q 'boolean true'; do
        waited=$((waited + 1))
        if ! kill -0 "$service_pid" 2>/dev/null; then
            wait "$service_pid"
            service_code=$?
            [ "$service_code" -ne 0 ] || service_code=70
            echo "ERROR: $service_label exited before acquiring $service_name (status $service_code)"
            return "$service_code"
        fi
        if [ "$waited" -ge 10 ]; then
            echo "ERROR: $service_label did not acquire $service_name (status 124)"
            return 124
        fi
        sleep 1
    done
    kill -0 "$service_pid" 2>/dev/null || return 70
}

# Keep PolicyKit's packaged authentication and rules. Explicit startup is required because this
# private system bus has no privileged activation helper. Do not pass --no-debug: it hides stderr.
[ -x /usr/lib/polkit-1/polkitd ] || {
    echo "ERROR: PolicyKit daemon is missing after dependency installation"; exit 1;
}
/usr/lib/polkit-1/polkitd --log-level=debug &
polkit_pid=$!
SESSION_PIDS="$SESSION_PIDS $polkit_pid"
wait_for_system_service "$polkit_pid" org.freedesktop.PolicyKit1 PolicyKit || exit "$?"

# Start PackageKit only after its authorization authority is ready.
if [ -x /usr/libexec/packagekitd ]; then
    PACKAGEKIT_DAEMON=/usr/libexec/packagekitd
elif [ -x /usr/lib/packagekit/packagekitd ]; then
    PACKAGEKIT_DAEMON=/usr/lib/packagekit/packagekitd
else
    echo "ERROR: PackageKit daemon is missing after dependency installation"; exit 1
fi
if [ -n "$PACKAGEKIT_DAEMON" ]; then
    # Keep the daemon alive for the session: this private bus has no privileged activation helper.
    "$PACKAGEKIT_DAEMON" --keep-environment --disable-timer &
    packagekit_pid=$!
    SESSION_PIDS="$SESSION_PIDS $packagekit_pid"
    wait_for_system_service "$packagekit_pid" org.freedesktop.PackageKit PackageKit || exit "$?"
    /usr/bin/dbus-send --system --print-reply --reply-timeout=10000 \
        --dest=org.freedesktop.PackageKit /org/freedesktop/PackageKit \
        org.freedesktop.DBus.Peer.Ping || {
        echo "ERROR: PackageKit is unavailable on the container system bus"; exit 1;
    }
fi

if command -v dbus-update-activation-environment >/dev/null 2>&1; then
    dbus-update-activation-environment --all || {
        echo "ERROR: could not update container D-Bus activation environment"; exit 1;
    }
fi

# Remove only the software override written by older app versions. Every application now inherits
# the requested renderer; Discover has no independent renderer override.
legacy_launcher="$HOME/.local/share/applications/org.kde.discover.desktop"
if [ -r "$legacy_launcher" ] && grep -q '^Exec=env QT_QUICK_BACKEND=software ' "$legacy_launcher"; then
    rm -f "$legacy_launcher" || exit 1
fi

# --- KDE services, then the shell -----------------------------------------
LIBEXEC="${KDE_LIBEXEC:-/usr/lib/aarch64-linux-gnu/libexec}"
# These installed Plasma components are required; missing or dead services abort the session.
KDE_SERVICE_PIDS=""
require_kde_service_alive() { # pid label
    if ! kill -0 "$1" 2>/dev/null; then
        wait "$1"
        kde_service_code=$?
        [ "$kde_service_code" -ne 0 ] || kde_service_code=70
        echo "ERROR: required KDE service $2 exited during startup (status $kde_service_code)"
        return "$kde_service_code"
    fi
}
start_required_kde_service() { # label executable [args...]
    kde_service_label="$1"
    shift
    if [ ! -x "$1" ] && ! command -v "$1" >/dev/null 2>&1; then
        echo "ERROR: required KDE service $kde_service_label is missing: $1"
        return 127
    fi
    "$@" &
    kde_service_pid=$!
    SESSION_PIDS="$SESSION_PIDS $kde_service_pid"
    KDE_SERVICE_PIDS="$KDE_SERVICE_PIDS $kde_service_pid:$kde_service_label"
    sleep 1
    require_kde_service_alive "$kde_service_pid" "$kde_service_label" || return "$?"
}

start_required_kde_service kactivitymanagerd "$LIBEXEC/kactivitymanagerd" || exit "$?"
start_required_kde_service kded6 kded6 || exit "$?"
# Debian kglobalacceld main.cpp returns 0 immediately on Wayland: it belongs to KWin.
# The native compositor has --no-global-shortcuts and a different private bus from these clients.
# This policy deliberately does not launch the X11 daemon or claim cross-bus shortcut support.
echo 'KDE service policy=kwin-wayland: standalone kglobalacceld is not launched (upstream Wayland no-op); global shortcuts are unavailable with the native compositor --no-global-shortcuts and separate private session buses.'
command -v kbuildsycoca6 >/dev/null 2>&1 || { echo 'ERROR: required kbuildsycoca6 is missing'; exit 127; }
kbuildsycoca6 --noincremental
cache_code=$?
[ "$cache_code" -eq 0 ] || { echo "ERROR: KDE service cache build failed (status $cache_code)"; exit "$cache_code"; }
for kde_service in $KDE_SERVICE_PIDS; do
    require_kde_service_alive "${kde_service%%:*}" "${kde_service#*:}" || exit "$?"
done

echo "=== launching plasmashell (renderer=$DESKTOP_GL_MODE, QSG_RHI_BACKEND=${QSG_RHI_BACKEND:-unset}, QT_QUICK_BACKEND=${QT_QUICK_BACKEND:-unset}) ==="
plasmashell &
shell_pid=$!
SESSION_PIDS="$SESSION_PIDS $shell_pid"
wait "$shell_pid"
exit "$?"
