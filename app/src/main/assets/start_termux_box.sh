#!/bin/sh
# TermuxBox Container Start Script
# Usage: sh start_termux_box.sh <container_conf_path>
#
# Starts a TermuxBox Wine container:
# 1. Loads container and system configuration
# 2. Patches box64 ELF and sets up Wine symlinks
# 3. Starts graphics (virgl) and audio (pulseaudio) services
# 4. Launches termux-x11 display
# 5. Starts Wine explorer /desktop=shell
# 6. Enters monitoring loop (type "1" to stop, or touch shutdown/reboot files)

set -u

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
TERMUX_BOX_CONTAINER_CONF="$CONTAINER_CONF"
TERMUX_BOX_TRACE_DIR="/sdcard/termux-boxtrace"
TERMUX_BOX_RUN_DIR="$TERMUX_BOX_ROOT/run"
TERMUX_BOX_WINE_LOCK_DIR="$TERMUX_BOX_RUN_DIR/glibc-wine.lock"

# ---- Ensure directories ----
mkdir -p "$TERMUX_BOX_TRACE_DIR"
mkdir -p /sdcard/Android/data/com.termux/files/Download
mkdir -p "$TERMUX_BOX_ROOT" "$TERMUX_BOX_RUN_DIR"

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

# ---- Load configuration ----
# Design: container.conf is the SINGLE source of container-specific configuration.
# All runtime defaults are hardcoded here. Files in config/ are optional overrides
# managed by the settings UI and are sourced only if they exist.
apply_container_env_vars() {
    if [ -n "${TERMUX_BOX_ENV_VARS:-}" ]; then
        for _env_pair in $TERMUX_BOX_ENV_VARS; do
            case "$_env_pair" in
                *=\ *) eval "export $_env_pair" ;;
                *=*) eval "export ${_env_pair%%=*}='${_env_pair#*=}'" ;;
            esac
        done
    fi
}

load_configs() {
    # ---- 1. Source container.conf (mandatory — contains all container-specific config) ----
    . "$TERMUX_BOX_CONTAINER_CONF"

    # ---- 2. Resolve container-specific variables (with defaults aligned to Winlator) ----
    export TERMUX_BOX_CONTAINER_DIR="${TERMUX_BOX_CONTAINER_DIR:-$TERMUX_BOX_ROOT/containers/container-1}"
    export TERMUX_BOX_CONTAINER_PREFIX="${TERMUX_BOX_CONTAINER_PREFIX:-$TERMUX_BOX_CONTAINER_DIR/prefix}"
    export WINE_PATH="$TERMUX_GLIBC_DIR/${TERMUX_BOX_WINE_PACKAGE:-wine-9.0-staging-wow64}"
    export WINEPREFIX="$TERMUX_BOX_CONTAINER_PREFIX"
    export RESOLUTION="${TERMUX_BOX_RESOLUTION:-1280x720}"
    if [ -z "${LC_ALL:-}" ] && [ -f "$TERMUX_OPT_DIR/locale.conf" ]; then
        LC_ALL=$(sed -n '1p' "$TERMUX_OPT_DIR/locale.conf")
    fi
    export LC_ALL="${LC_ALL:-en_US.utf8}"
    export PREFIX="${PREFIX:-$TERMUX_FILES_DIR/usr}"

    # ---- 3. Apply container envVars (Winlator-aligned) ----
    apply_container_env_vars

    # ---- 4. Hardcoded runtime defaults ----
    export BOX64_LD_LIBRARY_PATH="$WINE_PATH/lib64:$WINE_PATH/lib64/wine/x86_64-unix:$WINE_PATH/lib:$WINE_PATH/lib/wine/x86_64-unix:$TERMUX_GLIBC_DIR/lib/x86_64-linux-gnu"
    export VK_ICD_FILENAMES="$TERMUX_GLIBC_DIR/share/vulkan/icd.d/freedreno_icd.aarch64.json"
    export DXVK_CONFIG_FILE="$TERMUX_OPT_DIR/dxvk.conf"
    export FONTCONFIG_PATH="$TERMUX_GLIBC_DIR/etc/fonts"
    export BOX64_PATH="$TERMUX_GLIBC_DIR/bin"
    export BOX64_MMAP32="${BOX64_MMAP32:-1}"
    export DXVK_ASYNC="${DXVK_ASYNC:-1}"
    export VKD3D_FEATURE_LEVEL="${VKD3D_FEATURE_LEVEL:-12_0}"
    export tu_allow_oob_indirect_ubo_loads=true
    export PRIMARY_CORES="${PRIMARY_CORES:-0-1}"

    # Apply box64 preset (Winlator-aligned)
    TERMUX_BOX_BOX64_PRESET="${TERMUX_BOX_BOX64_PRESET:-INTERMEDIATE}"
    case "$TERMUX_BOX_BOX64_PRESET" in
        STABILITY)     export BOX64_DYNAREC_SAFEFLAGS=2 BOX64_DYNAREC_FASTNAN=0 BOX64_DYNAREC_FASTROUND=0 BOX64_DYNAREC_X87DOUBLE=1 BOX64_DYNAREC_BIGBLOCK=0 BOX64_DYNAREC_STRONGMEM=2 BOX64_DYNAREC_FORWARD=128 BOX64_DYNAREC_CALLRET=0 ;;
        CONSERVATIVE)  export BOX64_DYNAREC_SAFEFLAGS=2 BOX64_DYNAREC_FASTNAN=0 BOX64_DYNAREC_FASTROUND=0 BOX64_DYNAREC_X87DOUBLE=1 BOX64_DYNAREC_BIGBLOCK=1 BOX64_DYNAREC_STRONGMEM=1 BOX64_DYNAREC_FORWARD=128 BOX64_DYNAREC_CALLRET=0 ;;
        INTERMEDIATE)  export BOX64_DYNAREC_SAFEFLAGS=2 BOX64_DYNAREC_FASTNAN=1 BOX64_DYNAREC_FASTROUND=0 BOX64_DYNAREC_X87DOUBLE=1 BOX64_DYNAREC_BIGBLOCK=2 BOX64_DYNAREC_STRONGMEM=0 BOX64_DYNAREC_FORWARD=128 BOX64_DYNAREC_CALLRET=0 ;;
        PERFORMANCE)   export BOX64_DYNAREC_SAFEFLAGS=1 BOX64_DYNAREC_FASTNAN=1 BOX64_DYNAREC_FASTROUND=1 BOX64_DYNAREC_X87DOUBLE=0 BOX64_DYNAREC_BIGBLOCK=3 BOX64_DYNAREC_STRONGMEM=0 BOX64_DYNAREC_FORWARD=512 BOX64_DYNAREC_CALLRET=1 ;;
    esac

    # HUD mode (Winlator-aligned: 0=off, 1=simple fps, 2=full)
    TERMUX_BOX_HUD_MODE="${TERMUX_BOX_HUD_MODE:-0}"
    case "$TERMUX_BOX_HUD_MODE" in
        0) unset MESA_LOADER_DRIVER_OVERRIDE ;;  # No overlay
        1) export GALLIUM_HUD=fps ;;
        2) export GALLIUM_HUD=simple,fps,cpu,VRAM-usage ;;
    esac

    # ---- 5. Load package-provided Mobox settings, then Termux-box overrides ----
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

    # Container-specific values are authoritative over package defaults.
    apply_container_env_vars
}

# ---- Debug mode ----
apply_debug_mode() {
    case "${DEBUG_MODE:-0}" in
        0)
            export MESA_NO_ERROR=1
            export WINEDEBUG=-all
            export BOX64_LOG=0
            export BOX64_NOBANNER=1
            export BOX64_SHOWSEGV=0
            export BOX64_DLSYM_ERROR=0
            export BOX64_DYNAREC_MISSING=0
            unset BOX64_TRACE_FILE
            ;;
        1)
            export MESA_NO_ERROR=0
            unset WINEDEBUG
            export BOX64_LOG=1
            export BOX64_NOBANNER=0
            export BOX64_SHOWSEGV=1
            export BOX64_DLSYM_ERROR=0
            export BOX64_DYNAREC_MISSING=1
            export BOX64_TRACE_FILE="$TERMUX_BOX_TRACE_DIR/trace-%pid.txt"
            ;;
        2)
            export MESA_NO_ERROR=0
            export WINEDEBUG=warn+all
            export BOX64_LOG=1
            export BOX64_NOBANNER=0
            export BOX64_SHOWSEGV=1
            export BOX64_DLSYM_ERROR=1
            export BOX64_DYNAREC_MISSING=1
            export BOX64_TRACE_FILE="$TERMUX_BOX_TRACE_DIR/trace-%pid.txt"
            ;;
        3)
            export MESA_NO_ERROR=0
            export WINEDEBUG=+all
            export BOX64_LOG=1
            export BOX64_NOBANNER=0
            export BOX64_SHOWSEGV=1
            export BOX64_DLSYM_ERROR=1
            export BOX64_DYNAREC_MISSING=1
            ;;
    esac
}

# ---- Single-instance lock ----
release_single_instance_lock() {
    if [ -f "$TERMUX_BOX_WINE_LOCK_DIR/pid" ] && [ "$(cat "$TERMUX_BOX_WINE_LOCK_DIR/pid" 2>/dev/null)" = "$$" ]; then
        rm -rf "$TERMUX_BOX_WINE_LOCK_DIR"
    fi
}

acquire_single_instance_lock() {
    if [ -f "$TERMUX_BOX_WINE_LOCK_DIR/pid" ]; then
        old_pid=$(cat "$TERMUX_BOX_WINE_LOCK_DIR/pid" 2>/dev/null || true)
        if [ -n "$old_pid" ] && ! kill -0 "$old_pid" >/dev/null 2>&1; then
            echo "Removing stale Termux Box wine lock: $old_pid"
            rm -rf "$TERMUX_BOX_WINE_LOCK_DIR"
        fi
    fi
    if ! mkdir "$TERMUX_BOX_WINE_LOCK_DIR" >/dev/null 2>&1; then
        echo "Another glibc wine container is already running."
        echo "Stop it before starting this container."
        exit 1
    fi
    echo $$ >"$TERMUX_BOX_WINE_LOCK_DIR/pid"
    # Cleanup on interrupt: kill wine process group, stop services, then exit
    trap 'echo; echo "Stopping container..."; kill "$WINE_PID" 2>/dev/null; wait "$WINE_PID" 2>/dev/null; "$GLIBC_BIN/box64" "$GLIBC_BIN/wineserver" -k >/dev/null 2>&1; stop_all; release_single_instance_lock; exit 130' INT TERM
    # Cleanup on normal exit
    trap '"$GLIBC_BIN/box64" "$GLIBC_BIN/wineserver" -k >/dev/null 2>&1; stop_all; release_single_instance_lock' EXIT
}

# ---- Runtime package check ----
ensure_runtime_packages() {
    echo "[1/4] Checking runtime packages"
    missing=0
    if [ ! -f "$WINE_PATH/bin/wine" ]; then
        echo "Missing wine runtime: $WINE_PATH/bin/wine"
        missing=1
    fi
    if [ ! -f "$WINE_PATH/bin/wineserver" ]; then
        echo "Missing wine runtime: $WINE_PATH/bin/wineserver"
        missing=1
    fi
    if [ ! -f "$TERMUX_GLIBC_DIR/bin/box64" ]; then
        echo "Missing box64 runtime: $TERMUX_GLIBC_DIR/bin/box64"
        missing=1
    fi
    if [ ! -f "$TERMUX_OPT_DIR/prefix/drive_c.7z" ]; then
        echo "Missing prefix archive: $TERMUX_OPT_DIR/prefix/drive_c.7z"
        missing=1
    fi
    if [ "$missing" = "1" ]; then
        echo "Install required packages from Package Manager first."
        exit 1
    fi
}

# ---- Stop all running services ----
stop_all() {
    ps -ef | grep 'termux.x11*' | grep -v grep | awk '{print $2}' | xargs -r kill -9 >/dev/null 2>&1 || true
    rm -rf "$PREFIX/tmp/pulse-"* >/dev/null 2>&1 || true
    pulseaudio -k >/dev/null 2>&1 || true
    unset PULSE_SERVER
    pkill pulseaudio >/dev/null 2>&1 || true
    rm -rf "$PREFIX/tmp/.virgl_test" >/dev/null 2>&1 || true
    pkill virgl >/dev/null 2>&1 || true
    rm -rf "$PREFIX/tmp/.virgl_test" >/dev/null 2>&1 || true
}

# ---- Resolution ----
resolve_resolution() {
    RESOLUTION="${RESOLUTION:-$(cat "$TERMUX_BOX_CONFIG_DIR/last-resolution.conf" 2>/dev/null)}"
    if [ -z "$RESOLUTION" ]; then
        RESOLUTION="1280x720"
    fi
    if [ "${STARTUP_COMPATIBILITY_MODE:-0}" = "1" ]; then
        autores=""
    else
        autores=$(DISPLAY=:0 xrandr 2>/dev/null | grep current | awk '{print $8$9$10}' | tr -d ,)
    fi
    if [ -n "$autores" ] && [ "$autores" != "$RESOLUTION" ]; then
        export RESOLUTION="$autores"
    fi
}

# ============================================================
# Main
# ============================================================

load_configs
apply_debug_mode
acquire_single_instance_lock
ensure_runtime_packages

# Stop previous services, then start fresh
stop_all

echo "[2/4] Starting graphics and audio services"
if [ -e "$PREFIX/glibc/opt/virgl/virgl-enabled" ]; then
    chmod +x "$PREFIX/glibc/opt/virgl/libvirgl_test_server.so" >/dev/null 2>&1 || true
    TMPDIR="$PREFIX/tmp" "$PREFIX/glibc/opt/virgl/libvirgl_test_server.so" >/dev/null 2>&1 &
fi
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1
termux-x11 :0 >/dev/null 2>&1 &
sleep 1
if [ -e "$PREFIX/glibc/opt/virgl/virgl-enabled" ]; then
    chmod 777 "$PREFIX/tmp/.virgl_test" >/dev/null 2>&1 || true
fi

resolve_resolution

# Setup Wine binaries
chmod +x "$WINE_PATH/bin/wine" "$WINE_PATH/bin/wineserver" >/dev/null 2>&1 || true
patchelf --force-rpath --set-rpath "$PREFIX/glibc/lib" --set-interpreter "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" "$PREFIX/glibc/bin/box64" >/dev/null 2>&1 || true
rm -rf "$PREFIX/glibc/bin/wine" "$PREFIX/glibc/bin/wineserver"
ln -sf "$WINE_PATH/bin/wine" "$PREFIX/glibc/bin/wine"
ln -sf "$WINE_PATH/bin/wineserver" "$PREFIX/glibc/bin/wineserver"
# Critical: clear bionic LD_PRELOAD before running glibc binaries
unset LD_PRELOAD

# Check that container was bootstrapped
echo "[3/4] Checking container initialization"
if [ ! -e "$WINEPREFIX/.termux-box-bootstrap-done" ]; then
    echo "ERROR: Container prefix not initialized."
    echo "The Wine prefix has not been bootstrapped. Re-create the container or run bootstrap manually."
    exit 1
fi

export PULSE_SERVER=127.0.0.1

# External storage symlink
ln -sf $(df -H 2>/dev/null | grep -o "/storage/....-....") "$WINEPREFIX/dosdevices/f:" >/dev/null 2>&1 || true

# Locale
LC_ALL="${LC_ALL:-$(cat "$TERMUX_BOX_CONFIG_DIR/locale.conf" 2>/dev/null)}"
if [ -z "$LC_ALL" ]; then
    LC_ALL="en_US.utf8"
fi

# Build WINEDLLOVERRIDES from container gamepad mapper type
# 0 = Standard (DInput), 1 = XInput
WINE_DLL_OVERRIDES=""
case "${TERMUX_BOX_GAMEPAD_MAPPER:-1}" in
    0)
        WINE_DLL_OVERRIDES="dinput=native;dinput8=native;"
        ;;
    1)
        WINE_DLL_OVERRIDES="xinput1_1=native;xinput1_2=native;xinput1_3=native;xinput1_4=native;xinput9_1_0=native;xinputuap=native;"
        ;;
esac
[ -n "$WINE_DLL_OVERRIDES" ] && export WINEDLLOVERRIDES || unset WINEDLLOVERRIDES

# Start Wine (redirect output — wine produces massive fixme/err noise at default verbosity)
echo "[4/4] Starting Wine container: ${TERMUX_BOX_CONTAINER_NAME:-unknown}"
DISPLAY=:0 LC_ALL="$LC_ALL" run_with_affinity \
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" explorer /desktop=shell,"$RESOLUTION" "$TERMUX_OPT_DIR/apps/tfm.exe" >/dev/null 2>&1 &
WINE_PID=$!

if [ "${STARTUP_WINEDEVICE_MODE:-1}" = "0" ]; then
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" taskkill /f /im services.exe >/dev/null 2>&1 &
fi

# Open X11 activity
am start --user 0 -n com.termux.x11/.MainActivity >/dev/null 2>&1

echo "Container PID: $WINE_PID"
echo "To stop: kill $WINE_PID or press Ctrl+C"

# Wait for wine to exit
wait "$WINE_PID" 2>/dev/null || true
echo "Container stopped."
