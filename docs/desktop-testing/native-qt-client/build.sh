#!/usr/bin/env bash
set -euo pipefail
if [ "$#" != 7 ]; then
    echo 'Usage: build.sh patched-source build-dir cmake ninja clang++ Termux-prefix Qt-host-prefix' >&2
    exit 2
fi
source_dir="$1"; build_dir="$2"; cmake_path="$3"; ninja_path="$4"; compiler="$5"; prefix="$6"; qt_host="$7"
[ "$(uname -s)" = Linux ] || { echo 'Run this cross-build on the designated Linux builder.' >&2; exit 1; }
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
"$cmake_path" -G Ninja -S "$source_dir" -B "$build_dir" \
    -DCMAKE_MAKE_PROGRAM="$ninja_path" -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 -DCMAKE_SYSTEM_VERSION=24 \
    -DCMAKE_CXX_COMPILER="$compiler" \
    -DCMAKE_CXX_FLAGS="--target=aarch64-linux-android24 -isystem$prefix/include/c++/v1 -isystem$prefix/include" \
    -DCMAKE_MODULE_LINKER_FLAGS="--target=aarch64-linux-android24 -L$prefix/lib -landroid-shmem" \
    -DCMAKE_PREFIX_PATH="$prefix" -DCMAKE_FIND_ROOT_PATH="$prefix" \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
    -DCMAKE_MODULE_PATH="$prefix/share/ECM/find-modules" -DQT_HOST_PATH="$qt_host" -DCMAKE_BUILD_TYPE=Release
"$cmake_path" --build "$build_dir" -j4
