# Wine side of the on-screen / external controller, shared by the native (GLIBC) and the proot (RootFS)
# launchers. Sourced, POSIX sh.
#
# The controller reaches a game through two pieces that the stock Wine builds do not have:
#   * patched xinput1_1/1_2/1_3/1_4/9_1_0/xinputuap and dinput/dinput8 PE DLLs (wine.tar.gz in the app sources, packaged as wine.tar,
#     x86_64-windows/ and i386-windows/). They carry Wine's "builtin" marker, so Wine only loads them as *builtin* DLLs
#     from its own lib/wine directories: the native launcher installs them there, which makes the default (built-in)
#     load order use them. Wine's "native" mode rejects a file with that marker, and with "native,builtin" it silently
#     falls back to the stock builtin. The rootfs launcher copies marker-free versions into the prefix instead, for
#     the "native" settings of Runtime > Libraries;
#   * winhandler.exe, a wrapper the game is started through: it runs the real program and is the Wine end of
#     the controller / process bridge the app talks to over UDP (WinHandler, ports 7946/7947).
# This replaces the removed setMoBoxEnv script, which unpacked the DLLs into the shared Wine runtime and
# copied winhandler.exe next to the old start-tfm launcher.
#
# The app installs the payload next to the launcher as controller_wine.tar and controller_winhandler.exe.

GAMES_CONTROLLER_WINHANDLER_WINDOWS_PATH='C:\windows\winhandler.exe'

# Checksum of a file that ignores the file name, or nothing when it does not exist.
_games_controller_sum() {
    [ -f "$1" ] || return 0
    cksum < "$1" 2>/dev/null
}

# Removes the "Wine builtin DLL" marker (16 bytes at offset 0x40 of the DOS header) so that Wine treats the file as an
# ordinary native DLL. Files without the marker are left alone.
_games_controller_strip_marker() {
    [ "$(dd if="$1" bs=1 skip=64 count=16 2>/dev/null)" = 'Wine builtin DLL' ] || return 0
    printf '\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000' | dd of="$1" bs=1 seek=64 conv=notrunc 2>/dev/null
}

# Copies every *.dll of $1 into $2 unless it is already identical. A symlinked target is replaced rather than
# written through, so a prefix whose stubs link into a shared Wine build never modifies that build. With a third
# argument the replaced file is kept once as <name>.stock.
_games_controller_copy_dlls() {
    [ -d "$1" ] || return 0
    mkdir -p "$2" || return 1
    for _source in "$1"/*.dll; do
        [ -f "$_source" ] || continue
        _target="$2/${_source##*/}"
        [ "$(_games_controller_sum "$_source")" = "$(_games_controller_sum "$_target")" ] && continue
        [ -L "$_target" ] && rm -f "$_target"
        if [ -n "${3:-}" ] && [ -f "$_target" ] && [ ! -e "$_target.stock" ]; then
            cp -p "$_target" "$_target.stock" || return 1
        fi
        cp -f "$_source" "$_target" || return 1
    done
}

# Unpacks the DLL archive next to it, once per archive content: wine/ keeps the DLLs as shipped, wine-native/ holds
# the same DLLs without the builtin marker.
_games_controller_stage() {
    _runtime_dir=$1
    _archive="$_runtime_dir/controller_wine.tar"
    _stage="$_runtime_dir/controller-stage"
    [ -f "$_archive" ] || return 1
    _archive_sum=$(_games_controller_sum "$_archive")
    [ -n "$_archive_sum" ] || return 1
    [ "$(cat "$_stage/.archive-sum" 2>/dev/null)" = "$_archive_sum" ] && [ -d "$_stage/wine-native" ] && return 0
    rm -rf "$_stage.tmp"
    mkdir -p "$_stage.tmp" || return 1
    tar -xf "$_archive" -C "$_stage.tmp" || { rm -rf "$_stage.tmp"; return 1; }
    [ -f "$_stage.tmp/wine/x86_64-windows/xinput1_3.dll" ] || { rm -rf "$_stage.tmp"; return 1; }
    cp -R "$_stage.tmp/wine" "$_stage.tmp/wine-native" || { rm -rf "$_stage.tmp"; return 1; }
    for _dll in "$_stage.tmp"/wine-native/*/*.dll; do
        [ -f "$_dll" ] && _games_controller_strip_marker "$_dll"
    done
    printf '%s\n' "$_archive_sum" > "$_stage.tmp/.archive-sum"
    rm -rf "$_stage"
    mv "$_stage.tmp" "$_stage"
}

# install_games_controller_runtime <wine prefix> <runtime dir> [wine runtime dir]
# Releases the controller DLLs and winhandler.exe. With a Wine runtime directory (native launcher) the DLLs go into
# its lib/wine/x86_64-windows and lib/wine/i386-windows, as shipped, so that Wine loads them as builtin DLLs. Without
# one (rootfs launcher) marker-free copies go into the prefix: a prefix with a syswow64 directory is 64-bit, so
# x86_64 DLLs go to system32 and i386 DLLs to syswow64; otherwise system32 is 32-bit and takes the i386 DLLs.
# Returns non-zero when the payload is missing or cannot be copied; the caller then starts the game the old way (a
# missing controller must never keep a game from starting).
install_games_controller_runtime() {
    _prefix=$1
    _runtime_dir=$2
    _wine_dir=${3:-}
    _system32="$_prefix/drive_c/windows/system32"
    _syswow64="$_prefix/drive_c/windows/syswow64"
    [ -d "$_system32" ] || return 1
    [ -f "$_runtime_dir/controller_winhandler.exe" ] || return 1
    _games_controller_stage "$_runtime_dir" || return 1
    if [ -n "$_wine_dir" ]; then
        _installed=0
        for _arch in x86_64-windows i386-windows; do
            [ -d "$_wine_dir/lib/wine/$_arch" ] || continue
            _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine/$_arch" "$_wine_dir/lib/wine/$_arch" keep || return 1
            _installed=1
        done
        [ "$_installed" = 1 ] || return 1
    elif [ -d "$_syswow64" ]; then
        _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine-native/x86_64-windows" "$_system32" || return 1
        _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine-native/i386-windows" "$_syswow64" || return 1
    else
        _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine-native/i386-windows" "$_system32" || return 1
    fi
    _winhandler="$_prefix/drive_c/windows/winhandler.exe"
    if [ "$(_games_controller_sum "$_runtime_dir/controller_winhandler.exe")" != "$(_games_controller_sum "$_winhandler")" ]; then
        cp -f "$_runtime_dir/controller_winhandler.exe" "$_winhandler" || return 1
    fi
}

# games_controller_dll_overrides <gamepad mapper>
# The WINEDLLOVERRIDES entries for the patched DLLs, from the game's Runtime > Libraries settings: 0 = standard
# (DInput, GAMES_DLL_DINPUT), anything else = XInput (GAMES_DLL_XINPUT); the value is builtin, native or native,builtin.
# Nothing is printed while the setting is empty: the DLLs then load as builtin DLLs, which is what the installed
# patched copies are.
games_controller_dll_overrides() {
    case "$1" in
        0) _dlls='dinput dinput8'; _mode=${GAMES_DLL_DINPUT:-} ;;
        *) _dlls='xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap'; _mode=${GAMES_DLL_XINPUT:-} ;;
    esac
    [ -n "$_mode" ] || return 0
    for _dll in $_dlls; do
        printf '%s=%s;' "$_dll" "$_mode"
    done
}

# games_controller_windows_target <absolute Unix path inside the guest>
# winhandler.exe starts the program with ShellExecute, which does not take a Unix path ("path not found"): hand it the
# Z: drive form instead, Z: being the guest root of the Wine prefix.
games_controller_windows_target() {
    printf 'Z:%s' "$(printf '%s' "$1" | tr '/' '\\')"
}
