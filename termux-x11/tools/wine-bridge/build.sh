#!/bin/sh
set -eu
SOURCE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR=${1:?Usage: build.sh OUTPUT_DIRECTORY}
mkdir -p "$BUILD_DIR"
CC=${CC:-x86_64-w64-mingw32-gcc}
OBJCOPY=${OBJCOPY:-x86_64-w64-mingw32-objcopy}
STRIP=${STRIP:-x86_64-w64-mingw32-strip}
export SOURCE_DATE_EPOCH=0
"$CC" -std=c11 -O2 -g -Wall -Wextra -Werror -municode -mwindows -static-libgcc \
    -Wl,--no-insert-timestamp -o "$BUILD_DIR/wine-bridge.full.exe" \
    "$SOURCE_DIR/main.c" "$SOURCE_DIR/protocol.c" -lws2_32 -lshell32 -luser32 -lpsapi
"$OBJCOPY" --only-keep-debug "$BUILD_DIR/wine-bridge.full.exe" "$BUILD_DIR/wine-bridge.debug"
cp "$BUILD_DIR/wine-bridge.full.exe" "$BUILD_DIR/wine-bridge.exe"
"$STRIP" --strip-all "$BUILD_DIR/wine-bridge.exe"
"$OBJCOPY" --add-gnu-debuglink="$BUILD_DIR/wine-bridge.debug" "$BUILD_DIR/wine-bridge.exe"
"$CC" --version > "$BUILD_DIR/compiler.txt"
sha256sum "$BUILD_DIR/wine-bridge.exe" "$BUILD_DIR/wine-bridge.debug"
