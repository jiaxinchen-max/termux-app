#!/bin/sh

set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SOURCE_SCRIPT="$PROJECT_ROOT/games/src/main/assets/local-games/start_local_game.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/games-launch-script.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
trap 'rm -rf "$TEST_ROOT"' EXIT

TERMUX_ROOT="$TEST_ROOT/termux files"
TERMUX_ROOT_ALIAS="$TEST_ROOT/termux-files-alias"
RUNTIME_DIR="$TERMUX_ROOT/games/runtime"
LAUNCH_SCRIPT="$TERMUX_ROOT_ALIAS/games/runtime/start_local_game.sh"
FAKE_BIN="$TEST_ROOT/fake-bin"
GAME_ROOT="$TEST_ROOT/Game Root \$special"
WORK_DIR="$GAME_ROOT/bin folder"
CAPTURE_FILE="$TEST_ROOT/captured-argv"
ENV_CAPTURE_FILE="$TEST_ROOT/captured-env"
AFFINITY_CAPTURE_FILE="$TEST_ROOT/captured-affinity"
SPEC_FILE="$TEST_ROOT/task.launchspec"
EVENT_FILE="$TERMUX_ROOT/games/launches/events/task events.jsonl"
LOG_FILE="$TERMUX_ROOT/games/launches/logs/task log.txt"
LOCK_PATH="$TERMUX_ROOT/games/launches/locks/task lock"
CANCEL_PATH="$TERMUX_ROOT/games/launches/cancel/task cancel"
PREFIX_PATH="$TERMUX_ROOT/games/prefixes/prefix path"
WINE_PACKAGE="wine-fake"

mkdir -p "$TERMUX_ROOT/usr/glibc/bin" "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin" \
    "$TERMUX_ROOT/usr/glibc/opt/conf/dynarec" \
    "$TERMUX_ROOT/usr/glibc/termux-box/run" "$RUNTIME_DIR" "$FAKE_BIN" "$WORK_DIR" \
    "$PREFIX_PATH"
touch "$PREFIX_PATH/.termux-box-bootstrap-done" "$WORK_DIR/it's game.exe"
printf '%s\n' "$WINE_PACKAGE" > "$PREFIX_PATH/.termux-box-wine-package"
ln -s "$TERMUX_ROOT" "$TERMUX_ROOT_ALIAS"

cp "$SOURCE_SCRIPT" "$RUNTIME_DIR/start_local_game.sh"
printf '#!/bin/sh\nexec "$@"\n' > "$TERMUX_ROOT/usr/glibc/bin/box64"
printf '#!/bin/sh\nif [ "${FAKE_WINE_WAIT:-0}" = 1 ]; then trap "exit 0" TERM INT; while :; do sleep 1; done; fi\n: > "$CAPTURE_FILE"\nfor value in "$@"; do printf "%%s" "$value" | base64 | tr -d "\\n" >> "$CAPTURE_FILE"; printf "\\n" >> "$CAPTURE_FILE"; done\nprintf "BOX64_LD_LIBRARY_PATH=%%s\\nDXVK_CONFIG_FILE=%%s\\nFONTCONFIG_PATH=%%s\\nDXVK_ASYNC=%%s\\nWINEESYNC=%%s\\nLC_ALL=%%s\\nMESA_VK_WSI_PRESENT_MODE=%%s\\n" "$BOX64_LD_LIBRARY_PATH" "$DXVK_CONFIG_FILE" "$FONTCONFIG_PATH" "$DXVK_ASYNC" "$WINEESYNC" "$LC_ALL" "$MESA_VK_WSI_PRESENT_MODE" > "$ENV_CAPTURE_FILE"\n' \
    > "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wine"
printf '#!/bin/sh\nexit 0\n' > "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wineserver"
printf '#!/bin/sh\ntrap "exit 0" TERM INT\nwhile :; do sleep 1; done\n' > "$FAKE_BIN/termux-x11"
printf '#!/bin/sh\n[ "$1" = _NPROCESSORS_ONLN ] || exit 64\nprintf "4\\n"\n' > "$FAKE_BIN/getconf"
printf '#!/bin/sh\n[ "$1" = -c ] || exit 64\ncores=$2\nshift 2\n[ "$cores" = 2-3 ] || exit 64\n[ "$1" = true ] && exit 0\nprintf "%%s\\n" "$cores" > "$AFFINITY_CAPTURE_FILE"\nexec "$@"\n' > "$FAKE_BIN/taskset"
printf '#!/bin/sh\n[ -z "${PULSE_SERVER+x}" ] || exit 64\nprintf "%%s\\n" "$*" >> "$PULSE_FAKE_CALLS"\ncase "$1" in\n  --check) [ -e "$PULSE_FAKE_STATE" ] ;;\n  --start) : > "$PULSE_FAKE_STATE" ;;\n  --kill) rm -f "$PULSE_FAKE_STATE" ;;\nesac\n' > "$FAKE_BIN/pulseaudio"
printf '#!/bin/sh\nstate=${FAKE_DATE_STATE:?}\nvalue=$(sed -n "1p" "$state" 2>/dev/null || true)\nvalue=${value:-1000}\nvalue=$((value + 31))\nprintf "%%s\\n" "$value" > "$state"\nprintf "%%s\\n" "$value"\n' > "$FAKE_BIN/date"
chmod 700 "$RUNTIME_DIR/start_local_game.sh" \
    "$TERMUX_ROOT/usr/glibc/bin/box64" "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wine" \
    "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wineserver" "$FAKE_BIN/termux-x11" \
    "$FAKE_BIN/getconf" "$FAKE_BIN/taskset" "$FAKE_BIN/pulseaudio" "$FAKE_BIN/date"

printf 'export WINEESYNC=1\nexport WINEESYNC_TERMUX=1\n' \
    > "$TERMUX_ROOT/usr/glibc/opt/conf/wineesync.conf"
printf 'export MESA_VK_WSI_PRESENT_MODE=mailbox\n' \
    > "$TERMUX_ROOT/usr/glibc/opt/conf/wsi_present.conf"
printf 'export PRIMARY_CORES=4-7\nexport SECONDARY_CORES=0-3\n' \
    > "$TERMUX_ROOT/usr/glibc/opt/conf/cores.conf"
printf 'en_US.utf8\n' > "$TERMUX_ROOT/usr/glibc/opt/locale.conf"

export PULSE_FAKE_STATE="$TEST_ROOT/pulse-state"
export PULSE_FAKE_CALLS="$TEST_ROOT/pulse-calls"
export ENV_CAPTURE_FILE
export AFFINITY_CAPTURE_FILE

encode() {
    printf '%s' "$1" | base64 | tr -d '\n'
}

{
    printf 'schemaVersion=3\n'
    printf 'taskId=%s\n' "$(encode task-1)"
    printf 'gameId=%s\n' "$(encode game-1)"
    printf 'gameRootPath=%s\n' "$(encode "$GAME_ROOT")"
    printf 'executable=%s\n' "$(encode "bin folder/it's game.exe")"
    printf 'workingDirectory=%s\n' "$(encode "bin folder")"
    printf 'prefixPath=%s\n' "$(encode "$PREFIX_PATH")"
    printf 'winePackage=%s\n' "$(encode "$WINE_PACKAGE")"
    printf 'graphicsDriver=%s\n' "$(encode Turnip)"
    printf 'dxWrapper=%s\n' "$(encode DXVK)"
    printf 'audioDriver=%s\n' "$(encode ALSA)"
    printf 'resolution=%s\n' "$(encode 1280x720)"
    printf 'box64Preset=%s\n' "$(encode INTERMEDIATE)"
    printf 'inputProfileId=%s\n' "$(encode dinput:3)"
    printf 'launchExecutionMode=%s\n' "$(encode terminal_session)"
    printf 'eventPath=%s\n' "$(encode "$EVENT_FILE")"
    printf 'logPath=%s\n' "$(encode "$LOG_FILE")"
    printf 'lockPath=%s\n' "$(encode "$LOCK_PATH")"
    printf 'cancelPath=%s\n' "$(encode "$CANCEL_PATH")"
    printf 'timeoutSeconds=600\n'
    printf 'argumentCount=4\n'
    printf 'argument.0=%s\n' "$(encode "hello world")"
    printf 'argument.1=%s\n' "$(encode '$HOME')"
    printf 'argument.2=%s\n' "$(encode "it's-safe")"
    printf 'argument.3=%s\n' "$(encode 'back\slash')"
    printf 'environmentCount=2\n'
    printf 'environment.0.key=%s\n' "$(encode CAPTURE_FILE)"
    printf 'environment.0.value=%s\n' "$(encode "$CAPTURE_FILE")"
    printf 'environment.1.key=%s\n' "$(encode DXVK_ASYNC)"
    printf 'environment.1.value=%s\n' "$(encode 7)"
} > "$SPEC_FILE"

if ! FAKE_DATE_STATE="$TEST_ROOT/fake-date-state" PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"; then
    [ ! -f "$EVENT_FILE" ] || sed -n '1,120p' "$EVENT_FILE" >&2
    [ ! -f "$LOG_FILE" ] || sed -n '1,120p' "$LOG_FILE" >&2
    exit 1
fi

EXPECTED_FILE="$TEST_ROOT/expected-argv"
{
    encode "$(realpath "$WORK_DIR/it's game.exe")"; printf '\n'
    encode "hello world"; printf '\n'
    encode '$HOME'; printf '\n'
    encode "it's-safe"; printf '\n'
    encode 'back\slash'; printf '\n'
} > "$EXPECTED_FILE"

if ! cmp "$EXPECTED_FILE" "$CAPTURE_FILE"; then
    printf 'Expected:\n' >&2
    sed -n '1,20p' "$EXPECTED_FILE" >&2
    printf 'Captured:\n' >&2
    sed -n '1,20p' "$CAPTURE_FILE" >&2
    exit 1
fi
grep -Fqx "BOX64_LD_LIBRARY_PATH=$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib64:$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib64/wine/x86_64-unix:$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib:$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib/wine/x86_64-unix:$TERMUX_ROOT/usr/glibc/lib/x86_64-linux-gnu" "$ENV_CAPTURE_FILE"
grep -Fqx "DXVK_CONFIG_FILE=$TERMUX_ROOT/usr/glibc/opt/dxvk.conf" "$ENV_CAPTURE_FILE"
grep -Fqx "FONTCONFIG_PATH=$TERMUX_ROOT/usr/glibc/etc/fonts" "$ENV_CAPTURE_FILE"
grep -qx 'DXVK_ASYNC=7' "$ENV_CAPTURE_FILE"
grep -qx 'WINEESYNC=1' "$ENV_CAPTURE_FILE"
grep -qx 'LC_ALL=en_US.utf8' "$ENV_CAPTURE_FILE"
grep -qx 'MESA_VK_WSI_PRESENT_MODE=mailbox' "$ENV_CAPTURE_FILE"
grep -qx '2-3' "$AFFINITY_CAPTURE_FILE"
grep -q '"stage":"WAITING_FIRST_FRAME"' "$EVENT_FILE"
grep -q '"state":"SUCCEEDED".*"stage":"COMPLETE"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
[ ! -e "$PULSE_FAKE_STATE" ]
grep -q '^--start ' "$PULSE_FAKE_CALLS"
grep -qx -- '--kill' "$PULSE_FAKE_CALLS"

: > "$CANCEL_PATH"
FAKE_DATE_STATE="$TEST_ROOT/fake-date-state" PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
grep -q '"state":"CANCELLED".*"stage":"COMPLETE"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
rm -f "$CANCEL_PATH"

rm -f "$PREFIX_PATH/.termux-box-bootstrap-done"
set +e
FAKE_DATE_STATE="$TEST_ROOT/fake-date-state" PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
UNPROVISIONED_STATUS=$?
set -e
[ "$UNPROVISIONED_STATUS" -ne 0 ]
grep -q '"errorCode":"prefix_provision_required"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
touch "$PREFIX_PATH/.termux-box-bootstrap-done"

printf '%s\n' wine-other > "$PREFIX_PATH/.termux-box-wine-package"
set +e
FAKE_DATE_STATE="$TEST_ROOT/fake-date-state" PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
MISMATCH_STATUS=$?
set -e
[ "$MISMATCH_STATUS" -ne 0 ]
grep -q '"errorCode":"prefix_runtime_mismatch"' "$EVENT_FILE"
printf '%s\n' "$WINE_PACKAGE" > "$PREFIX_PATH/.termux-box-wine-package"

sed 's/^timeoutSeconds=600$/timeoutSeconds=30/' "$SPEC_FILE" > "$SPEC_FILE.tmp"
mv "$SPEC_FILE.tmp" "$SPEC_FILE"
set +e
FAKE_WINE_WAIT=1 FAKE_DATE_STATE="$TEST_ROOT/fake-date-state" PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
TIMEOUT_STATUS=$?
set -e
[ "$TIMEOUT_STATUS" -ne 0 ]
grep -q '"errorCode":"launch_timeout"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
printf 'Launch script integration test passed.\n'
