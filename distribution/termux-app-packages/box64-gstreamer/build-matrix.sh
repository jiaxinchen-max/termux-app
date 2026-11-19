#!/bin/sh
# Execute only inside the existing SDK on the component compilation server.
set -eu
if [ "$#" -ne 4 ]; then
    echo "Usage: $0 PATCHED_SOURCE NEW_BUILD_DIRECTORY NEW_OUTPUT_DIRECTORY native|proot" >&2
    exit 64
fi
source_dir=$1
build_dir=$2
output_dir=$3
backend=$4
test -f "$source_dir/src/wrapped/wrappedgstreamer.c"
test ! -e "$build_dir"
test ! -e "$output_dir"
flags='-Wl,-z,max-page-size=65536'
case "$backend" in
    native)
        flags="$flags -Wl,--dynamic-linker,/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1 -Wl,-rpath,/data/data/com.termux/files/usr/glibc/lib"
        ;;
    proot)
        flags="$flags -Wl,--dynamic-linker,/lib/ld-linux-aarch64.so.1"
        ;;
    *) echo "Unknown backend: $backend" >&2; exit 64 ;;
esac
cmake -S "$source_dir" -B "$build_dir" \
    -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
    -DCMAKE_C_COMPILER=/usr/bin/aarch64-linux-gnu-gcc \
    -DCMAKE_ASM_COMPILER=/usr/bin/aarch64-linux-gnu-gcc \
    -DCMAKE_BUILD_TYPE=Release -DARM64=ON -DARM_DYNAREC=ON \
    -DBAD_SIGNAL=ON -DNOGIT=ON -DNOLOADADDR=ON \
    -DANDROID=OFF -DTERMUX=OFF -DHAVE_TRACE=OFF \
    -DCMAKE_EXE_LINKER_FLAGS="$flags"
cmake --build "$build_dir" --target box64 --parallel "${BOX64_BUILD_JOBS:-8}"
mkdir "$output_dir"
aarch64-linux-gnu-strip --strip-debug --strip-unneeded \
    -o "$output_dir/box64" "$build_dir/box64"
chmod 755 "$output_dir/box64"
aarch64-linux-gnu-readelf -h -l -d -V "$output_dir/box64"
sha256sum "$output_dir/box64"
