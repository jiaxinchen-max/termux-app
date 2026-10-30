#!/data/data/com.termux/files/usr/bin/sh
# SPDX-License-Identifier: MIT
# Run from an actual Termux session/App child, not root or adb shell.
# Before running, grant CAMERA to the already installed, same-UID Termux:API.
set -eu
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
probe_source=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/android_ndk_camera.c
probe_work=$(mktemp -d "$PREFIX/../home/tusb-ndk-camera-XXXXXX")
printf 'NDK camera validation directory: %s\n' "$probe_work"
clang -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -O2 \
    "$probe_source" -lcamera2ndk -lmediandk -ldl -o "$probe_work/probe"
# Termux clang embeds a prefix RUNPATH. Unsetting LD_LIBRARY_PATH alone still
# loads Termux's same-named Media NDK wrapper. Keep system libs first here.
LD_LIBRARY_PATH=/system/lib64 "$probe_work/probe" --list
if [ "$#" -eq 0 ]; then
    printf 'To capture, pass camera IDs from the list, e.g.: sh %s 1 10\n' "$0"
    exit 0
fi
probe_index=0
for probe_id do
    LD_LIBRARY_PATH=/system/lib64 "$probe_work/probe" "$probe_id" "$probe_work/camera-$probe_index"
    probe_index=$((probe_index+1))
done
