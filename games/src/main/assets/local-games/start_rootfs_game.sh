#!/bin/sh
# RootFS/PRoot game launcher. Input is a private schema-v4 Base64 LaunchSpec.

set -u

SPEC_PATH=${1:?Usage: start_rootfs_game.sh <launch-spec>}
SCRIPT_PATH=$(realpath "$0" 2>/dev/null) || {
    printf '%s\n' launch_script_unreadable >&2
    exit 2
}
TERMUX_FILES_DIR=$(dirname "$(dirname "$(dirname "$SCRIPT_PATH")")")
GAMES_PRIVATE_ROOT="$TERMUX_FILES_DIR/games"
PROOT_DISTRO_CONTAINERS="$TERMUX_FILES_DIR/usr/var/lib/proot-distro/containers"
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

[ "$SPEC_SCHEMA" = 4 ] || fail_before_events unsupported_launch_spec_schema
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
validate_private_path "$EVENT_PATH"
validate_private_path "$LOG_PATH"
validate_private_path "$LOCK_PATH"
validate_private_path "$CANCEL_PATH"
case "$RUNTIME_ROOT_PATH" in "$PROOT_DISTRO_CONTAINERS"/*/rootfs) ;; *) fail_before_events rootfs_path_outside_termux_runtime ;; esac
RUNTIME_CONTAINER=${RUNTIME_ROOT_PATH#"$PROOT_DISTRO_CONTAINERS"/}
RUNTIME_CONTAINER=${RUNTIME_CONTAINER%/rootfs}
case "$RUNTIME_CONTAINER" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_rootfs_container ;; esac
[ "$RUNTIME_ROOT_PATH" = "$PROOT_DISTRO_CONTAINERS/$RUNTIME_CONTAINER/rootfs" ] || \
    fail_before_events rootfs_path_outside_termux_runtime

mkdir -p "$(dirname "$EVENT_PATH")" "$(dirname "$LOG_PATH")" \
    "$(dirname "$LOCK_PATH")" "$(dirname "$CANCEL_PATH")" "$PREFIX_PATH"
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
CONTAINERS_CANONICAL=$(realpath "$PROOT_DISTRO_CONTAINERS" 2>/dev/null) || terminal_failure proot_distro_store_unreadable null true
[ "$ROOTFS_CANONICAL" = "$CONTAINERS_CANONICAL/$RUNTIME_CONTAINER/rootfs" ] || \
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
[ "$GRAPHICS_DRIVER:$DX_WRAPPER" != rootfs-virgl-mesa:rootfs-dxvk ] || \
    terminal_failure runtime_combination_unsupported:virgl_dxvk
RUNTIME_TRANSLATOR=${GAMES_RUNTIME_TRANSLATOR:-}
[ -n "$RUNTIME_TRANSLATOR" ] || case "$WINE_PACKAGE" in hangover-*) RUNTIME_TRANSLATOR=hangover ;; *) RUNTIME_TRANSLATOR=box64 ;; esac
case "$RUNTIME_TRANSLATOR" in
    hangover)
        case "$WINE_PACKAGE" in hangover-*) ;; *) terminal_failure runtime_translator_package_mismatch null true ;; esac
        GUEST_COMMAND=/usr/bin/wine
        GUEST_WINEBOOT=/usr/bin/wineboot
        [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || terminal_failure rootfs_wine_missing null true
        [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || terminal_failure rootfs_wineboot_missing null true
        ;;
    box64)
        case "$WINE_PACKAGE" in box64-wine*) ;; *) terminal_failure runtime_translator_package_mismatch null true ;; esac
        GUEST_COMMAND=/usr/local/bin/box64
        GUEST_WINE=/opt/box64-wine/bin/wine
        GUEST_WINEBOOT=/opt/box64-wine/bin/wineboot
        [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || terminal_failure rootfs_box64_missing null true
        [ -x "$ROOTFS_CANONICAL$GUEST_WINE" ] || terminal_failure rootfs_wine_missing null true
        [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || terminal_failure rootfs_wineboot_missing null true
        ;;
    fex)
        case "$WINE_PACKAGE" in box64-wine*) ;; *) terminal_failure runtime_translator_package_mismatch null true ;; esac
        GUEST_COMMAND=/usr/bin/FEXInterpreter
        GUEST_WINE=/opt/box64-wine/bin/wine
        GUEST_WINEBOOT=/opt/box64-wine/bin/wineboot
        [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || terminal_failure rootfs_fex_missing null true
        [ -x "$ROOTFS_CANONICAL$GUEST_WINE" ] || terminal_failure rootfs_wine_missing null true
        [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || terminal_failure rootfs_wineboot_missing null true
        ;;
    *) terminal_failure runtime_translator_unsupported null true ;;
esac

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
export RESOLUTION
export GAMES_RUNTIME_PACKAGE="$WINE_PACKAGE"
export GAMES_DX_WRAPPER="$DX_WRAPPER"
unset LD_PRELOAD

run_rootfs_command() {
    if [ "$GUEST_PULSE_SERVER" = 1 ]; then
        "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -0 \
            -r "$ROOTFS_CANONICAL" \
            -b /dev -b /proc -b /sys \
            -b "$TERMUX_FILES_DIR/usr/tmp:/tmp" \
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

PREFIX_MARKER_DIR="$PREFIX_PATH/.games-runtime"
ROOTFS_LOCALE_MARKER="$PREFIX_MARKER_DIR/rootfs-zh-cn-locale"
EXPECTED_ROOTFS_LOCALE_MARKER="$RUNTIME_ROOT_PATH|zh_CN.UTF-8|v1"
CURRENT_ROOTFS_LOCALE_MARKER=
[ ! -f "$ROOTFS_LOCALE_MARKER" ] || \
    CURRENT_ROOTFS_LOCALE_MARKER=$(sed -n '1p' "$ROOTFS_LOCALE_MARKER" 2>/dev/null)
if [ "$CURRENT_ROOTFS_LOCALE_MARKER" != "$EXPECTED_ROOTFS_LOCALE_MARKER" ]; then
    printf '%s\n' 'Preparing zh_CN UTF-8 and GBK locales in the RootFS runtime.' >> "$LOG_PATH"
    if [ ! -x "$ROOTFS_CANONICAL/usr/sbin/locale-gen" ]; then
        run_rootfs_command /usr/bin/apt-get update >> "$LOG_PATH" 2>&1 && \
        run_rootfs_command /usr/bin/apt-get install -y --no-install-recommends locales \
            >> "$LOG_PATH" 2>&1 || \
            printf '%s\n' 'RootFS locale package installation failed.' >> "$LOG_PATH"
    fi
    if [ -x "$ROOTFS_CANONICAL/usr/sbin/locale-gen" ]; then
        run_rootfs_command /bin/sh -c \
            "sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' /etc/locale.gen && \
             sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen && \
             /usr/sbin/locale-gen" >> "$LOG_PATH" 2>&1 && \
        run_rootfs_command /bin/sh -c "locale -a | grep -qi '^zh_CN.utf8$'" \
            >> "$LOG_PATH" 2>&1
        if [ "$?" -eq 0 ]; then
            mkdir -p "$PREFIX_MARKER_DIR"
            printf '%s\n' "$EXPECTED_ROOTFS_LOCALE_MARKER" > "$ROOTFS_LOCALE_MARKER"
            GUEST_LOCALE=zh_CN.UTF-8
        else
            printf '%s\n' 'RootFS zh_CN locale generation failed; retaining C.UTF-8.' >> "$LOG_PATH"
        fi
    fi
else
    GUEST_LOCALE=zh_CN.UTF-8
fi

GUEST_CJK_FONT=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc
if [ ! -f "$ROOTFS_CANONICAL$GUEST_CJK_FONT" ]; then
    printf '%s\n' 'Installing Noto CJK fonts into the active RootFS runtime.' >> "$LOG_PATH"
    if ! run_rootfs_command /usr/bin/apt-get update >> "$LOG_PATH" 2>&1 || \
        ! run_rootfs_command /usr/bin/apt-get install -y --no-install-recommends \
            fontconfig fonts-noto-cjk >> "$LOG_PATH" 2>&1; then
        printf '%s\n' 'Noto CJK font installation failed; Wine may render CJK text as squares.' \
            >> "$LOG_PATH"
    else
        run_rootfs_command /usr/bin/fc-cache -f >> "$LOG_PATH" 2>&1 || true
    fi
fi

PREFIX_MARKER="$PREFIX_MARKER_DIR/runtime"
EXPECTED_PREFIX_MARKER="$RUNTIME_ROOT_PATH|$WINE_PACKAGE"
CURRENT_PREFIX_MARKER=
[ ! -f "$PREFIX_MARKER" ] || CURRENT_PREFIX_MARKER=$(sed -n '1p' "$PREFIX_MARKER" 2>/dev/null)
if [ "$CURRENT_PREFIX_MARKER" != "$EXPECTED_PREFIX_MARKER" ]; then
    if [ "$GUEST_COMMAND" = /usr/bin/wine ]; then
        run_rootfs_command "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1 &
    else
        run_rootfs_command "$GUEST_COMMAND" "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1 &
    fi
    PROOT_PID=$!
    PREFIX_STARTED_AT=$(date +%s)
    while kill -0 "$PROOT_PID" 2>/dev/null; do
        [ ! -e "$CANCEL_PATH" ] || { CANCELLED=1; kill "$PROOT_PID" 2>/dev/null || true; break; }
        now=$(date +%s)
        if [ $((now - PREFIX_STARTED_AT)) -ge 180 ]; then
            kill "$PROOT_PID" 2>/dev/null || true
            wait "$PROOT_PID" 2>/dev/null || true
            PROOT_PID=
            terminal_failure rootfs_prefix_initialization_timeout null true
        fi
        sleep 1
    done
    wait "$PROOT_PID"
    PREFIX_EXIT_CODE=$?
    PROOT_PID=
    [ "$CANCELLED" = 1 ] || [ "$PREFIX_EXIT_CODE" -eq 0 ] || \
        terminal_failure rootfs_prefix_initialization_failed "$PREFIX_EXIT_CODE" true
    if [ "$CANCELLED" = 0 ]; then
        mkdir -p "$PREFIX_MARKER_DIR"
        printf '%s\n' "$EXPECTED_PREFIX_MARKER" > "$PREFIX_MARKER"
    fi
fi

# Wine registers Linux fonts, but Hangover's `wine reg add` can report success
# without persisting the value.  Use the same .reg import path as Termux-box and
# also keep the CJK TTC files in the Windows font directory for applications
# that enumerate only C:\\Windows\\Fonts.
FONT_MARKER="$PREFIX_MARKER_DIR/cjk-fonts"
EXPECTED_FONT_MARKER="$RUNTIME_ROOT_PATH|$WINE_PACKAGE|Noto Sans CJK SC|v4"
FONT_LINK_REGISTRY_VALUE='"Tahoma"=hex(7):4e,00,6f,00,74,00,6f,00,53,00,61,00,6e,00,73,00,43,00,4a,00,4b,00,2d,00,52,00,65,00,67,00,75,00,6c,00,61,00,72,00,2e,00,74,00,74,00,63,00,2c,00,4e,00,6f,00,74,00,6f,00,20,00,53,00,61,00,6e,00,73,00,20,00,43,00,4a,00,4b,00,20,00,53,00,43,00,00,00,00,00'
CURRENT_FONT_MARKER=
[ ! -f "$FONT_MARKER" ] || CURRENT_FONT_MARKER=$(sed -n '1p' "$FONT_MARKER" 2>/dev/null)
if [ -f "$ROOTFS_CANONICAL$GUEST_CJK_FONT" ] && \
    [ "$CURRENT_FONT_MARKER" != "$EXPECTED_FONT_MARKER" ]; then
    FONT_DIRECTORY="$PREFIX_PATH/drive_c/windows/Fonts"
    FONT_REGISTRY_FILE="$PREFIX_MARKER_DIR/cjk-fonts.reg"
    GUEST_FONT_REGISTRY_FILE="$GUEST_PREFIX/.games-runtime/cjk-fonts.reg"
    mkdir -p "$FONT_DIRECTORY" "$PREFIX_MARKER_DIR"
    cp "$ROOTFS_CANONICAL/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc" \
        "$FONT_DIRECTORY/NotoSansCJK-Regular.ttc" && \
    cp "$ROOTFS_CANONICAL/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc" \
        "$FONT_DIRECTORY/NotoSansCJK-Bold.ttc" || \
        printf '%s\n' 'Failed to copy Noto CJK fonts into the Wine prefix.' >> "$LOG_PATH"
    {
        printf '%s\n' 'REGEDIT4'
        printf '\n'
        printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes]'
        printf '%s\n' '"MS Shell Dlg"="Tahoma"'
        printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
        printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
        printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
        printf '\n'
        printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontSubstitutes]'
        printf '%s\n' '"MS Shell Dlg"="Tahoma"'
        printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
        printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
        printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
        printf '\n'
        printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink]'
        printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
        printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '\n'
        printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink]'
        printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
        printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
    } > "$FONT_REGISTRY_FILE"
    # Hangover's regedit can exit successfully without flushing HKEY_LOCAL_MACHINE
    # when the prefix is bind-mounted through PRoot.  system.reg is Wine's durable
    # registry hive and is not in use until the game Wine server starts below.
    {
        printf '\n'
        printf '%s\n' '[Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes]'
        printf '%s\n' '"MS Shell Dlg"="Tahoma"'
        printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
        printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
        printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
        printf '\n'
        printf '%s\n' '[Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink]'
        printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
        printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '\n'
        printf '%s\n' '[Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes]'
        printf '%s\n' '"MS Shell Dlg"="Tahoma"'
        printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
        printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
        printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
        printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
        printf '\n'
        printf '%s\n' '[Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink]'
        printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
        printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
    } >> "$PREFIX_PATH/system.reg"
    if grep -F "$FONT_LINK_REGISTRY_VALUE" "$PREFIX_PATH/system.reg" \
        >/dev/null 2>&1; then
        mkdir -p "$PREFIX_MARKER_DIR"
        printf '%s\n' "$EXPECTED_FONT_MARKER" > "$FONT_MARKER"
    else
        printf '%s\n' 'Wine CJK FontLink registry setup failed.' >> "$LOG_PATH"
    fi
fi

case "$DX_WRAPPER" in
    rootfs-dxvk)
        DXVK_SYSTEM32="$ROOTFS_CANONICAL/opt/games-runtime/dxvk/system32"
        DXVK_SYSWOW64="$ROOTFS_CANONICAL/opt/games-runtime/dxvk/syswow64"
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
    set -- explorer "/desktop=games,$RESOLUTION" "$GUEST_EXECUTABLE" "$@"
else
    set -- "$GUEST_WINE" explorer "/desktop=games,$RESOLUTION" "$GUEST_EXECUTABLE" "$@"
fi
run_rootfs_command "$GUEST_COMMAND" "$@" >> "$LOG_PATH" 2>&1 &
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
