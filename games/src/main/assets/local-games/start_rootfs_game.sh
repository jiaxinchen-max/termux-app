#!/bin/sh
# RootFS/PRoot game launcher. Input is a private schema-v5 Base64 LaunchSpec.

set -u

SPEC_PATH=${1:?Usage: start_rootfs_game.sh <launch-spec>}
SCRIPT_PATH=$(realpath "$0" 2>/dev/null) || {
    printf '%s\n' launch_script_unreadable >&2
    exit 2
}
TERMUX_FILES_DIR=$(dirname "$(dirname "$(dirname "$SCRIPT_PATH")")")
GAMES_PRIVATE_ROOT="$TERMUX_FILES_DIR/games"
# Winlator-style: one shared, always-current RootFS every container's proot session mounts with
# `-r` -- never a per-containerId copy (see setup_rootfs_runtime.sh, which publishes it, and
# GameStoragePaths.getSharedRootfsDirectory(), which RootfsProotBackend resolves RUNTIME_ROOT_PATH
# from below).
SHARED_ROOTFS_CONTAINER_DIRECTORY="$TERMUX_FILES_DIR/usr/var/lib/proot-distro/games-shared-rootfs"
SHARED_ROOTFS="$SHARED_ROOTFS_CONTAINER_DIRECTORY/rootfs"
TEMPLATE_CACHE_DIR="$TERMUX_FILES_DIR/usr/var/lib/proot-distro/games-template-cache"
if [ -f "$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix.tar.zst" ]; then
    TEMPLATE_PREFIX_ARCHIVE="$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix.tar.zst"
elif [ -f "$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix.tar.gz" ]; then
    TEMPLATE_PREFIX_ARCHIVE="$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix.tar.gz"
else
    TEMPLATE_PREFIX_ARCHIVE=
fi
PROOT_BIN="$TERMUX_FILES_DIR/usr/bin/proot"
SEQUENCE=0
PROOT_PID=
DISPLAY_PID=
VIRGL_PID=
LOG_TAIL_PID=
AUDIO_OWNED=0
TASK_LOCK_OWNED=0
CANCELLED=0

decode_text() { printf '%s' "$1" | base64 -d; }
fail_before_events() { printf '%s\n' "$1" >&2; exit 2; }

[ -f "$SPEC_PATH" ] || fail_before_events launch_spec_missing

TASK_ID=
SPEC_SCHEMA=
GAME_ID=
GAME_ROOT=
EXECUTABLE=
WORKING_DIRECTORY=
PREFIX_PATH=
WINE_PACKAGE=
GRAPHICS_DRIVER=
DX_WRAPPER=
AUDIO_DRIVER=
RESOLUTION=
BOX64_PRESET=
INPUT_PROFILE_ID=
LAUNCH_EXECUTION_MODE=
RUNTIME_BACKEND_TYPE=
ROOTFS_PACKAGE=
RUNTIME_ROOT_PATH=
HOME_PATH=
EVENT_PATH=
LOG_PATH=
LOCK_PATH=
CANCEL_PATH=
TIMEOUT_SECONDS=
ARGUMENT_COUNT=
ENVIRONMENT_COUNT=
ACTUAL_ARGUMENT_COUNT=0
ACTUAL_ENVIRONMENT_COUNT=0
PENDING_ENV_KEY=
set --

while IFS= read -r line || [ -n "$line" ]; do
    key=${line%%=*}
    value=${line#*=}
    [ "$key" != "$line" ] || fail_before_events invalid_launch_spec_line
    case "$key" in
        schemaVersion) SPEC_SCHEMA=$value ;;
        timeoutSeconds) TIMEOUT_SECONDS=$value ;;
        argumentCount) ARGUMENT_COUNT=$value ;;
        environmentCount) ENVIRONMENT_COUNT=$value ;;
        taskId) TASK_ID=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        gameId) GAME_ID=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        gameRootPath) GAME_ROOT=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        executable) EXECUTABLE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        workingDirectory) WORKING_DIRECTORY=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        prefixPath) PREFIX_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        winePackage) WINE_PACKAGE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        graphicsDriver) GRAPHICS_DRIVER=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        dxWrapper) DX_WRAPPER=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        audioDriver) AUDIO_DRIVER=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        resolution) RESOLUTION=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        box64Preset) BOX64_PRESET=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        inputProfileId) INPUT_PROFILE_ID=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        launchExecutionMode) LAUNCH_EXECUTION_MODE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        runtimeBackendType) RUNTIME_BACKEND_TYPE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        rootfsPackage) ROOTFS_PACKAGE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        runtimeRootPath) RUNTIME_ROOT_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        homeDirectory) HOME_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        eventPath) EVENT_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        logPath) LOG_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        lockPath) LOCK_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        cancelPath) CANCEL_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64 ;;
        argument.*)
            decoded=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            set -- "$@" "$decoded"
            ACTUAL_ARGUMENT_COUNT=$((ACTUAL_ARGUMENT_COUNT + 1))
            ;;
        environment.*.key)
            [ -z "$PENDING_ENV_KEY" ] || fail_before_events invalid_launch_environment_pair
            PENDING_ENV_KEY=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            case "$PENDING_ENV_KEY" in ''|[0-9]*|*[!A-Za-z0-9_]*) fail_before_events invalid_launch_environment_key ;; esac
            case "$PENDING_ENV_KEY" in
                PATH|HOME|USER|LOGNAME|TMPDIR|LD_*|PROOT_*|TERMUX_*|WINEPREFIX|DISPLAY|PULSE_SERVER)
                    fail_before_events reserved_launch_environment_key
                    ;;
            esac
            ;;
        environment.*.value)
            [ -n "$PENDING_ENV_KEY" ] || fail_before_events invalid_launch_environment_pair
            decoded=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            export "$PENDING_ENV_KEY=$decoded"
            PENDING_ENV_KEY=
            ACTUAL_ENVIRONMENT_COUNT=$((ACTUAL_ENVIRONMENT_COUNT + 1))
            ;;
        *) fail_before_events unknown_launch_spec_field ;;
    esac
done < "$SPEC_PATH"

[ "$SPEC_SCHEMA" = 5 ] || fail_before_events unsupported_launch_spec_schema
[ "$RUNTIME_BACKEND_TYPE" = rootfs_proot ] || fail_before_events runtime_backend_script_mismatch
case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_task_id ;; esac
case "$GAME_ID" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_game_id ;; esac
case "$ROOTFS_PACKAGE" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_rootfs_package ;; esac
case "$WINE_PACKAGE" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_wine_package ;; esac
case "$ARGUMENT_COUNT" in ''|*[!0-9]*) fail_before_events invalid_launch_spec_number ;; esac
case "$ENVIRONMENT_COUNT" in ''|*[!0-9]*) fail_before_events invalid_launch_spec_number ;; esac
case "$TIMEOUT_SECONDS" in ''|*[!0-9]*) fail_before_events invalid_launch_spec_number ;; esac
[ "$ACTUAL_ARGUMENT_COUNT" -eq "$ARGUMENT_COUNT" ] || fail_before_events launch_argument_count_mismatch
[ "$ACTUAL_ENVIRONMENT_COUNT" -eq "$ENVIRONMENT_COUNT" ] || fail_before_events launch_environment_count_mismatch
[ -z "$PENDING_ENV_KEY" ] || fail_before_events invalid_launch_environment_pair
[ "$ARGUMENT_COUNT" -le 256 ] || fail_before_events launch_argument_count_out_of_range
[ "$ENVIRONMENT_COUNT" -le 256 ] || fail_before_events launch_environment_count_out_of_range
[ "$TIMEOUT_SECONDS" -ge 30 ] && [ "$TIMEOUT_SECONDS" -le 86400 ] || fail_before_events launch_timeout_out_of_range
case "$LAUNCH_EXECUTION_MODE" in app_shell|terminal_session) ;; *) fail_before_events invalid_launch_execution_mode ;; esac
case "$INPUT_PROFILE_ID" in
    xinput|dinput) ;;
    xinput:*|dinput:*)
        profile_number=${INPUT_PROFILE_ID#*:}
        case "$profile_number" in ''|*[!0-9]*|0*) fail_before_events invalid_input_profile ;; esac
        [ "$profile_number" -le 999999 ] || fail_before_events invalid_input_profile
        ;;
    *) fail_before_events invalid_input_profile ;;
esac

validate_private_path() {
    case "$1" in "$GAMES_PRIVATE_ROOT"/*) ;; *) fail_before_events launch_private_path_required ;; esac
    case "$1" in *'/../'*|*/..|*/./*) fail_before_events invalid_launch_private_path ;; esac
}
validate_private_path "$PREFIX_PATH"
validate_private_path "$HOME_PATH"
validate_private_path "$EVENT_PATH"
validate_private_path "$LOG_PATH"
validate_private_path "$LOCK_PATH"
validate_private_path "$CANCEL_PATH"
# Must be exactly the one shared RootFS every container mounts -- there is no more per-container
# path pattern to validate against (see SHARED_ROOTFS above).
[ "$RUNTIME_ROOT_PATH" = "$SHARED_ROOTFS" ] || fail_before_events rootfs_path_outside_termux_runtime

mkdir -p "$(dirname "$EVENT_PATH")" "$(dirname "$LOG_PATH")" \
    "$(dirname "$LOCK_PATH")" "$(dirname "$CANCEL_PATH")" "$PREFIX_PATH" "$HOME_PATH"
: > "$EVENT_PATH"
: > "$LOG_PATH"
if [ "$LAUNCH_EXECUTION_MODE" = terminal_session ]; then
    printf 'Local Games RootFS task: %s\nLog: %s\n\n' "$TASK_ID" "$LOG_PATH"
    tail -n +1 -f "$LOG_PATH" &
    LOG_TAIL_PID=$!
fi
exec >> "$LOG_PATH" 2>&1
PS4='+ '
set -x
printf '%s\n' 'Verbose command tracing enabled.'

emit() {
    SEQUENCE=$((SEQUENCE + 1))
    now=$(date +%s)
    printf '{"schemaVersion":1,"taskId":"%s","sequence":%s,"state":"%s","stage":"%s","progress":%s,"timestamp":%s000,"pid":%s,"exitCode":%s,"errorCode":"%s","recoverable":%s,"message":"%s","logRef":"%s"}\n' \
        "$TASK_ID" "$SEQUENCE" "$1" "$2" "$3" "$now" "$$" "$4" "$5" "$6" "$7" "$LOG_PATH" >> "$EVENT_PATH"
}

terminal_failure() {
    emit FAILED COMPLETE 100 "${2:-null}" "$1" "${3:-false}" "$1"
    exit 1
}

cleanup() {
    [ -z "$PROOT_PID" ] || kill "$PROOT_PID" 2>/dev/null || true
    [ -z "$PROOT_PID" ] || wait "$PROOT_PID" 2>/dev/null || true
    [ -z "$VIRGL_PID" ] || kill "$VIRGL_PID" 2>/dev/null || true
    [ -z "$DISPLAY_PID" ] || kill "$DISPLAY_PID" 2>/dev/null || true
    if [ "$AUDIO_OWNED" = 1 ]; then
        unset PULSE_SERVER
        pulseaudio --kill >/dev/null 2>&1 || true
        AUDIO_OWNED=0
    fi
    [ -z "$LOG_TAIL_PID" ] || kill "$LOG_TAIL_PID" 2>/dev/null || true
    if [ "$TASK_LOCK_OWNED" = 1 ] && [ -f "$LOCK_PATH/pid" ] && \
        [ "$(sed -n '1p' "$LOCK_PATH/pid" 2>/dev/null)" = "$$" ]; then
        rm -f "$LOCK_PATH/pid"
        rmdir "$LOCK_PATH" 2>/dev/null || true
    fi
}
on_signal() {
    CANCELLED=1
    : > "$CANCEL_PATH"
    [ -z "$PROOT_PID" ] || kill "$PROOT_PID" 2>/dev/null || true
    [ -z "$VIRGL_PID" ] || kill "$VIRGL_PID" 2>/dev/null || true
    [ -z "$DISPLAY_PID" ] || kill "$DISPLAY_PID" 2>/dev/null || true
}
trap cleanup EXIT
trap on_signal HUP INT TERM

emit RUNNING PRECHECK 5 null '' false precheck
[ -x "$PROOT_BIN" ] || terminal_failure proot_runtime_missing null true
ROOTFS_CANONICAL=$(realpath "$RUNTIME_ROOT_PATH" 2>/dev/null) || terminal_failure rootfs_unreadable null true
SHARED_ROOTFS_CANONICAL=$(realpath "$SHARED_ROOTFS" 2>/dev/null) || terminal_failure rootfs_unreadable null true
[ "$ROOTFS_CANONICAL" = "$SHARED_ROOTFS_CANONICAL" ] || \
    terminal_failure rootfs_path_outside_termux_runtime
[ -d "$ROOTFS_CANONICAL" ] || terminal_failure rootfs_unreadable null true
[ -x "$ROOTFS_CANONICAL/usr/bin/env" ] || terminal_failure rootfs_env_missing null true
RUNTIME_MANIFEST="$ROOTFS_CANONICAL/etc/games-runtime.properties"
[ -f "$RUNTIME_MANIFEST" ] || terminal_failure rootfs_runtime_manifest_missing null true

manifest_value() {
    awk -F= -v expected_key="$1" '
        $1 == expected_key { sub(/^[^=]*=/, ""); print; found=1; exit }
        END { if (!found) exit 1 }
    ' "$RUNTIME_MANIFEST"
}
manifest_has() {
    awk -F= -v expected_key="$1" -v expected_value="$2" '
        $1 == expected_key {
            found=1
            count=split($2, values, ",")
            for (item=1; item<=count; item++) if (values[item] == expected_value) matched=1
            exit
        }
        END { if (!found || !matched) exit 1 }
    ' "$RUNTIME_MANIFEST"
}

MANIFEST_SCHEMA=$(manifest_value schemaVersion 2>/dev/null) || terminal_failure rootfs_runtime_manifest_unsupported
case "$MANIFEST_SCHEMA" in 1|2) ;; *) terminal_failure rootfs_runtime_manifest_unsupported ;; esac
if [ "$MANIFEST_SCHEMA" = 2 ]; then
    [ "$(manifest_value runtimeBackend 2>/dev/null)" = rootfs_proot ] || terminal_failure rootfs_runtime_backend_mismatch
    [ "$(manifest_value architecture 2>/dev/null)" = aarch64 ] || terminal_failure rootfs_architecture_mismatch
    case "$(uname -m)" in aarch64|arm64) ;; *) terminal_failure host_architecture_unsupported ;; esac
    manifest_has audioDrivers "$AUDIO_DRIVER" || terminal_failure rootfs_audio_driver_missing
fi
manifest_has runtimePackages "$WINE_PACKAGE" || terminal_failure rootfs_runtime_package_missing
manifest_has graphicsDrivers "$GRAPHICS_DRIVER" || terminal_failure rootfs_graphics_driver_missing
manifest_has dxWrappers "$DX_WRAPPER" || terminal_failure rootfs_dx_wrapper_missing
case "$GRAPHICS_DRIVER:$DX_WRAPPER" in
    rootfs-virgl-mesa:rootfs-dxvk-*)
        terminal_failure runtime_combination_unsupported:virgl_dxvk ;;
esac
warmup_fail() { terminal_failure "$1" null true; }
. "$(dirname "$SCRIPT_PATH")/rootfs_prefix_warmup.sh"
resolve_rootfs_translator

ROOT_CANONICAL=$(realpath "$GAME_ROOT" 2>/dev/null) || terminal_failure game_root_unreadable null true
EXE_CANONICAL=$(realpath "$GAME_ROOT/$EXECUTABLE" 2>/dev/null) || terminal_failure game_executable_unreadable null true
if [ "$WORKING_DIRECTORY" = . ]; then
    WORK_CANONICAL=$ROOT_CANONICAL
else
    WORK_CANONICAL=$(realpath "$GAME_ROOT/$WORKING_DIRECTORY" 2>/dev/null) || terminal_failure game_workdir_unreadable null true
fi
case "$EXE_CANONICAL/" in "$ROOT_CANONICAL"/*) ;; *) terminal_failure game_executable_outside_root ;; esac
case "$WORK_CANONICAL/" in "$ROOT_CANONICAL"/*) ;; *) terminal_failure game_workdir_outside_root ;; esac
[ -f "$EXE_CANONICAL" ] || terminal_failure game_executable_unreadable null true
[ -d "$WORK_CANONICAL" ] || terminal_failure game_workdir_unreadable null true
[ -d "$ROOTFS_CANONICAL/mnt/games/game" ] && \
    [ -d "$ROOTFS_CANONICAL/mnt/games/prefix" ] || \
    terminal_failure rootfs_mountpoints_missing null true
GUEST_GAME_ROOT=/mnt/games/game
GUEST_PREFIX=/mnt/games/prefix
GUEST_EXECUTABLE="$GUEST_GAME_ROOT/$EXECUTABLE"
GUEST_LOCALE=C.UTF-8
if [ "$WORKING_DIRECTORY" = . ]; then
    GUEST_WORKING_DIRECTORY=$GUEST_GAME_ROOT
else
    GUEST_WORKING_DIRECTORY="$GUEST_GAME_ROOT/$WORKING_DIRECTORY"
fi

mkdir "$LOCK_PATH" 2>/dev/null || terminal_failure launch_task_locked null true
printf '%s\n' "$$" > "$LOCK_PATH/pid"
TASK_LOCK_OWNED=1

emit RUNNING PREPARING_PREFIX 25 null '' false preparing_prefix
export WINEPREFIX="$GUEST_PREFIX"
export DISPLAY=:0
export PULSE_SERVER=127.0.0.1
# Xorg runs on the Termux host while the GLIBC client sees its tmpfs at /tmp.
export TERMUX_VULKAN_BROKER_SOCKET="$TERMUX_FILES_DIR/usr/tmp/.vortek/V0"
export RESOLUTION
export GAMES_RUNTIME_PACKAGE="$WINE_PACKAGE"
export GAMES_DX_WRAPPER="$DX_WRAPPER"
# Both translator paths are box64-derived (Hangover's bundled WowBox64 is box64's own dynarec
# compiled in -- see the "[BOX64] WowBox64 ..." banner either path prints at startup), so
# BOX64_PROFILE applies regardless of which one is active. box64 interprets this itself; we only
# need to translate our own STABILITY/INTERMEDIATE/PERFORMANCE preset into its vocabulary.
case "$BOX64_PRESET" in
    STABILITY) export BOX64_PROFILE=safest ;;
    PERFORMANCE) export BOX64_PROFILE=fastest ;;
    *) export BOX64_PROFILE=default ;;
esac
# wine's esync/fsync backends need working POSIX shared memory (shm_open under /dev/shm) and/or
# futex2, neither reliably available inside this proot rootfs -- esync_init fails outright
# ("shm_open: No such file or directory") rather than degrading gracefully. Force the plain
# server-side synchronization wine falls back to without these, which does work under proot.
export WINEESYNC=0
export WINEFSYNC=0
unset LD_PRELOAD

run_rootfs_command() {
    if [ "$GUEST_PULSE_SERVER" = 1 ]; then
        "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -0 \
            -r "$ROOTFS_CANONICAL" \
            -b /dev -b /proc -b /sys \
            -b "$TERMUX_FILES_DIR/usr/tmp:/tmp" \
            -b "$HOME_PATH:/root" \
            -b "$ROOT_CANONICAL:$GUEST_GAME_ROOT" \
            -b "$PREFIX_PATH:$GUEST_PREFIX" \
            -w "$GUEST_WORKING_DIRECTORY" \
            /usr/bin/env -u FONTCONFIG_PATH -u FONTCONFIG_FILE -u FONTCONFIG_SYSROOT \
            HOME=/root USER=root LOGNAME=root LANG="$GUEST_LOCALE" LC_ALL="$GUEST_LOCALE" \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            FONTCONFIG_PATH=/etc/fonts FONTCONFIG_FILE=/etc/fonts/fonts.conf \
            XDG_DATA_DIRS=/usr/local/share:/usr/share DISPLAY=:0 PULSE_SERVER=127.0.0.1 \
            WINEPREFIX="$GUEST_PREFIX" \
            "$@"
    else
        "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -0 \
            -r "$ROOTFS_CANONICAL" \
            -b /dev -b /proc -b /sys \
            -b "$TERMUX_FILES_DIR/usr/tmp:/tmp" \
            -b "$HOME_PATH:/root" \
            -b "$ROOT_CANONICAL:$GUEST_GAME_ROOT" \
            -b "$PREFIX_PATH:$GUEST_PREFIX" \
            -w "$GUEST_WORKING_DIRECTORY" \
            /usr/bin/env -u PULSE_SERVER -u FONTCONFIG_PATH -u FONTCONFIG_FILE \
            -u FONTCONFIG_SYSROOT HOME=/root USER=root LOGNAME=root LANG="$GUEST_LOCALE" \
            LC_ALL="$GUEST_LOCALE" \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            FONTCONFIG_PATH=/etc/fonts FONTCONFIG_FILE=/etc/fonts/fonts.conf \
            XDG_DATA_DIRS=/usr/local/share:/usr/share DISPLAY=:0 WINEPREFIX="$GUEST_PREFIX" \
            "$@"
    fi
}

run_rootfs_wine() {
    if [ "$GUEST_COMMAND" = /usr/bin/wine ]; then
        run_rootfs_command "$GUEST_WINE" "$@"
    else
        run_rootfs_command "$GUEST_COMMAND" "$GUEST_WINE" "$@"
    fi
}

apply_rootfs_game_options() {
    case "${GAMES_SHOW_FPS:-0}" in 1) export GALLIUM_HUD=fps ;; esac
    case "${GAMES_WINE_DPI:-}" in
        96|120|144|192) run_rootfs_wine reg add 'HKCU\Control Panel\Desktop' /v LogPixels \
            /t REG_DWORD /d "$GAMES_WINE_DPI" /f >> "$LOG_PATH" 2>&1 || true ;;
    esac
    case "${GAMES_WINE_FONT:-}" in
        Tahoma|Arial|'Segoe UI') run_rootfs_wine reg add \
            'HKCU\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes' \
            /v 'MS Shell Dlg' /t REG_SZ /d "$GAMES_WINE_FONT" /f >> "$LOG_PATH" 2>&1 || true ;;
    esac
    case "${GAMES_WINE_BACKGROUND:-}" in
        '') ;;
        *) run_rootfs_wine reg add 'HKCU\Control Panel\Desktop' /v Wallpaper /t REG_SZ \
            /d "$GAMES_WINE_BACKGROUND" /f >> "$LOG_PATH" 2>&1 || true ;;
    esac
    case "${GAMES_WINE_THEME:-}" in
        light|dark) run_rootfs_wine reg add \
            'HKCU\Software\Microsoft\Windows\CurrentVersion\ThemeManager' /v AppsUseLightTheme \
            /t REG_DWORD /d "$( [ "$GAMES_WINE_THEME" = light ] && echo 1 || echo 0 )" \
            /f >> "$LOG_PATH" 2>&1 || true ;;
    esac
    case "${GAMES_MOUSE_WARP:-}" in
        disable|force) run_rootfs_wine reg add 'HKCU\Software\Wine\X11 Driver' \
            /v MouseWarpOverride /t REG_SZ /d "$GAMES_MOUSE_WARP" /f >> "$LOG_PATH" 2>&1 || true ;;
    esac
    case "${GAMES_WINDOWS_VERSION:-}" in
        'Windows 7'|'Windows 10'|'Windows 11') run_rootfs_wine reg add 'HKCU\Software\Wine' \
            /v Version /t REG_SZ /d "$GAMES_WINDOWS_VERSION" /f >> "$LOG_PATH" 2>&1 || true ;;
    esac
    for drive in D E; do
        eval target='${GAMES_DRIVE_'$drive':-}'
        case "$target" in ''|*'..'*) continue ;; esac
        [ -d "$target" ] || continue
        rm -f "$PREFIX_PATH/dosdevices/$(printf '%s' "$drive" | tr A-Z a-z):" 2>/dev/null || true
        ln -s "$target" "$PREFIX_PATH/dosdevices/$(printf '%s' "$drive" | tr A-Z a-z):" || true
    done
}

emit RUNNING STARTING_DISPLAY 45 null '' false starting_display
case "$GRAPHICS_DRIVER" in
    rootfs-virgl-mesa)
        export GALLIUM_DRIVER=virpipe
        VIRGL_SERVER="$TERMUX_FILES_DIR/usr/glibc/opt/virgl/libvirgl_test_server.so"
        [ -x "$VIRGL_SERVER" ] || terminal_failure virgl_server_missing null true
        TMPDIR="$TERMUX_FILES_DIR/usr/tmp" "$VIRGL_SERVER" >> "$LOG_PATH" 2>&1 &
        VIRGL_PID=$!
        ;;
    rootfs-llvmpipe)
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
        export LP_NUM_THREADS=${LP_NUM_THREADS:-4}
        ;;
    rootfs-turnip)
        [ -f "$ROOTFS_CANONICAL/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" ] || \
            terminal_failure rootfs_turnip_icd_missing null true
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        ;;
    *) terminal_failure renderer_unsupported null true ;;
esac
command -v termux-x11 >/dev/null 2>&1 || terminal_failure termux_x11_missing null true
termux-x11 :0 >> "$LOG_PATH" 2>&1 &
DISPLAY_PID=$!

emit RUNNING STARTING_AUDIO 60 null '' false starting_audio
GUEST_PULSE_SERVER=0
case "$AUDIO_DRIVER" in
    pulseaudio)
        unset PULSE_SERVER
        HOST_PULSEAUDIO="$TERMUX_FILES_DIR/usr/bin/pulseaudio"
        HOST_PKG="$TERMUX_FILES_DIR/usr/bin/pkg"
        if [ ! -x "$HOST_PULSEAUDIO" ]; then
            printf '%s\n' 'PulseAudio is missing from the Termux prefix; installing the shared host audio server.' \
                >> "$LOG_PATH"
            if [ -x "$HOST_PKG" ]; then
                DEBIAN_FRONTEND=noninteractive "$HOST_PKG" install -y \
                    -o Dpkg::Options::=--force-confdef \
                    -o Dpkg::Options::=--force-confold \
                    pulseaudio >> "$LOG_PATH" 2>&1 || \
                    printf '%s\n' 'PulseAudio installation failed; continuing without audio.' >> "$LOG_PATH"
            else
                printf '%s\n' 'Termux pkg is unavailable; continuing without audio.' >> "$LOG_PATH"
            fi
        fi
        if [ ! -x "$HOST_PULSEAUDIO" ]; then
            printf '%s\n' 'PulseAudio is unavailable in the Termux prefix; continuing without audio.' \
                >> "$LOG_PATH"
        elif ! "$HOST_PULSEAUDIO" --check >/dev/null 2>&1; then
            "$HOST_PULSEAUDIO" --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
                --exit-idle-time=-1 >> "$LOG_PATH" 2>&1 || \
                printf '%s\n' 'PulseAudio could not start; continuing without audio.' >> "$LOG_PATH"
            if "$HOST_PULSEAUDIO" --check >/dev/null 2>&1; then AUDIO_OWNED=1; fi
        fi
        if [ -x "$HOST_PULSEAUDIO" ] && "$HOST_PULSEAUDIO" --check >/dev/null 2>&1; then
            export PULSE_SERVER=127.0.0.1
            GUEST_PULSE_SERVER=1
        else
            unset PULSE_SERVER
        fi
        ;;
    *) terminal_failure rootfs_audio_driver_missing null true ;;
esac

warmup_rootfs_prefix

# English uses the always-present C.UTF-8 locale; Chinese uses the locale
# setuped above. This remains a game-level override.
case "${GAMES_LOCALE:-}" in
    en_US.UTF-8|en_US.utf8) GUEST_LOCALE=C.UTF-8 ;;
    zh_CN.UTF-8|zh_CN.utf8) GUEST_LOCALE=zh_CN.UTF-8 ;;
esac

case "$DX_WRAPPER" in
    rootfs-dxvk-*)
        # The dxWrapper value doubles as the install dir name set up by setup-container.sh.
        DXVK_SYSTEM32="$ROOTFS_CANONICAL/opt/games-runtime/$DX_WRAPPER/system32"
        DXVK_SYSWOW64="$ROOTFS_CANONICAL/opt/games-runtime/$DX_WRAPPER/syswow64"
        [ -d "$DXVK_SYSTEM32" ] && [ -d "$DXVK_SYSWOW64" ] || \
            terminal_failure rootfs_dxvk_payload_missing null true
        mkdir -p "$PREFIX_PATH/drive_c/windows/system32" "$PREFIX_PATH/drive_c/windows/syswow64"
        DXVK_FILE_COUNT=0
        for source in "$DXVK_SYSTEM32"/*.dll; do
            [ -f "$source" ] || continue
            cp "$source" "$PREFIX_PATH/drive_c/windows/system32/" || terminal_failure rootfs_dxvk_install_failed
            DXVK_FILE_COUNT=$((DXVK_FILE_COUNT + 1))
        done
        for source in "$DXVK_SYSWOW64"/*.dll; do
            [ -f "$source" ] || continue
            cp "$source" "$PREFIX_PATH/drive_c/windows/syswow64/" || terminal_failure rootfs_dxvk_install_failed
            DXVK_FILE_COUNT=$((DXVK_FILE_COUNT + 1))
        done
        [ "$DXVK_FILE_COUNT" -gt 0 ] || terminal_failure rootfs_dxvk_payload_missing null true
        export WINEDLLOVERRIDES='d3d8,d3d9,d3d10core,d3d11,dxgi=n,b'
        ;;
    rootfs-wined3d) unset WINEDLLOVERRIDES ;;
    *) terminal_failure rootfs_dx_wrapper_missing null true ;;
esac

apply_rootfs_game_options
[ -n "${GAMES_DLL_D3D:-}" ] && export WINEDLLOVERRIDES="d3d8,d3d9,d3d10core,d3d11,dxgi=${GAMES_DLL_D3D}"
[ -n "${GAMES_DLL_DSOUND:-}" ] && export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:+$WINEDLLOVERRIDES;}dsound=${GAMES_DLL_DSOUND}"
[ -n "${GAMES_DLL_DMUSIC:-}" ] && export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:+$WINEDLLOVERRIDES;}dmusic=${GAMES_DLL_DMUSIC}"
[ -n "${GAMES_DLL_DSHOW:-}" ] && export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:+$WINEDLLOVERRIDES;}quartz=${GAMES_DLL_DSHOW}"
[ -n "${GAMES_DLL_DPLAY:-}" ] && export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:+$WINEDLLOVERRIDES;}dplayx=${GAMES_DLL_DPLAY}"

[ ! -e "$CANCEL_PATH" ] || CANCELLED=1
if [ "$CANCELLED" = 1 ]; then
    emit CANCELLED COMPLETE 100 null cancelled false cancelled
    exit 0
fi

emit RUNNING STARTING_GAME 80 null '' false starting_game
# A RootFS session deliberately has no standalone Linux window manager.  Wine's
# virtual desktop supplies the desktop surface and window decoration instead,
# while keeping every game window inside the LaunchSpec resolution.
printf 'Launching Wine virtual desktop at %s\n' "$RESOLUTION" >> "$LOG_PATH"
if [ "$GUEST_COMMAND" = /usr/bin/wine ]; then
    set -- explorer "/desktop=shell,$RESOLUTION" "$GUEST_EXECUTABLE" "$@"
else
    set -- "$GUEST_WINE" explorer "/desktop=shell,$RESOLUTION" "$GUEST_EXECUTABLE" "$@"
fi
GAMES_EFFECTIVE_CPU_CORES="${GAMES_CPU_CORES:-${GAMES_CPU_CORES_32:-}}"
if [ -n "$GAMES_EFFECTIVE_CPU_CORES" ]; then
    case "$GAMES_EFFECTIVE_CPU_CORES" in *[!0-9,-]*|'') terminal_failure invalid_cpu_affinity ;; esac
    # "$@" at this point is already the correct guest-side command line built above (which already
    # includes GUEST_WINE for the box64/fex case, and omits it for hangover) -- taskset just needs
    # GUEST_COMMAND (the actual binary to exec: wine itself for hangover, box64/FEXInterpreter for
    # the standalone-translator paths) prepended in front of it. Do not special-case GUEST_WINE
    # here too: that previously substituted an *empty* string as the exec target for hangover
    # (GUEST_WINE is intentionally blank there), so taskset always failed with "No such file or
    # directory" whenever a CPU affinity was set together with the hangover translator.
    set -- -c "$GAMES_EFFECTIVE_CPU_CORES" "$GUEST_COMMAND" "$@"
    run_rootfs_command /usr/bin/taskset "$@" >> "$LOG_PATH" 2>&1 &
else
    run_rootfs_command "$GUEST_COMMAND" "$@" >> "$LOG_PATH" 2>&1 &
fi
PROOT_PID=$!
emit RUNNING WAITING_FIRST_FRAME 90 null '' false waiting_first_frame

STARTED_AT=$(date +%s)
while kill -0 "$PROOT_PID" 2>/dev/null; do
    if [ -e "$CANCEL_PATH" ]; then
        CANCELLED=1
        kill "$PROOT_PID" 2>/dev/null || true
        break
    fi
    now=$(date +%s)
    if [ $((now - STARTED_AT)) -ge "$TIMEOUT_SECONDS" ]; then
        kill "$PROOT_PID" 2>/dev/null || true
        wait "$PROOT_PID" 2>/dev/null || true
        PROOT_PID=
        terminal_failure launch_timeout null true
    fi
    sleep 1
done

wait "$PROOT_PID"
EXIT_CODE=$?
PROOT_PID=
emit RUNNING CLEANING 95 null '' false cleaning
if [ "$CANCELLED" = 1 ] || [ -e "$CANCEL_PATH" ]; then
    emit CANCELLED COMPLETE 100 null cancelled false cancelled
elif [ "$EXIT_CODE" -eq 0 ]; then
    emit SUCCEEDED COMPLETE 100 0 '' false exited
else
    terminal_failure wine_exit_nonzero "$EXIT_CODE" true
fi
