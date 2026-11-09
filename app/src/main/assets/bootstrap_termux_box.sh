#!/bin/sh
# Restore a version-bound Native prefix. Never initialize or migrate it with Wine.
set -eu
PS4='+ '
set -x
CONTAINER_CONF="${1:?Usage: $0 <container_conf_path>}"
[ -f "$CONTAINER_CONF" ] || exit 64
. "$CONTAINER_CONF"
TERMUX_FILES_DIR=/data/data/com.termux/files
TERMUX_PREFIX="$TERMUX_FILES_DIR/usr"
TERMUX_GLIBC_DIR="$TERMUX_PREFIX/glibc"
TERMUX_OPT_DIR="$TERMUX_GLIBC_DIR/opt"
TERMUX_BOX_ROOT="$TERMUX_GLIBC_DIR/termux-box"
TERMUX_BOX_RUN_DIR="$TERMUX_BOX_ROOT/run"
GLIBC_BIN="$TERMUX_GLIBC_DIR/bin"
PREFIX="$TERMUX_PREFIX"
export PREFIX
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/native_runtime_components.sh"
. "$SCRIPT_DIR/glibc_prefix_extras.sh"
export WINEPREFIX="${TERMUX_BOX_CONTAINER_PREFIX:?native_prefix_path_required}"
export WINE_PATH="$TERMUX_GLIBC_DIR/${TERMUX_BOX_WINE_PACKAGE:?wine_package_required}"
resolve_native_box64 || exit 1
require_native_wine_runtime || exit 1
ensure_games_cjk_locale || exit 1
BOOTSTRAP_LOCK="${WINEPREFIX}.bootstrap.lock"
RESTORE_PID=
PREPARATION_MARKER_TMP=
cleanup_bootstrap() {
    [ -z "$PREPARATION_MARKER_TMP" ] || rm -f "$PREPARATION_MARKER_TMP"
    if [ -n "$RESTORE_PID" ]; then
        kill "$RESTORE_PID" 2>/dev/null || true
        wait "$RESTORE_PID" 2>/dev/null || true
    fi
    if [ -f "$BOOTSTRAP_LOCK/pid" ] &&
       [ "$(cat "$BOOTSTRAP_LOCK/pid")" = "$$" ]; then
        rm -f "$BOOTSTRAP_LOCK/pid"
        rmdir "$BOOTSTRAP_LOCK" 2>/dev/null || true
    fi
}
trap cleanup_bootstrap EXIT
trap 'exit 130' HUP INT TERM
mkdir -p "$(dirname -- "$WINEPREFIX")" "$TERMUX_BOX_RUN_DIR"
if [ -f "$BOOTSTRAP_LOCK/pid" ]; then
    LOCK_PID=$(cat "$BOOTSTRAP_LOCK/pid")
    case "$LOCK_PID" in ''|*[!0-9]*) exit 75 ;; esac
    if ! kill -0 "$LOCK_PID" 2>/dev/null; then
        rm -f "$BOOTSTRAP_LOCK/pid"
        rmdir "$BOOTSTRAP_LOCK" 2>/dev/null || true
    fi
fi
mkdir "$BOOTSTRAP_LOCK" 2>/dev/null || { echo 'ERROR: native_prefix_prepare_busy'; exit 75; }
printf '%s\n' "$$" > "$BOOTSTRAP_LOCK/pid"
# Do not write a prefix while a Native desktop session owns the shared runtime.
if [ -f "$TERMUX_BOX_RUN_DIR/glibc-wine.lock/pid" ]; then
    SESSION_PID=$(cat "$TERMUX_BOX_RUN_DIR/glibc-wine.lock/pid")
    case "$SESSION_PID" in ''|*[!0-9]*) echo 'ERROR: native_session_lock_invalid'; exit 75 ;; esac
    if kill -0 "$SESSION_PID" 2>/dev/null; then
        echo 'ERROR: native_session_active'; exit 75
    fi
fi
: "${NATIVE_PREFIX_IDENTITY:?native_prefix_identity_required}"
: "${NATIVE_PREFIX_BUNDLE_SHA256:?native_prefix_bundle_required}"
: "${NATIVE_PREFIX_ASSETS:?native_prefix_resource_required}"
: "${NATIVE_PREFIX_PREPARATION_IDENTITY:?native_prefix_preparation_identity_required}"
if [ -f "$WINEPREFIX/.games-runtime/prepared-identity" ]; then
    [ "$(cat "$WINEPREFIX/.games-runtime/wine-dlls")" = "$TERMUX_BOX_WINE_PACKAGE" ] ||
    {
        echo 'ERROR: native_prefix_wine_package_mismatch'; exit 1;
    }
else
    [ -x "$PREFIX/bin/python" ] || { echo 'ERROR: native_prefix_restore_runtime_missing'; exit 1; }
    unset LD_PRELOAD
    "$PREFIX/bin/python" "$SCRIPT_DIR/restore_wine_prefix.py" \
        --backend glibc_termux_box --rootfs "$TERMUX_GLIBC_DIR" \
        --wine-root "$WINE_PATH" --assets "$NATIVE_PREFIX_ASSETS" \
        --bundle-sha256 "$NATIVE_PREFIX_BUNDLE_SHA256" \
        --destination "$WINEPREFIX" --identity "$NATIVE_PREFIX_IDENTITY" \
        --runtime "$TERMUX_GLIBC_DIR" --package "$TERMUX_BOX_WINE_PACKAGE" &
    RESTORE_PID=$!
    status=0
    wait "$RESTORE_PID" || status=$?
    RESTORE_PID=
    [ "$status" -eq 0 ] || exit "$status"
fi
# These selections add only graphics/DirectX files, never initialize a Wine prefix.
apply_native_graphics_component || exit 1
apply_native_dx_component || exit 1
ensure_games_wine_fonts || exit 1
printf '%s\n' "$TERMUX_BOX_WINE_PACKAGE" > "$WINEPREFIX/.termux-box-wine-package"
touch "$WINEPREFIX/.termux-box-bootstrap-done"
# Data identity is immutable. Only the independent script completion receipt changes.
PREPARATION_MARKER_TMP="$WINEPREFIX/.games-runtime/preparation-script-identity.tmp.$$"
printf '%s\n' "$NATIVE_PREFIX_PREPARATION_IDENTITY" > "$PREPARATION_MARKER_TMP"
mv -f "$PREPARATION_MARKER_TMP" "$WINEPREFIX/.games-runtime/preparation-script-identity"
PREPARATION_MARKER_TMP=
echo '[bootstrap] Native prebuilt Wine prefix ready without wineboot.'
