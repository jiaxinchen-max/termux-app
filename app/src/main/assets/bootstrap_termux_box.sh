#!/bin/sh
# TermuxBox Container Bootstrap Script
# Usage: sh bootstrap_termux_box.sh <container_conf_path>
#
# Initializes a Wine prefix for a TermuxBox container — runs ONCE at container creation.
# 1. Extracts prefix archives (drive_c.7z, directx.7z)
# 2. Patches box64 ELF to use glibc interpreter/rpath
# 3. Runs wineboot -u to create registry hives
# 4. Imports registry files and applies fixes
#
# Idempotent — safe to run multiple times.
# Once complete, the marker file .termux-box-bootstrap-done is created.

set -e

CONTAINER_CONF="${1:?Usage: $0 <container_conf_path>}"

if [ ! -f "$CONTAINER_CONF" ]; then
    echo "ERROR: Container config not found: $CONTAINER_CONF"
    exit 1
fi

# ---- Fixed paths ----
TERMUX_FILES_DIR="/data/data/com.termux/files"
TERMUX_GLIBC_DIR="$TERMUX_FILES_DIR/usr/glibc"
TERMUX_OPT_DIR="$TERMUX_GLIBC_DIR/opt"
TERMUX_BOX_ROOT="$TERMUX_GLIBC_DIR/termux-box"
TERMUX_BOX_CONFIG_DIR="$TERMUX_BOX_ROOT/config"
GLIBC_BIN="$TERMUX_GLIBC_DIR/bin"

# ---- Helpers ----
source_conf() {
    if [ -f "$1" ]; then
        . "$1"
    fi
}

# Run command with CPU affinity if taskset is available and functional,
# otherwise fall back to direct execution.
run_with_affinity() {
    _cores="${PRIMARY_CORES:-0-1}"
    if taskset -c "$_cores" true 2>/dev/null; then
        taskset -c "$_cores" "$@"
    else
        "$@"
    fi
}

# ---- 1. Source container.conf (mandatory) ----
echo "[bootstrap] Loading configuration..."
. "$CONTAINER_CONF"

# ---- 2. Resolve container-specific variables (with defaults) ----
export WINE_PATH="$TERMUX_GLIBC_DIR/${TERMUX_BOX_WINE_PACKAGE:-wine-9.0-staging-wow64}"
export WINEPREFIX="${TERMUX_BOX_CONTAINER_PREFIX:-${TERMUX_BOX_ROOT}/containers/container-1/prefix}"
CONTAINER_NAME="${TERMUX_BOX_CONTAINER_NAME:-unknown}"
export PRIMARY_CORES="${PRIMARY_CORES:-0-1}"

# ---- 3. Hardcoded runtime defaults ----
export BOX64_LD_LIBRARY_PATH="$WINE_PATH/lib64:$WINE_PATH/lib64/wine/x86_64-unix:$TERMUX_GLIBC_DIR/lib/x86_64-linux-gnu"

# ---- 4. Optional: source user setting overrides ----
source_conf "$TERMUX_BOX_CONFIG_DIR/cores.conf"
source_conf "$TERMUX_BOX_CONFIG_DIR/debug.conf"
source_conf "$TERMUX_BOX_CONFIG_DIR/virgl.conf"
source_conf "$TERMUX_BOX_CONFIG_DIR/hud.conf"
source_conf "$TERMUX_BOX_CONFIG_DIR/tu_debug.conf"
source_conf "$TERMUX_BOX_CONFIG_DIR/dynarec_preset.conf"
if [ -d "$TERMUX_BOX_CONFIG_DIR/dynarec" ]; then
    for i in "$TERMUX_BOX_CONFIG_DIR"/dynarec/*.conf; do
        [ -f "$i" ] && . "$i"
    done
fi

echo "[bootstrap] Container : $CONTAINER_NAME"
echo "[bootstrap] Wine path  : $WINE_PATH"
echo "[bootstrap] Prefix     : $WINEPREFIX"

# ---- Prerequisites ----
echo "[bootstrap] Checking prerequisites..."
if [ ! -f "$WINE_PATH/bin/wine" ]; then
    echo "ERROR: Missing wine runtime: $WINE_PATH/bin/wine"
    echo "Install required packages from Package Manager first."
    exit 1
fi
if [ ! -f "$GLIBC_BIN/box64" ]; then
    echo "ERROR: Missing box64 runtime: $GLIBC_BIN/box64"
    exit 1
fi

# ---- Early exit if already bootstrapped ----
mkdir -p "$WINEPREFIX"
if [ -e "$WINEPREFIX/.termux-box-bootstrap-done" ]; then
    echo "[bootstrap] Prefix already initialized, skipping."
    exit 0
fi

# ---- Setup binaries ----
echo "[bootstrap] Setting up Wine binaries..."
chmod +x "$WINE_PATH/bin/wine" "$WINE_PATH/bin/wineserver" 2>/dev/null || true

echo "[bootstrap] Patching box64 ELF (interpreter + rpath)..."
if ! patchelf --force-rpath \
     --set-rpath "$TERMUX_GLIBC_DIR/lib" \
     --set-interpreter "$TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1" \
     "$GLIBC_BIN/box64"; then
    echo "ERROR: patchelf failed. Install the patchelf package first."
    exit 1
fi

rm -rf "$GLIBC_BIN/wine" "$GLIBC_BIN/wineserver" 2>/dev/null || true
ln -sf "$WINE_PATH/bin/wine" "$GLIBC_BIN/wine"
ln -sf "$WINE_PATH/bin/wineserver" "$GLIBC_BIN/wineserver"

# ---- Critical: clear bionic LD_PRELOAD before running glibc binaries ----
unset LD_PRELOAD

# ---- Initialize Wine prefix ----
# Order matters: wineboot must run FIRST to create the proper directory structure
# and C: drive mapping. Archives are extracted on top afterwards.
echo "[bootstrap] Creating fresh prefix..."
rm -rf "$WINEPREFIX"
mkdir -p "$WINEPREFIX"

# Step A: Start temporary X server so wineboot runs cleanly
echo "[bootstrap] Starting temporary X server..."
termux-x11 :0 >/dev/null 2>&1 &
sleep 2

# Step B: wineboot -u creates the prefix skeleton (directories, C: symlink, registry hives)
echo "[bootstrap] Running wineboot (this may take several minutes)..."
WINEDLLOVERRIDES="winegstreamer=disabled,mscoree=disabled" \
    run_with_affinity "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" wineboot -u
WINEBOOT_EXIT=$?

# Stop temporary X server
echo "[bootstrap] Stopping temporary X server..."
ps -ef | grep 'termux.x11*' | grep -v grep | awk '{print $2}' | xargs -r kill -9 >/dev/null 2>&1 || true

if [ $WINEBOOT_EXIT -ne 0 ] || [ ! -e "$WINEPREFIX/.update-timestamp" ]; then
    echo "ERROR: Cannot configure Wine prefix."
    echo "Check that required packages are installed and try again."
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wineserver" -k 2>/dev/null || true
    exit 1
fi
echo "disable" >"$WINEPREFIX/.update-timestamp"

# Step C: Extract archives over the wineboot-created prefix
if [ -f "$TERMUX_OPT_DIR/prefix/drive_c.7z" ]; then
    echo "[bootstrap] Extracting base prefix archive..."
    7z x "$TERMUX_OPT_DIR/prefix/drive_c.7z" -o"$WINEPREFIX/drive_c" -y >/dev/null 2>&1
fi

if [ -f "$TERMUX_OPT_DIR/prefix/directx.7z" ]; then
    echo "[bootstrap] Extracting DirectX archive..."
    7z x "$TERMUX_OPT_DIR/prefix/directx.7z" -o"$WINEPREFIX/drive_c" -y >/dev/null 2>&1
fi

# Step D: Set up extra dosdevices drive mappings
echo "[bootstrap] Setting up drive mappings..."
rm -rf "$WINEPREFIX/dosdevices/z:" 2>/dev/null || true
ln -sf "$TERMUX_FILES_DIR" "$WINEPREFIX/dosdevices/z:"
ln -sf /sdcard/Download "$WINEPREFIX/dosdevices/d:"
ln -sf /sdcard/Android/data/com.termux/files/Download "$WINEPREFIX/dosdevices/e:"

# Step E: Post-wineboot fixups (start menu, fonts, registry)
if [ -d "$TERMUX_OPT_DIR/prefix/start" ]; then
    echo "[bootstrap] Installing Start Menu entries..."
    mkdir -p "$WINEPREFIX/drive_c/ProgramData/Microsoft/Windows/Start Menu"
    cp -rn "$TERMUX_OPT_DIR/prefix/start/"* "$WINEPREFIX/drive_c/ProgramData/Microsoft/Windows/Start Menu" 2>/dev/null || true
fi

if [ -f "$TERMUX_OPT_DIR/prefix/marlett.ttf" ]; then
    cp "$TERMUX_OPT_DIR/prefix/marlett.ttf" "$WINEPREFIX/drive_c/windows/Fonts" 2>/dev/null || true
fi

if [ -f "$TERMUX_OPT_DIR/prefix/user.reg" ]; then
    echo "[bootstrap] Importing user registry..."
    run_with_affinity "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" regedit "$TERMUX_OPT_DIR/prefix/user.reg" 2>/dev/null || true
fi

if [ -f "$TERMUX_OPT_DIR/prefix/system.reg" ]; then
    echo "[bootstrap] Importing system registry..."
    run_with_affinity "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" regedit "$TERMUX_OPT_DIR/prefix/system.reg" 2>/dev/null || true
fi

mkdir -p "$WINEPREFIX/termux-boxmeta"
if [ -f "$TERMUX_OPT_DIR/prefix/fix-services.reg" ]; then
    echo "[bootstrap] Applying service fixes..."
    run_with_affinity "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" regedit "$TERMUX_OPT_DIR/prefix/fix-services.reg" 2>/dev/null || true
fi
touch "$WINEPREFIX/termux-boxmeta/services-fix-applied"

if [ -f "$TERMUX_OPT_DIR/prefix/fix-fonts.tar.xz" ]; then
    echo "[bootstrap] Applying font fixes..."
    tar -xf "$TERMUX_OPT_DIR/prefix/fix-fonts.tar.xz" -C "$WINEPREFIX/drive_c/windows" 2>/dev/null || true
fi
touch "$WINEPREFIX/termux-boxmeta/fonts-fix-applied"
touch "$WINEPREFIX/termux-boxmeta/dxdlls-fix-applied"

touch "$WINEPREFIX/.termux-box-bootstrap-done"
echo "[bootstrap] Container '$CONTAINER_NAME' initialization complete."
