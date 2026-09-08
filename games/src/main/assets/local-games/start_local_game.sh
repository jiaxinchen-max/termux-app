#!/bin/sh
# Local-game launcher. Input is a private Base64 line-protocol LaunchSpec.

set -u

SPEC_PATH=${1:?Usage: start_local_game.sh <launch-spec>}
SCRIPT_PATH=$(realpath "$0" 2>/dev/null) || {
    printf '%s\n' launch_script_unreadable >&2
    exit 2
}
SCRIPT_DIR=$(dirname "$SCRIPT_PATH")
TERMUX_FILES_DIR=$(dirname "$(dirname "$(dirname "$SCRIPT_PATH")")")
GAMES_PRIVATE_ROOT="$TERMUX_FILES_DIR/games"
TERMUX_BOX_GAME_START="$SCRIPT_DIR/start_termux_box_game.sh"
SEQUENCE=0
RUNTIME_PID=
LOG_TAIL_PID=
TASK_LOCK_OWNED=0
CANCELLED=0

decode_text() {
    printf '%s' "$1" | base64 -d
}

fail_before_events() {
    printf '%s\n' "$1" >&2
    exit 2
}

[ -f "$SPEC_PATH" ] || fail_before_events launch_spec_missing
[ -f "$TERMUX_BOX_GAME_START" ] || fail_before_events termux_box_game_launcher_missing

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
RUNTIME_TRANSLATOR=box64
INPUT_PROFILE_ID=xinput
INPUT_PROFILE_SEEN=0
LAUNCH_EXECUTION_MODE=app_shell
LAUNCH_EXECUTION_MODE_SEEN=0
RUNTIME_BACKEND_TYPE=glibc_termux_box
RUNTIME_BACKEND_SEEN=0
ROOTFS_PACKAGE=
ROOTFS_PACKAGE_SEEN=0
RUNTIME_ROOT_PATH=
RUNTIME_ROOT_PATH_SEEN=0
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
INHERITED_ENV_KEYS=
set --

while IFS= read -r line || [ -n "$line" ]; do
    key=${line%%=*}
    value=${line#*=}
    [ "$key" != "$line" ] || fail_before_events invalid_launch_spec_line
    case "$key" in
        schemaVersion)
            [ "$value" = 1 ] || [ "$value" = 2 ] || [ "$value" = 3 ] || [ "$value" = 4 ] || fail_before_events unsupported_launch_spec_schema
            SPEC_SCHEMA=$value
            ;;
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
        inputProfileId)
            INPUT_PROFILE_ID=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            INPUT_PROFILE_SEEN=1
            ;;
        launchExecutionMode)
            LAUNCH_EXECUTION_MODE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            LAUNCH_EXECUTION_MODE_SEEN=1
            ;;
        runtimeBackendType)
            RUNTIME_BACKEND_TYPE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            RUNTIME_BACKEND_SEEN=1
            ;;
        rootfsPackage)
            ROOTFS_PACKAGE=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            ROOTFS_PACKAGE_SEEN=1
            ;;
        runtimeRootPath)
            RUNTIME_ROOT_PATH=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            RUNTIME_ROOT_PATH_SEEN=1
            ;;
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
            case "$PENDING_ENV_KEY" in [A-Za-z_]*) ;; *) fail_before_events invalid_launch_environment_key ;; esac
            case "$PENDING_ENV_KEY" in
                ''|*[!A-Za-z0-9_]*) fail_before_events invalid_launch_environment_key ;;
            esac
            ;;
        environment.*.value)
            [ -n "$PENDING_ENV_KEY" ] || fail_before_events invalid_launch_environment_pair
            decoded=$(decode_text "$value") || fail_before_events invalid_launch_spec_base64
            export "$PENDING_ENV_KEY=$decoded"
            INHERITED_ENV_KEYS="${INHERITED_ENV_KEYS}${INHERITED_ENV_KEYS:+ }$PENDING_ENV_KEY"
            PENDING_ENV_KEY=
            ACTUAL_ENVIRONMENT_COUNT=$((ACTUAL_ENVIRONMENT_COUNT + 1))
            ;;
        *) fail_before_events unknown_launch_spec_field ;;
    esac
done < "$SPEC_PATH"

case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_task_id ;; esac
case "$SPEC_SCHEMA:$INPUT_PROFILE_SEEN:$LAUNCH_EXECUTION_MODE_SEEN:$RUNTIME_BACKEND_SEEN:$ROOTFS_PACKAGE_SEEN:$RUNTIME_ROOT_PATH_SEEN" in
    1:0:0:0:0:0|2:1:0:0:0:0|3:1:1:0:0:0|4:1:1:1:1:1) ;;
    *) fail_before_events missing_launch_spec_version_field ;;
esac
[ "$RUNTIME_BACKEND_TYPE" = glibc_termux_box ] || fail_before_events runtime_backend_script_mismatch
[ -z "$ROOTFS_PACKAGE" ] || fail_before_events unexpected_rootfs_package
[ -z "$RUNTIME_ROOT_PATH" ] || fail_before_events unexpected_runtime_root_path
case "$GAME_ID" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_game_id ;; esac
case "$ARGUMENT_COUNT" in ''|*[!0-9]*) fail_before_events invalid_launch_spec_number ;; esac
case "$ENVIRONMENT_COUNT" in ''|*[!0-9]*) fail_before_events invalid_launch_spec_number ;; esac
case "$TIMEOUT_SECONDS" in ''|*[!0-9]*) fail_before_events invalid_launch_spec_number ;; esac
[ "$ACTUAL_ARGUMENT_COUNT" -eq "$ARGUMENT_COUNT" ] || fail_before_events launch_argument_count_mismatch
[ "$ACTUAL_ENVIRONMENT_COUNT" -eq "$ENVIRONMENT_COUNT" ] || fail_before_events launch_environment_count_mismatch
[ -z "$PENDING_ENV_KEY" ] || fail_before_events invalid_launch_environment_pair
[ "$ARGUMENT_COUNT" -le 256 ] || fail_before_events launch_argument_count_out_of_range
[ "$ENVIRONMENT_COUNT" -le 256 ] || fail_before_events launch_environment_count_out_of_range
[ "$TIMEOUT_SECONDS" -ge 30 ] && [ "$TIMEOUT_SECONDS" -le 86400 ] || fail_before_events launch_timeout_out_of_range
case "$WINE_PACKAGE" in ''|*[!A-Za-z0-9._-]*) fail_before_events invalid_wine_package ;; esac
case "$INPUT_PROFILE_ID" in
    xinput|dinput) ;;
    xinput:*|dinput:*)
        profile_number=${INPUT_PROFILE_ID#*:}
        case "$profile_number" in ''|*[!0-9]*|0*) fail_before_events invalid_input_profile ;; esac
        [ "$profile_number" -le 999999 ] || fail_before_events invalid_input_profile
        ;;
    *) fail_before_events invalid_input_profile ;;
esac
case "$LAUNCH_EXECUTION_MODE" in app_shell|terminal_session) ;; *) fail_before_events invalid_launch_execution_mode ;; esac
case "${GAMES_RUNTIME_TRANSLATOR:-box64}" in
    hangover|box64|fex) RUNTIME_TRANSLATOR=${GAMES_RUNTIME_TRANSLATOR:-box64} ;;
    *) fail_before_events runtime_translator_unsupported ;;
esac
validate_private_path() {
    case "$1" in
        "$GAMES_PRIVATE_ROOT"/*) ;;
        *) fail_before_events launch_private_path_required ;;
    esac
    case "$1" in *'/../'*|*/..|*/./*) fail_before_events invalid_launch_private_path ;; esac
}
validate_private_path "$PREFIX_PATH"
validate_private_path "$EVENT_PATH"
validate_private_path "$LOG_PATH"
validate_private_path "$LOCK_PATH"
validate_private_path "$CANCEL_PATH"

mkdir -p "$(dirname "$EVENT_PATH")" "$(dirname "$LOG_PATH")" \
    "$(dirname "$LOCK_PATH")" "$(dirname "$CANCEL_PATH")"
: > "$EVENT_PATH"
: > "$LOG_PATH"
if [ "$LAUNCH_EXECUTION_MODE" = terminal_session ]; then
    printf 'Local Games task: %s\nLog: %s\n\n' "$TASK_ID" "$LOG_PATH"
    tail -n +1 -f "$LOG_PATH" &
    LOG_TAIL_PID=$!
fi
exec >> "$LOG_PATH" 2>&1
PS4='+ '
set -x
printf '%s\n' 'Verbose command tracing enabled.'

emit() {
    SEQUENCE=$((SEQUENCE + 1))
    state=$1
    stage=$2
    progress=$3
    exit_code=$4
    error_code=$5
    recoverable=$6
    message=$7
    now=$(date +%s)
    printf '{"schemaVersion":1,"taskId":"%s","sequence":%s,"state":"%s","stage":"%s","progress":%s,"timestamp":%s000,"pid":%s,"exitCode":%s,"errorCode":"%s","recoverable":%s,"message":"%s","logRef":"%s"}\n' \
        "$TASK_ID" "$SEQUENCE" "$state" "$stage" "$progress" "$now" "$$" \
        "$exit_code" "$error_code" "$recoverable" "$message" "$LOG_PATH" >> "$EVENT_PATH"
}

terminal_failure() {
    emit FAILED COMPLETE 100 "${2:-null}" "$1" "${3:-false}" "$1"
    exit 1
}

release_lock() {
    lock=$1
    owned=$2
    if [ "$owned" = 1 ] && [ -f "$lock/pid" ] && [ "$(sed -n '1p' "$lock/pid" 2>/dev/null)" = "$$" ]; then
        rm -f "$lock/pid"
        rm -f "$lock/termux-box-game.conf"
        rmdir "$lock" 2>/dev/null || true
    fi
}

stop_owned_processes() {
    [ -z "$RUNTIME_PID" ] || kill "$RUNTIME_PID" 2>/dev/null || true
    [ -z "$RUNTIME_PID" ] || wait "$RUNTIME_PID" 2>/dev/null || true
}

cleanup() {
    stop_owned_processes
    [ -z "$LOG_TAIL_PID" ] || kill "$LOG_TAIL_PID" 2>/dev/null || true
    [ -z "$LOG_TAIL_PID" ] || wait "$LOG_TAIL_PID" 2>/dev/null || true
    release_lock "$LOCK_PATH" "$TASK_LOCK_OWNED"
}

on_signal() {
    CANCELLED=1
    : > "$CANCEL_PATH"
    [ -z "$RUNTIME_PID" ] || kill "$RUNTIME_PID" 2>/dev/null || true
}

trap on_signal INT TERM
trap cleanup EXIT

emit RUNNING PRECHECK 5 null '' false precheck

case "$GAME_ROOT:$PREFIX_PATH:$EVENT_PATH:$LOG_PATH:$LOCK_PATH:$CANCEL_PATH" in
    *"\n"*|*"\r"*) terminal_failure invalid_launch_path ;;
esac
validate_relative_path() {
    case "$1" in
        /*|*../*|*/../*|..|*\\*|*:*) terminal_failure invalid_game_relative_path ;;
    esac
}
validate_relative_path "$EXECUTABLE"
validate_relative_path "$WORKING_DIRECTORY"
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

mkdir "$LOCK_PATH" 2>/dev/null || terminal_failure launch_task_locked null true
printf '%s\n' "$$" > "$LOCK_PATH/pid"
TASK_LOCK_OWNED=1

emit RUNNING PREPARING_PREFIX 25 null '' false preparing_prefix
[ -e "$PREFIX_PATH/.termux-box-bootstrap-done" ] || \
    terminal_failure prefix_provision_required null true
if [ -f "$PREFIX_PATH/.termux-box-wine-package" ]; then
    PREFIX_WINE_PACKAGE=$(sed -n '1p' "$PREFIX_PATH/.termux-box-wine-package")
    [ "$PREFIX_WINE_PACKAGE" = "$WINE_PACKAGE" ] || \
        terminal_failure prefix_runtime_mismatch null true
fi

write_property() {
    property_value=$(printf '%s' "$2" | sed "s/'/'\\\\''/g")
    printf "%s='%s'\n" "$1" "$property_value"
}
case "$INPUT_PROFILE_ID" in dinput*) GAMEPAD_MAPPER=0 ;; *) GAMEPAD_MAPPER=1 ;; esac
TERMUX_BOX_GAME_CONF="$LOCK_PATH/termux-box-game.conf"
{
    CONTAINER_ID=$(basename "$(dirname "$PREFIX_PATH")")
    write_property TERMUX_BOX_CONTAINER_NAME "$CONTAINER_ID"
    write_property TERMUX_BOX_CONTAINER_DIR "$(dirname "$PREFIX_PATH")"
    write_property TERMUX_BOX_CONTAINER_PREFIX "$PREFIX_PATH"
    write_property TERMUX_BOX_WINE_PACKAGE "$WINE_PACKAGE"
    write_property TERMUX_BOX_RESOLUTION "$RESOLUTION"
    write_property TERMUX_BOX_BOX64_PRESET "$BOX64_PRESET"
    write_property TERMUX_BOX_TRANSLATOR "$RUNTIME_TRANSLATOR"
    write_property TERMUX_BOX_GRAPHICS_DRIVER "$GRAPHICS_DRIVER"
    write_property TERMUX_BOX_DXWRAPPER "$DX_WRAPPER"
    write_property TERMUX_BOX_AUDIO_DRIVER "$AUDIO_DRIVER"
    write_property TERMUX_BOX_GAMEPAD_MAPPER "$GAMEPAD_MAPPER"
    write_property TERMUX_BOX_GAME_ID "$GAME_ID"
    write_property TERMUX_BOX_GAME_ROOT "$ROOT_CANONICAL"
    write_property TERMUX_BOX_GAME_WORKDIR "$WORK_CANONICAL"
    write_property TERMUX_BOX_GAME_EXECUTABLE "$EXECUTABLE"
    write_property TERMUX_BOX_LAUNCH_LOG "$LOG_PATH"
    write_property TERMUX_BOX_INHERITED_ENV_KEYS "$INHERITED_ENV_KEYS"
} > "$TERMUX_BOX_GAME_CONF" || terminal_failure launch_config_write_failed null true

emit RUNNING STARTING_DISPLAY 45 null '' false starting_display
emit RUNNING STARTING_AUDIO 60 null '' false starting_audio
[ ! -e "$CANCEL_PATH" ] || CANCELLED=1
if [ "$CANCELLED" = 1 ]; then
    emit CANCELLED COMPLETE 100 null cancelled false cancelled
    exit 0
fi

emit RUNNING STARTING_GAME 80 null '' false starting_game
sh "$TERMUX_BOX_GAME_START" "$TERMUX_BOX_GAME_CONF" "$@" >> "$LOG_PATH" 2>&1 &
RUNTIME_PID=$!
emit RUNNING WAITING_FIRST_FRAME 90 null '' false waiting_first_frame

STARTED_AT=$(date +%s)
while kill -0 "$RUNTIME_PID" 2>/dev/null; do
    if [ -e "$CANCEL_PATH" ]; then
        CANCELLED=1
        kill "$RUNTIME_PID" 2>/dev/null || true
        break
    fi
    now=$(date +%s)
    if [ $((now - STARTED_AT)) -ge "$TIMEOUT_SECONDS" ]; then
        kill "$RUNTIME_PID" 2>/dev/null || true
        wait "$RUNTIME_PID" 2>/dev/null || true
        RUNTIME_PID=
        terminal_failure launch_timeout null true
    fi
    sleep 1
done

if wait "$RUNTIME_PID"; then EXIT_CODE=0; else EXIT_CODE=$?; fi
RUNTIME_PID=
emit RUNNING CLEANING 95 null '' false cleaning
if [ "$CANCELLED" = 1 ] || [ -e "$CANCEL_PATH" ]; then
    emit CANCELLED COMPLETE 100 null cancelled false cancelled
elif [ "$EXIT_CODE" -eq 0 ]; then
    emit SUCCEEDED COMPLETE 100 0 '' false exited
else
    terminal_failure wine_exit_nonzero "$EXIT_CODE" true
fi
