#!/usr/bin/env sh
# Verifies controller_runtime.sh: the patched xinput/dinput DLLs and winhandler.exe are released into a Wine
# prefix (64-bit and 32-bit layouts), the release is idempotent, a symlinked stub is replaced instead of written
# through, the launchers use it, and the DLL override strings are right.

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
for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
    same "$STAGE/x86_64-windows/$dll.dll" "$P64/drive_c/windows/system32/$dll.dll" || fail "system32/$dll.dll"
    same "$STAGE/i386-windows/$dll.dll" "$P64/drive_c/windows/syswow64/$dll.dll" || fail "syswow64/$dll.dll"
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
same "$STAGE/x86_64-windows/xinput1_4.dll" "$P64/drive_c/windows/system32/xinput1_4.dll" || fail "DLL not repaired"

# --- 32-bit prefix (no syswow64): system32 takes the i386 DLLs ---
P32="$WORK/p32"
mkdir -p "$P32/drive_c/windows/system32"
install_games_controller_runtime "$P32" "$RUNTIME" || fail "32-bit install failed"
same "$STAGE/i386-windows/xinput1_3.dll" "$P32/drive_c/windows/system32/xinput1_3.dll" || fail "32-bit system32"
[ ! -e "$P32/drive_c/windows/syswow64" ] || fail "syswow64 was created in a 32-bit prefix"

# --- failures are reported, not hidden ---
install_games_controller_runtime "$WORK/missing" "$RUNTIME" && fail "missing prefix accepted"
EMPTY="$WORK/empty"; mkdir -p "$EMPTY"
install_games_controller_runtime "$P32" "$EMPTY" && fail "missing payload accepted"

# --- override strings ---
[ "$(games_controller_dll_overrides 0)" = 'dinput=native;dinput8=native;' ] || fail "dinput overrides"
case "$(games_controller_dll_overrides 1)" in
    xinput1_1=native\;xinput1_2=native\;xinput1_3=native\;xinput1_4=native\;xinput9_1_0=native\;xinputuap=native\;) ;;
    *) fail "xinput overrides" ;;
esac

# --- both launchers use it, and the app installs the payload ---
grep -q 'controller_runtime.sh' "$ASSETS/start_termux_box_game.sh" || fail "native launcher does not source it"
grep -q 'install_games_controller_runtime "\$WINEPREFIX"' "$ASSETS/start_termux_box_game.sh" || fail "native launcher does not install"
grep -q 'CONTROLLER_WRAPPER' "$ASSETS/start_termux_box_game.sh" || fail "native launcher has no wrapper"
grep -q 'controller_runtime.sh' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher does not source it"
grep -q 'install_games_controller_runtime "\$PREFIX_PATH"' "$ASSETS/start_rootfs_game.sh" || fail "rootfs launcher does not install"
grep -q 'controller_winhandler.exe' "$PROJECT_ROOT/termux-ui/src/main/java/com/termux/ui/service/LaunchScriptInstaller.java" || fail "installer does not deploy winhandler.exe"

echo "controller runtime checks passed"
