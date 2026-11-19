#!/bin/sh
# Run inside the existing Debian 13 SDK on the component build machine.
set -eu
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 PATCHED_SOURCE NEW_BUILD_DIRECTORY NEW_OUTPUT_DIRECTORY" >&2
    exit 64
fi
source_dir=$1
build_dir=$2
output_dir=$3
test -f "$source_dir/src/wrapped/wrappedgstreamer.c"
# Refuse to reuse historical builds or replace an existing component.
test ! -e "$build_dir"
test ! -e "$output_dir"
cmake -S "$source_dir" -B "$build_dir" \
    -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
    -DCMAKE_C_COMPILER=/usr/bin/aarch64-linux-gnu-gcc \
    -DCMAKE_ASM_COMPILER=/usr/bin/aarch64-linux-gnu-gcc \
    -DCMAKE_BUILD_TYPE=Release -DARM64=ON -DARM_DYNAREC=ON \
    -DBAD_SIGNAL=ON -DNOGIT=ON -DNOLOADADDR=ON \
    '-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=65536 -Wl,--dynamic-linker,/lib/ld-linux-aarch64.so.1'
cmake --build "$build_dir" --parallel "${BOX64_BUILD_JOBS:-4}"
mkdir "$output_dir"
cp "$build_dir/box64" "$output_dir/box64"
aarch64-linux-gnu-strip "$output_dir/box64"
chmod 755 "$output_dir/box64"
sha256sum "$output_dir/box64"
