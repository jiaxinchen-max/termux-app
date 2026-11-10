#!/bin/sh
# Run inside the existing Hangover buildroot; never resets an existing source tree.
set -eu
TASK_DIR=${TASK_DIR:-/work/controller-api-switches}
TOOLCHAIN=/opt/llvm-mingw-20260826-ucrt-ubuntu-22.04-x86_64
export PATH="$TOOLCHAIN/bin:/usr/bin:/bin"
export LD_LIBRARY_PATH="$TOOLCHAIN/lib"
cd "$TASK_DIR"
[ ! -e wine-11.16 ] || { echo 'Task source already exists; inspect instead of resetting it.' >&2; exit 1; }
HANGOVER_SOURCE=${HANGOVER_SOURCE:-/work/hangover/wine}
[ "$(git -C "$HANGOVER_SOURCE" rev-parse HEAD)" = 3ea3a14941b6a122b892c84141ce9662c008081e ] || {
    echo 'Unexpected Hangover Wine source commit' >&2; exit 1;
}
cp -a "$HANGOVER_SOURCE" wine-11.16
rm -f wine-11.16/.git
for version in 11.16 9.15; do
    if [ "$version" = 9.15 ]; then
        [ -f "$TASK_DIR/wine-9.15.tar.gz" ] || { echo 'Wine9.15 source archive required' >&2; exit 1; }
        cd "$TASK_DIR"
        printf '%s  wine-9.15.tar.gz\n' '735c34c446fe00439d22f42eca9dec91acc0aed3b5164260fc726e03ad8bc60e' | sha256sum -c -
        sha256sum wine-9.15.tar.gz > source-archive.sha256
        mkdir wine-9.15
        tar -xzf wine-9.15.tar.gz --strip-components=1 -C wine-9.15
    fi
    cd "$TASK_DIR/wine-$version"
    patch -p1 < "$TASK_DIR/inputs/gamepad-wine-$version.patch"
    python3 "$TASK_DIR/inputs/apply-api-flags.py" "$PWD"
    mkdir "$TASK_DIR/build-$version"
    cd "$TASK_DIR/build-$version"
    case "$version" in 9.15) archs=x86_64,i386; output_archs='x86_64 i386' ;; 11.16) archs=arm64ec,aarch64,i386; output_archs='aarch64 i386' ;; esac
    "$TASK_DIR/wine-$version/configure" --disable-tests --with-mingw=clang --enable-archs="$archs" \
        --without-x --without-freetype --without-alsa --without-pulse --without-gstreamer --without-vulkan \
        --without-opengl --without-sdl --without-udev --without-usb --without-v4l2 --without-pcap --without-cups \
        --without-sane --without-gphoto --without-krb5 --without-ffmpeg --without-wayland --without-oss --without-capi \
        > "$TASK_DIR/configure-$version.log" 2>&1
    targets=
    for arch in $output_archs; do
        for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
            targets="$targets dlls/$dll/$arch-windows/$dll.dll"
        done
    done
    make -j6 $targets > "$TASK_DIR/make-$version.log" 2>&1
    for arch in $output_archs; do
        mkdir -p "$TASK_DIR/out/$version/$arch-windows"
        for dll in dinput dinput8 xinput1_1 xinput1_2 xinput1_3 xinput1_4 xinput9_1_0 xinputuap; do
            llvm-strip -o "$TASK_DIR/out/$version/$arch-windows/$dll.dll" "dlls/$dll/$arch-windows/$dll.dll"
        done
    done
done
cd "$TASK_DIR"
tar -czf controller-api-dlls.tar.gz out
sha256sum controller-api-dlls.tar.gz
