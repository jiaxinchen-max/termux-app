#!/bin/sh

set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SOURCE_SCRIPT="$PROJECT_ROOT/games/src/main/assets/local-games/start_rootfs_game.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/games-rootfs-script.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
trap 'rm -rf "$TEST_ROOT"' EXIT

TERMUX_ROOT="$TEST_ROOT/termux files"
TERMUX_ALIAS="$TEST_ROOT/termux-files-alias"
RUNTIME_DIR="$TERMUX_ROOT/games/runtime"
SCRIPT="$TERMUX_ALIAS/games/runtime/start_rootfs_game.sh"
FAKE_BIN="$TEST_ROOT/fake-bin"
GAME_ROOT="$TEST_ROOT/Game Root"
WORK_DIR="$GAME_ROOT/bin folder"
ROOTFS="$TERMUX_ROOT/usr/var/lib/proot-distro/containers/games-debian13-v1/rootfs"
PREFIX="$TERMUX_ROOT/games/prefixes/rootfs_proot/game-1"
EVENT="$TERMUX_ROOT/games/launches/events/rootfs.jsonl"
LOG="$TERMUX_ROOT/games/launches/logs/rootfs.log"
LOCK="$TERMUX_ROOT/games/launches/locks/rootfs"
CANCEL="$TERMUX_ROOT/games/launches/cancel/rootfs.cancel"
SPEC="$TEST_ROOT/rootfs.launchspec"
CAPTURE="$TEST_ROOT/proot-argv"

mkdir -p "$RUNTIME_DIR" "$FAKE_BIN" "$WORK_DIR" "$ROOTFS/usr/bin" "$PREFIX" \
    "$ROOTFS/etc" "$ROOTFS/opt/games-runtime/dxvk/system32" \
    "$ROOTFS/opt/games-runtime/dxvk/syswow64" "$ROOTFS/mnt/games/game" \
    "$ROOTFS/mnt/games/prefix" "$TERMUX_ROOT/usr/bin" \
    "$TERMUX_ROOT/usr/tmp" "$TERMUX_ROOT/usr/glibc/opt/virgl"
touch "$WORK_DIR/game.exe"
touch "$ROOTFS/opt/games-runtime/dxvk/system32/dxgi.dll" \
    "$ROOTFS/opt/games-runtime/dxvk/syswow64/dxgi.dll"
ln -s "$TERMUX_ROOT" "$TERMUX_ALIAS"
cp "$SOURCE_SCRIPT" "$RUNTIME_DIR/start_rootfs_game.sh"

printf '#!/bin/sh\n: > "$CAPTURE_FILE"\nfor value in "$@"; do printf "%%s" "$value" | base64 | tr -d "\\n" >> "$CAPTURE_FILE"; printf "\\n" >> "$CAPTURE_FILE"; done\nif [ "${FAKE_PROOT_WAIT:-0}" = 1 ]; then trap "exit 0" TERM INT; while :; do sleep 1; done; fi\nexit 0\n' > "$TERMUX_ROOT/usr/bin/proot"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/env"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/wine"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/wineboot"
printf '#!/bin/sh\ntrap "exit 0" TERM INT\nwhile :; do sleep 1; done\n' > "$FAKE_BIN/termux-x11"
printf '#!/bin/sh\ntrap "exit 0" TERM INT\nwhile :; do sleep 1; done\n' > "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so"
printf '#!/bin/sh\n[ -z "${PULSE_SERVER+x}" ] || exit 64\nprintf "%%s\\n" "$*" >> "$PULSE_FAKE_CALLS"\ncase "$1" in\n  --check) [ -e "$PULSE_FAKE_STATE" ] ;;\n  --start) : > "$PULSE_FAKE_STATE" ;;\n  --kill) rm -f "$PULSE_FAKE_STATE" ;;\nesac\n' > "$FAKE_BIN/pulseaudio"
printf '#!/bin/sh\nprintf "aarch64\\n"\n' > "$FAKE_BIN/uname"
cat > "$ROOTFS/etc/games-runtime.properties" <<'EOF'
schemaVersion=2
runtimeBackend=rootfs_proot
architecture=aarch64
runtimePackages=hangover-11.9,box64-wine-stable
graphicsDrivers=rootfs-virgl-mesa,rootfs-llvmpipe
dxWrappers=rootfs-dxvk,rootfs-wined3d
audioDrivers=pulseaudio,alsa
EOF
chmod 700 "$RUNTIME_DIR/start_rootfs_game.sh" "$TERMUX_ROOT/usr/bin/proot" \
    "$ROOTFS/usr/bin/env" "$ROOTFS/usr/bin/wine" "$ROOTFS/usr/bin/wineboot" \
    "$FAKE_BIN/termux-x11" "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so" \
    "$FAKE_BIN/pulseaudio" "$FAKE_BIN/uname"

export PULSE_FAKE_STATE="$TEST_ROOT/pulse-state"
export PULSE_FAKE_CALLS="$TEST_ROOT/pulse-calls"

encode() { printf '%s' "$1" | base64 | tr -d '\n'; }

{
    printf 'schemaVersion=4\n'
    printf 'taskId=%s\n' "$(encode rootfs-task)"
    printf 'gameId=%s\n' "$(encode game-1)"
    printf 'gameRootPath=%s\n' "$(encode "$GAME_ROOT")"
    printf 'executable=%s\n' "$(encode 'bin folder/game.exe')"
    printf 'workingDirectory=%s\n' "$(encode 'bin folder')"
    printf 'prefixPath=%s\n' "$(encode "$PREFIX")"
    printf 'winePackage=%s\n' "$(encode hangover-11.9)"
    printf 'graphicsDriver=%s\n' "$(encode rootfs-llvmpipe)"
    printf 'dxWrapper=%s\n' "$(encode rootfs-wined3d)"
    printf 'audioDriver=%s\n' "$(encode pulseaudio)"
    printf 'resolution=%s\n' "$(encode 1280x720)"
    printf 'box64Preset=%s\n' "$(encode INTERMEDIATE)"
    printf 'inputProfileId=%s\n' "$(encode xinput)"
    printf 'launchExecutionMode=%s\n' "$(encode app_shell)"
    printf 'runtimeBackendType=%s\n' "$(encode rootfs_proot)"
    printf 'rootfsPackage=%s\n' "$(encode debian-13-games-rootfs)"
    printf 'runtimeRootPath=%s\n' "$(encode "$ROOTFS")"
    printf 'eventPath=%s\n' "$(encode "$EVENT")"
    printf 'logPath=%s\n' "$(encode "$LOG")"
    printf 'lockPath=%s\n' "$(encode "$LOCK")"
    printf 'cancelPath=%s\n' "$(encode "$CANCEL")"
    printf 'timeoutSeconds=60\n'
    printf 'argumentCount=3\n'
    printf 'argument.0=%s\n' "$(encode 'hello world')"
    printf 'argument.1=%s\n' "$(encode '$HOME')"
    printf 'argument.2=%s\n' "$(encode "it's-safe")"
    printf 'environmentCount=1\n'
    printf 'environment.0.key=%s\n' "$(encode CAPTURE_FILE)"
    printf 'environment.0.value=%s\n' "$(encode "$CAPTURE")"
} > "$SPEC"

PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"

grep -qx "$(encode "$ROOTFS")" "$CAPTURE"
grep -qx "$(encode /usr/bin/wine)" "$CAPTURE"
grep -qx "$(encode /mnt/games/game/bin\ folder/game.exe)" "$CAPTURE"
grep -qx "$(encode 'hello world')" "$CAPTURE"
grep -qx "$(encode '$HOME')" "$CAPTURE"
grep -q '"stage":"WAITING_FIRST_FRAME"' "$EVENT"
grep -q '"state":"SUCCEEDED".*"stage":"COMPLETE"' "$EVENT"
[ ! -e "$LOCK" ]
[ ! -e "$PULSE_FAKE_STATE" ]
grep -q '^--start ' "$PULSE_FAKE_CALLS"
grep -qx -- '--kill' "$PULSE_FAKE_CALLS"

sed "s|^graphicsDriver=.*$|graphicsDriver=$(encode rootfs-virgl-mesa)|" "$SPEC" > "$SPEC.tmp"
mv "$SPEC.tmp" "$SPEC"
mv "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so" \
    "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so.disabled"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"virgl_server_missing"' "$EVENT"
mv "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so.disabled" \
    "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so"
sed "s|^graphicsDriver=.*$|graphicsDriver=$(encode rootfs-llvmpipe)|" "$SPEC" > "$SPEC.tmp"
mv "$SPEC.tmp" "$SPEC"

mkdir -p "$ROOTFS/opt/box64-wine/bin"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/box64"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/opt/box64-wine/bin/wine"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/opt/box64-wine/bin/wineboot"
chmod 700 "$ROOTFS/usr/bin/box64" "$ROOTFS/opt/box64-wine/bin/wine" \
    "$ROOTFS/opt/box64-wine/bin/wineboot"
sed "s|^winePackage=.*$|winePackage=$(encode box64-wine-stable)|" "$SPEC" > "$SPEC.tmp"
mv "$SPEC.tmp" "$SPEC"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
grep -qx "$(encode /usr/bin/box64)" "$CAPTURE"
grep -qx "$(encode /opt/box64-wine/bin/wine)" "$CAPTURE"
grep -q '"state":"SUCCEEDED".*"stage":"COMPLETE"' "$EVENT"
[ ! -e "$LOCK" ]

FAKE_PROOT_WAIT=1 PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC" &
LAUNCHER_PID=$!
sleep 1
: > "$CANCEL"
kill -TERM "$LAUNCHER_PID" 2>/dev/null || true
wait "$LAUNCHER_PID"
grep -q '"state":"CANCELLED".*"stage":"COMPLETE"' "$EVENT"
[ ! -e "$LOCK" ]
rm -f "$CANCEL"

mv "$TERMUX_ROOT/usr/bin/proot" "$TERMUX_ROOT/usr/bin/proot.disabled"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"proot_runtime_missing"' "$EVENT"
[ ! -e "$LOCK" ]

printf 'RootFS launch script integration test passed.\n'
