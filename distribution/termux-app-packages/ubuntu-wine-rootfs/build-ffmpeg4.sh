#!/bin/sh
# Ubuntu-built compatibility ABI for the pinned Wine10/Proton winedmo modules.
# Run only inside the dedicated Ubuntu 26.04 amd64 cross-build root.
set -eu
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LC_ALL=C.UTF-8 SOURCE_DATE_EPOCH=1787940812
. /etc/os-release
[ "$ID:$VERSION_ID:$VERSION_CODENAME" = ubuntu:26.04:resolute ] || exit 65
[ "$(dpkg --print-architecture)" = amd64 ] || exit 65
cd /work/ffmpeg-4.4.8
./configure --prefix=/usr/local --libdir=/usr/local/lib \
    --arch=aarch64 --target-os=linux --enable-cross-compile \
    --cross-prefix=aarch64-linux-gnu- --enable-shared --disable-static \
    --disable-programs --disable-doc --disable-debug --disable-autodetect \
    --disable-avdevice --disable-avfilter --disable-postproc
make -j16
make DESTDIR=/work/ffmpeg4-install install
# Installers package only versioned runtime libraries and legal notices;
# development headers, pkg-config files and unversioned linker names stay here.
find /work/ffmpeg4-install/usr/local/lib -maxdepth 1 -name '*.so.*' -print
