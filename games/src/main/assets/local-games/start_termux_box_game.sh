#!/bin/sh
# Games GLIBC Start Script — copied from the Termux-box launcher.
# Usage: sh start_termux_box_game.sh <game_conf_path> [game arguments...]
#
# Starts a TermuxBox Wine container:
# 1. Loads container and system configuration
# 2. Patches box64 ELF and sets up Wine symlinks
# 3. Starts graphics (virgl) and audio (pulseaudio) services
# 4. Launches termux-x11 display
# 5. Starts Wine explorer /desktop=shell
# 6. Enters monitoring loop (type "1" to stop, or touch shutdown/reboot files)

set -u
PS4='+ '
set -x

CONTAINER_CONF="${1:?Usage: $0 <container_conf_path>}"
shift

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
WINE_PID=
TERMUX_BOX_CONTAINER_CONF="$CONTAINER_CONF"
TERMUX_BOX_TRACE_DIR="/sdcard/termux-boxtrace"
TERMUX_BOX_RUN_DIR="$TERMUX_BOX_ROOT/run"
TERMUX_BOX_WINE_LOCK_DIR="$TERMUX_BOX_RUN_DIR/glibc-wine.lock"

# ---- Ensure directories ----
mkdir -p "$TERMUX_BOX_TRACE_DIR"
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

# Mobox's package default is 4-7/0-3. Games normalizes the copied launcher for
# the current process cpuset without changing the original Termux-box script.
normalize_cpu_affinity() {
    _allowed=$(sed -n 's/^Cpus_allowed_list:[[:space:]]*//p' /proc/self/status 2>/dev/null | sed -n '1p')
    case "$_allowed" in
        [0-9]*-[0-9]*) case "$_allowed" in *,*) _allowed= ;; esac ;;
        [0-9]*) case "$_allowed" in *[!0-9]*) _allowed= ;; esac ;;
        *) _allowed= ;;
    esac
    if [ -n "$_allowed" ]; then
        case "$_allowed" in
            *-*)
                _first=${_allowed%-*}
                _last=${_allowed#*-}
                _count=$((_last - _first + 1))
                if [ "$_count" -gt 1 ]; then
                    _middle=$((_first + _count / 2))
                    PRIMARY_CORES="$_middle-$_last"
                    SECONDARY_CORES="$_first-$((_middle - 1))"
                else
                    PRIMARY_CORES=$_first
                    SECONDARY_CORES=$_first
                fi
                ;;
            *) PRIMARY_CORES=$_allowed; SECONDARY_CORES=$_allowed ;;
        esac
    else
        PRIMARY_CORES=$(default_primary_cores)
        SECONDARY_CORES=$PRIMARY_CORES
    fi
    export PRIMARY_CORES SECONDARY_CORES
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
    for _env_key in ${TERMUX_BOX_INHERITED_ENV_KEYS:-}; do
        if _env_value=$(printenv "$_env_key"); then
            export "$_env_key=$_env_value"
        fi
    done
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
    export TERMUX_BOX_TRANSLATOR="${TERMUX_BOX_TRANSLATOR:-box64}"
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

ensure_launch_log() {
    _launch_log=${TERMUX_BOX_LAUNCH_LOG:-}
    [ -n "$_launch_log" ] || return 0
    mkdir -p "$(dirname "$_launch_log")" || {
        echo "ERROR: Cannot create game launch log directory: $_launch_log"
        return 1
    }
    : >> "$_launch_log" || {
        echo "ERROR: Cannot open game launch log: $_launch_log"
        return 1
    }
}

ensure_games_wine_fonts() {
    _glibc_font_dir="$TERMUX_GLIBC_DIR/share/fonts"
    _font_source="$_glibc_font_dir/NotoSansCJK-Regular.ttc"
    _serif_source="$_glibc_font_dir/NotoSerifCJK-Regular.ttc"
    _font_target="$WINEPREFIX/drive_c/windows/Fonts/NotoSansCJK-Regular.ttc"
    # The GLIBC Wine runtime resolves font fallback through FONTCONFIG_PATH, whose
    # configured source is $TERMUX_GLIBC_DIR/share/fonts.  Keeping a TTC only in
    # C:\\Windows\\Fonts leaves that source empty and produces tofu glyphs.
    _font_marker="$WINEPREFIX/termux-boxmeta/games-wine-fonts-v2"
    _font_registry="$WINEPREFIX/termux-boxmeta/games-wine-fonts.reg"
    _font_link='"Tahoma"=hex(7):4e,00,6f,00,74,00,6f,00,53,00,61,00,6e,00,73,00,43,00,4a,00,4b,00,2d,00,52,00,65,00,67,00,75,00,6c,00,61,00,72,00,2e,00,74,00,74,00,63,00,2c,00,4e,00,6f,00,74,00,6f,00,20,00,53,00,61,00,6e,00,73,00,20,00,43,00,4a,00,4b,00,20,00,53,00,43,00,00,00,00,00'
    [ -f "$_font_source" ] && [ -f "$_serif_source" ] || {
        echo "ERROR: Required Wine fonts runtime component is not activated."
        return 1
    }
    _font_registry_is_persisted() {
        grep -F '"Tahoma"="Noto Sans CJK SC"' "$WINEPREFIX/system.reg" >/dev/null 2>&1 && \
            grep -F '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"' \
                "$WINEPREFIX/system.reg" >/dev/null 2>&1
    }
    [ -f "$_font_marker" ] && _font_registry_is_persisted && return 0
    mkdir -p "$(dirname "$_font_target")" "$WINEPREFIX/termux-boxmeta" || return 1
    cp "$_font_source" "$_font_target" || return 1
    cp "$_serif_source" "$WINEPREFIX/drive_c/windows/Fonts/NotoSerifCJK-Regular.ttc" || return 1
    # Wine rewrites system.reg from its in-memory registry on startup.  Appending
    # keys directly therefore produces a marker without durable font fallbacks.
    # Import after the prefix exists and wait for wineserver to flush the hives.
    {
        printf '%s\n' 'REGEDIT4'
        printf '\n'
        for _branch in \
            'HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\Fonts' \
            'HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\Fonts'; do
            printf '[%s]\n' "$_branch"
            printf '%s\n' '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"'
            printf '%s\n' '"Noto Sans CJK SC Bold (TrueType)"="NotoSansCJK-Regular.ttc"'
            printf '\n'
        done
        for _branch in \
            'HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes' \
            'HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontSubstitutes'; do
            printf '[%s]\n' "$_branch"
            for _name in Tahoma 'Tahoma Bold' 'MS Shell Dlg' 'MS Shell Dlg 2' \
                'Microsoft Sans Serif' 'MS Sans Serif' 'Lucida Sans Unicode' Arial \
                'Arial Black' 'Segoe UI' 'Segoe UI Semibold' 'Segoe UI Symbol' \
                SimSun NSimSun 'Microsoft YaHei' 'Microsoft YaHei UI' Meiryo; do
                printf '"%s"="Noto Sans CJK SC"\n' "$_name"
            done
            printf '\n'
        done
        for _branch in \
            'HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink' \
            'HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink'; do
            printf '[%s]\n' "$_branch"
            printf '%s\n' "$_font_link"
            for _name in 'Tahoma Bold' 'MS Shell Dlg' 'MS Shell Dlg 2' \
                'Microsoft Sans Serif' 'MS Sans Serif' 'Lucida Sans Unicode' Arial 'Arial Black'; do
                printf '"%s"=%s\n' "$_name" "${_font_link#*=}"
            done
            printf '\n'
        done
    } > "$_font_registry" || return 1
    if ! run_container_wine regedit "$_font_registry"; then
        echo "ERROR: Wine CJK registry import failed."
        return 1
    fi
    # regedit may return before its wineserver flushes the prefix hives.
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wineserver" -w >/dev/null 2>&1 || true
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wineserver" -k >/dev/null 2>&1 || true
    if _font_registry_is_persisted; then
        : > "$_font_marker"
        echo "Configured CJK FontLink for the game prefix."
    else
        echo "ERROR: CJK FontLink was not persisted."
        return 1
    fi
}

ensure_games_cjk_locale() {
    _locale_marker="$TERMUX_BOX_RUN_DIR/games-zh-cn-locale-v1"
    if [ ! -f "$_locale_marker" ]; then
        if [ -x "$GLIBC_BIN/locale-gen" ] && [ -f "$TERMUX_GLIBC_DIR/etc/locale.gen" ]; then
            echo "Preparing zh_CN UTF-8 and GBK locales"
            (
                export PATH="$GLIBC_BIN:$PATH"
                sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' "$TERMUX_GLIBC_DIR/etc/locale.gen"
                sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' "$TERMUX_GLIBC_DIR/etc/locale.gen"
                "$GLIBC_BIN/locale-gen"
            ) || return 0
            [ -d "$TERMUX_GLIBC_DIR/lib/locale/zh_CN.utf8" ] && : > "$_locale_marker"
        fi
    fi
    if [ -f "$_locale_marker" ]; then
        export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
    fi
}

start_x11_when_ready() {
    _x11_socket="$PREFIX/tmp/.X11-unix/X0"
    rm -f "$_x11_socket" "$PREFIX/tmp/.X0-lock" 2>/dev/null || true
    termux-x11 :0 >> "${TERMUX_BOX_LAUNCH_LOG:-/dev/null}" 2>&1 &
    _attempt=0
    while [ "$_attempt" -lt 40 ]; do
        [ -S "$_x11_socket" ] && return 0
        sleep 0.1
        _attempt=$((_attempt + 1))
    done
    echo "ERROR: Termux-X11 display :0 did not become ready."
    return 1
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
    RESOLUTION="${TERMUX_BOX_RESOLUTION:-${RESOLUTION:-1280x720}}"
    case "$RESOLUTION" in
        *[!0-9x]*|*x*x*|x*|*x)
            echo "Invalid game display resolution: $RESOLUTION"
            exit 2
            ;;
    esac
    resolution_width=${RESOLUTION%%x*}
    resolution_height=${RESOLUTION#*x}
    [ "$resolution_width" -gt 0 ] 2>/dev/null &&
        [ "$resolution_height" -gt 0 ] 2>/dev/null || {
        echo "Invalid game display resolution: $RESOLUTION"
        exit 2
    }
    export RESOLUTION
}

# ============================================================
# Main
# ============================================================

load_configs
normalize_cpu_affinity
apply_debug_mode
ensure_launch_log || exit 1
acquire_single_instance_lock
ensure_runtime_packages

# Stop previous services, then start fresh
stop_all

echo "[2/4] Starting graphics and audio services"
if [ -e "$PREFIX/glibc/opt/virgl/virgl-enabled" ]; then
    chmod +x "$PREFIX/glibc/opt/virgl/libvirgl_test_server.so" >/dev/null 2>&1 || true
    TMPDIR="$PREFIX/tmp" "$PREFIX/glibc/opt/virgl/libvirgl_test_server.so" >/dev/null 2>&1 &
fi
if command -v pulseaudio >/dev/null 2>&1; then
    pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 || true
fi
start_x11_when_ready || exit 1
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

# The selected translator belongs to this container. Missing optional resources
# fail explicitly instead of silently falling back to Box64.
run_container_wine() {
    case "$TERMUX_BOX_TRANSLATOR" in
        box64) "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" "$@" ;;
        hangover)
            [ -x "$TERMUX_GLIBC_DIR/bin/hangover" ] || {
                echo "ERROR: Missing Hangover translator"; return 127;
            }
            "$TERMUX_GLIBC_DIR/bin/hangover" "$GLIBC_BIN/wine" "$@" ;;
        fex)
            if [ -x "$TERMUX_GLIBC_DIR/bin/FEXInterpreter" ]; then
                "$TERMUX_GLIBC_DIR/bin/FEXInterpreter" "$GLIBC_BIN/wine" "$@"
            elif [ -x "$TERMUX_GLIBC_DIR/bin/fex" ]; then
                "$TERMUX_GLIBC_DIR/bin/fex" "$GLIBC_BIN/wine" "$@"
            else
                echo "ERROR: Missing FEX translator"
                return 127
            fi
            ;;
        *) echo "ERROR: Unsupported translator: $TERMUX_BOX_TRANSLATOR"; return 127 ;;
    esac
}

# Check that container was bootstrapped
echo "[3/4] Checking container initialization"
if [ ! -e "$WINEPREFIX/.termux-box-bootstrap-done" ]; then
    echo "ERROR: Container prefix not initialized."
    echo "The Wine prefix has not been bootstrapped. Re-create the container or run bootstrap manually."
    exit 1
fi
ensure_games_wine_fonts || exit 1
ensure_games_cjk_locale

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

# Mount the imported root below Z:, which is the mapping established by the
# existing Termux-box prefix bootstrap, then use the same explorer desktop path.
echo "[4/4] Starting Wine container: ${TERMUX_BOX_CONTAINER_NAME:-unknown}"
case "${TERMUX_BOX_GAME_ID:-}" in
    ''|*[!A-Za-z0-9._-]*) echo "Invalid game id"; exit 2 ;;
esac
[ -d "${TERMUX_BOX_GAME_ROOT:-}" ] || { echo "Game root is unavailable"; exit 2; }
[ -d "${TERMUX_BOX_GAME_WORKDIR:-}" ] || { echo "Game working directory is unavailable"; exit 2; }
GAME_MOUNT_ROOT="$TERMUX_FILES_DIR/games/mounts"
GAME_MOUNT="$GAME_MOUNT_ROOT/$TERMUX_BOX_GAME_ID"
mkdir -p "$GAME_MOUNT_ROOT"
rm -f "$GAME_MOUNT" 2>/dev/null || true
ln -s "$TERMUX_BOX_GAME_ROOT" "$GAME_MOUNT" || exit 2
rm -f "$WINEPREFIX/dosdevices/g:" 2>/dev/null || true
ln -s "$TERMUX_BOX_GAME_ROOT" "$WINEPREFIX/dosdevices/g:" || exit 2
GAME_EXECUTABLE=$(printf '%s' "$TERMUX_BOX_GAME_EXECUTABLE" | sed 's|/|\\|g')
WINDOWS_TARGET="G:\\$GAME_EXECUTABLE"
cd "$TERMUX_BOX_GAME_WORKDIR" || exit 2
DISPLAY=:0 LC_ALL="$LC_ALL" run_container_wine explorer /desktop=shell,"$RESOLUTION" \
    "$WINDOWS_TARGET" "$@" >>"${TERMUX_BOX_LAUNCH_LOG:-/dev/null}" 2>&1 &
WINE_PID=$!

if [ "${STARTUP_WINEDEVICE_MODE:-1}" = "0" ]; then
    "$GLIBC_BIN/box64" "$GLIBC_BIN/wine" taskkill /f /im services.exe >/dev/null 2>&1 &
fi

# GameSessionActivity owns the embedded X11 view.

echo "Container PID: $WINE_PID"
echo "To stop: kill $WINE_PID or press Ctrl+C"

# Wait for wine to exit
if wait "$WINE_PID"; then WINE_EXIT=0; else WINE_EXIT=$?; fi
echo "Container stopped."
exit "$WINE_EXIT"
