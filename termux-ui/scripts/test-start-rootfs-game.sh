#!/bin/sh
# Local shared-RootFS/spec/process fixtures; no PRoot install or device required.

set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/games-rootfs-script.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
cleanup_fixture() {
    status=$?
    if [ "$status" -ne 0 ]; then
        [ ! -f "${LOG:-}" ] || tail -80 "$LOG" >&2
        [ ! -f "${EVENT:-}" ] || cat "$EVENT" >&2
    fi
    for owned in "$TEST_ROOT/x11.pid" "$TEST_ROOT/proot.pid" "$TEST_ROOT/orphan.pid" "$TEST_ROOT/unrelated.pid"; do
        [ ! -f "$owned" ] || kill -KILL "$(cat "$owned")" 2>/dev/null || true
    done
    rm -rf "$TEST_ROOT"
}
trap cleanup_fixture EXIT

TERMUX_ROOT="$TEST_ROOT/termux files"
TERMUX_ALIAS="$TEST_ROOT/termux-files-alias"
RUNTIME_DIR="$TERMUX_ROOT/front/runtime"
SCRIPT="$TERMUX_ALIAS/front/runtime/start_rootfs_game.sh"
FAKE_BIN="$TEST_ROOT/fake-bin"
GAME_ROOT="$TEST_ROOT/Game Root"
WORK_DIR="$GAME_ROOT/bin folder"
ROOTFS="$TERMUX_ROOT/usr/var/lib/proot-distro/games-shared-rootfs/rootfs"
PREFIX="$TERMUX_ROOT/front/containers/hangover/prefix"
HOME_PATH="$TERMUX_ROOT/front/containers/hangover/home"
EVENT="$TERMUX_ROOT/front/launches/events/rootfs.jsonl"
LOG="$TERMUX_ROOT/front/launches/logs/rootfs.log"
LOCK="$TERMUX_ROOT/front/launches/locks/rootfs"
CANCEL="$TERMUX_ROOT/front/launches/cancel/rootfs.cancel"
SPEC="$TEST_ROOT/rootfs.launchspec"
CAPTURE="$TEST_ROOT/proot-argv"

mkdir -p "$RUNTIME_DIR" "$FAKE_BIN" "$WORK_DIR" "$ROOTFS/usr/bin" "$PREFIX" \
    "$ROOTFS/etc" "$ROOTFS/opt/games-runtime/dxvk/system32" \
    "$ROOTFS/opt/games-runtime/dxvk/syswow64" "$ROOTFS/mnt/games/game" \
    "$ROOTFS/mnt/games/prefix" "$TERMUX_ROOT/usr/bin" \
    "$TERMUX_ROOT/usr/tmp" "$TERMUX_ROOT/usr/glibc/opt/virgl"
touch "$WORK_DIR/game.exe"
mkdir -p "$ROOTFS/usr/lib/wine/aarch64-windows"
for dll in wowbox64.dll libwow64fex.dll libarm64ecfex.dll; do
    printf 'emulator fixture\n' > "$ROOTFS/usr/lib/wine/aarch64-windows/$dll"
done
touch "$ROOTFS/opt/games-runtime/dxvk/system32/dxgi.dll" \
    "$ROOTFS/opt/games-runtime/dxvk/syswow64/dxgi.dll"
ln -s "$TERMUX_ROOT" "$TERMUX_ALIAS"
for asset in start_rootfs_game.sh proot_component_bindings.sh rootfs_prefix_warmup.sh controller_runtime.sh x_server_common.sh x_server_legacy.sh x_server_xlorie.sh x_server_dimensions.py; do
    cp "$PROJECT_ROOT/termux-ui/src/main/assets/local-games/$asset" "$RUNTIME_DIR/$asset"
done
[ -z "${ROOTFS_LAUNCHER_UNDER_TEST:-}" ] || cp "$ROOTFS_LAUNCHER_UNDER_TEST" "$RUNTIME_DIR/start_rootfs_game.sh"
# This is a prepared prefix, matching the current shared RootFS and Wine package.
seed_prefix() {
    mkdir -p "$1/drive_c/windows/system32" "$1/drive_c/windows/syswow64" "$1/.games-runtime"
    printf 'registry\n' > "$1/system.reg"
    printf 'registry\n' > "$1/user.reg"
    printf '%s|%s\n' "$ROOTFS" "$2" > "$1/.games-runtime/runtime"
    printf '%s\n' "$2" > "$1/.games-runtime/wine-dlls"
}
seed_prefix "$PREFIX" rootfs_hangover_11.16
WINE_PREFIX=$PREFIX
export PREFIX="$TERMUX_ROOT/usr" FIXTURE_X11_PID="$TEST_ROOT/x11.pid"
python3 - "$FAKE_BIN/termux-x11" <<'PYX11'
from pathlib import Path
import sys
Path(sys.argv[1]).write_text('#!'+sys.executable+'\n'+"import os,pathlib,socket,time\np=pathlib.Path(os.environ['PREFIX'])/'tmp/.X11-unix/X0'\np.parent.mkdir(parents=True,exist_ok=True)\np.unlink(missing_ok=True)\ns=socket.socket(socket.AF_UNIX); s.bind(str(p))\npathlib.Path(os.environ['FIXTURE_X11_PID']).write_text(str(os.getpid()))\nwhile True: time.sleep(.1)\n")
PYX11

cat > "$TERMUX_ROOT/usr/bin/proot" <<'FAKEPROOT'
#!/bin/sh
: > "$CAPTURE_FILE"
[ "$GAMES_SESSION_SPEC" = "$FIXTURE_SESSION_SPEC" ] || exit 89
printf '%s\n' "${VK_ICD_FILENAMES:-}" > "$CAPTURE_FILE.vk-icd"
printf '%s\n' "${HODLL:-}" "${HODLL64:-}" > "$CAPTURE_FILE.emulators"
for value in "$@"; do
    printf '%s' "$value" | base64 | tr -d '\n' >> "$CAPTURE_FILE"
    printf '\n' >> "$CAPTURE_FILE"
done
if [ "${FAKE_PROOT_ORPHAN:-0}" = 1 ]; then
    python3 - <<'ORPHAN'
import os, subprocess, sys, time
from pathlib import Path
code = """import os,signal,time
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
Path(os.environ['FIXTURE_ORPHAN_PID']).write_text(str(os.getpid()))
while True: time.sleep(1)
"""
subprocess.Popen([sys.executable, '-c', code], start_new_session=True,
                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(200):
    if Path(os.environ['FIXTURE_ORPHAN_PID']).exists(): break
    time.sleep(.01)
else: raise RuntimeError('orphan did not start')
ORPHAN
fi
if [ "${FAKE_PROOT_WAIT:-0}" = 1 ]; then
    printf '%s\n' "$$" > "$FIXTURE_PROOT_PID"
    trap 'exit 0' TERM INT HUP
    [ "${FAKE_PROOT_IGNORE_TERM:-0}" != 1 ] || trap '' TERM
    while :; do sleep 1; done
fi
exit "${FAKE_PROOT_EXIT:-0}"
FAKEPROOT
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/env"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/wine"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/bin/wineboot"
printf '#!/bin/sh\ntrap "exit 0" TERM INT\nwhile :; do sleep 1; done\n' > "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so"
printf '#!/bin/sh\n[ -z "${PULSE_SERVER+x}" ] || exit 64\nprintf "%%s\\n" "$*" >> "$PULSE_FAKE_CALLS"\ncase "$1" in\n  --check) [ -e "$PULSE_FAKE_STATE" ] ;;\n  --start) : > "$PULSE_FAKE_STATE" ;;\n  --kill) rm -f "$PULSE_FAKE_STATE" ;;\nesac\n' > "$FAKE_BIN/pulseaudio"
printf '#!/bin/sh\nprintf "aarch64\\n"\n' > "$FAKE_BIN/uname"
cat > "$ROOTFS/etc/games-runtime.properties" <<'EOF'
schemaVersion=2
runtimeBackend=rootfs_proot
architecture=aarch64
runtimePackages=rootfs_hangover_11.16,rootfs_wine_10.0
graphicsDrivers=rootfs-virgl-mesa,rootfs-llvmpipe
dxWrappers=rootfs-dxvk,rootfs-wined3d
audioDrivers=pulseaudio,alsa
EOF
chmod 700 "$RUNTIME_DIR/start_rootfs_game.sh" "$TERMUX_ROOT/usr/bin/proot" \
    "$ROOTFS/usr/bin/env" "$ROOTFS/usr/bin/wine" "$ROOTFS/usr/bin/wineboot" \
    "$FAKE_BIN/termux-x11" "$TERMUX_ROOT/usr/glibc/opt/virgl/libvirgl_test_server.so" \
    "$FAKE_BIN/pulseaudio" "$FAKE_BIN/uname"

cp "$FAKE_BIN/pulseaudio" "$TERMUX_ROOT/usr/bin/pulseaudio"
export FIXTURE_SESSION_SPEC="$SPEC"
export FIXTURE_PROOT_PID="$TEST_ROOT/proot.pid"
export FIXTURE_ORPHAN_PID="$TEST_ROOT/orphan.pid"
export PULSE_FAKE_STATE="$TEST_ROOT/pulse-state"
export PULSE_FAKE_CALLS="$TEST_ROOT/pulse-calls"

encode() { printf '%s' "$1" | base64 | tr -d '\n'; }

{
    printf 'schemaVersion=6\n'
    printf 'taskId=%s\n' "$(encode rootfs-task)"
    printf 'gameId=%s\n' "$(encode game-1)"
    printf 'gameRootPath=%s\n' "$(encode "$GAME_ROOT")"
    printf 'executable=%s\n' "$(encode 'bin folder/game.exe')"
    printf 'workingDirectory=%s\n' "$(encode 'bin folder')"
    printf 'prefixPath=%s\n' "$(encode "$WINE_PREFIX")"
    printf 'winePackage=%s\n' "$(encode rootfs_hangover_11.16)"
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
    printf 'homeDirectory=%s\n' "$(encode "$HOME_PATH")"
    printf 'desktopOnly=0\n'
    printf 'eventPath=%s\n' "$(encode "$EVENT")"
    printf 'logPath=%s\n' "$(encode "$LOG")"
    printf 'lockPath=%s\n' "$(encode "$LOCK")"
    printf 'cancelPath=%s\n' "$(encode "$CANCEL")"
    printf 'timeoutSeconds=60\n'
    printf 'argumentCount=4\n'
    printf 'argument.0=%s\n' "$(encode 'hello world')"
    printf 'argument.1=%s\n' "$(encode '$HOME')"
    printf 'argument.2=%s\n' "$(encode "it's-safe")"
    printf 'argument.3=%s\n' "$(encode 'back\slash')"
    printf 'environmentCount=1\n'
    printf 'environment.0.key=%s\n' "$(encode CAPTURE_FILE)"
    printf 'environment.0.value=%s\n' "$(encode "$CAPTURE")"
} > "$SPEC"

sed 's/^schemaVersion=6$/schemaVersion=4/' "$SPEC" > "$SPEC.old"
if PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC.old" > "$TEST_ROOT/rejected" 2>&1; then
    printf 'Obsolete schema was accepted\n' >&2; exit 1
fi
grep -qx unsupported_launch_spec_schema "$TEST_ROOT/rejected"
[ ! -e "$EVENT" ]
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"

grep -qx "$(encode "$ROOTFS")" "$CAPTURE"
# Verify the complete guest argv, including ordering and literal special characters.
assert_guest_argv() {
    python3 - "$CAPTURE" "$@" <<'PYARGV'
import base64,sys
from pathlib import Path
args=[base64.b64decode(line).decode() for line in Path(sys.argv[1]).read_text().splitlines()]
actual=args[args.index('game-session')+1:]
expected=sys.argv[2:]+['explorer','/desktop=shell,1280x720','/mnt/games/game/bin folder/game.exe',
                       'hello world','$HOME',"it's-safe",r'back\slash']
assert actual==expected, ('RootFS argv changed',actual,expected)
PYARGV
}
assert_guest_argv /usr/bin/wine
# Capture the actual PRoot launch environment; switching never writes the prepared prefix.
printf 'wowbox64.dll\nlibarm64ecfex.dll\n' > "$TEST_ROOT/expected-emulators"
cmp "$TEST_ROOT/expected-emulators" "$CAPTURE.emulators"
cp "$SPEC" "$SPEC.hangover-default"
cp "$WINE_PREFIX/system.reg" "$TEST_ROOT/system.reg.before-mode-switch"
for mode in fex wowbox64; do
    sed 's/^environmentCount=1$/environmentCount=2/' "$SPEC.hangover-default" > "$SPEC"
    printf 'environment.1.key=%s\nenvironment.1.value=%s\n' \
        "$(encode GAMES_HANGOVER_EMULATOR)" "$(encode "$mode")" >> "$SPEC"
    PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
    assert_guest_argv /usr/bin/wine
    case "$mode" in fex) dll=libwow64fex.dll ;; *) dll=wowbox64.dll ;; esac
    printf '%s\nlibarm64ecfex.dll\n' "$dll" > "$TEST_ROOT/expected-emulators"
    cmp "$TEST_ROOT/expected-emulators" "$CAPTURE.emulators"
    cmp "$WINE_PREFIX/system.reg" "$TEST_ROOT/system.reg.before-mode-switch"
done
# Selected DLLs must be present; no implicit change of mode or translator.
for mode_and_dll in fex:libwow64fex.dll wowbox64:wowbox64.dll fex:libarm64ecfex.dll; do
    mode=${mode_and_dll%%:*}; dll=${mode_and_dll#*:}
    sed 's/^environmentCount=1$/environmentCount=2/' "$SPEC.hangover-default" > "$SPEC"
    printf 'environment.1.key=%s\nenvironment.1.value=%s\n' \
        "$(encode GAMES_HANGOVER_EMULATOR)" "$(encode "$mode")" >> "$SPEC"
    mv "$ROOTFS/usr/lib/wine/aarch64-windows/$dll" "$TEST_ROOT/$dll"
    rm -f "$CAPTURE"
    if PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"; then
        printf 'Missing Hangover emulator was accepted\n' >&2; exit 1
    fi
    grep -q "hangover_emulator_dll_missing:$dll" "$EVENT"
    [ ! -e "$CAPTURE" ]
    mv "$TEST_ROOT/$dll" "$ROOTFS/usr/lib/wine/aarch64-windows/$dll"
done
sed 's/^environmentCount=1$/environmentCount=2/' "$SPEC.hangover-default" > "$SPEC"
printf 'environment.1.key=%s\nenvironment.1.value=%s\n' \
    "$(encode GAMES_HANGOVER_EMULATOR)" "$(encode invented)" >> "$SPEC"
rm -f "$CAPTURE"
if PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"; then
    printf 'Invalid Hangover mode was accepted\n' >&2; exit 1
fi
grep -q hangover_emulator_invalid "$EVENT"
[ ! -e "$CAPTURE" ]
mv "$SPEC.hangover-default" "$SPEC"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
grep -q 'X11 display: .* wine=rootfs_hangover_11.16 target=1280x720' "$LOG"

# Current Debian Mesa uses unqualified ICD filenames. Verify the actual launch
# branch selects that file and still rejects a missing driver descriptor.
cp "$SPEC" "$SPEC.llvmpipe"
cp "$ROOTFS/etc/games-runtime.properties" "$TEST_ROOT/manifest.llvmpipe"
sed 's/^graphicsDrivers=.*/graphicsDrivers=rootfs-virgl-mesa,rootfs-llvmpipe,rootfs-turnip/' \
    "$TEST_ROOT/manifest.llvmpipe" > "$ROOTFS/etc/games-runtime.properties"
sed "s/^graphicsDriver=.*/graphicsDriver=$(encode rootfs-turnip)/" "$SPEC.llvmpipe" > "$SPEC"
mkdir -p "$ROOTFS/usr/share/vulkan/icd.d"
printf '{}\n' > "$ROOTFS/usr/share/vulkan/icd.d/freedreno_icd.json"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
grep -qx '/usr/share/vulkan/icd.d/freedreno_icd.json' "$CAPTURE.vk-icd"
rm "$ROOTFS/usr/share/vulkan/icd.d/freedreno_icd.json"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"rootfs_turnip_icd_missing"' "$EVENT"
mv "$SPEC.llvmpipe" "$SPEC"
mv "$TEST_ROOT/manifest.llvmpipe" "$ROOTFS/etc/games-runtime.properties"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
grep -qx "$(encode /mnt/games/game/bin\ folder/game.exe)" "$CAPTURE"
grep -qx "$(encode 'hello world')" "$CAPTURE"
grep -qx "$(encode '$HOME')" "$CAPTURE"
grep -qx "$(encode "it's-safe")" "$CAPTURE"
grep -qx "$(encode 'back\slash')" "$CAPTURE"
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

mkdir -p "$ROOTFS/opt/box64-wine/bin" "$ROOTFS/opt/box64/rootfs_box64_0.4.5/bin"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/opt/box64/rootfs_box64_0.4.5/bin/box64"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/opt/box64-wine/bin/wine64"
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/opt/box64-wine/bin/wineboot"
chmod 700 "$ROOTFS/opt/box64/rootfs_box64_0.4.5/bin/box64" "$ROOTFS/opt/box64-wine/bin/wine64" \
    "$ROOTFS/opt/box64-wine/bin/wineboot"
sed "s|^winePackage=.*$|winePackage=$(encode rootfs_wine_10.0)|" "$SPEC" > "$SPEC.tmp"
mv "$SPEC.tmp" "$SPEC"
# Switching translator families in the same nonempty C drive must preserve it and fail.
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"rootfs_prefix_translator_mismatch"' "$EVENT"
grep -qx registry "$WINE_PREFIX/system.reg"
BOX_PREFIX="$TERMUX_ROOT/front/containers/box64/prefix"
seed_prefix "$BOX_PREFIX" rootfs_wine_10.0
sed "s|^prefixPath=.*$|prefixPath=$(encode "$BOX_PREFIX")|" "$SPEC" > "$SPEC.tmp"
mv "$SPEC.tmp" "$SPEC"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
grep -qx "$(encode /opt/box64/rootfs_box64_0.4.5/bin/box64)" "$CAPTURE"
grep -qx "$(encode /opt/box64-wine/bin/wine64)" "$CAPTURE"
assert_guest_argv /opt/box64/rootfs_box64_0.4.5/bin/box64 /opt/box64-wine/bin/wine64
grep -q '"state":"SUCCEEDED".*"stage":"COMPLETE"' "$EVENT"
[ ! -e "$LOCK" ]

# v7 reads the same frozen source for host executable checks and every guest session.
# Empty guest mountpoints prove that none of these checks secretly use embedded payloads.
python3 - "$TERMUX_ROOT" "$ROOTFS" "$SPEC" <<'PYBIND'
import base64, pathlib, shutil, sys
files, rootfs, spec = map(pathlib.Path, sys.argv[1:])
bindings = []
for cid, guest, role in [('rootfs_wine_10.0','/opt/box64-wine','WINE'),
    ('rootfs_box64_0.4.5','/opt/box64/rootfs_box64_0.4.5','BOX64')]:
    version = files / 'front/components/install' / cid / 'versions/v1-fixture'
    version.mkdir(parents=True)
    source = version / "payload $' ="
    shutil.move(str(rootfs / guest.lstrip('/')), source)
    (rootfs / guest.lstrip('/')).mkdir()
    (version / '.games-component.properties').write_text(
        'schemaVersion=1\npackageName='+cid+'\nversion=1\nsha256='+'a'*64+'\n')
    bindings.append(dict(componentId=cid,version='1',sha256='a'*64,
        versionDirectory=str(version),contentRelativePath=source.name,
        guestTarget=guest,role=role,revision='1'))
raw = spec.read_text().replace('schemaVersion=6','schemaVersion=7')
raw += 'bindingCount='+str(len(bindings))+'\n'
for i, binding in enumerate(bindings):
    for key, value in binding.items():
        raw += 'binding.'+str(i)+'.'+key+'='+base64.b64encode(value.encode()).decode()+'\n'
spec.write_text(raw)
PYBIND
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
assert_guest_argv /opt/box64/rootfs_box64_0.4.5/bin/box64 /opt/box64-wine/bin/wine64
assert_frozen_bind_argv() {
    python3 - "$CAPTURE" "$TERMUX_ROOT" <<'PYBINDARGV'
import base64, pathlib, sys
args=[base64.b64decode(line).decode() for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
for cid, target in [('rootfs_wine_10.0','/opt/box64-wine'),
    ('rootfs_box64_0.4.5','/opt/box64/rootfs_box64_0.4.5')]:
    expected=str(pathlib.Path(sys.argv[2])/'front/components/install'/cid/'versions/v1-fixture'/"payload $' =")+':'+target
    index=args.index(expected)
    assert args[index-1]=='-b', args
PYBINDARGV
}
assert_frozen_bind_argv
# Activate the real v11 admission branch with frozen portable Wine/Box64 and no embedded
# optional capabilities. Ordinary launch must consume the prepared identity and never wineboot.
cat > "$ROOTFS/etc/games-runtime.properties" <<'V11MANIFEST'
schemaVersion=3
layout=external-components-v1
recipeSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
runtimeBackend=rootfs_proot
architecture=aarch64
runtimePackages=rootfs_hangover_11.16
graphicsDrivers=rootfs-virgl-mesa,rootfs-llvmpipe
dxWrappers=rootfs-wined3d
audioDrivers=pulseaudio
V11MANIFEST
(
    TERMUX_FILES_DIR=$TERMUX_ROOT
    . "$RUNTIME_DIR/proot_component_bindings.sh"
    while IFS= read -r field; do
        case "$field" in bindingCount=*|binding.*=*) proot_binding_field "${field%%=*}" "${field#*=}" ;; esac
    done < "$SPEC"
    ROOTFS_CANONICAL=$ROOTFS
    PREFIX_PATH=$BOX_PREFIX
    TEMPLATE_CACHE_DIR="$TERMUX_ROOT/usr/var/lib/proot-distro/games-template-cache"
    WINE_PACKAGE=rootfs_wine_10.0
    RUNTIME_TRANSLATOR=box64
    . "$RUNTIME_DIR/rootfs_prefix_warmup.sh"
    # A newly prepared prefix has its own recipe; the shared image remains on recipe a.
    GAMES_PREPARATION_RECIPE_SHA256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    printf '%s\n' "$GAMES_PREPARATION_RECIPE_SHA256" > "$BOX_PREFIX/.games-runtime/preparation-recipe"
    resolve_external_template_identity
    printf '%s\n' "$GAMES_TEMPLATE_KEY" > "$BOX_PREFIX/.games-runtime/prepared-identity"
)
printf '\nwarmup_rootfs_prefix() { warmup_fail forbidden_launch_wineboot; }\n' >> "$RUNTIME_DIR/rootfs_prefix_warmup.sh"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
assert_frozen_bind_argv
assert_guest_argv /opt/box64/rootfs_box64_0.4.5/bin/box64 /opt/box64-wine/bin/wine64
# Readiness is structural after digest checks were removed. A missing registry
# must still stop launch without wineboot; a missing digest marker is not a gate.
mv "$BOX_PREFIX/user.reg" "$BOX_PREFIX/user.reg-kept"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"rootfs_configuration_prepare_required"' "$EVENT"
grep -qx registry "$BOX_PREFIX/system.reg"
mv "$BOX_PREFIX/user.reg-kept" "$BOX_PREFIX/user.reg"
# Missing host dependencies require explicit preparation; launch must never run pkg.
mv "$TERMUX_ROOT/usr/bin/pulseaudio" "$TERMUX_ROOT/usr/bin/pulseaudio.disabled"
printf '#!/bin/sh\n: > "$FIXTURE_PKG_MARKER"\nexit 1\n' > "$TERMUX_ROOT/usr/bin/pkg"
chmod 700 "$TERMUX_ROOT/usr/bin/pkg"
export FIXTURE_PKG_MARKER="$TEST_ROOT/pkg-called"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"rootfs_configuration_prepare_required"' "$EVENT"
[ ! -e "$FIXTURE_PKG_MARKER" ]
# An installed audio server that cannot start still permits the silent argv branch.
printf '#!/bin/sh\nexit 1\n' > "$TERMUX_ROOT/usr/bin/pulseaudio"
chmod 700 "$TERMUX_ROOT/usr/bin/pulseaudio"
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
assert_frozen_bind_argv
assert_guest_argv /opt/box64/rootfs_box64_0.4.5/bin/box64 /opt/box64-wine/bin/wine64
mv "$TERMUX_ROOT/usr/bin/pulseaudio.disabled" "$TERMUX_ROOT/usr/bin/pulseaudio"

# A parent-directory link must not let any DLL/controller operation touch the source.
FROZEN_WINE_SOURCE="$TERMUX_ROOT/front/components/install/rootfs_wine_10.0/versions/v1-fixture/payload \$' ="
SOURCE_HASH_BEFORE=$(sha256sum "$FROZEN_WINE_SOURCE/bin/wine64")
mv "$BOX_PREFIX/drive_c/windows/system32" "$BOX_PREFIX/drive_c/windows/system32-kept"
ln -s "$FROZEN_WINE_SOURCE/bin" "$BOX_PREFIX/drive_c/windows/system32"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"rootfs_prefix_path_escape"' "$EVENT"
[ "$SOURCE_HASH_BEFORE" = "$(sha256sum "$FROZEN_WINE_SOURCE/bin/wine64")" ]
rm "$BOX_PREFIX/drive_c/windows/system32"
mv "$BOX_PREFIX/drive_c/windows/system32-kept" "$BOX_PREFIX/drive_c/windows/system32"

FAKE_PROOT_WAIT=1 PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC" &
LAUNCHER_PID=$!
attempt=0
while ! grep -q '"stage":"WAITING_FIRST_FRAME"' "$EVENT" || [ ! -f "$TEST_ROOT/proot.pid" ]; do
    kill -0 "$LAUNCHER_PID" 2>/dev/null || { wait "$LAUNCHER_PID"; exit 1; }
    attempt=$((attempt + 1))
    [ "$attempt" -lt 100 ] || { kill "$LAUNCHER_PID"; wait "$LAUNCHER_PID"; exit 1; }
    sleep 0.1
done
: > "$CANCEL"
kill -TERM "$LAUNCHER_PID" 2>/dev/null || true
wait "$LAUNCHER_PID"
! kill -0 "$(cat "$TEST_ROOT/proot.pid")" 2>/dev/null
grep -q '"state":"CANCELLED".*"stage":"COMPLETE"' "$EVENT"
[ ! -e "$LOCK" ]
rm -f "$CANCEL"

# Daemonized Wine-shaped children can survive the PRoot parent and ignore TERM.
# A different launch marker must survive every cleanup below.
GAMES_LAUNCH_TOKEN=unrelated-session python3 -c 'import time; time.sleep(180)' &
UNRELATED_PID=$!
printf '%s\n' "$UNRELATED_PID" > "$TEST_ROOT/unrelated.pid"
assert_orphan_stopped() {
    python3 - "$FIXTURE_ORPHAN_PID" <<'CHECKPROCESS'
import sys
from pathlib import Path
pid = int(Path(sys.argv[1]).read_text())
status = Path('/proc')/str(pid)/'status'
if status.exists():
    assert '\nState:\tZ' in status.read_text(), ('live session orphan', pid)
CHECKPROCESS
    kill -0 "$UNRELATED_PID"
    [ ! -e "$LOCK" ]
}
wait_test_session() {
    attempt=0
    while [ ! -f "$FIXTURE_ORPHAN_PID" ] || ! grep -q '^+ STARTED_AT=' "$LOG"; do
        kill -0 "$LAUNCHER_PID" 2>/dev/null || { wait "$LAUNCHER_PID"; exit 1; }
        attempt=$((attempt + 1))
        [ "$attempt" -lt 200 ] || { kill "$LAUNCHER_PID"; wait "$LAUNCHER_PID"; exit 1; }
        sleep 0.1
    done
}
rm -f "$FIXTURE_ORPHAN_PID" "$FIXTURE_PROOT_PID"
FAKE_PROOT_ORPHAN=1 PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
assert_orphan_stopped
grep -q '"state":"SUCCEEDED".*"stage":"COMPLETE"' "$EVENT"
for stop_method in cancel_file signal stubborn_proot; do
    rm -f "$FIXTURE_ORPHAN_PID" "$FIXTURE_PROOT_PID" "$CANCEL"
    ignore_term=0
    [ "$stop_method" != stubborn_proot ] || ignore_term=1
    FAKE_PROOT_ORPHAN=1 FAKE_PROOT_WAIT=1 FAKE_PROOT_IGNORE_TERM=$ignore_term \
        PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC" &
    LAUNCHER_PID=$!
    wait_test_session
    case "$stop_method" in
        cancel_file|stubborn_proot) : > "$CANCEL" ;;
        signal) kill -TERM "$LAUNCHER_PID" ;;
    esac
    wait "$LAUNCHER_PID"
    assert_orphan_stopped
    grep -q '"state":"CANCELLED".*"stage":"COMPLETE"' "$EVENT"
done
rm -f "$FIXTURE_ORPHAN_PID" "$FIXTURE_PROOT_PID" "$CANCEL"
set +e
FAKE_PROOT_ORPHAN=1 FAKE_PROOT_EXIT=7 PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
assert_orphan_stopped
grep -q '"state":"FAILED".*"exitCode":7.*"errorCode":"wine_exit_nonzero"' "$EVENT"

# Advance only the fixture's clock after the main loop starts; avoid a real 30s timeout.
export FIXTURE_REAL_DATE=$(command -v date)
export FIXTURE_CLOCK_EXPIRED="$TEST_ROOT/clock-expired"
cat > "$FAKE_BIN/date" <<'FAKEDATE'
#!/bin/sh
if [ "$*" = +%s ] && [ -e "$FIXTURE_CLOCK_EXPIRED" ]; then
    now=$($FIXTURE_REAL_DATE +%s)
    printf '%s\n' "$((now + 100000))"
else
    exec "$FIXTURE_REAL_DATE" "$@"
fi
FAKEDATE
chmod 700 "$FAKE_BIN/date"
rm -f "$FIXTURE_ORPHAN_PID" "$FIXTURE_PROOT_PID"
FAKE_PROOT_ORPHAN=1 FAKE_PROOT_WAIT=1 PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC" &
LAUNCHER_PID=$!
wait_test_session
: > "$FIXTURE_CLOCK_EXPIRED"
set +e
wait "$LAUNCHER_PID"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
assert_orphan_stopped
grep -q '"state":"FAILED".*"errorCode":"launch_timeout"' "$EVENT"
rm "$FAKE_BIN/date" "$FIXTURE_CLOCK_EXPIRED"

# Profiles cannot impersonate a different internal session marker.
sed 's/^environmentCount=1$/environmentCount=2/' "$SPEC" > "$SPEC.reserved"
printf 'environment.1.key=%s\nenvironment.1.value=%s\n' \
    "$(encode GAMES_LAUNCH_TOKEN)" "$(encode unrelated-session)" >> "$SPEC.reserved"
if PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC.reserved" > "$TEST_ROOT/reserved-rejected" 2>&1; then
    printf 'User-supplied session marker accepted\n' >&2; exit 1
fi
grep -qx reserved_launch_environment_key "$TEST_ROOT/reserved-rejected"
kill "$UNRELATED_PID"
wait "$UNRELATED_PID" 2>/dev/null || true
rm "$TEST_ROOT/unrelated.pid"

mv "$TERMUX_ROOT/usr/bin/proot" "$TERMUX_ROOT/usr/bin/proot.disabled"
set +e
PATH="$FAKE_BIN:$PATH" "$SCRIPT" "$SPEC"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ]
grep -q '"errorCode":"proot_runtime_missing"' "$EVENT"
[ ! -e "$LOCK" ]

printf 'RootFS launch script integration test passed.\n'
