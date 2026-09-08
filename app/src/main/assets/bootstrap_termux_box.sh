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
PS4='+ '
set -x

CONTAINER_CONF="${1:?Usage: $0 <container_conf_path>}"

if [ ! -f "$CONTAINER_CONF" ]; then
    echo "ERROR: Container config not found: $CONTAINER_CONF"
    exit 1
fi

# ---- Fixed paths ----
TERMUX_FILES_DIR="/data/data/com.termux/files"
TERMUX_PREFIX="$TERMUX_FILES_DIR/usr"
TERMUX_GLIBC_DIR="$TERMUX_FILES_DIR/usr/glibc"
TERMUX_OPT_DIR="$TERMUX_GLIBC_DIR/opt"
TERMUX_BOX_ROOT="$TERMUX_GLIBC_DIR/termux-box"
TERMUX_BOX_CONFIG_DIR="$TERMUX_BOX_ROOT/config"
GLIBC_BIN="$TERMUX_GLIBC_DIR/bin"

# ---- Helpers ----
# Run command with CPU affinity if taskset is available and functional,
# otherwise fall back to direct execution.
default_primary_cores() {
    _core_count=$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)
    case "$_core_count" in ''|*[!0-9]*|0) _core_count=1 ;; esac
    if [ "$_core_count" -le 1 ]; then
        printf '0\n'
    else
        printf '%s-%s\n' "$((_core_count / 2))" "$((_core_count - 1))"
    fi
}

run_with_affinity() {
    _configured_cores="${PRIMARY_CORES:-}"
    _fallback_cores=$(default_primary_cores)
    if command -v taskset >/dev/null 2>&1; then
        if [ -n "$_configured_cores" ] && taskset -c "$_configured_cores" true 2>/dev/null; then
            taskset -c "$_configured_cores" "$@"
            return
        fi
        if taskset -c "$_fallback_cores" true 2>/dev/null; then
            taskset -c "$_fallback_cores" "$@"
            return
        fi
    fi
    "$@"
}

# ---- 1. Source container.conf (mandatory) ----
echo "[bootstrap] Loading configuration..."
. "$CONTAINER_CONF"

# ---- 2. Resolve container-specific variables (with defaults) ----
export WINE_PATH="$TERMUX_GLIBC_DIR/${TERMUX_BOX_WINE_PACKAGE:-wine-9.0-staging-wow64}"
TERMUX_BOX_WINE_PACKAGE="${TERMUX_BOX_WINE_PACKAGE:-wine-9.0-staging-wow64}"
export WINEPREFIX="${TERMUX_BOX_CONTAINER_PREFIX:-${TERMUX_BOX_ROOT}/containers/container-1/prefix}"
CONTAINER_NAME="${TERMUX_BOX_CONTAINER_NAME:-unknown}"
export PRIMARY_CORES="${PRIMARY_CORES:-0-1}"
export PREFIX="${PREFIX:-$TERMUX_PREFIX}"
BOOTSTRAP_LOCK="${WINEPREFIX}.bootstrap.lock"
X11_PID=
WINEBOOT_PID=
PULSE_OWNED=0

cleanup_bootstrap() {
    if [ -n "$X11_PID" ]; then
        kill "$X11_PID" >/dev/null 2>&1 || true
        wait "$X11_PID" >/dev/null 2>&1 || true
    fi
    if [ -n "$WINEBOOT_PID" ]; then
        kill "$WINEBOOT_PID" >/dev/null 2>&1 || true
        wait "$WINEBOOT_PID" >/dev/null 2>&1 || true
    fi
    if [ "$PULSE_OWNED" = 1 ]; then
        pulseaudio --kill >/dev/null 2>&1 || true
    fi
    if [ -f "$BOOTSTRAP_LOCK/pid" ] &&
       [ "$(sed -n '1p' "$BOOTSTRAP_LOCK/pid" 2>/dev/null)" = "$$" ]; then
        rm -f "$BOOTSTRAP_LOCK/pid"
        rmdir "$BOOTSTRAP_LOCK" 2>/dev/null || true
    fi
}

# ---- 3. Hardcoded runtime defaults ----
export BOX64_LD_LIBRARY_PATH="${BOX64_LD_LIBRARY_PATH:-$WINE_PATH/lib64:$WINE_PATH/lib64/wine/x86_64-unix:$WINE_PATH/lib:$WINE_PATH/lib/wine/x86_64-unix:$TERMUX_GLIBC_DIR/lib/x86_64-linux-gnu}"
export BOX64_PATH="${BOX64_PATH:-$GLIBC_BIN}"
export BOX64_MMAP32="${BOX64_MMAP32:-1}"
export VK_ICD_FILENAMES="${VK_ICD_FILENAMES:-$TERMUX_GLIBC_DIR/share/vulkan/icd.d/freedreno_icd.aarch64.json}"
export DXVK_CONFIG_FILE="${DXVK_CONFIG_FILE:-$TERMUX_OPT_DIR/dxvk.conf}"
export FONTCONFIG_PATH="${FONTCONFIG_PATH:-$TERMUX_GLIBC_DIR/etc/fonts}"
export DXVK_ASYNC="${DXVK_ASYNC:-1}"
export VKD3D_FEATURE_LEVEL="${VKD3D_FEATURE_LEVEL:-12_0}"
export tu_allow_oob_indirect_ubo_loads="${tu_allow_oob_indirect_ubo_loads:-true}"
if [ -z "${LC_ALL:-}" ] && [ -f "$TERMUX_OPT_DIR/locale.conf" ]; then
    LC_ALL=$(sed -n '1p' "$TERMUX_OPT_DIR/locale.conf")
fi
export LC_ALL="${LC_ALL:-en_US.utf8}"

# ---- 4. Load package-provided Mobox settings, then Termux-box overrides ----
for config_dir in "$TERMUX_OPT_DIR/conf" "$TERMUX_BOX_CONFIG_DIR"; do
    [ -d "$config_dir" ] || continue
    for config_file in "$config_dir"/*.conf; do
        [ -f "$config_file" ] || continue
        case "$(basename "$config_file")" in
            cores.conf|debug.conf|dynarec_preset.conf|force_compatibility.conf|hud.conf|\
            tu_debug.conf|virgl.conf|winedevice_startup.conf|wineesync.conf|\
            wsi_debug.conf|wsi_present.conf) . "$config_file" ;;
        esac
    done
done
for config_dir in "$TERMUX_OPT_DIR/conf/dynarec" "$TERMUX_BOX_CONFIG_DIR/dynarec"; do
    [ -d "$config_dir" ] || continue
    for config_file in "$config_dir"/*.conf; do
        [ -f "$config_file" ] && . "$config_file"
    done
done
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
    if [ -f "$WINEPREFIX/.termux-box-wine-package" ] &&
       [ "$(sed -n '1p' "$WINEPREFIX/.termux-box-wine-package")" !=
         "$TERMUX_BOX_WINE_PACKAGE" ]; then
        echo "ERROR: Existing prefix uses a different Wine runtime."
        exit 65
    fi
    if [ ! -f "$WINEPREFIX/.termux-box-wine-package" ]; then
        printf '%s\n' "$TERMUX_BOX_WINE_PACKAGE" > "$WINEPREFIX/.termux-box-wine-package"
    fi
    echo "[bootstrap] Prefix already initialized, skipping."
    exit 0
fi

if [ -f "$BOOTSTRAP_LOCK/pid" ]; then
    LOCK_PID=$(sed -n '1p' "$BOOTSTRAP_LOCK/pid" 2>/dev/null || true)
    if [ -z "$LOCK_PID" ] || ! kill -0 "$LOCK_PID" 2>/dev/null; then
        rm -f "$BOOTSTRAP_LOCK/pid"
        rmdir "$BOOTSTRAP_LOCK" 2>/dev/null || true
    fi
fi
mkdir "$BOOTSTRAP_LOCK" 2>/dev/null || {
    echo "ERROR: Prefix initialization is already running."
    exit 75
}
printf '%s\n' "$$" > "$BOOTSTRAP_LOCK/pid"
trap cleanup_bootstrap EXIT INT TERM

# ---- Setup binaries ----
echo "[bootstrap] Setting up Wine binaries..."
chmod +x "$WINE_PATH/bin/wine" "$WINE_PATH/bin/wineserver" 2>/dev/null || true

echo "[bootstrap] Patching box64 ELF (interpreter + rpath)..."
# patchelf rewrites in place, which fails with ETXTBSY while any process still has
# box64 mapped (a previous container, or the app itself right after installing it).
# Skip the rewrite when the binary already carries the interpreter we want.
box64_interpreter=
if command -v patchelf >/dev/null 2>&1; then
    box64_interpreter=$(patchelf --print-interpreter "$GLIBC_BIN/box64" 2>/dev/null || true)
elif [ -x /system/bin/file ]; then
    box64_description=$(/system/bin/file "$GLIBC_BIN/box64" 2>/dev/null || true)
    case "$box64_description" in
        *"dynamic ($TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1)"*)
            # Indexed Box64 packages are already patched. A clean Termux bootstrap
            # does not include patchelf, so use Android's ELF inspection fallback.
            box64_interpreter="$TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1"
            ;;
    esac
fi
if [ "$box64_interpreter" = "$TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1" ]; then
    echo "[bootstrap] box64 already patched; keeping existing interpreter."
elif ! command -v patchelf >/dev/null 2>&1; then
    echo "ERROR: Box64 has an unexpected ELF interpreter and patchelf is unavailable."
    exit 1
elif ! patchelf --force-rpath \
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

# Step A: Match Mobox initialization prerequisites without taking over display :0.
echo "[bootstrap] Starting temporary X server..."
command -v termux-x11 >/dev/null 2>&1 || {
    echo "ERROR: Termux:X11 bridge is not installed."
    exit 1
}
if command -v pulseaudio >/dev/null 2>&1 && ! pulseaudio --check >/dev/null 2>&1; then
    pulseaudio --start \
        --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
        --exit-idle-time=-1 >/dev/null 2>&1
    PULSE_OWNED=1
fi
termux-x11 :97 >/dev/null 2>&1 &
X11_PID=$!
sleep 2

# Step B: wineboot -u creates the prefix skeleton (directories, C: symlink, registry hives)
echo "[bootstrap] Running wineboot (this may take several minutes)..."
unset BOX64_DYNAREC_BIGBLOCK BOX64_DYNAREC_CALLRET WINEESYNC WINEESYNC_TERMUX
DISPLAY=:97 LC_ALL="$LC_ALL" \
WINEDLLOVERRIDES="winegstreamer=disabled,mscoree=disabled" \
    run_with_affinity "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" wineboot -u &
WINEBOOT_PID=$!
WINEBOOT_ELAPSED=0
REGISTRY_STABLE_COUNT=0
REGISTRY_SIGNATURE=
while kill -0 "$WINEBOOT_PID" 2>/dev/null; do
    sleep 3
    WINEBOOT_ELAPSED=$((WINEBOOT_ELAPSED + 3))
    if [ -e "$WINEPREFIX/.update-timestamp" ] &&
       [ -s "$WINEPREFIX/system.reg" ] &&
       [ -s "$WINEPREFIX/user.reg" ] &&
       [ -s "$WINEPREFIX/userdef.reg" ]; then
        CURRENT_SIGNATURE=$(wc -c "$WINEPREFIX/system.reg" "$WINEPREFIX/user.reg" \
            "$WINEPREFIX/userdef.reg" 2>/dev/null | tail -n 1)
        if [ "$CURRENT_SIGNATURE" = "$REGISTRY_SIGNATURE" ]; then
            REGISTRY_STABLE_COUNT=$((REGISTRY_STABLE_COUNT + 1))
        else
            REGISTRY_SIGNATURE=$CURRENT_SIGNATURE
            REGISTRY_STABLE_COUNT=0
        fi
        if [ "$REGISTRY_STABLE_COUNT" -ge 5 ]; then
            echo "[bootstrap] Registry is stable; stopping Wine services..."
            WINEPREFIX="$WINEPREFIX" "$GLIBC_BIN/box64" \
                "$GLIBC_BIN/wineserver" -k >/dev/null 2>&1 || true
            break
        fi
    fi
    if [ "$WINEBOOT_ELAPSED" -ge 300 ]; then
        echo "[bootstrap] Wineboot timeout; stopping Wine services..."
        WINEPREFIX="$WINEPREFIX" "$GLIBC_BIN/box64" \
            "$GLIBC_BIN/wineserver" -k >/dev/null 2>&1 || true
        break
    fi
done
WINEBOOT_GRACE=0
while kill -0 "$WINEBOOT_PID" 2>/dev/null && [ "$WINEBOOT_GRACE" -lt 15 ]; do
    sleep 1
    WINEBOOT_GRACE=$((WINEBOOT_GRACE + 1))
done
if kill -0 "$WINEBOOT_PID" 2>/dev/null; then
    echo "[bootstrap] Wineboot did not stop after wineserver shutdown; terminating it."
    kill "$WINEBOOT_PID" >/dev/null 2>&1 || true
fi
if wait "$WINEBOOT_PID"; then WINEBOOT_EXIT=0; else WINEBOOT_EXIT=$?; fi
WINEBOOT_PID=

# Stop temporary X server
echo "[bootstrap] Stopping temporary X server..."
kill "$X11_PID" >/dev/null 2>&1 || true
wait "$X11_PID" >/dev/null 2>&1 || true
X11_PID=

if [ ! -e "$WINEPREFIX/.update-timestamp" ]; then
    echo "ERROR: Cannot configure Wine prefix."
    echo "Check that required packages are installed and try again."
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wineserver" -k 2>/dev/null || true
    exit 1
fi
if [ "$WINEBOOT_EXIT" -ne 0 ]; then
    echo "[bootstrap] Wineboot exited with $WINEBOOT_EXIT after creating the prefix; continuing."
fi
echo "disable" >"$WINEPREFIX/.update-timestamp"

# Step C: Extract archives over the wineboot-created prefix
if [ -d "$TERMUX_OPT_DIR/prefix-expanded/drive_c" ]; then
    echo "[bootstrap] Installing verified base prefix overlay..."
    cp -a "$TERMUX_OPT_DIR/prefix-expanded/drive_c/." "$WINEPREFIX/drive_c/"
elif [ -f "$TERMUX_OPT_DIR/prefix/drive_c.7z" ]; then
    echo "[bootstrap] Extracting base prefix archive..."
    command -v 7z >/dev/null 2>&1 || {
        echo "ERROR: Base prefix overlay was not staged and p7zip is unavailable."
        exit 1
    }
    7z x "$TERMUX_OPT_DIR/prefix/drive_c.7z" -o"$WINEPREFIX/drive_c" -y >/dev/null 2>&1
fi

if [ -d "$TERMUX_OPT_DIR/prefix-expanded/directx" ]; then
    echo "[bootstrap] Installing verified DirectX overlay..."
    cp -a "$TERMUX_OPT_DIR/prefix-expanded/directx/." "$WINEPREFIX/drive_c/"
elif [ -f "$TERMUX_OPT_DIR/prefix/directx.7z" ]; then
    echo "[bootstrap] Extracting DirectX archive..."
    command -v 7z >/dev/null 2>&1 || {
        echo "ERROR: DirectX overlay was not staged and p7zip is unavailable."
        exit 1
    }
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

printf '%s\n' "$TERMUX_BOX_WINE_PACKAGE" > "$WINEPREFIX/.termux-box-wine-package"
touch "$WINEPREFIX/.termux-box-bootstrap-done"
echo "[bootstrap] Container '$CONTAINER_NAME' initialization complete."
