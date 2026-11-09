#!/bin/sh
# Local launcher/argv/process fixtures; no Android build, device or package download.

set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/games-launch-script.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
# Keep relative artifacts from the Android launcher inside this disposable fixture.
cd "$TEST_ROOT"
cleanup_fixture() {
    status=$?
    if [ "$status" -ne 0 ]; then
        [ ! -f "${LOG_FILE:-}" ] || tail -80 "$LOG_FILE" >&2
        [ ! -f "${EVENT_FILE:-}" ] || cat "$EVENT_FILE" >&2
    fi
    for owned in "$TEST_ROOT/x11.pid" "$TEST_ROOT/wine.pid"; do
        [ ! -f "$owned" ] || kill "$(cat "$owned")" 2>/dev/null || true
    done
    rm -rf "$TEST_ROOT"
}
trap cleanup_fixture EXIT

TERMUX_ROOT="$TEST_ROOT/termux files"
TERMUX_ROOT_ALIAS="$TEST_ROOT/termux-files-alias"
RUNTIME_DIR="$TERMUX_ROOT/front/runtime"
LAUNCH_SCRIPT="$TERMUX_ROOT_ALIAS/front/runtime/start_local_game.sh"
FAKE_BIN="$TEST_ROOT/fake-bin"
GAME_ROOT="$TEST_ROOT/Game Root \$special"
WORK_DIR="$GAME_ROOT/bin folder"
CAPTURE_FILE="$TEST_ROOT/captured-argv"
ENV_CAPTURE_FILE="$TEST_ROOT/captured-env"
AFFINITY_CAPTURE_FILE="$TEST_ROOT/captured-affinity"
SPEC_FILE="$TEST_ROOT/task.launchspec"
EVENT_FILE="$TERMUX_ROOT/front/launches/events/task events.jsonl"
LOG_FILE="$TERMUX_ROOT/front/launches/logs/task log.txt"
LOCK_PATH="$TERMUX_ROOT/front/launches/locks/task lock"
CANCEL_PATH="$TERMUX_ROOT/front/launches/cancel/task cancel"
PREFIX_PATH="$TERMUX_ROOT/front/containers/native/prefix"
WINE_PACKAGE="glibc_wine_fixture"

mkdir -p "$TERMUX_ROOT/usr/glibc/bin" "$TERMUX_ROOT/usr/glibc/components/glibc_box64_0.2.7/bin" "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin" \
    "$TERMUX_ROOT/usr/glibc/opt/conf/dynarec" \
    "$TERMUX_ROOT/usr/glibc/termux-box/run" "$RUNTIME_DIR" "$FAKE_BIN" "$WORK_DIR" \
    "$PREFIX_PATH"
touch "$PREFIX_PATH/.termux-box-bootstrap-done" "$WORK_DIR/it's game.exe"
printf '%s\n' "$WINE_PACKAGE" > "$PREFIX_PATH/.termux-box-wine-package"
ln -s "$TERMUX_ROOT" "$TERMUX_ROOT_ALIAS"

# Execute both real launchers and helpers. Only the Android fixed filesystem roots
# are relocated in the temporary copy; all runtime decisions remain production code.
python3 - "$PROJECT_ROOT" "$RUNTIME_DIR" "$TERMUX_ROOT" "$TEST_ROOT" <<'PYFIXTURE'
from pathlib import Path
import sys, os
root, runtime, termux, temporary = map(Path, sys.argv[1:])
for source in (root/'termux-ui/src/main/assets/local-games').glob('*.sh'):
    target=runtime/source.name
    target.write_text(source.read_text().replace('/data/data/com.termux/files',str(termux)).replace('/sdcard/termux-boxtrace',str(temporary/'trace')))
    target.chmod(0o700)
if os.environ.get('NATIVE_LAUNCHER_UNDER_TEST'):
    source = Path(os.environ['NATIVE_LAUNCHER_UNDER_TEST'])
    (runtime/'start_termux_box_game.sh').write_text(source.read_text().replace('/data/data/com.termux/files', str(termux)).replace('/sdcard/termux-boxtrace', str(temporary/'trace')))
PYFIXTURE
cp "$PROJECT_ROOT/termux-ui/src/main/assets/local-games/x_server_dimensions.py" "$RUNTIME_DIR/x_server_dimensions.py"
mkdir -p "$TERMUX_ROOT/usr/glibc/lib" "$TERMUX_ROOT/usr/glibc/share/fonts" \
    "$PREFIX_PATH/termux-boxmeta" "$PREFIX_PATH/dosdevices" \
    "$TERMUX_ROOT/usr/glibc/opt/libs/mesa" "$TERMUX_ROOT/usr/glibc/opt/libs/d3d" \
    "$TERMUX_ROOT/usr/glibc/components/native-turnip-v2/lib" \
    "$TERMUX_ROOT/usr/glibc/components/native-dxvk-1.6.1/system32" \
    "$TERMUX_ROOT/usr/glibc/components/native-dxvk-1.6.1/syswow64"
printf fixture > "$TERMUX_ROOT/usr/glibc/components/native-turnip-v2/lib/libvulkan_freedreno.so"
touch "$TERMUX_ROOT/usr/glibc/opt/libs/mesa/turnip-v2.7z" \
    "$TERMUX_ROOT/usr/glibc/opt/libs/d3d/dxvk-1.6.1.7z" \
    "$TERMUX_ROOT/usr/glibc/components/native-turnip-v2/.unpacked" \
    "$TERMUX_ROOT/usr/glibc/components/native-dxvk-1.6.1/.unpacked" \
    "$TERMUX_ROOT/usr/glibc/components/native-dxvk-1.6.1/system32/dxgi.dll" \
    "$TERMUX_ROOT/usr/glibc/components/native-dxvk-1.6.1/syswow64/dxgi.dll" \
    "$TERMUX_ROOT/usr/glibc/share/fonts/NotoSansCJK-Regular.ttc" \
    "$TERMUX_ROOT/usr/glibc/share/fonts/NotoSerifCJK-Regular.ttc" \
    "$PREFIX_PATH/termux-boxmeta/games-wine-fonts-v2"
for locale_name in zh_CN.utf8 zh_CN.gbk; do
    mkdir -p "$TERMUX_ROOT/usr/glibc/lib/locale/$locale_name"
    printf locale > "$TERMUX_ROOT/usr/glibc/lib/locale/$locale_name/LC_CTYPE"
done
touch "$TERMUX_ROOT/usr/glibc/termux-box/run/games-zh-cn-locale-v1"
mkdir -p "$PREFIX_PATH/drive_c/windows/Fonts"
touch "$PREFIX_PATH/drive_c/windows/Fonts/NotoSansCJK-Regular.ttc" \
    "$PREFIX_PATH/drive_c/windows/Fonts/NotoSerifCJK-Regular.ttc"
printf '%s\n' '"Tahoma"="Noto Sans CJK SC"' '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"' > "$PREFIX_PATH/system.reg"
printf '#!/bin/sh\n[ "$1" = --library-path ] || exit 64\nshift 2\nexec "$@"\n' > "$TERMUX_ROOT/usr/glibc/lib/ld-linux-aarch64.so.1"
chmod 700 "$TERMUX_ROOT/usr/glibc/lib/ld-linux-aarch64.so.1"
printf '#!/bin/sh\nexec "$@"\n' > "$TERMUX_ROOT/usr/glibc/components/glibc_box64_0.2.7/bin/box64"
printf '#!/bin/sh\n[ "$GAMES_SESSION_SPEC" = "$FIXTURE_SESSION_SPEC" ] || exit 89\nprintf "%%s\\n" "$$" > "$FIXTURE_WINE_PID"; if [ "${FAKE_WINE_WAIT:-0}" = 1 ]; then trap "exit 0" TERM INT; while :; do sleep 1; done; fi\n: > "$CAPTURE_FILE"\nfor value in "$@"; do printf "%%s" "$value" | base64 | tr -d "\\n" >> "$CAPTURE_FILE"; printf "\\n" >> "$CAPTURE_FILE"; done\nprintf "BOX64_LD_LIBRARY_PATH=%%s\\nDXVK_CONFIG_FILE=%%s\\nFONTCONFIG_PATH=%%s\\nDXVK_ASYNC=%%s\\nWINEESYNC=%%s\\nLC_ALL=%%s\\nMESA_VK_WSI_PRESENT_MODE=%%s\\n" "$BOX64_LD_LIBRARY_PATH" "$DXVK_CONFIG_FILE" "$FONTCONFIG_PATH" "$DXVK_ASYNC" "$WINEESYNC" "$LC_ALL" "$MESA_VK_WSI_PRESENT_MODE" > "$ENV_CAPTURE_FILE"\n' \
    > "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wine"
printf '#!/bin/sh\n[ "$1" != -k ] || { [ ! -f "$FIXTURE_WINE_PID" ] || kill "$(cat "$FIXTURE_WINE_PID")" 2>/dev/null || true; }\nexit 0\n' > "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wineserver"
python3 - "$FAKE_BIN/termux-x11" <<'PYX11'
from pathlib import Path
import sys
Path(sys.argv[1]).write_text('#!'+sys.executable+'\n'+"import os,pathlib,socket,time\np=pathlib.Path(os.environ['PREFIX'])/'tmp/.X11-unix/X0'\np.parent.mkdir(parents=True,exist_ok=True)\ns=socket.socket(socket.AF_UNIX); s.bind(str(p))\npathlib.Path(os.environ['FIXTURE_X11_PID']).write_text(str(os.getpid()))\nwhile True: time.sleep(.1)\n")
PYX11
if [ "${TEST_XSERVER_BACKEND:-legacy}" = xlorie ]; then
    cp "$FAKE_BIN/termux-x11" "$FAKE_BIN/Xlorie"
    python3 - "$FAKE_BIN/Xlorie" <<'PYXLORIE'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text().replace('import os,pathlib,socket,time', 'import os,pathlib,socket,time,sys')
s = s.replace('while True:', "pathlib.Path(os.environ['FIXTURE_X11_ARGS']).write_text('\\n'.join(sys.argv[1:]))\nwhile True:")
p.write_text(s)
PYXLORIE
    chmod 700 "$FAKE_BIN/Xlorie"
    printf '%s\n' xlorie > "$RUNTIME_DIR/x-server-backend"
    export XLORIE_WAIT_RENDER=0 FIXTURE_X11_ARGS="$TEST_ROOT/x11-args"
fi
printf '#!/bin/sh\n[ ! -f "$FIXTURE_X11_PID" ] || printf "user %%s 1 termux.x11\\n" "$(cat "$FIXTURE_X11_PID")"\n' > "$FAKE_BIN/ps"
printf '#!/bin/sh\nexit 1\n' > "$FAKE_BIN/pkill"
printf '#!/bin/sh\n[ "$1" = _NPROCESSORS_ONLN ] || exit 64\nprintf "4\\n"\n' > "$FAKE_BIN/getconf"
printf '#!/bin/sh\n[ "$1" = -c ] || exit 64\ncores=$2\nshift 2\n[ "$cores" = 2-3 ] || exit 64\n[ "$1" = true ] && exit 0\nprintf "%%s\\n" "$cores" > "$AFFINITY_CAPTURE_FILE"\nexec "$@"\n' > "$FAKE_BIN/taskset"
printf '#!/bin/sh\n[ "$1" = -k ] || [ -z "${PULSE_SERVER+x}" ] || exit 64\nprintf "%%s\\n" "$*" >> "$PULSE_FAKE_CALLS"\ncase "$1" in\n  --check) [ -e "$PULSE_FAKE_STATE" ] ;;\n  --start) : > "$PULSE_FAKE_STATE" ;;\n  --kill|-k) rm -f "$PULSE_FAKE_STATE" ;;\nesac\n' > "$FAKE_BIN/pulseaudio"
chmod 700 "$RUNTIME_DIR/start_local_game.sh" \
    "$TERMUX_ROOT/usr/glibc/components/glibc_box64_0.2.7/bin/box64" "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wine" \
    "$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/bin/wineserver" "$FAKE_BIN/termux-x11" \
    "$FAKE_BIN/getconf" "$FAKE_BIN/taskset" "$FAKE_BIN/pulseaudio" "$FAKE_BIN/ps" "$FAKE_BIN/pkill"

printf 'export WINEESYNC=1\nexport WINEESYNC_TERMUX=1\n' \
    > "$TERMUX_ROOT/usr/glibc/opt/conf/wineesync.conf"
printf 'export MESA_VK_WSI_PRESENT_MODE=mailbox\n' \
    > "$TERMUX_ROOT/usr/glibc/opt/conf/wsi_present.conf"
printf 'export PRIMARY_CORES=4-7\nexport SECONDARY_CORES=0-3\n' \
    > "$TERMUX_ROOT/usr/glibc/opt/conf/cores.conf"
# Package defaults must not start X at a different resolution than Wine explorer.
printf 'export RESOLUTION=1024x768\n' > "$TERMUX_ROOT/usr/glibc/opt/conf/force_compatibility.conf"
printf 'en_US.utf8\n' > "$TERMUX_ROOT/usr/glibc/opt/locale.conf"

export FIXTURE_SESSION_SPEC="$SPEC_FILE"
export PREFIX="$TERMUX_ROOT/usr" FIXTURE_X11_PID="$TEST_ROOT/x11.pid" FIXTURE_WINE_PID="$TEST_ROOT/wine.pid"
export LC_ALL=en_US.utf8
export PULSE_FAKE_STATE="$TEST_ROOT/pulse-state"
export PULSE_FAKE_CALLS="$TEST_ROOT/pulse-calls"
export ENV_CAPTURE_FILE
export AFFINITY_CAPTURE_FILE

encode() {
    printf '%s' "$1" | base64 | tr -d '\n'
}

{
    printf 'schemaVersion=6\n'
    printf 'taskId=%s\n' "$(encode task-1)"
    printf 'gameId=%s\n' "$(encode game-1)"
    printf 'gameRootPath=%s\n' "$(encode "$GAME_ROOT")"
    printf 'executable=%s\n' "$(encode "bin folder/it's game.exe")"
    printf 'workingDirectory=%s\n' "$(encode "bin folder")"
    printf 'prefixPath=%s\n' "$(encode "$PREFIX_PATH")"
    printf 'winePackage=%s\n' "$(encode "$WINE_PACKAGE")"
    printf 'graphicsDriver=%s\n' "$(encode native-turnip-v2)"
    printf 'dxWrapper=%s\n' "$(encode native-dxvk-1.6.1)"
    printf 'audioDriver=%s\n' "$(encode ALSA)"
    printf 'resolution=%s\n' "$(encode 1280x720)"
    printf 'box64Preset=%s\n' "$(encode INTERMEDIATE)"
    printf 'inputProfileId=%s\n' "$(encode dinput:3)"
    printf 'launchExecutionMode=%s\n' "$(encode terminal_session)"
    printf 'homeDirectory=%s\n' "$(encode "$TERMUX_ROOT/front/containers/native/home")"
    printf 'desktopOnly=0\n'
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
    printf 'environmentCount=3\n'
    printf 'environment.0.key=%s\n' "$(encode CAPTURE_FILE)"
    printf 'environment.0.value=%s\n' "$(encode "$CAPTURE_FILE")"
    printf 'environment.1.key=%s\n' "$(encode DXVK_ASYNC)"
    printf 'environment.1.value=%s\n' "$(encode 7)"
    printf 'environment.2.key=%s\n' "$(encode GAMES_CPU_CORES)"
    printf 'environment.2.value=%s\n' "$(encode 2-3)"
} > "$SPEC_FILE"

# Missing installed launcher and obsolete input schemas must fail before events.
mv "$RUNTIME_DIR/start_termux_box_game.sh" "$RUNTIME_DIR/start_termux_box_game.disabled"
if PATH="$FAKE_BIN:$PATH" "$LAUNCH_SCRIPT" "$SPEC_FILE" > "$TEST_ROOT/rejected" 2>&1; then
    printf 'Missing launcher was accepted\n' >&2; exit 1
fi
grep -qx termux_box_game_launcher_missing "$TEST_ROOT/rejected"
[ ! -e "$EVENT_FILE" ]
mv "$RUNTIME_DIR/start_termux_box_game.disabled" "$RUNTIME_DIR/start_termux_box_game.sh"
sed 's/^schemaVersion=6$/schemaVersion=3/' "$SPEC_FILE" > "$SPEC_FILE.old"
if PATH="$FAKE_BIN:$PATH" "$LAUNCH_SCRIPT" "$SPEC_FILE.old" > "$TEST_ROOT/rejected" 2>&1; then
    printf 'Obsolete schema was accepted\n' >&2; exit 1
fi
grep -qx unsupported_launch_spec_schema "$TEST_ROOT/rejected"
[ ! -e "$EVENT_FILE" ]

if ! PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"; then
    [ ! -f "$EVENT_FILE" ] || sed -n '1,120p' "$EVENT_FILE" >&2
    [ ! -f "$LOG_FILE" ] || sed -n '1,120p' "$LOG_FILE" >&2
    exit 1
fi

EXPECTED_FILE="$TEST_ROOT/expected-argv"
if [ "${TEST_XSERVER_BACKEND:-legacy}" = xlorie ]; then
    python3 - "$FIXTURE_X11_ARGS" <<'PYCHECKSCREEN'
from pathlib import Path
import sys
args = Path(sys.argv[1]).read_text().splitlines()
assert args[args.index('-screen') + 2] == '1280x720x24', args
PYCHECKSCREEN
fi
grep -q 'X11 display: .* target=1280x720' "$LOG_FILE"
{
    encode explorer; printf '\n'
    encode /desktop=shell,1280x720; printf '\n'
    encode "G:\\bin folder\\it's game.exe"; printf '\n'
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
[ "${TEST_DISPLAY_ONLY:-0}" != 1 ] || { printf 'Native display startup integration test passed.\n'; exit 0; }
grep -Fqx "BOX64_LD_LIBRARY_PATH=$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib64:$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib64/wine/x86_64-unix:$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib:$TERMUX_ROOT/usr/glibc/$WINE_PACKAGE/lib/wine/x86_64-unix:$TERMUX_ROOT/usr/glibc/lib/x86_64-linux-gnu" "$ENV_CAPTURE_FILE"
grep -Fqx "DXVK_CONFIG_FILE=$TERMUX_ROOT/usr/glibc/opt/dxvk.conf" "$ENV_CAPTURE_FILE"
grep -Fqx "FONTCONFIG_PATH=$TERMUX_ROOT/usr/glibc/etc/fonts" "$ENV_CAPTURE_FILE"
grep -qx 'DXVK_ASYNC=7' "$ENV_CAPTURE_FILE"
grep -qx 'WINEESYNC=1' "$ENV_CAPTURE_FILE"
# Installed shared Chinese locale selects the existing default for games.
grep -qx 'LC_ALL=zh_CN.UTF-8' "$ENV_CAPTURE_FILE"
grep -qx 'MESA_VK_WSI_PRESENT_MODE=mailbox' "$ENV_CAPTURE_FILE"
grep -qx '2-3' "$AFFINITY_CAPTURE_FILE"
grep -q '"stage":"WAITING_FIRST_FRAME"' "$EVENT_FILE"
grep -q '"state":"SUCCEEDED".*"stage":"COMPLETE"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
[ ! -e "$PULSE_FAKE_STATE" ]
grep -q '^--start ' "$PULSE_FAKE_CALLS"
grep -qx -- '-k' "$PULSE_FAKE_CALLS"

: > "$CANCEL_PATH"
PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
grep -q '"state":"CANCELLED".*"stage":"COMPLETE"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
rm -f "$CANCEL_PATH"

rm -f "$PREFIX_PATH/.termux-box-bootstrap-done"
set +e
PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
UNPROVISIONED_STATUS=$?
set -e
[ "$UNPROVISIONED_STATUS" -ne 0 ]
grep -q '"errorCode":"prefix_setup_required"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
touch "$PREFIX_PATH/.termux-box-bootstrap-done"

printf '%s\n' wine-other > "$PREFIX_PATH/.termux-box-wine-package"
set +e
PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
MISMATCH_STATUS=$?
set -e
[ "$MISMATCH_STATUS" -ne 0 ]
grep -q '"errorCode":"prefix_runtime_mismatch"' "$EVENT_FILE"
printf '%s\n' "$WINE_PACKAGE" > "$PREFIX_PATH/.termux-box-wine-package"

# A missing shared locale must fail without executing Wine or generating locale data.
rm -f "$CAPTURE_FILE" "$TERMUX_ROOT/usr/glibc/lib/locale/zh_CN.gbk/LC_CTYPE"
printf '#!/bin/sh\necho forbidden-locale-generation >&2\nexit 99\n' > "$TERMUX_ROOT/usr/glibc/bin/locale-gen"
chmod 700 "$TERMUX_ROOT/usr/glibc/bin/locale-gen"
set +e
PATH="$FAKE_BIN:$PATH" "$LAUNCH_SCRIPT" "$SPEC_FILE"
LOCALE_STATUS=$?
set -e
[ "$LOCALE_STATUS" -ne 0 ]
grep -q 'glibc_cjk_locale_missing' "$LOG_FILE"
! grep -q 'forbidden-locale-generation' "$LOG_FILE"
[ ! -e "$CAPTURE_FILE" ]
printf locale > "$TERMUX_ROOT/usr/glibc/lib/locale/zh_CN.gbk/LC_CTYPE"

sed 's/^timeoutSeconds=600$/timeoutSeconds=30/' "$SPEC_FILE" > "$SPEC_FILE.tmp"
mv "$SPEC_FILE.tmp" "$SPEC_FILE"
set +e
FAKE_WINE_WAIT=1 PATH="$FAKE_BIN:$PATH" \
    "$LAUNCH_SCRIPT" "$SPEC_FILE"
TIMEOUT_STATUS=$?
set -e
[ "$TIMEOUT_STATUS" -ne 0 ]
grep -q '"errorCode":"launch_timeout"' "$EVENT_FILE"
[ ! -e "$LOCK_PATH" ]
[ ! -e "$TERMUX_ROOT/usr/glibc/termux-box/run/glibc-wine.lock" ]
printf 'Launch script integration test passed.\n'
