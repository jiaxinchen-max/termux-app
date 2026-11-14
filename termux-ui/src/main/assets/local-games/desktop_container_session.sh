#!/bin/sh
# Plasma clients run in the container; the native compositor has its own independent mode.

set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# proot-distro's non-login command environment omits SHELL. Konsole otherwise
# receives an empty command and selects its own fallback shell.
export SHELL=/bin/bash

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
export PULSE_SERVER="${PULSE_SERVER:?Explicit host PulseAudio endpoint is required}"
export XDG_DATA_DIRS="${XDG_DATA_DIRS:-/usr/share:/usr/local/share}"
export XDG_MENU_PREFIX="${XDG_MENU_PREFIX:-plasma-}"
unset DISPLAY

# Clear inherited renderer settings before applying the requested mode exactly.
unset QSG_RHI_BACKEND QT_QUICK_BACKEND VK_ICD_FILENAMES VK_DRIVER_FILES
unset LIBGL_ALWAYS_SOFTWARE MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER
# Old entry points may still pass their historical container-only alias.
[ -n "${DESKTOP_GUEST_GL_MODE:-}" ] && [ -z "${DESKTOP_GL_MODE:-}" ] || exit 64
case "$DESKTOP_GUEST_GL_MODE" in
    zink)
        # Use OpenGL over the selected Zink backend, rather than a Vulkan RHI bypassing Zink.
        case "${DESKTOP_PLASMA_GUEST_ICD:-}" in /usr/share/vulkan/icd.d/*) ;; *) echo 'ERROR: explicit initialized guest ICD required' >&2; exit 66 ;; esac
        case "${DESKTOP_PLASMA_GUEST_SOFTWARE:-}" in 0|1) ;; *) exit 66 ;; esac
        export QSG_RHI_BACKEND=opengl GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink
        export VK_ICD_FILENAMES="$DESKTOP_PLASMA_GUEST_ICD" VK_DRIVER_FILES="$DESKTOP_PLASMA_GUEST_ICD"
        export LIBGL_ALWAYS_SOFTWARE="$DESKTOP_PLASMA_GUEST_SOFTWARE"
        ;;
    llvmpipe)
        export QT_QUICK_BACKEND=software
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
        ;;
    *) echo "ERROR: container Plasma requires DESKTOP_GUEST_GL_MODE=zink or llvmpipe; got '$DESKTOP_GUEST_GL_MODE'." >&2
       exit 64 ;;
esac

# Optional: set PLASMA_CONTAINER_DEBUG=1 for Qt RHI/scenegraph diagnostics.
if [ "${PLASMA_CONTAINER_DEBUG:-0}" = "1" ]; then
    export QT_LOGGING_RULES="qt.rhi.general=true;qt.scenegraph.general=true"
fi

LOG="${PLASMA_CONTAINER_LOG:-$XDG_RUNTIME_DIR/plasma-container.log}"
READY_FILE="$XDG_RUNTIME_DIR/plasma-container.ready"
SESSION_TOKEN="${DESKTOP_PLASMA_SESSION_TOKEN:?Explicit session token is required}"
case "$SESSION_TOKEN" in ''|*[!a-zA-Z0-9-]*) exit 64 ;; esac
exec >"$LOG" 2>&1
echo "=== Plasma container session (renderer=$DESKTOP_GUEST_GL_MODE, fallback=disabled) $(date) ==="
rm -f "$READY_FILE"
monotonic_cs() {
    read -r uptime_seconds uptime_unused < /proc/uptime || return 1
    uptime_fraction="${uptime_seconds#*.}00"
    uptime_fraction="${uptime_fraction%${uptime_fraction#??}}"
    NOW_CS=$(( ${uptime_seconds%.*} * 100 + 1$uptime_fraction - 100 ))
}
monotonic_cs || exit 70
SESSION_STARTED_CS="${DESKTOP_PLASMA_STARTED_CS:-$NOW_CS}"
case "$SESSION_STARTED_CS" in ''|*[!0-9]*) exit 64 ;; esac
stage() {
    monotonic_cs || return 70
    echo "STARTUP stage=$1 elapsed_ms=$(( (NOW_CS - SESSION_STARTED_CS) * 10 ))"
}

# Dependencies and polkit identity are prepared by desktop_install.sh.

# Each desktop owns both buses. Never inherit a host bus across proot credentials.
SESSION_PIDS=""
cleanup() {
    trap - EXIT TERM INT
    rm -f "$READY_FILE" "$READY_FILE.$$"
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
stage buses-ready

# kill -0 also succeeds for zombies; distinguish an exited child before waiting.
require_service_alive() {
    service_state=''
    if kill -0 "$1" 2>/dev/null; then
        if ! read -r service_stat < "/proc/$1/stat" 2>/dev/null; then
            echo "ERROR: cannot read process state for $2"
            return 70
        fi
        service_state="${service_stat##*) }"
        service_state="${service_state%% *}"
        case "$service_state" in Z|X) ;; '') return 70 ;; *) return 0 ;; esac
    fi
    wait "$1"; service_code=$?
    [ "$service_code" -ne 0 ] || service_code=70
    echo "ERROR: required service $2 exited (status $service_code)"
    return "$service_code"
}

# One monotonic deadline includes every request. Resolve the unique name without
# activation, then Ping that owner so a race cannot auto-start a duplicate daemon.
bus_call() {
    monotonic_cs || return 70
    remaining_cs=$((service_deadline - NOW_CS))
    [ "$remaining_cs" -gt 0 ] || return 124
    [ "$remaining_cs" -le 100 ] || remaining_cs=100
    call_seconds="$((remaining_cs / 100)).$(printf '%02d' "$((remaining_cs % 100))")"
    timeout -k 0.1 "$call_seconds" /usr/bin/dbus-send "$service_bus" --print-reply \
        --reply-timeout="$((remaining_cs * 10))" "$@"
}
wait_for_service() { # pid --system|--session name object-path
    service_pid="$1"; service_bus="$2"; service_name="$3"; service_path="$4"
    monotonic_cs || return 70
    service_deadline=$((NOW_CS + 1000))
    while :; do
        require_service_alive "$service_pid" "$service_name" || return "$?"
        owner_reply=$(bus_call --dest=org.freedesktop.DBus /org/freedesktop/DBus \
            org.freedesktop.DBus.GetNameOwner "string:$service_name" 2>/dev/null) || owner_reply=''
        case "$owner_reply" in
            *'string "'*)
                service_owner="${owner_reply#*string \"}"; service_owner="${service_owner%%\"*}"
                if bus_call --dest="$service_owner" "$service_path" org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                    require_service_alive "$service_pid" "$service_name" || return "$?"
                    stage "$service_name-ready"
                    return 0
                fi ;;
        esac
        monotonic_cs || return 70
        if [ "$NOW_CS" -ge "$service_deadline" ]; then
            echo "ERROR: $service_name did not respond before startup deadline (status 124)"
            return 124
        fi
        sleep 0.1
    done
}

# Keep PolicyKit's packaged authentication and rules. Explicit startup is required because this
# private system bus has no privileged activation helper. Do not pass --no-debug: it hides stderr.
[ -x /usr/lib/polkit-1/polkitd ] || {
    echo "ERROR: PolicyKit daemon is missing after dependency installation"; exit 1;
}
/usr/lib/polkit-1/polkitd --log-level=debug &
polkit_pid=$!
SESSION_PIDS="$SESSION_PIDS $polkit_pid"
wait_for_service "$polkit_pid" --system org.freedesktop.PolicyKit1 /org/freedesktop/PolicyKit1/Authority || exit "$?"

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
    wait_for_service "$packagekit_pid" --system org.freedesktop.PackageKit /org/freedesktop/PackageKit || exit "$?"
fi

if command -v dbus-update-activation-environment >/dev/null 2>&1; then
    timeout -k 1 5 dbus-update-activation-environment --all || {
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
    require_service_alive "$1" "$2"
}
start_required_kde_service() { # bus-name object-path executable [args...]
    kde_service_label="$1"
    kde_service_path="$2"
    shift 2
    if [ ! -x "$1" ] && ! command -v "$1" >/dev/null 2>&1; then
        echo "ERROR: required KDE service $kde_service_label is missing: $1"
        return 127
    fi
    "$@" &
    kde_service_pid=$!
    SESSION_PIDS="$SESSION_PIDS $kde_service_pid"
    KDE_SERVICE_PIDS="$KDE_SERVICE_PIDS $kde_service_pid:$kde_service_label"
    wait_for_service "$kde_service_pid" --session "$kde_service_label" "$kde_service_path"
}

start_required_kde_service org.kde.ActivityManager /ActivityManager "$LIBEXEC/kactivitymanagerd" || exit "$?"
start_required_kde_service org.kde.kded6 /kded kded6 || exit "$?"
# Debian kglobalacceld main.cpp returns 0 immediately on Wayland: it belongs to KWin.
# The native compositor has --no-global-shortcuts and a different private bus from these clients.
# This policy deliberately does not launch the X11 daemon or claim cross-bus shortcut support.
echo 'KDE service policy=kwin-wayland: standalone kglobalacceld is not launched (upstream Wayland no-op); global shortcuts are unavailable with the native compositor --no-global-shortcuts and separate private session buses.'
# KDE cache is prepared during explicit installation/initialization.
for kde_service in $KDE_SERVICE_PIDS; do
    require_kde_service_alive "${kde_service%%:*}" "${kde_service#*:}" || exit "$?"
done

echo "=== launching plasmashell (renderer=$DESKTOP_GUEST_GL_MODE, QSG_RHI_BACKEND=${QSG_RHI_BACKEND:-unset}, QT_QUICK_BACKEND=${QT_QUICK_BACKEND:-unset}) ==="
plasmashell &
shell_pid=$!
SESSION_PIDS="$SESSION_PIDS $shell_pid"
wait_for_service "$shell_pid" --session org.kde.plasmashell /PlasmaShell || exit "$?"
require_session_services_alive() {
    for required_pid in $SESSION_PIDS; do
        require_service_alive "$required_pid" "session-pid-$required_pid" || return "$?"
    done
}
require_session_services_alive || exit "$?"
printf '%s\n' "$SESSION_TOKEN" > "$READY_FILE.$$" && mv "$READY_FILE.$$" "$READY_FILE" || exit 74
stage shell-responsive
while :; do
    require_session_services_alive || exit "$?"
    sleep 1
done
