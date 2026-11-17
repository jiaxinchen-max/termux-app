#!/bin/sh
# Run in the dedicated Ubuntu 26.04 amd64 cross-build root.
# /work/wine must be the source commit pinned in hangover-source.lock.json.
set -eu
export PATH=/opt/llvm-mingw-20260826-ucrt-ubuntu-22.04-x86_64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LD_LIBRARY_PATH=/opt/llvm-mingw-20260826-ucrt-ubuntu-22.04-x86_64/lib
export LC_ALL=C.UTF-8 SOURCE_DATE_EPOCH=1787940812
. /etc/os-release
[ "$ID:$VERSION_ID:$VERSION_CODENAME" = ubuntu:26.04:resolute ] || exit 65
[ "$(dpkg --print-architecture)" = amd64 ] || exit 65
mkdir -p /work/native-tools /work/arm64-unix
cd /work/native-tools
if [ ! -f Makefile ]; then
    /work/wine/configure --enable-win64 --disable-tests --without-mingw \
        --without-x --without-wayland --without-freetype
fi
make -j16 __tooldeps__
cd /work/arm64-unix
PKG_CONFIG=/usr/bin/pkg-config \
    PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig \
    /work/wine/configure --host=aarch64-linux-gnu --enable-win64 \
    --with-wine-tools=/work/native-tools --disable-tests \
    --without-x --without-wayland --without-freetype
grep -qx '#define HAVE_FFMPEG 1' include/config.h
make -j16 dlls/winedmo/winedmo.so
aarch64-linux-gnu-readelf -d dlls/winedmo/winedmo.so
