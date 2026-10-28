#!/bin/sh
# Plasma session launcher for the proot/glibc container, GPU-accelerated.
#
# Architecture: kwin_wayland runs NATIVELY (Termux/Bionic) as the only native
# process, started with `start-plasma --compositor-only`. The whole Plasma
# shell + apps run here, inside the Debian (glibc) container, and connect back
# to the native compositor over the shared Wayland socket that start-plasma
# exposes in its runtime dir (bind-mounted here as /run/wl). The container runs
# its own D-Bus (the native bus cannot be shared across proot -- see below).
#
# Run it, from Termux, as:
#   proot-distro login debian --bind \
#       "$HOME/.termux/kwin-runtime/runtime-$(id -u)":/run/wl \
#       -- /root/plasma-container-session.sh
#
# ---------------------------------------------------------------------------
# Why QSG_RHI_BACKEND=vulkan (and NOT the default OpenGL/zink)?
#
# The container has no DRM render node (Adreno is driven through KGSL:
# /dev/kgsl-3d0 is not a DRM device). Qt's default QtQuick RHI uses OpenGL,
# which on Wayland goes through Mesa's EGL-Wayland path. That path needs
# gbm_create_device() on a DRM node to allocate window buffers; on KGSL it
# fails, so Mesa silently falls back to the swrast / wl_shm present path. zink
# still renders on the GPU, but the present then copies the (tiled) GPU image
# into a linear wl_shm buffer on the CPU and the result is BLACK, with no error.
#
# Vulkan RHI avoids all of that: Qt renders with Turnip and presents through
# Turnip's Vulkan WSI (VkSwapchain on the wl_surface -> dma-buf, no gbm). KWin
# imports those dma-bufs fine -- the same path vkcube uses.
#
# Prerequisite in the container: the unversioned Vulkan loader symlink must
# exist so Qt's QLibrary("vulkan") can dlopen it:
#   apt-get install -y libvulkan-dev      # provides /usr/.../libvulkan.so
# (libvulkan1 alone only ships libvulkan.so.1, which Qt does not look for.)
# ---------------------------------------------------------------------------

set -u

# --- Wayland / XDG --------------------------------------------------------
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/wl}"
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
export GDK_BACKEND="${GDK_BACKEND:-wayland}"
export XDG_CURRENT_DESKTOP="${XDG_CURRENT_DESKTOP:-KDE}"
export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-wayland}"
export XDG_DATA_DIRS="${XDG_DATA_DIRS:-/usr/share:/usr/local/share}"
export XDG_MENU_PREFIX="${XDG_MENU_PREFIX:-plasma-}"
unset DISPLAY

# --- GPU: Vulkan RHI on Turnip (see header) -------------------------------
export QSG_RHI_BACKEND="${QSG_RHI_BACKEND:-vulkan}"
export VK_ICD_FILENAMES="${VK_ICD_FILENAMES:-/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json}"
# QtQuick must NOT be forced to the software backend here -- that would undo
# the whole point (GPU rendering).
unset QT_QUICK_BACKEND

# Optional: set PLASMA_CONTAINER_DEBUG=1 for Qt RHI/scenegraph diagnostics.
if [ "${PLASMA_CONTAINER_DEBUG:-0}" = "1" ]; then
    export QT_LOGGING_RULES="qt.rhi.general=true;qt.scenegraph.general=true"
fi

LOG="${PLASMA_CONTAINER_LOG:-$XDG_RUNTIME_DIR/plasma-container.log}"
exec >"$LOG" 2>&1
echo "=== Plasma container session (Vulkan RHI) $(date) ==="

# --- Ensure required container packages ------------------------------------
# apt-installable components are auto-installed if missing; a failed install
# aborts the session. The Turnip Vulkan ICD is NOT apt-installable (it ships in
# the mesa-for-android-container tarball), so it is only checked, not installed.
ensure_container_deps() {
    needed=""
    command -v plasmashell  >/dev/null 2>&1 || needed="$needed plasma-workspace"
    # Check the container's own binary: proot-distro appends the host's Termux bin to PATH, where a
    # bionic dbus-launch would satisfy `command -v` and start a host-side bus the container cannot use.
    [ -x /usr/bin/dbus-launch ] || needed="$needed dbus-x11"
    # Unversioned Vulkan loader symlink that Qt's QLibrary("vulkan") dlopens.
    if ! ls /usr/lib/*/libvulkan.so >/dev/null 2>&1 && [ ! -e /usr/lib/libvulkan.so ]; then
        needed="$needed libvulkan-dev"
    fi
    # Qt Wayland platform plugin (QT_QPA_PLATFORM=wayland).
    if ! ls /usr/lib/*/qt6/plugins/platforms/libqwayland*.so >/dev/null 2>&1; then
        needed="$needed qt6-wayland"
    fi

    if [ -n "$needed" ]; then
        echo "Installing missing container packages:$needed"
        export DEBIAN_FRONTEND=noninteractive
        if ! apt-get update; then
            echo "ERROR: 'apt-get update' failed; cannot install:$needed" >&2
            exit 1
        fi
        if ! apt-get install -y $needed; then
            echo "ERROR: failed to install:$needed" >&2
            exit 1
        fi
    fi

    # Turnip Vulkan ICD (the GPU driver) -- from the mesa-for-android-container
    # tarball, not apt. Without it there is no hardware Vulkan device.
    if [ ! -r "$VK_ICD_FILENAMES" ]; then
        echo "ERROR: Turnip Vulkan ICD not found: $VK_ICD_FILENAMES" >&2
        echo "  Install the mesa-for-android-container tarball" >&2
        echo "  (provides libvulkan_freedreno.so + the ICD json)." >&2
        exit 1
    fi
}
ensure_container_deps

# --- D-Bus: the container runs its OWN session bus ------------------------
# Joining the native compositor's bus over a bind-mounted socket does NOT work:
# proot cannot forge SO_PEERCRED, so dbus's EXTERNAL auth against the native
# daemon fails ("Did not receive a reply"). Window management still works over
# Wayland (plasma-window-management); only org.kde.KWin D-Bus scripting is
# unavailable across the native/container split.
if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
    eval "$(/usr/bin/dbus-launch --sh-syntax)"
    echo "Started container D-Bus: ${DBUS_SESSION_BUS_ADDRESS:-none}"
fi

if command -v dbus-update-activation-environment >/dev/null 2>&1; then
    dbus-update-activation-environment --systemd \
        WAYLAND_DISPLAY XDG_RUNTIME_DIR XDG_CURRENT_DESKTOP XDG_SESSION_TYPE \
        QT_QPA_PLATFORM QSG_RHI_BACKEND VK_ICD_FILENAMES >/dev/null 2>&1 || true
fi

# --- KDE services, then the shell -----------------------------------------
LIBEXEC="${KDE_LIBEXEC:-/usr/lib/aarch64-linux-gnu/libexec}"
start_bg() {
    if [ -x "$1" ]; then "$@" &
    elif command -v "$1" >/dev/null 2>&1; then "$@" &
    else echo "skip (not found): $1"; fi
}

start_bg "$LIBEXEC/kactivitymanagerd"
start_bg kded6
start_bg "$LIBEXEC/kglobalacceld"
sleep 2
command -v kbuildsycoca6 >/dev/null 2>&1 && kbuildsycoca6 --noincremental >/dev/null 2>&1 || true

echo "=== launching plasmashell (QSG_RHI_BACKEND=$QSG_RHI_BACKEND) ==="
exec plasmashell
