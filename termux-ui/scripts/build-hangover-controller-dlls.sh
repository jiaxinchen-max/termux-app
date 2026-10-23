#!/bin/sh
# Builds the patched xinput/dinput DLLs for the arm64 Hangover Wine from Hangover's own Wine source.
#
#   LLVM_MINGW=/path/to/llvm-mingw [WORKDIR=dir] [OUT=dir] sh build-hangover-controller-dlls.sh
#
# Result: $OUT/aarch64-windows/*.dll and $OUT/i386-windows/*.dll (stripped, "Wine builtin DLL" marker intact). They go
# into wine.tar.gz as wine-hangover/aarch64-windows and wine-hangover/i386-windows. See hangover-controller-dlls/README.md.
set -eu

HANGOVER_TAG=${HANGOVER_TAG:-hangover-11.16}
HANGOVER_URL=${HANGOVER_URL:-https://github.com/AndreRH/hangover}
LLVM_MINGW=${LLVM_MINGW:?set LLVM_MINGW to an unpacked llvm-mingw release (the Linux archive, not the .zip)}
WORKDIR=${WORKDIR:-$PWD/hangover-controller-build}
OUT=${OUT:-$WORKDIR/out}
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}
PATCH=$(cd "$(dirname "$0")" && pwd)/hangover-controller-dlls/gamepad-wine-11.16.patch
DLLS='xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap dinput dinput8'

[ -f "$PATCH" ] || { echo "missing $PATCH" >&2; exit 1; }
[ -x "$LLVM_MINGW/bin/aarch64-w64-mingw32-clang" ] || { echo "$LLVM_MINGW is not an llvm-mingw release" >&2; exit 1; }
export PATH="$LLVM_MINGW/bin:$PATH"
mkdir -p "$WORKDIR"

# Hangover at the release tag; only the wine submodule is needed (FEX and box64 are not built here).
[ -d "$WORKDIR/hangover/.git" ] || git clone --depth 1 --branch "$HANGOVER_TAG" "$HANGOVER_URL" "$WORKDIR/hangover"
WINE_SRC=$WORKDIR/hangover/wine
git -C "$WORKDIR/hangover" submodule update --init --depth 1 wine
echo "Wine source: $(cat "$WINE_SRC/VERSION")"

# Start from the pristine submodule checkout, then add the controller patch.
git -C "$WINE_SRC" reset --hard -q
git -C "$WINE_SRC" clean -fdxq
git -C "$WINE_SRC" apply --whitespace=nowarn "$PATCH"

# llvm-mingw has no libgcc; winegcc links -lgcc for the aarch64 DLLs, so the compiler-rt builtins stand in for it.
BUILTINS=$(ls "$LLVM_MINGW"/lib/clang/*/lib/windows/libclang_rt.builtins-aarch64.a | head -n 1)
[ -f "$LLVM_MINGW/aarch64-w64-mingw32/lib/libgcc.a" ] || cp "$BUILTINS" "$LLVM_MINGW/aarch64-w64-mingw32/lib/libgcc.a"

# Same archs as Hangover itself. The unix-side libraries are not needed for these PE DLLs.
mkdir -p "$WORKDIR/build"
cd "$WORKDIR/build"
"$WINE_SRC/configure" --disable-tests --with-mingw=clang --enable-archs=arm64ec,aarch64,i386 \
    --without-x --without-freetype --without-alsa --without-pulse --without-gstreamer --without-vulkan \
    --without-opengl --without-sdl --without-udev --without-usb --without-v4l2 --without-pcap --without-cups \
    --without-sane --without-gphoto --without-krb5 --without-ffmpeg --without-wayland --without-oss \
    --without-capi > configure.log 2>&1 || { tail -n 30 configure.log >&2; exit 1; }

targets=
for arch in aarch64 i386; do
    for dll in $DLLS; do targets="$targets dlls/$dll/$arch-windows/$dll.dll"; done
done
make -j"$JOBS" $targets

for arch in aarch64 i386; do
    mkdir -p "$OUT/$arch-windows"
    for dll in $DLLS; do
        llvm-strip -o "$OUT/$arch-windows/$dll.dll" "dlls/$dll/$arch-windows/$dll.dll"
    done
done
echo "DLLs written to $OUT"
