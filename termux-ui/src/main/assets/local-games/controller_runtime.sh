# Wine side of the on-screen / external controller, shared by the native (GLIBC) and the proot (RootFS)
# launchers. Sourced, POSIX sh.
#
# The controller reaches a game through two pieces that the stock Wine builds do not have:
#   * patched xinput1_1/1_2/1_3/1_4/9_1_0/xinputuap and dinput/dinput8 PE DLLs (wine.tar.gz in the app sources, packaged as wine.tar,
#     x86_64-windows/ and i386-windows/), loaded through the "native" DLL overrides below;
#   * winhandler.exe, a wrapper the game is started through: it runs the real program and is the Wine end of
#     the controller / process bridge the app talks to over UDP (WinHandler, ports 7946/7947).
# This replaces the removed setMoBoxEnv script, which unpacked the DLLs into the shared Wine runtime and
# copied winhandler.exe next to the old start-tfm launcher. The DLLs are released into the *prefix* instead
# (system32 / syswow64), because that is where a "native" override looks first and it leaves the shared Wine
# runtime untouched.
#
# The app installs the payload next to the launcher as controller_wine.tar and controller_winhandler.exe.

GAMES_CONTROLLER_WINHANDLER_WINDOWS_PATH='C:\windows\winhandler.exe'

# Checksum of a file that ignores the file name, or nothing when it does not exist.
_games_controller_sum() {
    [ -f "$1" ] || return 0
    cksum < "$1" 2>/dev/null
}

# Copies every *.dll of $1 into $2 unless it is already identical. A symlinked target is replaced rather than
# written through, so a prefix whose stubs link into a shared Wine build never modifies that build.
_games_controller_copy_dlls() {
    [ -d "$1" ] || return 0
    mkdir -p "$2" || return 1
    for _source in "$1"/*.dll; do
        [ -f "$_source" ] || continue
        _target="$2/${_source##*/}"
        [ "$(_games_controller_sum "$_source")" = "$(_games_controller_sum "$_target")" ] && continue
        [ -L "$_target" ] && rm -f "$_target"
        cp -f "$_source" "$_target" || return 1
    done
}

# Unpacks the DLL archive next to it, once per archive content.
_games_controller_stage() {
    _runtime_dir=$1
    _archive="$_runtime_dir/controller_wine.tar"
    _stage="$_runtime_dir/controller-stage"
    [ -f "$_archive" ] || return 1
    _archive_sum=$(_games_controller_sum "$_archive")
    [ -n "$_archive_sum" ] || return 1
    [ "$(cat "$_stage/.archive-sum" 2>/dev/null)" = "$_archive_sum" ] && return 0
    rm -rf "$_stage.tmp"
    mkdir -p "$_stage.tmp" || return 1
    tar -xf "$_archive" -C "$_stage.tmp" || { rm -rf "$_stage.tmp"; return 1; }
    [ -f "$_stage.tmp/wine/x86_64-windows/xinput1_3.dll" ] || { rm -rf "$_stage.tmp"; return 1; }
    printf '%s\n' "$_archive_sum" > "$_stage.tmp/.archive-sum"
    rm -rf "$_stage"
    mv "$_stage.tmp" "$_stage"
}

# install_games_controller_runtime <wine prefix> <runtime dir>
# Releases the controller DLLs and winhandler.exe into the prefix. A prefix with a syswow64 directory is
# 64-bit: x86_64 DLLs go to system32 and i386 DLLs to syswow64; otherwise system32 is 32-bit and takes the
# i386 DLLs. Returns non-zero when the payload is missing or cannot be copied; the caller then starts the
# game the old way (a missing controller must never keep a game from starting).
install_games_controller_runtime() {
    _prefix=$1
    _runtime_dir=$2
    _system32="$_prefix/drive_c/windows/system32"
    _syswow64="$_prefix/drive_c/windows/syswow64"
    [ -d "$_system32" ] || return 1
    [ -f "$_runtime_dir/controller_winhandler.exe" ] || return 1
    _games_controller_stage "$_runtime_dir" || return 1
    if [ -d "$_syswow64" ]; then
        _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine/x86_64-windows" "$_system32" || return 1
        _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine/i386-windows" "$_syswow64" || return 1
    else
        _games_controller_copy_dlls "$_runtime_dir/controller-stage/wine/i386-windows" "$_system32" || return 1
    fi
    _winhandler="$_prefix/drive_c/windows/winhandler.exe"
    if [ "$(_games_controller_sum "$_runtime_dir/controller_winhandler.exe")" != "$(_games_controller_sum "$_winhandler")" ]; then
        cp -f "$_runtime_dir/controller_winhandler.exe" "$_winhandler" || return 1
    fi
}

# games_controller_dll_overrides <gamepad mapper>
# The WINEDLLOVERRIDES entries that switch the patched DLLs on: 0 = standard (DInput), anything else = XInput.
games_controller_dll_overrides() {
    case "$1" in
        0) printf '%s' 'dinput=native;dinput8=native;' ;;
        *) printf '%s' 'xinput1_1=native;xinput1_2=native;xinput1_3=native;xinput1_4=native;xinput9_1_0=native;xinputuap=native;' ;;
    esac
}
