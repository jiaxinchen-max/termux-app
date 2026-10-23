#!/usr/bin/env sh
# Verifies controller_runtime.sh: the patched xinput/dinput DLLs are installed as shipped (builtin marker intact) into
# the Wine runtime for the native launcher, and as marker-free copies into the prefix (64-bit and 32-bit layouts) for
# the rootfs launcher, and the Hangover build of the DLLs, marker intact, into the guest Wine and the prefix of an
# arm64 Hangover container; winhandler.exe is released into the prefix; the release is idempotent, a symlinked stub is
# replaced instead of written through, the launchers use it, and the DLL override strings are right.

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ASSETS="$PROJECT_ROOT/termux-ui/src/main/assets/local-games"
PAYLOAD="$PROJECT_ROOT/termux-x11/src/main/assets"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

fail() { echo "FAIL: $*" >&2; exit 1; }

RUNTIME="$WORK/runtime"
mkdir -p "$RUNTIME"
# The app ships the archive inflated (aapt2 drops the .gz suffix): wine.tar.
gzip -dc "$PAYLOAD/wine.tar.gz" > "$RUNTIME/controller_wine.tar"
cp "$PAYLOAD/winhandler.exe" "$RUNTIME/controller_winhandler.exe"
. "$ASSETS/controller_runtime.sh"

same() { [ "$(cksum < "$1")" = "$(cksum < "$2")" ]; }

# --- 64-bit prefix (has syswow64) ---
P64="$WORK/p64"
mkdir -p "$P64/drive_c/windows/system32" "$P64/drive_c/windows/syswow64" "$WORK/shared"
echo stock > "$WORK/shared/xinput1_3.dll"
ln -s "$WORK/shared/xinput1_3.dll" "$P64/drive_c/windows/system32/xinput1_3.dll"
echo stock32 > "$P64/drive_c/windows/syswow64/dinput8.dll"
install_games_controller_runtime "$P64" "$RUNTIME" || fail "64-bit install failed"
STAGE="$RUNTIME/controller-stage/wine"
NATIVE_STAGE="$RUNTIME/controller-stage/wine-native"
# marker <file>: the 16 bytes at offset 0x40 of the DOS header.
marker() { dd if="$1" bs=1 skip=64 count=16 2>/dev/null; }
for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
    same "$NATIVE_STAGE/x86_64-windows/$dll.dll" "$P64/drive_c/windows/system32/$dll.dll" || fail "system32/$dll.dll"
    same "$NATIVE_STAGE/i386-windows/$dll.dll" "$P64/drive_c/windows/syswow64/$dll.dll" || fail "syswow64/$dll.dll"
    # Wine's native mode rejects a DLL with the builtin marker, so the prefix copies must not have it.
    [ "$(marker "$P64/drive_c/windows/system32/$dll.dll")" != 'Wine builtin DLL' ] || fail "system32/$dll.dll keeps the builtin marker"
    [ "$(marker "$P64/drive_c/windows/syswow64/$dll.dll")" != 'Wine builtin DLL' ] || fail "syswow64/$dll.dll keeps the builtin marker"
    # The shipped copies keep it: that is what lets Wine load them as builtin DLLs from its own directory.
    [ "$(marker "$STAGE/x86_64-windows/$dll.dll")" = 'Wine builtin DLL' ] || fail "staged $dll.dll lost the builtin marker"
    # Only the marker differs.
    [ "$(cmp -l "$STAGE/x86_64-windows/$dll.dll" "$NATIVE_STAGE/x86_64-windows/$dll.dll" | wc -l)" -le 16 ] || fail "$dll.dll differs from the shipped file by more than the marker"
done
[ ! -L "$P64/drive_c/windows/system32/xinput1_3.dll" ] || fail "symlinked stub was kept"
[ "$(cat "$WORK/shared/xinput1_3.dll")" = stock ] || fail "shared Wine file was modified through the symlink"
same "$PAYLOAD/winhandler.exe" "$P64/drive_c/windows/winhandler.exe" || fail "winhandler.exe"

# --- idempotent: nothing is rewritten the second time ---
touch -d '2001-01-01 00:00:00' "$P64/drive_c/windows/system32/xinput1_3.dll" "$P64/drive_c/windows/winhandler.exe"
install_games_controller_runtime "$P64" "$RUNTIME" || fail "second install failed"
[ "$(date -r "$P64/drive_c/windows/system32/xinput1_3.dll" +%Y)" = 2001 ] || fail "identical DLL was rewritten"
[ "$(date -r "$P64/drive_c/windows/winhandler.exe" +%Y)" = 2001 ] || fail "identical winhandler.exe was rewritten"

# --- a modified DLL is repaired ---
echo broken > "$P64/drive_c/windows/system32/xinput1_4.dll"
install_games_controller_runtime "$P64" "$RUNTIME" || fail "repair install failed"
same "$NATIVE_STAGE/x86_64-windows/xinput1_4.dll" "$P64/drive_c/windows/system32/xinput1_4.dll" || fail "DLL not repaired"

# --- 32-bit prefix (no syswow64): system32 takes the i386 DLLs ---
P32="$WORK/p32"
mkdir -p "$P32/drive_c/windows/system32"
install_games_controller_runtime "$P32" "$RUNTIME" || fail "32-bit install failed"
same "$NATIVE_STAGE/i386-windows/xinput1_3.dll" "$P32/drive_c/windows/system32/xinput1_3.dll" || fail "32-bit system32"
[ ! -e "$P32/drive_c/windows/syswow64" ] || fail "syswow64 was created in a 32-bit prefix"

# --- native launcher: the DLLs go into the Wine runtime, as shipped, and the stock files are kept once ---
WINE="$WORK/wine"
PB="$WORK/pb"
mkdir -p "$WINE/lib/wine/x86_64-windows" "$WINE/lib/wine/i386-windows" "$PB/drive_c/windows/system32" "$PB/drive_c/windows/syswow64"
echo stock64 > "$WINE/lib/wine/x86_64-windows/xinput1_3.dll"
echo stock32 > "$WINE/lib/wine/i386-windows/xinput1_3.dll"
echo untouched > "$WINE/lib/wine/x86_64-windows/kernel32.dll"
install_games_controller_runtime "$PB" "$RUNTIME" "$WINE" || fail "install into the Wine runtime failed"
for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
    same "$STAGE/x86_64-windows/$dll.dll" "$WINE/lib/wine/x86_64-windows/$dll.dll" || fail "Wine runtime x86_64 $dll.dll"
    same "$STAGE/i386-windows/$dll.dll" "$WINE/lib/wine/i386-windows/$dll.dll" || fail "Wine runtime i386 $dll.dll"
    [ "$(marker "$WINE/lib/wine/x86_64-windows/$dll.dll")" = 'Wine builtin DLL' ] || fail "installed $dll.dll lost the builtin marker"
done
[ "$(cat "$WINE/lib/wine/x86_64-windows/xinput1_3.dll.stock")" = stock64 ] || fail "stock x86_64 xinput1_3.dll was not kept"
[ "$(cat "$WINE/lib/wine/i386-windows/xinput1_3.dll.stock")" = stock32 ] || fail "stock i386 xinput1_3.dll was not kept"
[ "$(cat "$WINE/lib/wine/x86_64-windows/kernel32.dll")" = untouched ] || fail "an unrelated Wine file was modified"
[ ! -e "$PB/drive_c/windows/system32/xinput1_3.dll" ] || fail "the DLLs were copied into the prefix as well"
same "$PAYLOAD/winhandler.exe" "$PB/drive_c/windows/winhandler.exe" || fail "winhandler.exe with a Wine runtime"
# A second install changes nothing; the first stock file is not replaced by the patched one.
touch -d '2001-01-01 00:00:00' "$WINE/lib/wine/x86_64-windows/xinput1_3.dll"
install_games_controller_runtime "$PB" "$RUNTIME" "$WINE" || fail "second install into the Wine runtime failed"
[ "$(date -r "$WINE/lib/wine/x86_64-windows/xinput1_3.dll" +%Y)" = 2001 ] || fail "identical DLL in the Wine runtime was rewritten"
[ "$(cat "$WINE/lib/wine/x86_64-windows/xinput1_3.dll.stock")" = stock64 ] || fail "the stock backup was overwritten"
# Only the architecture directories that exist are filled; none at all is a failure.
WINE64="$WORK/wine64"
mkdir -p "$WINE64/lib/wine/x86_64-windows"
install_games_controller_runtime "$PB" "$RUNTIME" "$WINE64" || fail "64-bit only Wine runtime failed"
[ -f "$WINE64/lib/wine/x86_64-windows/xinput1_3.dll" ] || fail "64-bit only Wine runtime got no DLL"
[ ! -e "$WINE64/lib/wine/i386-windows" ] || fail "an i386 directory was created in a Wine runtime without one"
install_games_controller_runtime "$PB" "$RUNTIME" "$WORK/no-wine" && fail "Wine runtime without lib/wine accepted"

# --- failures are reported, not hidden ---
install_games_controller_runtime "$WORK/missing" "$RUNTIME" && fail "missing prefix accepted"
EMPTY="$WORK/empty"; mkdir -p "$EMPTY"
install_games_controller_runtime "$P32" "$EMPTY" && fail "missing payload accepted"

# --- arm64 Hangover: its own DLL build, marker intact, in the guest Wine and in the prefix ---
HANGOVER_STAGE="$RUNTIME/controller-stage/wine-hangover"
HWINE="$WORK/hwine"
PH="$WORK/ph"
mkdir -p "$HWINE/aarch64-windows" "$HWINE/i386-windows" "$PH/drive_c/windows/system32" "$PH/drive_c/windows/syswow64"
echo stock-a64 > "$HWINE/aarch64-windows/xinput1_3.dll"
echo stock-i386 > "$HWINE/i386-windows/xinput1_3.dll"
echo untouched > "$HWINE/aarch64-windows/kernel32.dll"
echo prefix-stock-a64 > "$PH/drive_c/windows/system32/xinput1_3.dll"
echo prefix-stock-i386 > "$PH/drive_c/windows/syswow64/xinput1_3.dll"
install_games_controller_hangover "$PH" "$RUNTIME" "$HWINE" || fail "Hangover install failed"
# pe_machine <file>: the PE machine type (0xaa64) is the little-endian word after the PE signature.
pe_machine() { _pe=$(od -An -tu4 -j60 -N4 "$1" | tr -d ' '); od -An -tx2 -j$((_pe + 4)) -N2 "$1" | tr -d ' '; }
for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
    same "$HANGOVER_STAGE/aarch64-windows/$dll.dll" "$HWINE/aarch64-windows/$dll.dll" || fail "Hangover Wine aarch64 $dll.dll"
    same "$HANGOVER_STAGE/aarch64-windows/$dll.dll" "$PH/drive_c/windows/system32/$dll.dll" || fail "Hangover system32/$dll.dll"
    same "$HANGOVER_STAGE/i386-windows/$dll.dll" "$PH/drive_c/windows/syswow64/$dll.dll" || fail "Hangover syswow64/$dll.dll"
    [ "$(pe_machine "$PH/drive_c/windows/system32/$dll.dll")" = aa64 ] || fail "Hangover system32/$dll.dll is not an arm64 DLL"
    [ "$(marker "$PH/drive_c/windows/system32/$dll.dll")" = 'Wine builtin DLL' ] || fail "Hangover system32/$dll.dll lost the builtin marker"
    [ "$(marker "$PH/drive_c/windows/syswow64/$dll.dll")" = 'Wine builtin DLL' ] || fail "Hangover syswow64/$dll.dll lost the builtin marker"
done
[ "$(cat "$HWINE/aarch64-windows/xinput1_3.dll.stock")" = stock-a64 ] || fail "Hangover Wine stock aarch64 DLL was not kept"
[ "$(cat "$HWINE/i386-windows/xinput1_3.dll.stock")" = stock-i386 ] || fail "Hangover Wine stock i386 DLL was not kept"
[ "$(cat "$PH/drive_c/windows/system32/xinput1_3.dll.stock")" = prefix-stock-a64 ] || fail "prefix stock DLL was not kept"
[ "$(cat "$HWINE/aarch64-windows/kernel32.dll")" = untouched ] || fail "an unrelated Hangover Wine file was modified"
same "$PAYLOAD/winhandler.exe" "$PH/drive_c/windows/winhandler.exe" || fail "winhandler.exe in a Hangover prefix"
# No x86 DLL of the native trees reaches an arm64 prefix.
for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
    same "$NATIVE_STAGE/x86_64-windows/$dll.dll" "$PH/drive_c/windows/system32/$dll.dll" && fail "x86_64 $dll.dll was copied into an arm64 prefix"
done
# Idempotent: nothing is rewritten and the first stock file stays.
touch -d '2001-01-01 00:00:00' "$PH/drive_c/windows/system32/xinput1_3.dll" "$HWINE/aarch64-windows/xinput1_3.dll"
install_games_controller_hangover "$PH" "$RUNTIME" "$HWINE" || fail "second Hangover install failed"
[ "$(date -r "$PH/drive_c/windows/system32/xinput1_3.dll" +%Y)" = 2001 ] || fail "identical Hangover prefix DLL was rewritten"
[ "$(date -r "$HWINE/aarch64-windows/xinput1_3.dll" +%Y)" = 2001 ] || fail "identical Hangover Wine DLL was rewritten"
[ "$(cat "$PH/drive_c/windows/system32/xinput1_3.dll.stock")" = prefix-stock-a64 ] || fail "the Hangover stock backup was overwritten"
# Without a Hangover tree in the archive, or without the guest Wine dir, nothing is touched.
NOHANGOVER="$WORK/no-hangover"
mkdir -p "$NOHANGOVER/unpack"
tar -xf "$RUNTIME/controller_wine.tar" -C "$NOHANGOVER/unpack"
rm -rf "$NOHANGOVER/unpack/wine-hangover"
tar -cf "$NOHANGOVER/controller_wine.tar" -C "$NOHANGOVER/unpack" wine
cp "$PAYLOAD/winhandler.exe" "$NOHANGOVER/controller_winhandler.exe"
PN="$WORK/pn"
mkdir -p "$PN/drive_c/windows/system32"
install_games_controller_hangover "$PN" "$NOHANGOVER" "$HWINE" && fail "archive without wine-hangover/ accepted"
[ -z "$(ls "$PN/drive_c/windows/system32")" ] || fail "a failed Hangover install touched the prefix"
install_games_controller_hangover "$PN" "$RUNTIME" "$WORK/no-wine" && fail "Hangover install without a guest Wine accepted"
[ -z "$(ls "$PN/drive_c/windows/system32")" ] || fail "an install without a guest Wine touched the prefix"
# The rootfs launcher uses it for Hangover only, and never switches these DLLs to native there.
grep -q 'install_games_controller_hangover "\$PREFIX_PATH" .* "\$ROOTFS_CANONICAL/usr/lib/wine"' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher does not install the Hangover DLLs"
grep -qF '[ "$RUNTIME_TRANSLATOR" != hangover ] || CONTROLLER_DLL_OVERRIDES=' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher still applies native overrides under Hangover"

# --- override strings ---
# games_overrides <gamepad mapper> [NAME=value ...]: the controller overrides for that Libraries setting.
games_overrides() {
    (
        _mapper=$1; shift
        unset GAMES_DLL_DINPUT GAMES_DLL_XINPUT
        for _assignment in "$@"; do export "$_assignment"; done
        games_controller_dll_overrides "$_mapper"
    )
}
DINPUT_DLLS='dinput dinput8'
XINPUT_DLLS='xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap'
# expected_overrides <mode> <dll>...: every DLL with the same load order.
expected_overrides() {
    _expected_mode=$1; shift
    for _expected_dll in "$@"; do printf '%s=%s;' "$_expected_dll" "$_expected_mode"; done
}
# An empty setting prints nothing (the DLLs load as builtin, which is what is installed); each mode from
# Runtime > Libraries applies to the DLLs of its own mapper only.
[ -z "$(games_overrides 0)" ] || fail "DirectInput overrides without a setting"
[ -z "$(games_overrides 1)" ] || fail "XInput overrides without a setting"
[ -z "$(games_overrides 0 GAMES_DLL_DINPUT=)" ] || fail "empty DirectInput setting prints overrides"
[ -z "$(games_overrides 1 GAMES_DLL_XINPUT=)" ] || fail "empty XInput setting prints overrides"
for mode in builtin native native,builtin; do
    # shellcheck disable=SC2086
    [ "$(games_overrides 0 GAMES_DLL_DINPUT=$mode)" = "$(expected_overrides $mode $DINPUT_DLLS)" ] || fail "DirectInput $mode"
    # shellcheck disable=SC2086
    [ "$(games_overrides 1 GAMES_DLL_XINPUT=$mode)" = "$(expected_overrides $mode $XINPUT_DLLS)" ] || fail "XInput $mode"
    [ -z "$(games_overrides 0 GAMES_DLL_XINPUT=$mode)" ] || fail "XInput setting leaked into the DInput mapper ($mode)"
    [ -z "$(games_overrides 1 GAMES_DLL_DINPUT=$mode)" ] || fail "DirectInput setting leaked into the XInput mapper ($mode)"
done

# --- the program path handed to winhandler.exe is a Windows path ---
[ "$(games_controller_windows_target /mnt/games/game/wfm.exe)" = 'Z:\mnt\games\game\wfm.exe' ] || fail "windows target of a guest path"
[ "$(games_controller_windows_target '/mnt/games/game/My Game/a b.exe')" = 'Z:\mnt\games\game\My Game\a b.exe' ] || fail "windows target keeps spaces"
grep -q 'games_controller_windows_target "\$GUEST_EXECUTABLE"' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher passes a Unix path to winhandler.exe"

# --- both launchers use it, and the app installs the payload ---
grep -q 'controller_runtime.sh' "$ASSETS/start_termux_box_game.sh" || fail "native launcher does not source it"
grep -q 'install_games_controller_runtime "\$WINEPREFIX"' "$ASSETS/start_termux_box_game.sh" || fail "native launcher does not install"
grep -q 'install_games_controller_runtime "\$WINEPREFIX" "\$GAMES_RUNTIME_SCRIPT_DIR" "\$WINE_PATH"' "$ASSETS/start_termux_box_game.sh" || fail "native launcher does not pass the Wine runtime"
grep -q 'CONTROLLER_WRAPPER' "$ASSETS/start_termux_box_game.sh" || fail "native launcher has no wrapper"
grep -q 'controller_runtime.sh' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher does not source it"
grep -q 'install_games_controller_runtime "\$PREFIX_PATH"' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher does not install"
grep -q 'controller_winhandler.exe' "$PROJECT_ROOT/termux-ui/src/main/java/com/termux/ui/service/LaunchScriptInstaller.java" || fail "installer does not deploy winhandler.exe"

# --- the native launcher really exports the overrides to Wine ---
# The launcher once ended its override block with a bare `export WINEDLLOVERRIDES`, which exports nothing when the
# variable was never assigned, so no override reached Wine. Run that block as the launcher does and look at what ends
# up in the environment (`env` lists exported variables only).
OVERRIDES_BLOCK="$WORK/overrides-block.sh"
sed -n '/^WINE_DLL_OVERRIDES=\$(games_controller_dll_overrides/,/unset WINEDLLOVERRIDES$/p' \
    "$ASSETS/start_termux_box_game.sh" > "$OVERRIDES_BLOCK"
[ -s "$OVERRIDES_BLOCK" ] || fail "overrides block not found in the native launcher"

# launcher_overrides <gamepad mapper> [NAME=value ...]: the exported WINEDLLOVERRIDES, or <unset>.
launcher_overrides() {
    (
        _mapper=$1; shift
        unset WINEDLLOVERRIDES GAMES_DLL_D3D GAMES_DLL_DSOUND GAMES_DLL_DMUSIC GAMES_DLL_DSHOW GAMES_DLL_DPLAY GAMES_DLL_DINPUT GAMES_DLL_XINPUT
        for _assignment in "$@"; do export "$_assignment"; done
        TERMUX_BOX_GAMEPAD_MAPPER=$_mapper
        . "$OVERRIDES_BLOCK"
        _value=$(env | sed -n 's/^WINEDLLOVERRIDES=//p')
        printf '%s' "${_value:-<unset>}"
    )
}

# Nothing is exported for the controller DLLs while their settings are empty: they load as builtin DLLs.
[ "$(launcher_overrides 1)" = '<unset>' ] || fail "native launcher exports controller overrides without a setting"
[ "$(launcher_overrides 0)" = '<unset>' ] || fail "native launcher exports dinput overrides without a setting"
[ "$(launcher_overrides 1 GAMES_DLL_D3D=native,builtin)" = 'd3d8,d3d9,d3d10core,d3d11,dxgi=native,builtin;' ] \
    || fail "native launcher does not export the D3D override on its own"
# The Libraries settings reach the exported variable through the launcher, ahead of the other libraries.
[ "$(launcher_overrides 1 GAMES_DLL_XINPUT=native,builtin)" = "$(expected_overrides native,builtin $XINPUT_DLLS)" ] \
    || fail "native launcher ignores the XInput library setting"
[ "$(launcher_overrides 0 GAMES_DLL_DINPUT=builtin GAMES_DLL_DSOUND=b)" = 'dinput=builtin;dinput8=builtin;dsound=b;' ] \
    || fail "native launcher ignores the DirectInput library setting"
[ "$(launcher_overrides 0 GAMES_DLL_DINPUT=native GAMES_DLL_D3D=n GAMES_DLL_DSOUND=b GAMES_DLL_DMUSIC=d GAMES_DLL_DSHOW=n,b GAMES_DLL_DPLAY=b)" \
    = 'dinput=native;dinput8=native;d3d8,d3d9,d3d10core,d3d11,dxgi=n;dsound=b;dmusic=d;quartz=n,b;dplayx=b;' ] \
    || fail "native launcher override order is wrong"
# An empty override string must leave the variable unset, even if the caller had one.
[ "$( (games_controller_dll_overrides() { :; }; WINEDLLOVERRIDES=stale; export WINEDLLOVERRIDES; \
    unset GAMES_DLL_D3D GAMES_DLL_DSOUND GAMES_DLL_DMUSIC GAMES_DLL_DSHOW GAMES_DLL_DPLAY; \
    . "$OVERRIDES_BLOCK"; _value=$(env | sed -n 's/^WINEDLLOVERRIDES=//p'); printf '%s' "${_value:-<unset>}") )" = '<unset>' ] \
    || fail "native launcher keeps WINEDLLOVERRIDES set when there is nothing to override"

echo "controller runtime checks passed"
