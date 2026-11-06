#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/rootfs-maintenance.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT
cat > "$TEST_ROOT/proot" <<'EOF'
#!/bin/sh
while [ "$#" -gt 0 ] && [ "$1" != /usr/bin/env ]; do shift; done
[ "$#" -gt 0 ] || exit 64
exec "$@"
EOF
chmod 700 "$TEST_ROOT/proot"
PROOT_BIN="$TEST_ROOT/proot"
ROOTFS_CANONICAL="$TEST_ROOT/rootfs"
TERMUX_FILES_DIR="$TEST_ROOT/files"
HOME_PATH="$TEST_ROOT/home"
PREFIX_PATH="$TEST_ROOT/prefix"
MAINTENANCE_DISPLAY=:9
export GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink LIBGL_ALWAYS_SOFTWARE=0
export LIBGL_DRIVERS_PATH=/stale/host __EGL_VENDOR_LIBRARY_FILENAMES=/stale/vendor
export GALLIUM_HUD=fps PULSE_SERVER=tcp:host:4713
. "$PROJECT_ROOT/termux-ui/src/main/assets/local-games/rootfs_prefix_warmup.sh"
(run_rootfs_maintenance_command /bin/sh -c '
    test "$GALLIUM_DRIVER" = llvmpipe
    test "$MESA_LOADER_DRIVER_OVERRIDE" = swrast
    test "$LIBGL_ALWAYS_SOFTWARE" = 1
    test "$DISPLAY" = :9
    test "$WINEPREFIX" = /mnt/games/prefix
    test "${LIBGL_DRIVERS_PATH+x}" != x
    test "${__EGL_VENDOR_LIBRARY_FILENAMES+x}" != x
    test "${GALLIUM_HUD+x}" != x
    test "${PULSE_SERVER+x}" != x
')
test "$GALLIUM_DRIVER" = zink
test "$MESA_LOADER_DRIVER_OVERRIDE" = zink
test "$LIBGL_ALWAYS_SOFTWARE" = 0
test "$PULSE_SERVER" = tcp:host:4713
printf '%s\n' 'RootFS maintenance renderer isolation passed.'
